plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.selfkaizen.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.selfkaizen.app"
        // minSdk 26: UsageStatsManager のイベント API が動作する最低ライン。
        // ただし ACTIVITY_RESUMED/PAUSED 定数は API 29 追加のため、
        // これは「コンパイル時にインライン展開される同値の別名」として機能する。
        // 詳細は PLAN-android.md §Phase 2 を参照。
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
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

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    val roomVersion = "2.6.1"

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")

    // Compose
    implementation(platform("androidx.compose:compose-bom:2024.10.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    // Room
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    // WorkManager
    implementation("androidx.work:work-runtime-ktx:2.9.1")

    // トークンの暗号化保存（EncryptedSharedPreferences / Android Keystore）
    // docs/PRIVACY.md §3.2: 平文で SharedPreferences に置いてはいけない
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // テスト
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    testImplementation("com.google.truth:truth:1.4.2")
    // JVM テストで JSON を扱うため（Android の org.json はスタブのため）
    testImplementation("org.json:json:20240303")
    androidTestImplementation("androidx.test.ext:junit:1.2.1")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.6.1")
    // ウィジェットの描画検証でも Truth を使う（RemoteViews は
    // 実際に inflate しないと検証できない）
    androidTestImplementation("com.google.truth:truth:1.4.2")
}
