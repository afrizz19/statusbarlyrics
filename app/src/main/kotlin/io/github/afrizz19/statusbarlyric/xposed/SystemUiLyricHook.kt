package io.github.afrizz19.statusbarlyric.xposed

import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedBridge
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage.LoadPackageParam

/**
 * LSPosed entry point. Hooks SystemUI and injects a lyric TextView next to the
 * status bar clock, then hands it to [LyricController] which does the
 * media-session -> API -> render loop *inside the SystemUI process*.
 *
 * Running in SystemUI is what makes this self-contained: SystemUI already holds
 * MEDIA_CONTENT_CONTROL (read any media session) and INTERNET, so there is no
 * companion service, no NotificationListener, and no SuperLyric middleman.
 */
class SystemUiLyricHook : IXposedHookLoadPackage {

    override fun handleLoadPackage(lpparam: LoadPackageParam) {
        if (lpparam.packageName != SYSTEM_UI) return

        val clockClass = CLOCK_CLASSES.firstNotNullOfOrNull {
            XposedHelpers.findClassIfExists(it, lpparam.classLoader)
        }

        if (clockClass == null) {
            XposedBridge.log("$TAG: no Clock class found; tried $CLOCK_CLASSES")
            return
        }

        XposedBridge.log("$TAG: hooking ${clockClass.name}")

        // Clock extends TextView; onAttachedToWindow fires once it is in the hierarchy,
        // so its parent (the status bar container) is available.
        XposedHelpers.findAndHookMethod(
            clockClass,
            "onAttachedToWindow",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    try {
                        attach(param.thisObject as? TextView ?: return)
                    } catch (t: Throwable) {
                        XposedBridge.log("$TAG: attach failed: ${android.util.Log.getStackTraceString(t)}")
                    }
                }
            },
        )
    }

    private fun attach(clock: TextView) {
        // SystemUI has several Clock instances (status bar, keyguard, quick settings).
        // Only the status bar one is called "clock".
        val idName = runCatching {
            clock.resources.getResourceEntryName(clock.id)
        }.getOrNull()
        if (idName != STATUS_BAR_CLOCK_ID) return

        val parent = clock.parent as? ViewGroup ?: return

        // Idempotent: onAttachedToWindow fires again after every SystemUI relayout.
        if (parent.findViewWithTag<View>(LyricView.TAG_LYRIC) != null) return

        val lyricView = LyricView.create(clock)
        parent.addView(lyricView, parent.indexOfChild(clock) + 1)

        XposedBridge.log(
            "$TAG: injected into ${parent.javaClass.name} " +
                "(clockVisible=${clock.visibility == View.VISIBLE}, " +
                "parentId=${runCatching { parent.resources.getResourceEntryName(parent.id) }.getOrNull()})"
        )
        LyricController.start(clock.context.applicationContext, lyricView, clock)
    }

    private companion object {
        const val TAG = "StatusBarLyric16"
        const val SYSTEM_UI = "com.android.systemui"
        const val STATUS_BAR_CLOCK_ID = "clock"

        val CLOCK_CLASSES = listOf(
            "com.android.systemui.statusbar.policy.Clock",
            "com.android.systemui.statusbar.views.Clock",
        )
    }
}
