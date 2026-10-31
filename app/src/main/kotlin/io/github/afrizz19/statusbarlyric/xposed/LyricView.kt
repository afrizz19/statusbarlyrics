package io.github.afrizz19.statusbarlyric.xposed

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.text.TextUtils
import android.util.TypedValue
import android.view.Gravity
import android.view.ViewGroup
import android.view.ViewPropertyAnimator
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.TextView
import io.github.afrizz19.statusbarlyric.prefs.LyricPrefs

/**
 * The status bar lyric view.
 *
 * Two behaviours the plain TextView didn't give us:
 *  - **Two rows on overflow.** A line that doesn't fit on one row is re-laid out as
 *    two rows at a reduced text size (clamped so both rows still fit the status bar
 *    height). Short lines stay single-row at full size.
 *  - **Animated transitions.** Lines cross-fade with a small vertical slide instead
 *    of snapping, so the status bar doesn't flicker on every line change.
 */
class LyricView(context: Context) : TextView(context) {

    /** Full-size text size, inherited from the clock; the 2-row mode scales down from this. */
    private var baseTextSizePx = 0f
    private var shiftPx = 0f
    private var anim: ViewPropertyAnimator? = null
    private var currentLyric: String? = null

    /** Width the parent last offered us; the 1-row/2-row choice is made against this. */
    private var knownWidth = 0
    /** Width the current layout decision was made with, so we can redo it if it changes. */
    private var decidedWidth = -1

    // Painted style, in px, mirrored from the prefs so we can skip no-op updates.
    private var outlineOn = false
    private var outlineWidthPx = 0f
    private var outlineColor = 0
    private var outlinePadding = 0
    private var shadowRadiusPx = 0f
    private var shadowDxPx = 0f
    private var shadowDyPx = 0f
    private var shadowColorArgb = 0
    private var inDraw = false
    /** Set once a karaoke frame has failed, so the log gets one entry and not one per frame. */
    private var loggedSweepFailure = false

    // Word-mode (karaoke) highlight state.
    private var wordCharOffset = 0f
    private var wordCharOffsetFor: String? = null
    private var wordDimColor = 0
    private var smoothRunnable: Runnable? = null
    private var smoothAnchorMs = 0L
    private var smoothStartMs = 0L
    private var smoothEndMs = 0L
    private var smoothWords: List<io.github.afrizz19.statusbarlyric.api.TimedWord>? = null

    /**
     * Pushes the karaoke highlight state: how much of the line has been sung.
     *
     * @param offset fractional character offset into [forText] that has been sung. 0f
     *        before the first word, text.length after the last, lerped across each word.
     * @param forText the line this offset belongs to. Mismatches are drawn fully unsung.
     * @param sungColor ARGB of the already-sung portion.
     * @param unsungColor ARGB of the not-yet-sung portion.
     * @param smooth interpolate between ticks instead of stepping.
     * @param words the per-word timing for smooth interpolation.
     * @param positionMs current playback position, for smooth interpolation.
     */
    fun setWordProgress(
        offset: Float,
        forText: String,
        sungColor: Int,
        unsungColor: Int,
        smooth: Boolean,
        words: List<io.github.afrizz19.statusbarlyric.api.TimedWord>?,
        positionMs: Long,
    ) {
        wordCharOffset = offset
        wordCharOffsetFor = forText
        wordDimColor = unsungColor

        // Only arm the smooth loop when the setting is on and we have word data.
        if (smooth && !words.isNullOrEmpty()) {
            smoothWords = words
            smoothAnchorMs = positionMs
            smoothStartMs = 0L
            smoothEndMs = 0L
            // Find which word the offset lands in, set the loop window to that word's [start, end].
            var acc = 0
            for (w in words) {
                if (offset >= acc && offset < acc + w.word.length) {
                    smoothStartMs = w.startMs
                    smoothEndMs = w.endMs
                    break
                }
                acc += w.word.length
            }
            armSmoothLoop()
        } else {
            disarmSmoothLoop()
        }

        setLyricColor(sungColor)
        invalidate()
    }

    /** Clears the karaoke state — lines in line mode, or when playback stops. */
    fun clearWordProgress() {
        wordCharOffset = 0f
        wordCharOffsetFor = null
        wordDimColor = 0
        disarmSmoothLoop()
        invalidate()
    }

    private fun armSmoothLoop() {
        if (smoothRunnable != null) return
        val r = object : Runnable {
            override fun run() {
                // This is posted to SystemUI's main thread, so an escaping throwable is a
                // SystemUI crash exactly like one out of onDraw. Give up on smoothing
                // instead: the tick still pushes a stepped offset every 100 ms.
                try {
                    step()
                } catch (t: Throwable) {
                    disarmSmoothLoop()
                    logSweepFailure(t)
                }
            }

            private fun step() {
                // Stop when detached, invisible, or the window elapsed.
                if (!isAttachedToWindow || visibility != VISIBLE) {
                    disarmSmoothLoop()
                    return
                }
                val now = android.os.SystemClock.uptimeMillis()
                val elapsed = now - smoothAnchorMs
                val pos = smoothStartMs + elapsed
                if (pos > smoothEndMs) {
                    disarmSmoothLoop()
                    return
                }

                val words = smoothWords ?: emptyList()
                val newOffset = io.github.afrizz19.statusbarlyric.sync.WordProgressResolver.charOffsetAt(words, pos)
                val layout = layout
                if (layout == null) {
                    disarmSmoothLoop()
                    return
                }
                val prevPx = totalPaddingLeft + charOffsetToPx(wordCharOffset, layout)
                val newPx = totalPaddingLeft + charOffsetToPx(newOffset, layout)

                // Stop the loop if the boundary moved less than 1 px (a held note, or finished).
                if (kotlin.math.abs(newPx - prevPx) < 1f) {
                    disarmSmoothLoop()
                    return
                }

                wordCharOffset = newOffset
                // postInvalidateOnAnimation bypasses the overridden invalidate(), which is
                // exactly what we want — inDraw would not catch it and the frame would schedule
                // forever. Never call this from onDraw.
                postInvalidateOnAnimation()
                post(this)
            }
        }
        smoothRunnable = r
        post(r)
    }

    private fun disarmSmoothLoop() {
        smoothRunnable?.let { removeCallbacks(it) }
        smoothRunnable = null
        smoothWords = null
    }

    /**
     * Pixel x of a fractional character offset, within its own row (so the caller adds
     * the left padding). Lerps between the two neighbouring characters so the sweep moves
     * smoothly through a glyph rather than snapping between them.
     */
    private fun charOffsetToPx(offset: Float, layout: android.text.Layout): Float {
        val limit = laidOutLength(layout)
        if (limit <= 0) return 0f
        val clamped = offset.coerceIn(0f, limit.toFloat())
        val whole = clamped.toInt().coerceIn(0, limit)
        val frac = clamped - whole

        val x0 = layout.getPrimaryHorizontal(whole)
        if (frac <= 0f || whole >= limit) return x0

        // Don't lerp across a row break: the next character sits at x≈0 on the next row,
        // which would run the sweep backwards.
        val next = whole + 1
        if (layout.getLineForOffset(next) != layout.getLineForOffset(whole)) return x0

        return x0 + frac * (layout.getPrimaryHorizontal(next) - x0)
    }

    /**
     * How much of the string the layout actually laid out — **not** `text.length`.
     *
     * [maxLines] caps the rows, so `StaticLayout` stops generating at the last permitted
     * row and the rest of the string is simply not in the layout. Every positional query
     * ([android.text.Layout.getPrimaryHorizontal] especially) is bounded by *this*, and
     * one past it throws `IndexOutOfBoundsException: offset(N) should be less than line
     * limit(M)` — which, inside SystemUI's draw, is a SystemUI crash and eventually
     * LSPosed safe mode.
     *
     * The window that produced it: the status bar squeezes the view, [onMeasure] builds a
     * truncated layout for the smaller width but can only *post* the re-layout, so the
     * next frame draws a full-text sweep offset against that already-truncated layout.
     */
    private fun laidOutLength(layout: android.text.Layout): Int {
        val lines = layout.lineCount
        if (lines <= 0) return 0
        return layout.getLineEnd(lines - 1)
    }

    /** Copies the clock's look so the lyric belongs in the status bar on any ROM/theme. */
    fun styleFrom(clock: TextView) {
        baseTextSizePx = clock.textSize
        shiftPx = clock.resources.displayMetrics.density * SHIFT_DP

        setTextColor(clock.currentTextColor)
        setTextSize(TypedValue.COMPLEX_UNIT_PX, baseTextSizePx)
        typeface = clock.typeface
        includeFontPadding = false
        gravity = Gravity.CENTER_VERTICAL or Gravity.START
        ellipsize = TextUtils.TruncateAt.END
        setLineSpacing(0f, LINE_SPACING_MULT)
        // Row count is driven by maxLines alone — setSingleLine() also turns on
        // horizontal scrolling, which then sticks and blocks wrapping.
        setSingleLine(false)
        maxLines = 1

        // Cap the width so the lyric can never push the status bar icons off-screen.
        maxWidth = (clock.resources.displayMetrics.widthPixels * MAX_WIDTH_FRACTION).toInt()

        layoutParams = ViewGroup.MarginLayoutParams(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
        ).apply { marginStart = (clock.resources.displayMetrics.density * START_MARGIN_DP).toInt() }

        visibility = GONE
    }

    /** Shows [text], animating the swap from whatever is currently displayed. */
    fun setLyric(text: String) {
        if (text == currentLyric) return
        currentLyric = text
        anim?.cancel()

        val appearing = visibility != VISIBLE || this.text.isNullOrEmpty()
        if (appearing) {
            applyText(text)
            alpha = 0f
            translationY = shiftPx
            visibility = VISIBLE
            anim = animate().alpha(1f).translationY(0f)
                .setDuration(FADE_MS)
                .setInterpolator(EASING)
                .apply { start() }
            return
        }

        // Out (up), swap, then in (from below).
        anim = animate().alpha(0f).translationY(-shiftPx)
            .setDuration(FADE_MS)
            .setInterpolator(EASING)
            .withEndAction {
                applyText(text)
                translationY = shiftPx
                anim = animate().alpha(1f).translationY(0f)
                    .setDuration(FADE_MS)
                    .setInterpolator(EASING)
                    .apply { start() }
            }
            .apply { start() }
    }

    /** Sets the lyric colour, ignoring repeats (setTextColor invalidates every time). */
    fun setLyricColor(color: Int) {
        if (color == currentTextColor) return
        setTextColor(color)
    }

    /**
     * Applies the configured shadow/outline. Cheap to call repeatedly — it returns
     * early unless something actually changed.
     */
    fun applyStyle(style: LyricPrefs.Style, resolvedColor: Int) {
        setLyricColor(resolvedColor)

        val density = resources.displayMetrics.density
        val newOutlineOn = style.outlineOn && style.outlineWidthDp > 0f
        val newOutlineWidth = style.outlineWidthDp * density

        if (newOutlineOn != outlineOn ||
            newOutlineWidth != outlineWidthPx ||
            style.outlineColor != outlineColor
        ) {
            outlineOn = newOutlineOn
            outlineWidthPx = newOutlineWidth
            outlineColor = style.outlineColor
            // An outline grows the glyphs; without padding the edges get clipped.
            val pad = if (outlineOn) Math.ceil(outlineWidthPx.toDouble()).toInt() else 0
            if (pad != outlinePadding) {
                outlinePadding = pad
                setPadding(pad, 0, pad, 0)
            }
            invalidate()
        }

        val radius = if (style.shadowOn) style.shadowRadiusDp * density else 0f
        val dx = if (style.shadowOn) style.shadowDxDp * density else 0f
        val dy = if (style.shadowOn) style.shadowDyDp * density else 0f
        if (radius != shadowRadiusPx || dx != shadowDxPx ||
            dy != shadowDyPx || style.shadowColor != shadowColorArgb
        ) {
            shadowRadiusPx = radius
            shadowDxPx = dx
            shadowDyPx = dy
            shadowColorArgb = style.shadowColor
            applyShadowLayer()
        }
    }

    private fun applyShadowLayer() {
        // A zero radius with a non-zero offset still draws a hard shadow, so clear
        // the layer outright when the shadow is off.
        if (shadowRadiusPx <= 0f && shadowDxPx == 0f && shadowDyPx == 0f) {
            setShadowLayer(0f, 0f, 0f, 0)
        } else {
            // Radius must be > 0 for the blur; keep a hair above zero for a hard shadow.
            setShadowLayer(shadowRadiusPx.coerceAtLeast(0.01f), shadowDxPx, shadowDyPx, shadowColorArgb)
        }
    }

    /**
     * Draws the outline as a stroked pass under the normal fill pass. The shadow layer
     * is suspended for the stroke, otherwise the shadow is painted twice (once per
     * pass) and looks muddy.
     *
     * Word mode adds a third clipped fill pass for the sung portion.
     */
    override fun onDraw(canvas: Canvas) {
        // "This line is under karaoke control" is a separate question from "any of it has
        // been sung yet": at offset 0 the line must still draw fully dim, not fully lit.
        val karaoke = wordCharOffsetFor != null

        if (!outlineOn && !karaoke) {
            super.onDraw(canvas)
            return
        }

        val fillColor = currentTextColor
        val hasShadow = shadowRadiusPx > 0f || shadowDxPx != 0f || shadowDyPx != 0f

        // setTextColor/setShadowLayer both invalidate(), which from inside onDraw would
        // schedule another frame forever. Swallow those for the duration of the draw.
        inDraw = true
        try {
            // Pass 1: outline colour, shadow off, glyph outlines only.
            if (outlineOn && outlineWidthPx > 0f) {
                if (hasShadow) setShadowLayer(0f, 0f, 0f, 0)
                paint.style = Paint.Style.STROKE
                paint.strokeWidth = outlineWidthPx * 2f
                paint.strokeJoin = Paint.Join.ROUND
                paint.strokeMiter = 10f
                setTextColor(outlineColor)
                super.onDraw(canvas)
            }

            // Pass 2: whole line in the unsung colour (or the sung colour when no word data).
            paint.style = Paint.Style.FILL
            paint.strokeWidth = 0f
            val baseColor = if (karaoke) wordDimColor else fillColor
            setTextColor(baseColor)
            if (hasShadow) applyShadowLayer()
            super.onDraw(canvas)

            // Pass 3: the sung portion, clipped. Shadow stays off — pass 2 already drew it,
            // and drawing it twice looks muddy for the same reason the stroke pass does.
            if (karaoke && wordCharOffset > 0f && rendersStashedLine()) {
                if (hasShadow) setShadowLayer(0f, 0f, 0f, 0)
                setTextColor(fillColor)
                // The sweep is cosmetic, but this runs on SystemUI's draw thread: an
                // exception here kills SystemUI, and a few of those in a row put LSPosed
                // into safe mode. Pass 2 has already drawn the whole line, so dropping
                // the sweep for this frame degrades to a fully-unsung line — the same
                // fallback as a line with no word data.
                val saveCount = canvas.saveCount
                try {
                    drawSungRegion(canvas)
                } catch (t: Throwable) {
                    // drawSungRegion clips per row, so unwind whatever it left on the stack.
                    canvas.restoreToCount(saveCount)
                    logSweepFailure(t)
                }
                if (hasShadow) applyShadowLayer()
            }
        } finally {
            inDraw = false
        }
    }

    /**
     * Whether what's on screen is the line the stashed offset was computed for.
     *
     * A two-row line is *not* character-identical to the line the controller sent us:
     * [balancedSplit] swaps one space for `"\n"`. That substitution is length-preserving
     * and changes nothing else, so character indices still map 1:1 and the offset remains
     * valid — normalise the break back out before comparing. (Comparing raw strings here
     * is what made every two-row line draw fully dim.)
     *
     * A genuine mismatch means [applyText] hasn't caught up yet — [setLyric] sets
     * `currentLyric` immediately but swaps the text ~160 ms later inside the fade's
     * end action, so for that window the on-screen text is still the *previous* line.
     * Drawing that fully unsung is the intended fallback.
     */
    private fun rendersStashedLine(): Boolean {
        val stashed = wordCharOffsetFor ?: return false
        val rendered = text?.toString() ?: return false
        if (rendered == stashed) return true
        return rendered.length == stashed.length && rendered.replace('\n', ' ') == stashed
    }

    /** Logs a swallowed karaoke failure once — this sits on the draw path, so never per frame. */
    private fun logSweepFailure(t: Throwable) {
        if (loggedSweepFailure) return
        loggedSweepFailure = true
        de.robv.android.xposed.XposedBridge.log(
            "StatusBarLyric16: karaoke sweep disabled for this frame: " +
                android.util.Log.getStackTraceString(t)
        )
    }

    /**
     * Draws the already-sung part of the line by clipping to it and re-running the fill
     * pass. `clipRect` *intersects*, so the sung region — "all of row 0 plus the left part
     * of row 1" in the two-row case — needs one save/clip/draw per row, not one combined
     * clip. There are at most two rows, so at most two extra passes.
     *
     * Anything unexpected (no layout, an offset past the text) degrades to drawing nothing
     * extra, which shows the line fully unsung rather than taking SystemUI down.
     */
    private fun drawSungRegion(canvas: Canvas) {
        val layout = layout ?: return
        // The laid-out length, not text.length — maxLines truncates the layout and every
        // positional query below is bounded by what was actually laid out. See [laidOutLength].
        val limit = laidOutLength(layout)
        if (limit <= 0) return

        val boundary = wordCharOffset.coerceIn(0f, limit.toFloat())
        val activeRow = layout.getLineForOffset(boundary.toInt().coerceIn(0, limit))
        val voffset = verticalTextOffset(layout)
        val left = totalPaddingLeft.toFloat()

        // Rows fully sung.
        for (row in 0 until activeRow) {
            canvas.save()
            canvas.clipRect(
                0f,
                (layout.getLineTop(row) + voffset).toFloat(),
                width.toFloat(),
                (layout.getLineBottom(row) + voffset).toFloat(),
            )
            super.onDraw(canvas)
            canvas.restore()
        }

        // The active row, clipped at the sweep boundary.
        val sungPx = left + charOffsetToPx(boundary, layout)
        val rtl = layout.getParagraphDirection(activeRow) == android.text.Layout.DIR_RIGHT_TO_LEFT
        canvas.save()
        canvas.clipRect(
            if (rtl) sungPx else 0f,
            (layout.getLineTop(activeRow) + voffset).toFloat(),
            if (rtl) width.toFloat() else sungPx,
            (layout.getLineBottom(activeRow) + voffset).toFloat(),
        )
        super.onDraw(canvas)
        canvas.restore()
    }

    /**
     * Where the text block actually sits vertically. `gravity` is CENTER_VERTICAL, so
     * TextView centres the layout in the box rather than starting at the top padding,
     * and the layout's own line coordinates have to be shifted by the same amount.
     */
    private fun verticalTextOffset(layout: android.text.Layout): Int {
        val boxHeight = height - compoundPaddingTop - compoundPaddingBottom
        val textHeight = layout.height
        val centred = ((boxHeight - textHeight) / 2).coerceAtLeast(0)
        return compoundPaddingTop + centred
    }

    // Invalidate suspension, used only while the two-pass draw swaps colours.
    override fun invalidate() {
        if (!inDraw) super.invalidate()
    }

    override fun invalidate(dirty: Rect) {
        if (!inDraw) super.invalidate(dirty)
    }

    override fun invalidate(l: Int, t: Int, r: Int, b: Int) {
        if (!inDraw) super.invalidate(l, t, r, b)
    }

    /** Fades the lyric out, then hides it and runs [onHidden] (used to restore the clock). */
    fun hideLyric(onHidden: () -> Unit) {
        if (visibility != VISIBLE) {
            currentLyric = null
            onHidden()
            return
        }
        currentLyric = null
        anim?.cancel()
        anim = animate().alpha(0f).translationY(-shiftPx)
            .setDuration(FADE_MS)
            .setInterpolator(EASING)
            .withEndAction {
                visibility = GONE
                text = ""
                onHidden()
                decidedWidth = -1
            }
            .apply { start() }
    }

    private fun applyText(text: String) {
        decidedWidth = -1
        layOutFor(text, availableWidth())
    }

    /**
     * The parent only reveals how much room we really get during measurement, and it can
     * shrink us on a later pass. Re-run the row choice (on the next frame, never during
     * the measure pass) whenever that width turns out different from what we assumed.
     */
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        super.onMeasure(widthMeasureSpec, heightMeasureSpec)

        if (MeasureSpec.getMode(widthMeasureSpec) == MeasureSpec.UNSPECIFIED) return
        val avail = MeasureSpec.getSize(widthMeasureSpec).coerceAtMost(maxWidth)
        if (avail <= 0) return
        knownWidth = avail

        val text = currentLyric ?: return
        if (avail == decidedWidth) return
        post { if (currentLyric == text) layOutFor(text, avail) }
    }

    private fun availableWidth(): Int =
        if (knownWidth > 0) knownWidth else maxWidth

    /** Our height is what caps the two-row size, so redo the choice when it appears. */
    override fun onSizeChanged(w: Int, h: Int, oldw: Int, oldh: Int) {
        super.onSizeChanged(w, h, oldw, oldh)
        if (h == oldh) return
        val text = currentLyric ?: return
        decidedWidth = -1
        post { if (currentLyric == text) layOutFor(text, availableWidth()) }
    }

    /** Picks a one- or two-row layout for [text] within [avail] px and applies it. */
    private fun layOutFor(text: String, avail: Int) {
        decidedWidth = avail
        val content = (avail - paddingLeft - paddingRight).coerceAtLeast(1)

        paint.textSize = baseTextSizePx
        val fitsOneRow = paint.measureText(text) <= content

        val wantSize: Float
        val wantLines: Int
        val rendered: String

        if (fitsOneRow) {
            wantSize = baseTextSizePx
            wantLines = 1
            rendered = text
        } else {
            wantSize = twoRowTextSize()
            wantLines = 2
            // Break it ourselves: at the smaller size the line would often fit on a
            // single row again, and letting TextView wrap leaves a lopsided second row.
            paint.textSize = wantSize
            rendered = balancedSplit(text, content) ?: text
        }

        // Only touch the properties that actually change — each setter requests a
        // layout, and we are called from a layout pass' tail.
        if (textSize != wantSize) setTextSize(TypedValue.COMPLEX_UNIT_PX, wantSize)
        if (maxLines != wantLines) maxLines = wantLines
        if (this.text?.toString() != rendered) setText(rendered)
    }

    /**
     * Splits [text] at the word boundary that makes the two rows most even, or returns
     * null if no split keeps both rows inside [content] px (then plain wrapping wins).
     * [paint] must already be set to the target text size.
     */
    private fun balancedSplit(text: String, content: Int): String? {
        var bestAt = -1
        var bestWidest = Float.MAX_VALUE

        var i = text.indexOf(' ')
        while (i > 0) {
            val left = paint.measureText(text, 0, i)
            val right = paint.measureText(text, i + 1, text.length)
            val widest = maxOf(left, right)
            if (widest < bestWidest) {
                bestWidest = widest
                bestAt = i
            }
            i = text.indexOf(' ', i + 1)
        }

        if (bestAt < 0 || bestWidest > content) return null
        return text.substring(0, bestAt) + "\n" + text.substring(bestAt + 1)
    }

    /**
     * Text size for two-row mode: the clock's own size, shrunk *only* if two rows at
     * that size wouldn't fit the status bar height (otherwise the second row clips).
     */
    private fun twoRowTextSize(): Float {
        val available = height.takeIf { it > 0 } ?: (parent as? ViewGroup)?.height ?: 0
        if (available <= 0) return baseTextSizePx * FALLBACK_SCALE
        // Each row occupies roughly textSize * LINE_SPACING_MULT * 1.2 vertically.
        return minOf(baseTextSizePx, available / (2f * LINE_SPACING_MULT * 1.2f))
    }

    companion object {
        const val TAG_LYRIC = "statusbar_lyric_16"

        private const val MAX_WIDTH_FRACTION = 0.62f
        private const val START_MARGIN_DP = 4
        /** Only used before the view has a height to measure against. */
        private const val FALLBACK_SCALE = 0.65f
        private const val LINE_SPACING_MULT = 0.92f
        private const val FADE_MS = 160L
        private const val SHIFT_DP = 5f
        private val EASING = AccelerateDecelerateInterpolator()

        fun create(clock: TextView): LyricView =
            LyricView(clock.context).apply {
                tag = TAG_LYRIC
                styleFrom(clock)
            }
    }
}
