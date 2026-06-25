plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt.android)
}

android {
    namespace = "com.example.guet_map"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.guet_map"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        buildConfigField("String", "BASE_URL", "\"https://bonus-blurred-hypnosis.ngrok-free.dev/\"")
        // TODO: 替换为你自己的 DeepSeek API Key
        // 访问 https://platform.deepseek.com 获取
        buildConfigField("String", "DEEPSEEK_API_KEY", "\"sk-e038262627654331a61db169264efa0b\"")

        ndk {
            // 高德 3D SDK 仅提供 ARM 原生库，x86_64 模拟器通过 libndk_translation 翻译运行
            abiFilters += listOf("armeabi-v7a", "arm64-v8a")
        }
    }

        buildTypes {
        debug {
            buildConfigField("boolean", "USE_MOCK_API", "false")
        }
        release {
            isMinifyEnabled = false
            buildConfigField("boolean", "USE_MOCK_API", "false")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        viewBinding = true
        buildConfig = true
    }
}

// KSP 配置 - 禁用增量编译解决跨平台缓存问题
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
    arg("room.incremental", "false")
    arg("room.expandProjection", "true")
}

dependencies {
    // AndroidX
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.activity.ktx)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.androidx.fragment.ktx)
    implementation(libs.androidx.swiperefreshlayout)

    // Navigation
    implementation(libs.androidx.navigation.fragment)
    implementation(libs.androidx.navigation.ui)

    // Lifecycle
    implementation(libs.androidx.lifecycle.viewmodel)
    implementation(libs.androidx.lifecycle.runtime)
    implementation(libs.androidx.lifecycle.livedata)

    // Hilt
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.fragment)

    // Room
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    // Retrofit
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.gson)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)
    implementation(libs.gson)

    // Coroutines
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)

    // 高德合包：3D 地图 + 定位 + 搜索（PoiSearch），避免 3dmap 与 search 单独引入时的重复类
    implementation("com.amap.api:3dmap-location-search:latest.integration")

    // Image loading
    implementation(libs.coil)

    // Testing
    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
