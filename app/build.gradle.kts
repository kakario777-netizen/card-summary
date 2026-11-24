plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val buildNumber = (System.getenv("GITHUB_RUN_NUMBER") ?: "1").toInt()
// 버전 이름은 저장소 맨 위 VERSION 파일
val tagVersion = rootProject.file("VERSION").takeIf { it.exists() }?.readText()?.trim()?.ifEmpty { null }

android {
    namespace = "com.kakario.cardsummary"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.kakario.cardsummary"
        minSdk = 29
        targetSdk = 34
        versionCode = buildNumber
        versionName = tagVersion ?: "1.0.$buildNumber"
    }

    signingConfigs {
        create("release") {
            // 서명 키는 GitHub Secrets(KEYSTORE_BASE64 등)에서 빌드 때만 풀어 씀
            val ks = file(System.getenv("KEYSTORE_FILE") ?: "release.jks")
            storeFile = ks
            storePassword = System.getenv("KEYSTORE_PASSWORD") ?: ""
            keyAlias = System.getenv("KEY_ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true }
}

dependencies {
    val bom = platform("androidx.compose:compose-bom:2024.09.00")
    implementation(bom)
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.foundation:foundation")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
