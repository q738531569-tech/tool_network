// core-common：模型 + Room 数据库 + 通用工具（M1 实现）
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.installer.toolbox.common"
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
    // Room 运行时与协程扩展
    // 注意：room-runtime 用 api 暴露（ToolboxDatabase 继承自 RoomDatabase，
    // 依赖本模块的 app 需要能看到其超类）
    api(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    // Room 编译期代码生成（KSP）
    ksp(libs.androidx.room.compiler)
}
