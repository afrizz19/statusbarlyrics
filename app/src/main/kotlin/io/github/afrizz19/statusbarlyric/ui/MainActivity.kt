package io.github.afrizz19.statusbarlyric.ui

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.systemBars
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Card
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalUriHandler
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import io.github.afrizz19.statusbarlyric.BuildConfig
import io.github.afrizz19.statusbarlyric.api.LrcMuxApiClient
import io.github.afrizz19.statusbarlyric.prefs.LyricPrefs

/**
 * Status / setup screen and the module's settings. The hook picks changes up from the
 * shared preference file within a second — no reboot, no SystemUI restart.
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MaterialTheme(colorScheme = AppColors) {
                Surface(modifier = Modifier.fillMaxSize()) {
                    SetupScreen()
                }
            }
        }
    }
}

@Composable
private fun SetupScreen() {
    val context = LocalContext.current
    val prefs = remember { LyricPrefs.of(context) }
    var style by remember { mutableStateOf(LyricPrefs.style(prefs)) }

    fun update(next: LyricPrefs.Style) {
        style = next
        LyricPrefs.save(prefs, next)
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            // Before the scroll, so the viewport itself is inset: the status bar and
            // gesture bar never overlap the content, scrolled or not.
            .windowInsetsPadding(WindowInsets.systemBars)
            .verticalScroll(rememberScrollState())
            .padding(20.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
        Header()

        if (!LyricPrefs.sharedWithHook) InactiveWarning()

        SectionLabel("Preview")
        StylePreview(style)

        SectionLabel("Settings")
        SyncSetting(style) { update(it) }
        ModeSetting(style) { update(it) }
        ColorSetting(style) { update(it) }
        ShadowSetting(style) { update(it) }
        OutlineSetting(style) { update(it) }

        SectionLabel("Setup")
        Step(
            1,
            "Enable in LSPosed",
            "Open LSPosed → Modules → enable \"Status Bar Lyric\", and tick " +
                "System UI in its scope.",
        )
        Step(
            2,
            "Reboot (or restart SystemUI)",
            "The hook is installed when SystemUI starts.",
        )
        Step(
            3,
            "Play a song",
            "YouTube Music (incl. ReVanced) or Spotify. The lyric replaces the " +
                "clock while a line is showing, and the clock returns when playback stops.",
        )

        Card(modifier = Modifier.padding(top = 4.dp)) {
            Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text("Lyrics API", style = MaterialTheme.typography.titleSmall)
                Text(
                    "All lyrics come from lrcmux, a public third-party service — " +
                        "${LrcMuxApiClient.DEFAULT_BASE_URL}/get?artist=…&title=…",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }

        Text(
            "No notification access or companion service is needed — SystemUI can " +
                "already read media sessions.",
            style = MaterialTheme.typography.bodySmall,
        )
        Footer()
    }
}

/** Sample lyric on a status-bar-like strip, drawn with the current settings. */
@Composable
private fun StylePreview(style: LyricPrefs.Style) {
    val following = style.textColor == LyricPrefs.FOLLOW_CLOCK
    val fill = if (following) Color(0xFFE8E8E8) else Color(style.textColor)
    val wordMode = style.mode == LyricPrefs.MODE_WORD
    val dim = Color(LyricPrefs.dimColorFor(style, fill.toArgb()))

    val base = TextStyle(
        fontSize = 15.sp,
        shadow = if (style.shadowOn) Shadow(
            color = Color(style.shadowColor),
            offset = Offset(style.shadowDxDp, style.shadowDyDp),
            blurRadius = style.shadowRadiusDp,
        ) else null,
    )

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(Color(0xFF202124), RoundedCornerShape(8.dp))
            .padding(horizontal = 12.dp, vertical = 12.dp),
        contentAlignment = Alignment.CenterStart,
    ) {
        // Stroke behind, fill on top — the same two passes the status bar view does.
        if (style.outlineOn && style.outlineWidthDp > 0f) {
            Text(
                PREVIEW_TEXT,
                color = Color(style.outlineColor),
                style = base.copy(drawStyle = Stroke(width = style.outlineWidthDp * 2f)),
            )
        }
        if (wordMode) {
            // Frozen mid-sweep, so the two colours are both visible at a glance.
            Text(
                buildAnnotatedString {
                    val at = (PREVIEW_TEXT.length * PREVIEW_SUNG_FRACTION).toInt()
                    withStyle(SpanStyle(color = fill)) { append(PREVIEW_TEXT.take(at)) }
                    withStyle(SpanStyle(color = dim)) { append(PREVIEW_TEXT.drop(at)) }
                },
                style = base,
            )
        } else {
            Text(PREVIEW_TEXT, color = fill, style = base)
        }
        if (following) {
            Text(
                "  (follows the clock colour)",
                color = Color(0xFF8A8A8A),
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 26.dp),
            )
        }
    }
}

/**
 * Word-level (karaoke) animation toggle. All lyrics come from lrcmux;
 * it provides word timings when available, otherwise line-level timing.
 */
@Composable
private fun ModeSetting(style: LyricPrefs.Style, onChange: (LyricPrefs.Style) -> Unit) {
    val wordMode = style.mode == LyricPrefs.MODE_WORD

    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleHeader("Per-word (karaoke)", wordMode) {
                onChange(style.copy(mode = if (it) LyricPrefs.MODE_WORD else LyricPrefs.MODE_LINE))
            }
            Text(
                "Each word lights up as it's sung. Lrcmux provides word timings when " +
                    "available; tracks with only line timing show as a plain line.",
                style = MaterialTheme.typography.bodySmall,
            )

            if (wordMode) {
                Text("Not-yet-sung colour", style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = { onChange(style.copy(wordDimColor = LyricPrefs.DERIVE_DIM)) },
                    enabled = style.wordDimColor != LyricPrefs.DERIVE_DIM,
                ) {
                    Text(
                        if (style.wordDimColor == LyricPrefs.DERIVE_DIM) "Fading the lyric colour"
                        else "Fade the lyric colour instead"
                    )
                }

                if (style.wordDimColor == LyricPrefs.DERIVE_DIM) {
                    DpSlider("Dim amount", style.wordDimAlpha, 0.1f, 0.9f, 15) {
                        onChange(style.copy(wordDimAlpha = it))
                    }
                } else {
                    ColorPicker(style.wordDimColor, sentinel = LyricPrefs.DERIVE_DIM) {
                        onChange(style.copy(wordDimColor = it))
                    }
                }

                ToggleHeader("Smooth sweep", style.wordSmooth) {
                    onChange(style.copy(wordSmooth = it))
                }
                Text(
                    "Animates between updates instead of stepping 10× a second. " +
                        "Smoother, but redraws the status bar every frame while a word is sung.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

/**
 * Manual timing nudge. Music apps publish their playback position lazily and Bluetooth
 * adds its own delay, so the offset lets the user pull the lyric forward to match.
 */
@Composable
private fun SyncSetting(style: LyricPrefs.Style, onChange: (LyricPrefs.Style) -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("Sync offset", style = MaterialTheme.typography.titleSmall)
                TextButton(
                    onClick = { onChange(style.copy(syncOffsetMs = LyricPrefs.DEF_SYNC_OFFSET)) },
                    enabled = style.syncOffsetMs != LyricPrefs.DEF_SYNC_OFFSET,
                ) { Text("Reset") }
            }

            Text(
                "${if (style.syncOffsetMs > 0) "+" else ""}${style.syncOffsetMs} ms" +
                    when {
                        style.syncOffsetMs > 0 -> " (lines show earlier)"
                        style.syncOffsetMs < 0 -> " (lines show later)"
                        else -> ""
                    },
                style = MaterialTheme.typography.bodySmall,
            )
            Slider(
                value = style.syncOffsetMs.toFloat(),
                onValueChange = { onChange(style.copy(syncOffsetMs = (it / 50f).toInt() * 50)) },
                valueRange = LyricPrefs.SYNC_OFFSET_MIN.toFloat()..LyricPrefs.SYNC_OFFSET_MAX.toFloat(),
                steps = 59,
            )
            Text(
                "Drag right if the lyric arrives late. Bluetooth headphones usually need " +
                    "+150 to +300 ms.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ColorSetting(style: LyricPrefs.Style, onChange: (LyricPrefs.Style) -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text("Lyric colour", style = MaterialTheme.typography.titleSmall)

            TextButton(
                onClick = { onChange(style.copy(textColor = LyricPrefs.FOLLOW_CLOCK)) },
                enabled = style.textColor != LyricPrefs.FOLLOW_CLOCK,
            ) {
                Text(
                    if (style.textColor == LyricPrefs.FOLLOW_CLOCK) "Following the clock colour"
                    else "Follow the clock colour"
                )
            }

            ColorPicker(style.textColor, sentinel = LyricPrefs.FOLLOW_CLOCK) {
                onChange(style.copy(textColor = it))
            }

            Text(
                "Applies within a second — no restart needed.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun ShadowSetting(style: LyricPrefs.Style, onChange: (LyricPrefs.Style) -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleHeader("Shadow", style.shadowOn) { onChange(style.copy(shadowOn = it)) }

            if (style.shadowOn) {
                ColorPicker(style.shadowColor, alphaAware = true) {
                    onChange(style.copy(shadowColor = it))
                }
                DpSlider("Blur", style.shadowRadiusDp, 0f, 8f, 16) {
                    onChange(style.copy(shadowRadiusDp = it))
                }
                DpSlider("Offset X", style.shadowDxDp, -4f, 4f, 16) {
                    onChange(style.copy(shadowDxDp = it))
                }
                DpSlider("Offset Y", style.shadowDyDp, -4f, 4f, 16) {
                    onChange(style.copy(shadowDyDp = it))
                }
            }
        }
    }
}

@Composable
private fun OutlineSetting(style: LyricPrefs.Style, onChange: (LyricPrefs.Style) -> Unit) {
    Card {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            ToggleHeader("Outline", style.outlineOn) { onChange(style.copy(outlineOn = it)) }

            if (style.outlineOn) {
                ColorPicker(style.outlineColor) { onChange(style.copy(outlineColor = it)) }
                DpSlider("Thickness", style.outlineWidthDp, 0.25f, 3f, 10) {
                    onChange(style.copy(outlineWidthDp = it))
                }
                Text(
                    "A thick outline on small text can close up the letters.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

@Composable
private fun ToggleHeader(title: String, checked: Boolean, onCheck: (Boolean) -> Unit) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall)
        Switch(checked = checked, onCheckedChange = onCheck)
    }
}

@Composable
private fun DpSlider(
    label: String,
    value: Float,
    from: Float,
    to: Float,
    steps: Int,
    onChange: (Float) -> Unit,
) {
    Column {
        val formatted = if (label.startsWith("Dim")) {
            String.format("%.2f", value)
        } else {
            String.format("%.2f", value).trimEnd('0').trimEnd('.')
        }
        val unit = if (label.startsWith("Dim")) "" else " dp"
        Text(
            "$label: $formatted$unit",
            style = MaterialTheme.typography.bodySmall,
        )
        Slider(
            value = value.coerceIn(from, to),
            onValueChange = onChange,
            valueRange = from..to,
            steps = steps,
        )
    }
}

@Composable
private fun Swatches(selected: Int, onPick: (Int) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LyricPrefs.PRESETS.chunked(5).forEach { row ->
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                row.forEach { color -> Swatch(color, color == selected) { onPick(color) } }
            }
        }
    }
}

/**
 * Presets plus a free-form hex field, for any setting that is just a colour.
 *
 * The field is keyed on [selected] so picking a swatch refreshes the text, but typing
 * only pushes a change once the input actually parses — a half-typed `#FF` shouldn't
 * repaint the status bar. [alphaAware] keeps the `#AARRGGBB` hint on the shadow, where
 * a translucent colour is a normal thing to want.
 *
 * [sentinel] is the "not an explicit colour" value for settings that have one
 * ([LyricPrefs.FOLLOW_CLOCK], [LyricPrefs.DERIVE_DIM]). Both are 0, which is a real
 * ARGB value, so the field must blank instead of claiming `#00000000`.
 */
@Composable
private fun ColorPicker(
    selected: Int,
    alphaAware: Boolean = false,
    sentinel: Int? = null,
    onPick: (Int) -> Unit,
) {
    var hex by remember(selected) {
        mutableStateOf(if (selected == sentinel) "" else LyricPrefs.toHex(selected))
    }
    var invalid by remember { mutableStateOf(false) }

    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Swatches(selected) { onPick(it) }
        OutlinedTextField(
            value = hex,
            onValueChange = { input ->
                hex = input
                val parsed = LyricPrefs.parseHex(input)
                invalid = input.isNotBlank() && parsed == null
                if (parsed != null) onPick(parsed)
            },
            label = { Text("Custom colour") },
            placeholder = { Text(if (alphaAware) "#AARRGGBB" else "#RRGGBB") },
            isError = invalid,
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )
    }
}

@Composable
private fun Swatch(color: Int, isSelected: Boolean, onClick: () -> Unit) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(Color(color), CircleShape)
            .border(
                width = if (isSelected) 3.dp else 1.dp,
                color = if (isSelected) MaterialTheme.colorScheme.primary
                else MaterialTheme.colorScheme.outlineVariant,
                shape = CircleShape,
            )
            .clickable(onClick = onClick),
    )
}

@Composable
private fun Step(number: Int, title: String, body: String) {
    Card {
        Row(
            modifier = Modifier.padding(14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .background(MaterialTheme.colorScheme.primaryContainer, CircleShape),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    number.toString(),
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold,
                )
            }
            Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(title, style = MaterialTheme.typography.titleSmall)
                Text(body, style = MaterialTheme.typography.bodySmall)
            }
        }
    }
}

/** Where the credit and the GitHub profile link point. */
private const val AUTHOR_NAME = "Afrizz19"
private const val AUTHOR_URL = "https://github.com/afrizz19"
private const val REPO_URL = "https://github.com/afrizz19/io.github.afrizz19.statusbarlyric"

private val AppColors = darkColorScheme(
    primary = Color(0xFFC4B0FF),
    onPrimary = Color(0xFF2A1366),
    primaryContainer = Color(0xFF42308C),
    onPrimaryContainer = Color(0xFFE7DDFF),
    secondary = Color(0xFF7FD6C8),
    background = Color(0xFF111114),
    onBackground = Color(0xFFE6E1E9),
    surface = Color(0xFF111114),
    onSurface = Color(0xFFE6E1E9),
    onSurfaceVariant = Color(0xFFC9C4D0),
    surfaceContainerHighest = Color(0xFF1E1D24),
    outlineVariant = Color(0xFF3A3842),
)

/** Gradient hero: app name, one-line summary, version and the author's GitHub link. */
@Composable
private fun Header() {
    val uriHandler = LocalUriHandler.current
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .background(
                Brush.linearGradient(listOf(Color(0xFF42308C), Color(0xFF1B5560))),
                RoundedCornerShape(24.dp),
            )
            .padding(20.dp),
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(
                "Status Bar Lyric",
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = Color.White,
            )
            Text(
                "Synced lyrics in your status bar. An LSPosed module that runs " +
                    "entirely inside SystemUI.",
                style = MaterialTheme.typography.bodyMedium,
                color = Color.White.copy(alpha = 0.85f),
            )
            Row(
                modifier = Modifier.padding(top = 6.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Pill("v${BuildConfig.VERSION_NAME}")
                Pill("by $AUTHOR_NAME", onClick = { uriHandler.openUri(AUTHOR_URL) })
            }
        }
    }
}

@Composable
private fun Pill(text: String, onClick: (() -> Unit)? = null) {
    val shape = RoundedCornerShape(50)
    Text(
        text,
        style = MaterialTheme.typography.labelLarge,
        color = Color.White,
        modifier = Modifier
            .background(Color.White.copy(alpha = 0.16f), shape)
            .border(1.dp, Color.White.copy(alpha = 0.28f), shape)
            .let { if (onClick != null) it.clickable(onClick = onClick) else it }
            .padding(horizontal = 12.dp, vertical = 6.dp),
    )
}

/** Shown when LSPosed isn't active for this app, so settings can't reach SystemUI. */
@Composable
private fun InactiveWarning() {
    Card(
        colors = androidx.compose.material3.CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.errorContainer,
            contentColor = MaterialTheme.colorScheme.onErrorContainer,
        ),
    ) {
        Column(Modifier.padding(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("Module not active", style = MaterialTheme.typography.titleSmall)
            Text(
                "LSPosed isn't sharing this app's settings with System UI, so changes here " +
                    "won't affect the status bar. Enable this module in LSPosed (scope: " +
                    "System UI), reboot, then reopen the app. If an older copy of the module " +
                    "is installed, disable and uninstall it.",
                style = MaterialTheme.typography.bodySmall,
            )
        }
    }
}

@Composable
private fun SectionLabel(text: String) {
    Text(
        text.uppercase(),
        style = MaterialTheme.typography.labelMedium,
        fontWeight = FontWeight.Bold,
        letterSpacing = 1.2.sp,
        color = MaterialTheme.colorScheme.primary,
        modifier = Modifier.padding(top = 10.dp, start = 4.dp),
    )
}

/** Credit line with a link to the author's GitHub profile and the project repo. */
@Composable
private fun Footer() {
    val uriHandler = LocalUriHandler.current
    Spacer(Modifier.height(4.dp))
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(2.dp),
    ) {
        Text(
            "Made by $AUTHOR_NAME",
            style = MaterialTheme.typography.titleSmall,
            textAlign = TextAlign.Center,
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { uriHandler.openUri(AUTHOR_URL) }) {
                Text("GitHub profile")
            }
            Spacer(Modifier.width(4.dp))
            TextButton(onClick = { uriHandler.openUri(REPO_URL) }) {
                Text("Source code")
            }
        }
        Text(
            "v${BuildConfig.VERSION_NAME} - GPL-3.0",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
    Spacer(Modifier.height(8.dp))
}

private const val PREVIEW_TEXT = "It's a cruel summer"

/** Where the preview freezes the karaoke sweep, so both colours are visible. */
private const val PREVIEW_SUNG_FRACTION = 0.55f
