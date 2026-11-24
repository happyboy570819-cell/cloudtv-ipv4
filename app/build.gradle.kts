plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.cloudtv.iptv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.cloudtv.iptv"
        // minSdk 21 支持市面上 99% 的安卓 5.0+ 老旧电视盒子与新款智能电视
        minSdk = 21
        targetSdk = 34
        versionCode = 103
        versionName = "1.3.0-TVBox-Stable"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // 使用 debug 签名兜底以便免配置直接生成可安装在电视盒子的 Release APK
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            applicationIdSuffix = ""
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-opt-in=androidx.media3.common.util.UnstableApi")
    }

    lint {
        checkReleaseBuilds = false
        abortOnError = false
    }
}

dependencies {
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")

    // AndroidX 基础与 TV 兼容库
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // Jetpack Media3 (ExoPlayer) 直播核心引擎：支持 HLS(.m3u8)、HTTP-FLV/TS、RTSP、OkHttp纯IPv4栈
    val media3Version = "1.2.1"
    implementation("androidx.media3:media3-exoplayer:$media3Version")
    implementation("androidx.media3:media3-exoplayer-hls:$media3Version")
    implementation("androidx.media3:media3-exoplayer-rtsp:$media3Version")
    implementation("androidx.media3:media3-datasource-okhttp:$media3Version")
    implementation("androidx.media3:media3-ui:$media3Version")
    // 内置 FFmpeg 音频软解引擎：完美解决广电/酒店源 MP2(0x03) / AC-3(0x06) 杜比音轨导致安卓盒子无声及音画时钟卡顿的问题！
    implementation("org.jellyfin.media3:media3-ffmpeg-decoder:1.2.1+1")

    // OkHttp 网络层（用于强制纯 IPv4 DNS 过滤 & 后台并发测速）
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
}
