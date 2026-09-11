plugins {
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("knurl.ingestion.MainKt")
}

dependencies {
    implementation(project(":shared-domain"))

    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("com.sksamuel.scrimage:scrimage-core:4.1.3")
    implementation("com.sksamuel.scrimage:scrimage-webp:4.1.3")

    implementation(platform("software.amazon.awssdk:bom:2.54.14"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation("com.sksamuel.hoplite:hoplite-core:2.9.0")
    implementation("com.sksamuel.hoplite:hoplite-hocon:2.9.0")

    implementation("org.slf4j:slf4j-simple:2.0.16")
}
