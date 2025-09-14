plugins {
    alias(libs.plugins.kotlin.jvm)
    id("application")
    alias(libs.plugins.jib)
}

dependencies {
    implementation(project(":libs:common-model"))
    implementation(project(":libs:common-kafka"))
    implementation(libs.kafka.streams)
    implementation(libs.kafka.clients)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)
    implementation(libs.logback.classic)

    // Coroutines for async processing
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-jdk8:1.8.0")
}

application {
    mainClass.set("com.trading.signalprocessor.MainKt")
    applicationDefaultJvmArgs = listOf(
        "--enable-preview",
        "-XX:+UseZGC",
        "-Djdk.virtualThreadScheduler.parallelism=128"
    )
}

jib {
    from {
        image = "gcr.io/distroless/java21:nonroot"
    }
    to {
        image = System.getenv("REGISTRY_IMAGE") ?: "localhost:9001/signal-processor:0.1.0"
    }
    container {
        mainClass = "com.trading.signalprocessor.MainKt"
        jvmFlags = listOf("--enable-preview", "-XX:+UseZGC")
        creationTime = "USE_CURRENT_TIMESTAMP"
        user = "nonroot"
    }
}
