package io.github.afrizz19.statusbarlyric.api

import android.net.Uri
import android.util.Log
import io.github.afrizz19.statusbarlyric.BuildConfig
import org.json.JSONObject
import java.io.BufferedReader
import java.net.HttpURLConnection
import java.net.URL

/**
 * Client for the public lrcmux API, which is the only source here that publishes
 * per-word ("karaoke") timings.
 *
 *   GET {base}/get?artist=…&title=…&level=word&format=json
 *   { "track": {...},
 *     "meta":  { "level": "word"|"line"|"none", "instrumental": bool, "source": {...} },
 *     "lines": [ { "text", "start", "end", "words": [ { "text", "start", "end" } ] } ] }
 *
 * All times are absolute milliseconds from the start of the track.
 *
 * Framework-only: [HttpURLConnection] + [org.json], because this runs inside the
 * SystemUI process. [fetchLyrics] blocks — call it off the main thread.
 */
class LrcMuxApiClient(
    private val baseUrl: String = DEFAULT_BASE_URL,
) {

    /**
     * @param durationMs track length, 0 if unknown. Both this and [album] are optional but
     *        materially improve which recording the server matches.
     * @return the result, or null on any failure (network, non-200, empty, parse).
     */
    fun fetchLyrics(
        artist: String,
        title: String,
        album: String = "",
        durationMs: Long = 0L,
    ): LyricsResult? {
        if (artist.isBlank() || title.isBlank()) return null

        val builder = Uri.parse(baseUrl.trimEnd('/'))
            .buildUpon()
            .appendPath("get")
            .appendQueryParameter("artist", artist)
            .appendQueryParameter("title", title)
            // Ask for word level but deliberately do NOT send `strict`: when a track has no
            // word data the server downgrades to line level, which is exactly the fallback
            // we want to render.
            .appendQueryParameter("level", "word")
            .appendQueryParameter("format", "json")
        if (album.isNotBlank()) builder.appendQueryParameter("album", album)
        if (durationMs > 0) builder.appendQueryParameter("duration", (durationMs / 1000).toString())

        var conn: HttpURLConnection? = null
        return try {
            conn = (URL(builder.build().toString()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = 10_000
                readTimeout = 15_000
                setRequestProperty("Accept", "application/json")
            }

            val code = conn.responseCode
            if (code != HttpURLConnection.HTTP_OK) {
                Log.w(TAG, "lrcmux $code for $artist - $title")
                return null
            }

            val body = conn.inputStream.bufferedReader().use(BufferedReader::readText)
            parse(body)?.takeIf { it.lines.isNotEmpty() }
        } catch (t: Throwable) {
            Log.w(TAG, "lrcmux fetch failed for $artist - $title", t)
            null
        } finally {
            conn?.disconnect()
        }
    }

    companion object {
        private const val TAG = "LrcMuxApiClient"
        val DEFAULT_BASE_URL: String = BuildConfig.LRCMUX_BASE_URL

        /**
         * Pure body parser, split out so it can be unit-tested without a device.
         *
         * @return null if the body isn't parseable at all. An instrumental track, or one
         *         with `lines: null`, parses fine and simply yields no lines.
         */
        internal fun parse(body: String): LyricsResult? {
            val root = try {
                JSONObject(body)
            } catch (t: Throwable) {
                Log.w(TAG, "lrcmux body is not JSON", t)
                return null
            }

            val meta = root.optJSONObject("meta")
            val level = when (meta?.optString("level")) {
                "word" -> SyncLevel.WORD
                "line" -> SyncLevel.LINE
                else -> SyncLevel.NONE
            }
            val source = meta?.optJSONObject("source")?.optString("id")?.takeIf { it.isNotBlank() }

            // `lines` is nullable in the schema, and an instrumental track has nothing to show.
            if (meta?.optBoolean("instrumental") == true) {
                return LyricsResult(emptyList(), level, source)
            }
            val arr = root.optJSONArray("lines") ?: return LyricsResult(emptyList(), level, source)

            val out = ArrayList<SyncedLine>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                val words = parseWords(o.optJSONArray("words"))

                // Build the line text from the words when we have them: that makes the
                // "character index into the line == character index across the words"
                // invariant true by construction, which is what the karaoke clip relies on.
                val text = words?.joinToString("") { it.word } ?: o.optString("text")
                if (text.isBlank()) continue

                val start = o.optLong("start", words?.firstOrNull()?.startMs ?: 0L)
                val end = if (o.has("end")) o.optLong("end") else words?.lastOrNull()?.endMs

                out.add(
                    SyncedLine(
                        startMs = start,
                        text = text,
                        endMs = end?.takeIf { it > start },
                        words = words,
                    )
                )
            }
            out.sortBy { it.startMs }
            return LyricsResult(out, level, source)
        }

        /** @return null when the line carries no usable word data (the documented `words: null`). */
        private fun parseWords(arr: org.json.JSONArray?): List<TimedWord>? {
            if (arr == null || arr.length() == 0) return null
            val out = ArrayList<TimedWord>(arr.length())
            for (i in 0 until arr.length()) {
                val o = arr.optJSONObject(i) ?: continue
                // Trailing spaces are part of the word text upstream and must be kept —
                // trimming them would break the concatenation invariant above.
                val text = o.optString("text")
                if (text.isEmpty()) continue
                val start = o.optLong("start")
                out.add(TimedWord(text, start, o.optLong("end").coerceAtLeast(start)))
            }
            return out.takeIf { it.isNotEmpty() }
        }
    }
}
