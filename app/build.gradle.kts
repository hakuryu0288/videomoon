plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.videomoon.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.otsuuchi.cam"
        minSdk = 29          // Android 10+（MediaStoreの保存先指定・CameraXの60fps指定を素直に使うため）
        targetSdk = 35
        versionCode = 2
        versionName = "0.2.0"
    }

    // 毎回同じ鍵で署名する（鍵が変わると、新しいAPKを上書きインストールできなくなるため）
    // ※テスト配布用の鍵。Google Playで公開するときは別の鍵を用意すること
    signingConfigs {
        getByName("debug") {
            storeFile = file("videomoon-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"))
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    lint {
        // Media3の「UnstableApi」（書き出し・フェード処理で使用）をリリースビルドで止めない
        disable += "UnsafeOptInUsageError"
    }
    buildFeatures { compose = true }
}

dependencies {
    val camerax = "1.4.1"
    val media3 = "1.5.1"
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-video:$camerax")
    implementation("androidx.camera:camera-view:$camerax")

    implementation("androidx.media3:media3-exoplayer:$media3")
    implementation("androidx.media3:media3-transformer:$media3")
    implementation("androidx.media3:media3-effect:$media3")
    implementation("androidx.media3:media3-common:$media3")
    implementation("androidx.media3:media3-ui:$media3")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
}
