package ru.souz.skilloauth.impl

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.DriverManager
import java.util.Properties
import java.util.UUID
import org.flywaydb.core.Flyway
import org.junit.jupiter.api.Assumptions.assumeTrue
import org.testcontainers.DockerClientFactory
import org.testcontainers.containers.PostgreSQLContainer

/** Mirrors `:backend`'s `PostgresTestSupport` but is self-contained, since `:skill-oauth-impl` must
 *  not depend on `:backend`. */
internal object SharedSkillOAuthPostgresContainer {
    val instance: PostgreSQLContainer<Nothing> by lazy {
        PostgreSQLContainer<Nothing>("postgres:16-alpine").apply {
            withDatabaseName("souz")
            withUsername("souz")
            withPassword("souz")
            start()
        }
    }
}

internal fun newSkillOAuthTestSchema(prefix: String): String =
    "${prefix}_${UUID.randomUUID().toString().replace("-", "")}"

internal fun skillOAuthTestDataSource(schema: String): HikariDataSource {
    assumeTrue(
        runCatching { DockerClientFactory.instance().isDockerAvailable() }.getOrDefault(false),
        "Docker is required for Postgres Testcontainers tests.",
    )
    val container = SharedSkillOAuthPostgresContainer.instance
    val jdbcUrl = "jdbc:postgresql://${container.host}:${container.firstMappedPort}/${container.databaseName}"

    Properties().apply {
        setProperty("user", container.username)
        setProperty("password", container.password)
    }.let { properties ->
        DriverManager.getConnection(jdbcUrl, properties).use { connection ->
            connection.createStatement().use { it.execute("create schema if not exists \"$schema\"") }
        }
    }

    val hikariConfig = HikariConfig().apply {
        this.jdbcUrl = jdbcUrl
        username = container.username
        password = container.password
        maximumPoolSize = 4
        this.schema = schema
        addDataSourceProperty("currentSchema", schema)
    }
    return HikariDataSource(hikariConfig).also { dataSource ->
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .defaultSchema(schema)
            .schemas(schema)
            .createSchemas(false)
            .load()
            .migrate()
    }
}
