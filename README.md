<div align="center">

# 🎵 Status Bar Lyric

### Synced lyrics, right in your Android status bar.

An **LSPosed module** for **Android 16** that shows the line you're listening to, in time with the music, in place of the clock. No companion app, no notification access, no background service.

[![Latest release](https://img.shields.io/github/v/release/afrizz19/io.github.afrizz19.statusbarlyric?style=for-the-badge&color=1db954)](https://github.com/afrizz19/io.github.afrizz19.statusbarlyric/releases/latest)
[![License: GPL-3.0](https://img.shields.io/badge/license-GPL--3.0-blue?style=for-the-badge)](#-license)
[![Android 16](https://img.shields.io/badge/Android-16-3ddc84?style=for-the-badge&logo=android&logoColor=white)](#-requirements)
[![Issues](https://img.shields.io/github/issues/afrizz19/io.github.afrizz19.statusbarlyric?style=for-the-badge)](https://github.com/afrizz19/io.github.afrizz19.statusbarlyric/issues)

[**⬇️ Download**](https://github.com/afrizz19/io.github.afrizz19.statusbarlyric/releases/latest) · [**🛠 Setup**](#-setup) · [**🐛 Report a bug**](https://github.com/afrizz19/io.github.afrizz19.statusbarlyric/issues/new) · [**💡 Request a feature**](https://github.com/afrizz19/io.github.afrizz19.statusbarlyric/issues/new)

<!-- Add a screenshot or GIF here, it is the best way to attract people:
<img src="docs/preview.gif" width="600" alt="Status Bar Lyric preview"> -->

</div>

---

## 🚧 Work in progress

This project is **still under active development**. Things may break, some ROMs may not work yet, and some songs may sync badly.

**If you see a problem or have an idea, please [open an issue](https://github.com/afrizz19/io.github.afrizz19.statusbarlyric/issues/new).** Every report helps, especially:

- 🐛 **Bugs**: lyrics out of sync, the clock not coming back, crashes in SystemUI
- 📱 **ROM / device compatibility**: tell me your device, ROM and Android version
- 💡 **Feature requests**: new players, styling options, anything you'd like to see
- 🎶 **Wrong or missing lyrics**: include the song title and artist

> **Tip:** when reporting a bug, attach your LSPosed log and say which player you used (Spotify, YouTube Music, or ReVanced). It makes fixing it much faster.

Pull requests are welcome too. ⭐ Star the repo if you like it, it helps other people find it.

---

## ✨ Features

|                                                  |                                                                 |
| ------------------------------------------------ | --------------------------------------------------------------- |
| 🎤 **Synced lyrics**                             | The active line replaces the clock while it's showing           |
| 🎼 **Line or karaoke mode**                      | Highlight each word as it's sung, with an optional smooth sweep |
| 🎧 **Spotify, YouTube Music, YT Music ReVanced** | Reads the media session directly                                |
| 📐 **Two-row layout**                            | Long lines split evenly, at the clock's own text size           |
| 🎞 **Animated transitions**                      | Lines fade out upward and back in from below                    |
| 🎨 **Fully styleable**                           | Colour, shadow and outline with a live preview                  |
| ⏱ **Sync offset**                                | ±1500 ms to compensate for Bluetooth latency                    |
| ⚡ **Live changes**                              | No reboot and no SystemUI restart                               |

---

## 📋 Requirements

- A **rooted** device with **LSPosed** (developed on KernelSU + zygisk_lsposed)
- **Android 16** (`minSdk 30`, `targetSdk 36`)

That's it. No SuperLyric and no separate renderer app.

---

## 🛠 Setup

1. **Download** the APK from [Releases](https://github.com/afrizz19/io.github.afrizz19.statusbarlyric/releases/latest) and install it (or [build it yourself](#-building)).
2. Open **LSPosed → Modules → Status Bar Lyric**, enable it, and tick **System UI** in its scope.
3. **Reboot** (or restart SystemUI).
4. **Open the app once** so the settings file is created, then play a song in Spotify or YouTube Music.

<details>
<summary><b>Using your own lyrics API mirror</b></summary>

The default base URL is `https://api.lrcmux.dev`. To override it without touching code, add this to `local.properties`:

```properties
lrcmuxBaseUrl=https://your-lrcmux-mirror.example.com
```

The app calls `{base}/get?artist=<artist>&title=<title>&level=word&format=json`.

</details>

---

## ⚙️ How it works

Everything runs **inside the SystemUI process**. SystemUI already holds `MEDIA_CONTENT_CONTROL` and `INTERNET`, so the module can read any app's media session **without notification-listener access** and fetch lyrics directly.

```
                    ┌──────────────────────── SystemUI process ────────────────────────┐
 ┌────────────────┐ │  MediaSessionReader ──▶ LrcMuxApiClient ──▶ LyricSyncEngine ──┐   │
 │ Spotify /      │─┼─▶ (getActiveSessions)     GET lrcmux.dev     active line      │   │
 │ YT Music       │ │                                                               ▼   │
 └────────────────┘ │                                    LyricView (status bar)     ✦   │
                    └──────────────────────────────────────────────────────────────────┘
```

1. `SystemUiLyricHook` hooks `Clock.onAttachedToWindow` and injects a lyric `TextView` next to the status bar clock.
2. `LyricController` ticks every 300 ms; `MediaSessionReader` reads title, artist, duration, position and play state.
3. On a track change, lyrics are fetched from the lrcmux API on a background thread (line and word timing when available).
4. `LyricSyncEngine` binary-searches the active line for the current playback position.
5. The line is drawn in the status bar. The clock is hidden while a lyric shows and restored when playback stops.

<details>
<summary><b>🎨 Rendering details</b></summary>

- **Line / per-word (karaoke) mode.** In word mode the sung part is drawn in the lyric colour and the rest is dimmed. `WordProgressResolver` turns the playback position into a fractional character offset that clips the highlight. Tracks with only line timing fall back to a plain line.
- **Bilingual lyrics.** `TranslationFilter` drops interleaved Chinese translation lines that some sources return, so the lyric doesn't flicker between lines.
- **Animated transitions.** Each new line fades out upward, swaps, and fades back in from below (160 ms).
- **Two rows on overflow.** Lines that don't fit are split at the word boundary that makes the rows most even, at the clock's own text size. It only shrinks if two full-size rows wouldn't fit. If even two rows don't fit, the second row ellipsizes.
- Width is capped at **62 %** of the screen so status bar icons are never pushed off.

</details>

<details>
<summary><b>⏱ Sync details</b></summary>

Playback position is extrapolated from the media session's last `PlaybackState` sample. Two things get in the way, and both are handled:

- **Apps that lie about the position.** YouTube Music (including ReVanced) republishes `position=0` mid-song while moving `lastPositionUpdateTime` to _now_. Taken at face value, the lyric would jump back to an early line. `MediaSessionReader` keeps its own anchor and rejects a "back at zero" report when playback never stopped. A genuine backwards seek reports where it landed, so it's still honoured.
- **Everything else that adds delay** (Bluetooth latency, late sampling). It can't be measured from outside, so there's a **Sync offset** slider (±1500 ms). Positive shows lines earlier. Bluetooth headphones usually want **+150 to +300 ms**.

The active line is re-evaluated every 100 ms. The media session is only polled every 500 ms (it's a binder call), and the cached snapshot extrapolates in between. `PlaybackState.speed` is honoured, so non-1× playback doesn't drift.

</details>

<details>
<summary><b>🖌 Colour, shadow and outline</b></summary>

- **Colour**: 10 presets, a `#RRGGBB` field, or "follow the clock colour" (default, tracks light/dark status bars).
- **Shadow**: on/off, colour, blur radius, and X/Y offset (dp).
- **Outline**: on/off, colour and thickness (dp). Drawn as a stroked pass under the fill pass, which is handy over busy wallpapers on a transparent status bar.

Settings live in a shared preference file that LSPosed makes world-readable (`xposedsharedprefs`), read inside SystemUI with `XSharedPreferences` at most once a second, so **changes apply live**. Opening the app once creates the file.

</details>

---

## 🔨 Building

Prerequisites: **JDK 21** and the Android SDK with **API 36**. Make sure `local.properties` has a correct `sdk.dir`.

```bash
./gradlew assembleDebug        # debug APK
./gradlew assembleRelease      # release APK
./gradlew testDebugUnitTest    # unit tests (sync engine, word timing)
```

APK output: `app/build/outputs/apk/<variant>/StatusBarLyric16-<ver>-<variant>.apk`

<details>
<summary><b>Signing and publishing a release</b></summary>

Without a keystore, `assembleRelease` signs with the debug key. To use your own, add this to `local.properties` (git-ignored):

```properties
androidStoreFile=C:/path/to/keystore.jks
androidStorePassword=...
androidKeyAlias=...
androidKeyPassword=...
```

> Keep signing with the **same key** across releases. Android only allows updating an installed app with an APK signed by the same key.

</details>

---

## 🗂 Project layout

```
app/src/main/kotlin/io/github/afrizz19/statusbarlyric/
├── xposed/   SystemUiLyricHook.kt   LSPosed entry; injects the lyric view
│             LyricController.kt     tick loop: read → fetch → render
│             LyricView.kt           the status bar view, styled from the clock
│             HookPrefs.kt           reads settings inside SystemUI (XSharedPreferences)
├── api/      LrcMuxApiClient.kt     HttpURLConnection + org.json (no OkHttp)
│             LyricsRepository.kt
│             LyricsModels.kt
│             TranslationFilter.kt   strips interleaved translation lines
├── media/    MediaSessionReader.kt, TrackInfo.kt
├── prefs/    LyricPrefs.kt          shared settings (Style, presets, hex parsing)
├── sync/     LyricSyncEngine.kt, WordProgressResolver.kt, WordTimingSynthesizer.kt
└── ui/       MainActivity.kt        setup screen + style settings
```

> **Why no OkHttp / kotlinx-serialization?** The networking code is loaded into **SystemUI**, so it uses only framework classes (`HttpURLConnection`, `org.json`). That way no third-party library gets loaded into the system process. Please keep it that way in pull requests.

---

## ⚠️ Known limitations

- **Position accuracy**: position is extrapolated between `PlaybackState` samples, assuming steady playback. Fine for line-level sync.
- **Unsynced tracks**: if lrcmux returns nothing, or the track is instrumental, no lyric is shown (logged only).
- **Word-level karaoke** needs word timings from lrcmux. Tracks without them show as a plain line. `WordTimingSynthesizer` can approximate word windows by character weight, but it is **not wired into the renderer yet**.
- **ROM compatibility**: the hook looks for `Clock` in `com.android.systemui.statusbar.policy` and `.views`. Heavily customised ROMs may need another class added to `CLOCK_CLASSES`. [Open an issue](https://github.com/afrizz19/io.github.afrizz19.statusbarlyric/issues/new) with your ROM and I'll look into it.
- **Legality**: make sure your lyrics API usage complies with the source's terms.

---

## 🤝 Contributing

1. Found a bug or have an idea? [Open an issue](https://github.com/afrizz19/io.github.afrizz19.statusbarlyric/issues/new).
2. Want to fix it yourself? Fork the repo, make your change, run `./gradlew testDebugUnitTest`, and open a pull request.

## 📄 License

Released under the **GPL-3.0** license. See [LICENSE](LICENSE).
