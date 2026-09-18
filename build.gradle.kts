plugins {
    kotlin("jvm") version "2.4.20" apply false
    kotlin("plugin.serialization") version "2.4.20" apply false
    id("org.jmailen.kotlinter") version "5.5.0" apply false
}

allprojects {
    repositories {
        mavenCentral()
        // Fallback in case a very recent http4k release hasn't yet mirrored to Maven Central.
        maven("https://maven.http4k.org")
    }
}

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "org.jmailen.kotlinter")

    extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }

    dependencies {
        "testImplementation"(platform("io.kotest:kotest-bom:6.2.5"))
        "testImplementation"("io.kotest:kotest-runner-junit5")
        "testImplementation"("io.kotest:kotest-assertions-core")
        "testImplementation"("io.mockk:mockk:1.14.9")
    }

    tasks.withType<Test> {
        useJUnitPlatform()
    }
}
