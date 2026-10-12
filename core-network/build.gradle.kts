// core-network：设备发现 + 诊断引擎 + Wi-Fi 分析（M1 实现）
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.installer.toolbox.core.network"
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
    implementation(libs.kotlinx.coroutines.android)
    // DNS 查询（dnsjava）
    implementation(libs.dnsjava)
    // OkHttp：IdcTargetDetector / AI 链路使用
    implementation(libs.okhttp)
    // 二维码/条码生成与解码（BarcodeUtils 供设备发现/AI 工具用；
    // api 暴露使依赖本模块的 core-agent 可传 BarcodeFormat 参数）
    api(libs.zxing.core)
    // SNMP v2c 查询（SnmpProbe：设备系统信息，仅模块内使用，不对外暴露类型）
    implementation(libs.snmp4j)
    // SNMP4J 依赖 slf4j-api，Android 无实现会运行时报 "No SLF4J providers"，绑 NOP 静默
    implementation(libs.slf4j.nop)
}
