// core-isapi：海康 SADP + ISAPI（M3 里程碑实现）
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "com.installer.toolbox.isapi"
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
    // 海康 SADP/ISAPI 原生库（gomobile bind 产物，源码见 gomobile/mobile.go，
    // 构建命令见 gomobile/README.md）。
    // 注意：library 模块不能直接 implementation 本地 .aar（AGP 会报错），
    // 这里用 compileOnly 仅满足编译期引用，运行时由 app 模块 implementation 打包。
    compileOnly(files("libs/hikvision.aar"))
}
