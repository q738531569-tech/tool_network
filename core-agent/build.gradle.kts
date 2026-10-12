// core-agent：DeepSeek Agent（M5 里程碑实现，当前仅空骨架）
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.installer.toolbox.agent"
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
    implementation(libs.okhttp)
    // 协程（版本目录已有，非新增依赖）：Dispatchers.IO 网络调用
    implementation(libs.kotlinx.coroutines.android)
    // SSH（ssh_exec 工具）：AI 助手远程执行设备命令
    implementation(libs.jsch)
    // M5.1 Agent 工具调用：AgentTools 执行器需要调用诊断 / 发现 / ONVIF / 抓包 API
    // （内部模块依赖，非新第三方依赖）
    implementation(project(":core-network"))
    implementation(project(":core-isapi"))
    // capture_traffic 工具：VpnService 抓包统计（CaptureBus / PacketCaptureService）
    implementation(project(":core-capture"))
    // 反馈/诊断支持类：UsageStats 功能使用统计（core-common）
    implementation(project(":core-common"))
}
