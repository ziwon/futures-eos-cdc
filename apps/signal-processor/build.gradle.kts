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
        jvmFlags = listOf(
            "--enable-preview",
            // ZGC Configuration
            "-XX:+UseZGC",
            "-XX:+ZGenerational",  // Enable generational ZGC (Java 21+)
            "-Xmx2g",  // Max heap size
            "-Xms512m",  // Initial heap size
            "-XX:SoftMaxHeapSize=1800m",  // Soft limit to trigger GC under memory pressure
            "-XX:ZCollectionInterval=30",  // Force GC at least every 30 seconds
            "-XX:+ZProactive",  // Enable proactive GC (clean up when memory is available)
            "-XX:ZUncommitDelay=60",  // Return unused memory to OS after 60 seconds
            // Diagnostics & Monitoring
            "-XX:+UnlockDiagnosticVMOptions",
            "-XX:ZStatisticsInterval=10",  // Log ZGC statistics every 10 seconds
            "-Xlog:gc*:stdout:time,uptime,level,tags",  // Comprehensive GC logging
            // Virtual Threads
            "-Djdk.virtualThreadScheduler.parallelism=128"
        )
        creationTime = "USE_CURRENT_TIMESTAMP"
        user = "nonroot"
    }
}
