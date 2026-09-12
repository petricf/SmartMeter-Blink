import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

/**
 * Release signing secret: environment variable (upper-cased name) first, then
 * the local `~/.android/blink-release.properties` file (lower-case keys).
 * Never commit a keystore or its passwords to the repository.
 */
fun releaseSigningSecret(name: String): String?
{
    System.getenv(name.uppercase())?.let { return it }
    val propsFile = file("${System.getProperty("user.home")}/.android/blink-release.properties")
    if (propsFile.isFile) {
        val props = Properties()
        propsFile.inputStream().use { props.load(it) }
        props.getProperty(name)?.let { return it }
    }
    return null
}

android {
    namespace = "de.smartmeter.blink"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "de.smartmeter.blink"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            val userHome = System.getProperty("user.home")
            val storePath = releaseSigningSecret("blinkReleaseKeystore")
                ?: "$userHome/.android/blink-release.keystore"
            val storePassword = releaseSigningSecret("blinkStorePassword")
            val keyAlias = releaseSigningSecret("blinkKeyAlias") ?: "blink"
            val keyPassword = releaseSigningSecret("blinkKeyPassword")
            if (storePassword != null && keyPassword != null && file(storePath).isFile) {
                storeFile = file(storePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
            else
            {
                logger.warn(
                    "Release signing credentials missing — falling back to the debug keystore " +
                        "(set BLINK_STORE_PASSWORD / BLINK_KEY_PASSWORD or create " +
                        "~/.android/blink-release.properties)."
                )
                storeFile = file("$userHome/.android/debug.keystore")
                this.storePassword = "android"
                this.keyAlias = "androiddebugkey"
                this.keyPassword = "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
            optimization {
                enable = false
            }
        }
    }

    androidComponents {
        onVariants(selector().all()) { variant ->
            variant.outputs.forEach { output ->
                output.outputFileName.set("SmartMeter-Blink.apk")
            }
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
    implementation(libs.androidx.lifecycle.viewmodel)
    testImplementation(libs.junit)
    testImplementation(libs.json.org)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}