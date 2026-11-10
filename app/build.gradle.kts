import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}
android {
    namespace = "io.github.ganondorofu.deskport"
    compileSdk = 36
    defaultConfig {
        applicationId = "io.github.ganondorofu.deskport"
        minSdk = 34
        targetSdk = 36
        versionCode = 2
        versionName = "0.1.1"
    }
    // 公開用の署名鍵は、リポジトリの外(環境変数 DESKPORT_KEYS のフォルダ。なければ、リポジトリの隣の deskport-keys の keystore.properties)に置く。なければ debug 署名になる
    // (注意: 更新できるのは、同じ鍵で署名したアプリだけ。鍵をなくすと、更新を出せなくなる)
    val ks = Properties().apply {
        val f = File(System.getenv("DESKPORT_KEYS") ?: File(rootDir.parentFile, "deskport-keys").path, "keystore.properties")
        if (f.exists()) f.inputStream().use { load(it) }
    }
    signingConfigs {
        if (!ks.isEmpty) create("release") {
            storeFile = file(ks.getProperty("storeFile"))
            storePassword = ks.getProperty("storePassword")
            keyAlias = ks.getProperty("keyAlias")
            keyPassword = ks.getProperty("keyPassword")
        }
    }
    buildTypes {
        release {
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
            isMinifyEnabled = false
        }
    }
    buildFeatures {
        compose = true
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
dependencies {
    val bom = platform("androidx.compose:compose-bom:2025.09.00")
    implementation(bom)
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.window:window:1.4.0")
    implementation("androidx.camera:camera-core:1.4.2")
    implementation("androidx.camera:camera-camera2:1.4.2")
    implementation("androidx.camera:camera-lifecycle:1.4.2")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.2")
    // 更新情報の署名(Ed25519)の検証
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    // QRコードの読み取り(Google の画面を使う。カメラの権限は要らない)
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
}
