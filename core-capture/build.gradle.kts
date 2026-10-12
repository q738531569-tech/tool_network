// core-capture：VpnService 抓包引擎（M2 里程碑：免 root 抓包 + 广播/组播风暴统计与告警）
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.installer.toolbox.core.capture"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    // CaptureBus 的 MutableSharedFlow / coroutine flow 支持
    implementation(libs.kotlinx.coroutines.android)
}
