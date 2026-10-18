// AGP 9 起 Kotlin 由 built-in Kotlin 提供，不再 apply org.jetbrains.kotlin.android。
// AGP 自身携带的 KGP 版本偏旧；把 2.4.21 放到 buildscript classpath 才能真正用上最新稳定 Kotlin。
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.21")
    }
}

plugins {
    id("com.android.application") version "9.4.1" apply false
}
