plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.duallink.downloader"
    compileSdk = 34
    // CI: use the NDK preinstalled on the runner instead of downloading one.
    System.getenv("ANDROID_NDK_LATEST_HOME")?.takeIf { it.isNotBlank() }?.let {
        ndkPath = it
        ndkVersion = File(it).name // directory is named after the version, e.g. 30.0.16248370
    }

    defaultConfig {
        applicationId = "com.duallink.downloader"
        minSdk = 24
        targetSdk = 34
        // CI build number => every published build is a higher version (used by in-app update).
        val build = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull() ?: 0
        versionCode = 1000 + build
        versionName = "4.$build"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        externalNativeBuild {
            ndkBuild {
                arguments += listOf(
                    "APP_CFLAGS+=-DPKGNAME=com/duallink/downloader -ffile-prefix-map=${rootDir}=.",
                    "APP_LDFLAGS+=-Wl,--build-id=none",
                )
            }
        }
    }

    externalNativeBuild {
        ndkBuild {
            path = file("src/main/jni/Android.mk")
        }
    }

    // One fixed key for every build, so each new APK installs over the previous one.
    signingConfigs {
        create("fixed") {
            storeFile = rootProject.file("signing/duallink.jks")
            storePassword = "duallink-sign"
            keyAlias = "duallink"
            keyPassword = "duallink-sign"
        }
    }

    buildTypes {
        getByName("debug") {
            signingConfig = signingConfigs.getByName("fixed")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("fixed")
        }
    }

    // Store native libs compressed in the APK (much smaller download; unpacked on install).
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Built in CI from xraybridge/ (Go, gomobile): Xray-core + per-socket link binding.
    implementation(files("libs/xraybridge.aar"))
}
