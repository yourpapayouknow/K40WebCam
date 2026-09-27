plugins {
    id("com.android.application")
}

android {
    namespace = "com.k40webcam"
    compileSdk = 37

    defaultConfig {
        applicationId = "com.k40webcam"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // 采集与编码层：Camera2ApiManager（相机控制 + 直连 Surface）、VideoEncoder（MediaCodec 封装）
    implementation("com.github.pedroSG94.RootEncoder:library:2.8.1")
    // RTSP 服务端：仅用其 RtspServer，与采集/编码层解耦
    implementation("com.github.pedroSG94:RTSP-Server:1.4.3")
}
