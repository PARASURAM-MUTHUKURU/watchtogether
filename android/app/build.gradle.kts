plugins { id("com.android.application"); id("org.jetbrains.kotlin.android") }
android {
    namespace = "com.watchtogether.app"
    compileSdk = 35
    defaultConfig { applicationId = "com.watchtogether.app"; minSdk = 26; targetSdk = 35; versionCode = 2; versionName = "0.2" }
    signingConfigs {
        create("release") {
            storeFile = file("release.keystore")
            storePassword = "watchtogether123"
            keyAlias = "watchtogether"
            keyPassword = "watchtogether123"
            enableV1Signing = true
            enableV2Signing = true
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    implementation("androidx.media3:media3-exoplayer:1.6.1")
    implementation("androidx.media3:media3-ui:1.6.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.6.1")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
