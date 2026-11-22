plugins {
    id("com.android.application")
}

val releaseKeystore = rootProject.file("keystore/watchparty-release.jks")

android {
    namespace = "com.imluckii.watchparty"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.imluckii.watchparty"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        create("release") {
            if (releaseKeystore.exists()) {
                storeFile = releaseKeystore
                storePassword = System.getenv("WP_KEYSTORE_PASSWORD") ?: "watchparty"
                keyAlias = System.getenv("WP_KEY_ALIAS") ?: "watchparty"
                keyPassword = System.getenv("WP_KEY_PASSWORD") ?: "watchparty"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (releaseKeystore.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    androidResources {
        // The web bundle is read straight out of the APK by WebViewAssetLoader.
        // Storing these uncompressed + 4-byte aligned lets the WebView stream them
        // without inflating on every request, which is the difference between a
        // local bundle that feels native and one that stutters on first paint.
        noCompress.addAll(
            listOf(
                "js", "css", "wasm", "woff2", "ttf", "json", "png", "jpg", "jpeg",
                "webp", "svg", "ico", "txt", "map", "webmanifest"
            )
        )
    }
}

dependencies {
    implementation("androidx.webkit:webkit:1.14.0")
    implementation("androidx.core:core:1.16.0")
}
