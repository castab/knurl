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

    // SLF4J binding. Without one, slf4j-api falls back to NOP and this service produces no logs at all -
    // no request log, no failed-auth record, no Hikari/Undertow/AWS SDK diagnostics.
    // Same binding and version as ingestion-service.
    implementation("org.slf4j:slf4j-simple:2.0.16")
}

/**
 * Dumps the live OpenAPI spec (same route definitions [Main] serves at GET /openapi.json) to a
 * checked-in file, without needing a running Postgres/MinIO/HTTP server - see
 * GenerateOpenApiSpec.kt. Re-run and commit the result whenever a route changes.
 * Usage: ./gradlew :presentation-service:generateOpenApiSpec
 * Output defaults to presentation-service/openapi.json; override with --args="path/to/output.json".
 */
tasks.register<JavaExec>("generateOpenApiSpec") {
    group = "documentation"
    description = "Generates the presentation-service OpenAPI spec to openapi.json"
    classpath = sourceSets["main"].runtimeClasspath
    mainClass.set("knurl.presentation.openapi.GenerateOpenApiSpecKt")
    args(layout.projectDirectory.file("openapi.json").asFile.path)
}
