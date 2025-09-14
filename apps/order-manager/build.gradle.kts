plugins {
    alias(libs.plugins.kotlin.jvm)
    id("application")
    alias(libs.plugins.jib)
}

dependencies {
    implementation(project(":libs:common-model"))
    implementation(project(":libs:common-kafka"))

    // Kafka
    implementation(libs.kafka.clients)

    // Database
    implementation("org.postgresql:postgresql:42.7.1")
    implementation("com.zaxxer:HikariCP:5.1.0")

    // JSON
    implementation(libs.jackson.module.kotlin)
    implementation(libs.jackson.databind)
    implementation(libs.jackson.datatype.jsr310)

    // Logging
    implementation(libs.logback.classic)

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.0")
}

application {
    mainClass.set("com.trading.ordermanager.MainKt")
}

jib {
    from {
        image = "gcr.io/distroless/java21:nonroot"
    }
    to {
        image = System.getenv("REGISTRY_IMAGE") ?: "localhost:9001/order-manager:0.1.0"
    }
    container {
        mainClass = "com.trading.ordermanager.MainKt"
        jvmFlags = listOf(
            "-XX:+UseZGC",
            "-XX:+ZGenerational",
            "-Xmx1g",
            "-Xms256m"
        )
        creationTime = "USE_CURRENT_TIMESTAMP"
        user = "nonroot"
    }
}