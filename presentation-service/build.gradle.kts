plugins {
    kotlin("plugin.serialization")
    application
}

application {
    mainClass.set("knurl.presentation.MainKt")
}

dependencies {
    implementation(project(":shared-domain"))

    implementation(platform("org.http4k:http4k-bom:6.58.0.0"))
    implementation("org.http4k:http4k-core")
    implementation("org.http4k:http4k-server-undertow")
    implementation("org.http4k:http4k-api-openapi")
    implementation("org.http4k:http4k-format-kotlinx-serialization")
    // Used only by the OpenAPI renderer's schema generation - kotlinx.serialization's reflection
    // can't build JSON schemas reliably (see AGENTS.md); route bodies still use KotlinxSerialization.
    implementation("org.http4k:http4k-format-jackson")

    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    implementation(platform("software.amazon.awssdk:bom:2.54.14"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")

    implementation("com.sksamuel.hoplite:hoplite-core:2.9.0")
    implementation("com.sksamuel.hoplite:hoplite-hocon:2.9.0")
}
