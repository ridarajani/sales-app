import java.util.Properties
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.composeCompiler)
}

// Kept on this Mac only, never committed: the live server address in local.properties
// (salesApiUrl=https://sales.example.com) and the signing key in keystore.properties.
fun propertiesFile(name: String) = Properties().apply {
    rootProject.file(name).takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val local = propertiesFile("local.properties")
val keystore = propertiesFile("keystore.properties")
val liveApiUrl = (local.getProperty("salesApiUrl") ?: "").trim().trimEnd('/')
val appVersionName = "1.0.0"

dependencies {
    implementation(projects.shared)
    implementation(libs.androidx.activity.compose)
    implementation(libs.compose.foundation)
}

android {
    namespace = "com.salesapp"
    compileSdk = libs.versions.android.compileSdk.get().toInt()

    defaultConfig {
        applicationId = "com.salesapp"
        minSdk = libs.versions.android.minSdk.get().toInt()
        targetSdk = libs.versions.android.targetSdk.get().toInt()
        // Each new version for staff needs a bigger versionCode: the in-app update check compares it.
        versionCode = 2
        versionName = appVersionName
    }
    buildFeatures {
        buildConfig = true
    }
    signingConfigs {
        if (keystore.getProperty("storeFile") != null) {
            create("release") {
                storeFile = rootProject.file(keystore.getProperty("storeFile"))
                storePassword = keystore.getProperty("storePassword")
                keyAlias = keystore.getProperty("keyAlias")
                keyPassword = keystore.getProperty("keyPassword")
            }
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
    buildTypes {
        getByName("debug") {
            // The Mac, as the Android emulator sees it.
            buildConfigField("String", "API_URL", "\"http://10.0.2.2:3000\"")
        }
        getByName("release") {
            isMinifyEnabled = false
            buildConfigField("String", "API_URL", "\"$liveApiUrl\"")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_11
    }
}

// The file staff download is called sales-1.0.0-release.apk.
base {
    archivesName = "sales-$appVersionName"
}

// A release build must know the live https address and have its signing key, or it stops here.
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    doFirst {
        require(liveApiUrl.startsWith("https://")) {
            "Set salesApiUrl=https://your-live-address in mobile/local.properties before building a release."
        }
        require(keystore.getProperty("storeFile") != null) {
            "Put the signing key details in mobile/keystore.properties (see keystore.properties.example)."
        }
    }
}
