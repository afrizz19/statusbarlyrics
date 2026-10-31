import com.android.build.gradle.internal.api.BaseVariantOutputImpl
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

val localProperties = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.canRead()) f.inputStream().use { load(it) }
}

android {
    namespace = "io.github.afrizz19.statusbarlyric"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.afrizz19.statusbarlyric"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        // Lyrics come from lrcmux, a third-party public API that provides both
        // line-level and word-level (karaoke) timing.
        // Override in local.properties:  lrcmuxBaseUrl=https://api.lrcmux.dev
        val lrcmuxBase = localProperties.getProperty("lrcmuxBaseUrl")
            ?: "https://api.lrcmux.dev"
        buildConfigField("String", "LRCMUX_BASE_URL", "\"$lrcmuxBase\"")
    }

    val signing = localProperties.getProperty("androidStoreFile")?.let {
        signingConfigs.create("release") {
            storeFile = file(it)
            storePassword = localProperties.getProperty("androidStorePassword")
            keyAlias = localProperties.getProperty("androidKeyAlias")
            keyPassword = localProperties.getProperty("androidKeyPassword")
            enableV3Signing = true
            enableV4Signing = true
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signing ?: signingConfigs["debug"]
            setProguardFiles(
                listOf(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            )
        }
        debug {
            signingConfig = signingConfigs["debug"]
        }
    }

    applicationVariants.all {
        outputs.all {
            (this as BaseVariantOutputImpl).outputFileName =
                "StatusBarLyric16-$versionName($versionCode)-$name.apk"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // The API clients touch android.util.Log; the stub throws otherwise and the
        // pure parser tests would fail for a reason that has nothing to do with parsing.
        unitTests.isReturnDefaultValues = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    kotlin { jvmToolchain(21) }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    // Xposed framework API. compileOnly: provided by LSPosed at runtime — must NOT
    // be bundled into the APK.
    compileOnly(libs.xposed.api)

    val composeBom = platform(libs.compose.bom)
    implementation(composeBom)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.material3)
    implementation(libs.compose.ui.tooling.preview)
    debugImplementation(libs.compose.ui.tooling)

    testImplementation(libs.junit)
    // TEST ONLY — do not move to implementation. The Android SDK's org.json is a stub
    // that throws on the JVM, so the parser tests need a real implementation. It is
    // never packaged into the APK, which keeps third-party classes out of SystemUI.
    testImplementation("org.json:json:20240303")
}
