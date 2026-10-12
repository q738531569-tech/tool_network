// app：壳工程（导航 + 主题 + 主界面），M1 实现 4 个页面
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

// 正式签名凭据从 local.properties 读取（不入库）：KEYSTORE_PASSWORD / KEY_PASSWORD / KEY_ALIAS
val keystoreProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}

android {
    namespace = "com.installer.toolbox"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.installer.toolbox"
        minSdk = 26
        targetSdk = 34
        // 1.6.19（versionCode 43）：1.6.17 追加功能正式发版——远程强制升级（服务器强制设备更新+自动重启）/光猫网关自动切换/web_page_read 等待加载（防 AI 误读）/Wi-Fi 雷达 RTT 距离过滤
        // 2026-08-19 修复：远程升级后自动拉起（UpdateRestartReceiver 直接拉前台服务，MY_PACKAGE_REPLACED 豁免 FGS 限制）+ 开机自启 BootReceiver
        versionCode = 43
        versionName = "1.6.19"
    }

    signingConfigs {
        create("release") {
            // keystore 在项目外（F:\Reasonix_work\keystore\），不随仓库分发
            storeFile = rootProject.file("../keystore/toolbox-release.keystore")
            storePassword = keystoreProps.getProperty("KEYSTORE_PASSWORD") ?: ""
            keyAlias = keystoreProps.getProperty("KEY_ALIAS") ?: "installer-toolbox"
            keyPassword = keystoreProps.getProperty("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 正式签名
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
    }
}

dependencies {
    // 内部模块
    implementation(project(":core-common"))
    implementation(project(":core-network"))
    implementation(project(":core-isapi"))
    // 海康原生库：core-isapi 用 compileOnly 引用，运行时依赖在此打包进 APK
    implementation(files("../core-isapi/libs/hikvision.aar"))
    implementation(project(":core-capture"))
    // M5 AI 诊断 Agent（DeepSeek）
    implementation(project(":core-agent"))

    // AndroidX 基础
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.activity.compose)

    // Compose（BOM 管理版本）
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)

    // 导航
    implementation(libs.androidx.navigation.compose)

    // 网络请求（反馈接口 POST / OTA 下载，同 core-network 版本 4.12.0）
    implementation(libs.okhttp)

    // 扫码工具：二维码/条码生成与解码
    implementation(libs.zxing.core)
    // 扫码工具：CameraX 实时预览 + 闪光灯控制（暗光适配）
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)

    // 调试工具
    debugImplementation(libs.androidx.compose.ui.tooling)
}
