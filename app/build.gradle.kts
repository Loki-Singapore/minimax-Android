plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.loki.minimax"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.loki.minimax"
        minSdk = 26
        targetSdk = 34
        versionCode = 2
        versionName = "1.1"
    }

    // 从环境变量读取签名信息（GitHub Actions 中由 workflow 注入；本地缺省时跳过，仍可出未签名包）
    val keystorePath = System.getenv("SIGNING_KEYSTORE_PATH")
    val storePassword = System.getenv("SIGNING_STORE_PASSWORD")
    val keyAlias = System.getenv("SIGNING_KEY_ALIAS")

    signingConfigs {
        create("release") {
            if (keystorePath != null && storePassword != null && keyAlias != null) {
                storeFile = file(keystorePath)
                this.storePassword = storePassword
                this.keyAlias = keyAlias
                // PKCS12 keystore：key 密码与 store 密码相同
                keyPassword = storePassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // 提供 keystore 时用固定签名，否则回退默认（未签名 release 不可直接安装）
            signingConfig = if (keystorePath != null) {
                signingConfigs.getByName("release")
            } else {
                null
            }
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
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.2")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.google.code.gson:gson:2.10.1")
    implementation("androidx.media3:media3-exoplayer:1.3.1")
    implementation("androidx.media3:media3-ui:1.3.1")
    implementation("com.github.bumptech.glide:glide:4.16.0")
}
