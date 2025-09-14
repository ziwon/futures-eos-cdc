plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(libs.jackson.module.kotlin)
    api(libs.jackson.databind)
    api(libs.jackson.datatype.jsr310)
    api(libs.slf4j.api)
    implementation(libs.kafka.clients)
}

