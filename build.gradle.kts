plugins {
    // Version set in libs.versions.toml via type-safe accessors
    alias(libs.plugins.kotlin.jvm) apply false
}

allprojects {
    group = "com.trading"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
    }

    // Kotlin + JDK 21 toolchain for all subprojects
    plugins.withId("org.jetbrains.kotlin.jvm") {
        the<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension>().apply {
            jvmToolchain(21)
        }
    }
}
