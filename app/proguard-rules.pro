# ===== Xposed / LSPosed =====
# LSPosed loads the entry class by name from assets/xposed_init via reflection,
# so R8 must not rename or strip it (or anything it reaches).
-keep class io.github.afrizz19.statusbarlyric.xposed.** { *; }

# The Xposed API is provided by the framework at runtime (compileOnly).
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }
