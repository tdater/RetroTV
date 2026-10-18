plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.aiconsole.retrotv"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.aiconsole.retrotv"
        minSdk = 24
        targetSdk = 37
        // CI builds number themselves so each APK installs over the last one.
        val ciBuild = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
        val releaseTag = System.getenv("GITHUB_REF_NAME")?.takeIf { System.getenv("GITHUB_REF_TYPE") == "tag" }?.removePrefix("v")
        versionCode = ciBuild ?: 1
        versionName = releaseTag ?: if (ciBuild != null) "1.0.$ciBuild" else "1.0"
    }

    // Release signing from the environment (CI secrets); unsigned release builds without it.
    val keystore = System.getenv("RETROTV_KEYSTORE")?.let(::file)?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) {
            create("release") {
                storeFile = keystore
                storePassword = System.getenv("RETROTV_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RETROTV_KEY_ALIAS")
                keyPassword = System.getenv("RETROTV_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
            if (keystore != null) signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.webkit)
    testImplementation(libs.junit)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
