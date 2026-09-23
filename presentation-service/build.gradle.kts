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

    implementation(platform("software.amazon.awssdk:bom:2.54.17"))
    implementation("software.amazon.awssdk:s3")
    implementation("software.amazon.awssdk:url-connection-client")

    implementation("com.sksamuel.hoplite:hoplite-core:2.9.0")
    implementation("com.sksamuel.hoplite:hoplite-hocon:2.9.0")

    // SLF4J binding. Without one, slf4j-api falls back to NOP and this service produces no logs at all -
    // no request log, no failed-auth record, no Hikari/Undertow/AWS SDK diagnostics.
    // Same binding and version as ingestion-service.
    implementation("org.slf4j:slf4j-simple:2.0.16")

    // Pins the server stack that http4k-server-undertow/http4k-format-jackson pull in transitively,
    // so a version bump there happens only via a deliberate edit to this block, never silently as a
    // side effect of bumping the http4k-bom version above. Versions below are whatever the BOM
    // currently resolves to - re-pin these (./gradlew :presentation-service:dependencies) whenever
    // http4k-bom is bumped, rather than letting the pin quietly go stale and mask a real upgrade.
    implementation(platform("io.netty:netty-bom:4.1.137.Final"))
    constraints {
        implementation("io.undertow:undertow-core:2.4.2.Final")
        implementation("org.jboss.xnio:xnio-api:3.8.16.Final")
        implementation("org.jboss.xnio:xnio-nio:3.8.16.Final")
        implementation("com.fasterxml.jackson.core:jackson-core:2.22.2")
        implementation("com.fasterxml.jackson.core:jackson-databind:2.22.2")
        // jackson-annotations versions independently of core/databind and hadn't reached 2.22.2 as
        // of this pin - 2.22 is the version conflict resolution already converges on above.
        implementation("com.fasterxml.jackson.core:jackson-annotations:2.22")
    }

    // OkHttp itself arrives transitively from shared-domain's credential-HTTP hardening, which this
    // service's credential fetch is built on. MockWebServer exercises that fetch for real, rather
    // than mocking away the client whose bounds and TLS rules are the point of the tests.
    testImplementation("com.squareup.okhttp3:mockwebserver:5.5.0")
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
