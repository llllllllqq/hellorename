import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

/**
 * 签名配置来源（按优先级）：
 *   1) 仓库根目录的 keystore.properties（本地开发用，已被 .gitignore 忽略）
 *   2) 环境变量（GitHub Actions 用，值来自仓库 Secrets）
 * 两处都没有时，release 会退回用 debug 签名，保证本地/CI 都能出包，
 * 仓库里永远不会出现任何密钥文件。
 */
val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) {
        keystorePropsFile.inputStream().use { load(it) }
    }
}

fun secret(propKey: String, envKey: String): String? =
    keystoreProps.getProperty(propKey)?.takeIf { it.isNotBlank() }
        ?: System.getenv(envKey)?.takeIf { it.isNotBlank() }

val sigStoreFile = secret("storeFile", "SIGNING_STORE_FILE")
val sigStorePassword = secret("storePassword", "SIGNING_STORE_PASSWORD")
val sigKeyAlias = secret("keyAlias", "SIGNING_KEY_ALIAS")
val sigKeyPassword = secret("keyPassword", "SIGNING_KEY_PASSWORD")
val hasReleaseSigning = sigStoreFile != null && sigStorePassword != null &&
    sigKeyAlias != null && sigKeyPassword != null

android {
    namespace = "moe.hellorename"
    compileSdk = 34

    defaultConfig {
        applicationId = "moe.hellorename"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = file(sigStoreFile!!)
                storePassword = sigStorePassword
                keyAlias = sigKeyAlias
                keyPassword = sigKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
            signingConfig = if (hasReleaseSigning) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        viewBinding = true
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module")
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
