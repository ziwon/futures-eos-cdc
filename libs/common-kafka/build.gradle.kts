plugins {
    alias(libs.plugins.kotlin.jvm)
}

dependencies {
    api(project(":libs:common-model"))
    api(libs.kafka.clients)
    api(libs.kafka.streams)
    api(libs.slf4j.api)
    implementation(libs.logback.classic)
}

