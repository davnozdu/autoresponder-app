import java.net.URI
import java.security.MessageDigest

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
        versionCode = 108
        versionName = "0.29.1"
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
    buildFeatures { compose = true; buildConfig = true }
    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

// sherpa-onnx не публикуется в Maven Central — только прямыми .aar-файлами на GitHub
// Releases. Качаем один раз при сборке (в libs/, .gitignore) вместо коммита 50МБ бинаря
// в историю репозитория; повторные сборки просто находят уже скачанный файл и не лезут в сеть.
val sherpaOnnxAarVersion = "1.13.8"
val sherpaOnnxAarFile = file("libs/sherpa-onnx-$sherpaOnnxAarVersion.aar")
// Зафиксировано вручную (shasum -a 256) с фактически скачанного файла релиза v1.13.8.
// Найдено внешним аудитом: без проверки хэша подмена/обрыв ответа на этом URL превращается
// в труднодиагностируемую ошибку сборки либо падение при инициализации модели, а не в чёткую
// ошибку прямо на этапе скачивания.
val sherpaOnnxAarSha256 = "633c24321e06b1fe79feafa03ea16cbc0f8a286641e2da3559bac91bdb13bd96"

tasks.register("downloadSherpaOnnxAar") {
    outputs.file(sherpaOnnxAarFile)
    doLast {
        if (!sherpaOnnxAarFile.exists()) {
            sherpaOnnxAarFile.parentFile.mkdirs()
            val url = "https://github.com/k2-fsa/sherpa-onnx/releases/download/" +
                "v$sherpaOnnxAarVersion/sherpa-onnx-$sherpaOnnxAarVersion.aar"
            logger.lifecycle("Скачиваю sherpa-onnx AAR: $url")
            val tmp = file("${sherpaOnnxAarFile.path}.tmp")
            URI(url).toURL().openStream().use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            val digest = MessageDigest.getInstance("SHA-256")
            tmp.inputStream().use { input ->
                val buf = ByteArray(64 * 1024)
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    digest.update(buf, 0, n)
                }
            }
            val actual = digest.digest().joinToString("") { "%02x".format(it) }
            if (actual != sherpaOnnxAarSha256) {
                tmp.delete()
                throw GradleException(
                    "sherpa-onnx AAR: SHA-256 не совпал (ожидали $sherpaOnnxAarSha256, получили $actual) — " +
                        "скачанный файл повреждён или подменён, сборка остановлена"
                )
            }
            if (!tmp.renameTo(sherpaOnnxAarFile)) {
                throw GradleException("sherpa-onnx AAR: не удалось переименовать временный файл")
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
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.8.7")

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
    implementation("com.google.ai.edge.litertlm:litertlm-android:0.17.1")
    // org.json НЕ подключаем: он есть в android.jar. Внешняя копия дублирует классы
    // фреймворка и даёт расхождение поведения/VerifyError на устройстве.
}
