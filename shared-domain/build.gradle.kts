plugins {
    `java-library`
}

dependencies {
    api(platform("org.jdbi:jdbi3-bom:3.50.0"))
    api("org.jdbi:jdbi3-core")
    api("org.jdbi:jdbi3-postgres")
    api("org.jdbi:jdbi3-kotlin")

    api("com.zaxxer:HikariCP:7.1.0")

    // `api`, not `implementation`: both services build their credential HTTP clients on the
    // hardening in knurl.domain.http, whose types (HttpUrl, OkHttpClient) are part of that surface.
    api("com.squareup.okhttp3:okhttp:4.12.0")

    implementation("org.flywaydb:flyway-core:13.7.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.7.0")

    implementation("org.postgresql:postgresql:42.7.7")
}
