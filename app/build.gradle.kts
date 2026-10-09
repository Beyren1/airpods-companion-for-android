plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "dev.podscompanion"
    compileSdk = 36

    defaultConfig {
        applicationId = "dev.podscompanion"
        minSdk = 29
        targetSdk = 36
        // versionCode растёт на 1 с каждым выпуском (Android по нему понимает, что это обновление),
        // versionName — то, что видит человек.
        versionCode = 1
        versionName = "1.0.0"
    }

    // Ключ подписи выпуска. Его файл и пароли никогда не лежат в репозитории: GitHub Actions берёт их
    // из секретов (см. .github/workflows/release.yml и docs/vypusk.md). Без них release собирается
    // неподписанным, а обычные debug/fast-сборки работают как раньше.
    val releaseKeystore = System.getenv("RELEASE_KEYSTORE_PATH")
    signingConfigs {
        if (releaseKeystore != null) {
            create("release") {
                storeFile = file(releaseKeystore)
                storePassword = System.getenv("RELEASE_KEYSTORE_PASSWORD")
                keyAlias = System.getenv("RELEASE_KEY_ALIAS")
                keyPassword = System.getenv("RELEASE_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            // Включает hex-логирование пакетов (advertising и AAP) в logcat и файл.
            buildConfigField("boolean", "PACKET_LOGGING", "true")
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            buildConfigField("boolean", "PACKET_LOGGING", "false")
            signingConfig = signingConfigs.findByName("release")
        }
        // Быстрая сборка для проверки на телефоне: как release (R8, без отладчика — Compose и весь код
        // работают в разы быстрее), но подписана debug-ключом и с тем же id, что debug. Ставится поверх
        // debug-сборки с сохранением настроек, журнал AAP в приложении работает как обычно.
        create("fast") {
            initWith(getByName("release"))
            applicationIdSuffix = ".debug"
            signingConfig = signingConfigs.getByName("debug")
            matchingFallbacks += "release"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation(project(":ui"))
    implementation(project(":data"))
    implementation(project(":core-bluetooth"))
    implementation(project(":protocol-aap"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.material3)
    implementation(libs.androidx.glance.appwidget)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.timber)
}
