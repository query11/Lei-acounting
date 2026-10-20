import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.lei.accounting"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.lei.accounting"
        // 无障碍截图 API（takeScreenshot）需要 Android 11+；Flyme AIOS 远高于此
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
    }

    // 固定签名：每次云端编译都用同一把钥匙，新版本才能直接覆盖安装、保留数据。
    // （以前用的是 CI 机器上随机生成的 debug 签名，每次都不一样，系统会提示「不兼容」。）
    // 这是自用 App 的签名，只用于自己侧载安装；如需更安全，可改为从 GitHub Secrets 读取。
    signingConfigs {
        create("lei") {
            storeFile = file("signing/lei-accounting.jks")
            storePassword = "leiaccounting"
            keyAlias = "lei"
            keyPassword = "leiaccounting"
        }
    }

    buildTypes {
        debug {
            signingConfig = signingConfigs.getByName("lei")
        }
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("lei")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions {
        // 截图测试（Robolectric）需要真实的资源和主题
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        resources.excludes += "/META-INF/{AL2.0,LGPL2.1}"
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(project(":core"))

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.okhttp)
    // 内置中文 OCR 模型，不依赖 Google Play 服务（国行魅族没有 GMS）
    implementation(libs.mlkit.text.recognition.chinese)

    // 界面截图测试：在电脑上渲染 Compose 界面，用来检查排版
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
