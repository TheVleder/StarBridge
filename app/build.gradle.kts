import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing: the key never lives in the repository. Locally it is read from
// ~/.starbridge-release/keystore.properties; on CI from STARBRIDGE_STORE_FILE,
// STARBRIDGE_STORE_PASSWORD, STARBRIDGE_KEY_ALIAS and STARBRIDGE_KEY_PASSWORD.
// Without either, release builds are signed with the debug key (still installable).
val releaseSigning = Properties().apply {
    val f = File(System.getProperty("user.home"), ".starbridge-release/keystore.properties")
    if (f.isFile) f.inputStream().use { load(it) }
}
fun signingValue(key: String, env: String): String? = System.getenv(env)?.takeIf { it.isNotBlank() } ?: releaseSigning.getProperty(key)

android {
    namespace = "org.starbridge.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "org.starbridge.app"
        minSdk = 29 // Huawei P20 Pro: Android 10
        targetSdk = 36
        versionCode = 6
        versionName = providers.gradleProperty("starbridgeVersion").get()
    }

    signingConfigs {
        create("release") {
            signingValue("storeFile", "STARBRIDGE_STORE_FILE")?.let { storeFile = file(it) }
            storePassword = signingValue("storePassword", "STARBRIDGE_STORE_PASSWORD")
            keyAlias = signingValue("keyAlias", "STARBRIDGE_KEY_ALIAS")
            keyPassword = signingValue("keyPassword", "STARBRIDGE_KEY_PASSWORD")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            val own = signingConfigs.getByName("release")
            signingConfig = if (own.storeFile?.isFile == true) own else signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // The computer's control panel (desktop/…/desk) is served by the Android too.
    sourceSets["main"].assets.srcDir("../desktop/src/main/resources")

    packaging {
        resources {
            excludes += setOf("META-INF/INDEX.LIST", "META-INF/io.netty.versions.properties")
        }
    }
}

kotlin {
    jvmToolchain(17)
}

dependencies {
    implementation(project(":core"))
    implementation(project(":server"))
    implementation(libs.coroutines.android)
    implementation(libs.usb.serial)
    implementation(libs.ktor.server.cio) {
        exclude(group = "org.fusesource.jansi") // desktop console colors, useless on Android
    }
    implementation(libs.ktor.server.websockets)
}
