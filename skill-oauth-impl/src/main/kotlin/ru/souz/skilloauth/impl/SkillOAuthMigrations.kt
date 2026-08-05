package ru.souz.skilloauth.impl

import javax.sql.DataSource
import org.flywaydb.core.Flyway

/**
 * This module's own schema migrations, run independently of the host application's. A dedicated
 * `locations` root and `table` (schema history table name) mean this module's version sequence
 * (V1, V2, ...) can never collide with the host's own numbering just because both happen to land
 * on the same Postgres classpath — deploying a host migration also named V10 must not be able to
 * crash-loop the whole backend over an unrelated module's version number. It also means this
 * module keeps owning its own schema evolution if it's ever extracted into a standalone service.
 */
object SkillOAuthMigrations {
    fun migrate(dataSource: DataSource, schema: String) {
        Flyway.configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration-skill-oauth")
            .table("flyway_schema_history_skill_oauth")
            .defaultSchema(schema)
            .schemas(schema)
            .createSchemas(false)
            .load()
            .migrate()
    }
}
