plugins {
    `java-library`
}

dependencies {
    api(platform("org.jdbi:jdbi3-bom:3.50.0"))
    api("org.jdbi:jdbi3-core")
    api("org.jdbi:jdbi3-postgres")
    api("org.jdbi:jdbi3-kotlin")

    api("com.zaxxer:HikariCP:5.1.0")

    implementation("org.flywaydb:flyway-core:13.6.0")
    implementation("org.flywaydb:flyway-database-postgresql:13.6.0")

    implementation("org.postgresql:postgresql:42.7.7")
}
