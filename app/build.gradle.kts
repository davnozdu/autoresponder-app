import java.net.URI

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.davnozdu.autoresponder"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.davnozdu.autoresponder"
        minSdk = 29
        targetSdk = 35
        versionCode = 103
        versionName = "0.25.0"
        // Телефон, для которого собирается приложение, — arm64-only. sherpa-onnx AAR (локальная
        // дешифровка речи, Parakeet) несёт нативные .so под 4 архитектуры разом — без фильтра
        // APK раздулся бы на лишние ~100+МБ ради архитектур, которых на этом устройстве нет.
        ndk { abiFilters += "arm64-v8a" }
    }

    signingConfigs {
        create("release") {
            val ksFile = System.getenv("KEYSTORE_FILE")
            if (ksFile != null && file(ksFile).exists()) {
                storeFile = file(ksFile)
                storeType = "PKCS12"
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS") ?: "autoresp"
                keyPassword = System.getenv("KEYSTORE_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Стабильный release-ключ, если задан через env (CI/secrets); иначе debug для локальной сборки.
            signingConfig = if (System.getenv("KEYSTORE_FILE") != null)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

// sherpa-onnx не публикуется в Maven Central — только прямыми .aar-файлами на GitHub
// Releases. Качаем один раз при сборке (в libs/, .gitignore) вместо коммита 50МБ бинаря
// в историю репозитория; повторные сборки просто находят уже скачанный файл и не лезут в сеть.
val sherpaOnnxAarVersion = "1.13.8"
val sherpaOnnxAarFile = file("libs/sherpa-onnx-$sherpaOnnxAarVersion.aar")

tasks.register("downloadSherpaOnnxAar") {
    outputs.file(sherpaOnnxAarFile)
    doLast {
        if (!sherpaOnnxAarFile.exists()) {
            sherpaOnnxAarFile.parentFile.mkdirs()
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "v$sherpaOnnxAarVersion/sherpa-onnx-$sherpaOnnxAarVersion.aar"
            logger.lifecycle("Скачиваю sherpa-onnx AAR: $url")
            URI(url).toURL().openStream().use { input ->
                sherpaOnnxAarFile.outputStream().use { output -> input.copyTo(output) }
            }
        }
    }
}
tasks.named("preBuild") { dependsOn("downloadSherpaOnnxAar") }

dependencies {
    implementation(files(sherpaOnnxAarFile))

    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.ui:ui-tooling-preview")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
    testImplementation("junit:junit:4.13.2")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // org.json НЕ подключаем: он есть в android.jar. Внешняя копия дублирует классы
    // фреймворка и даёт расхождение поведения/VerifyError на устройстве.
}
