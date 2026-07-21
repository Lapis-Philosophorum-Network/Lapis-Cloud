package network.lapis.cloud.server.backup

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import network.lapis.cloud.server.db.DatabaseConfig
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.v1.jdbc.Database
import org.jetbrains.exposed.v1.jdbc.transactions.TransactionManager

/**
 * Test-only replica of `network.lapis.cloud.server.db.DatabaseConfig`'s `buildAndMigrate()` logic,
 * parameterized by instance [name] instead of being a JVM-wide singleton -- [DatabaseConfig] itself
 * cannot be reused here because [OrganizationBackupRoundTripTest] genuinely needs TWO independently
 * Flyway-migrated H2 databases alive in one JVM at the same time (a "source" org and a fresh
 * "target" instance), which is also exactly why [network.lapis.cloud.server.backup
 * .OrganizationExportService]/[OrganizationRestoreService] take an explicit [Database] constructor
 * parameter rather than the ambient default.
 *
 * **Critical Exposed gotcha this function guards against**: `Database.connect(dataSource)` does not
 * just return a new [Database] handle -- as a side effect, it also silently overwrites Exposed's
 * process-wide `TransactionManager.defaultDatabase`, the database every *unparameterized*
 * `transaction { }` call anywhere in the JVM implicitly uses (including in completely unrelated
 * Spec classes sharing this one Gradle test JVM, e.g. `network.lapis.cloud.server.db.DevSeedData
 * .seedIfEmpty`'s own `transaction { }` and every RPC service's `resolveCurrentMember`). Without
 * restoring it immediately after connecting, every isolated test database created here would
 * "leak" and become the accidental new ambient default for the rest of the test run -- silently
 * breaking every *other* Spec class that relies on the real shared `DatabaseConfig.connect()`
 * database (observed empirically: `DevSeedData`'s fixed demo members would appear to vanish for
 * whichever Spec class happened to run afterward). [DatabaseConfig.connect] is called first here
 * specifically to guarantee the real ambient database is already initialized and capturable before
 * this function's own [Database.connect] call reassigns the default out from under it.
 */
internal object TestDatabaseFactory {
    fun freshMigratedH2Database(name: String): Database {
        val ambientDefault = DatabaseConfig.connect()
        val jdbcUrl = "jdbc:h2:mem:$name;DB_CLOSE_DELAY=-1;MODE=PostgreSQL;DATABASE_TO_LOWER=TRUE"
        val hikariConfig =
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                this.username = "sa"
                this.password = ""
                this.driverClassName = "org.h2.Driver"
                this.maximumPoolSize = 5
                this.poolName = "test-backup-$name"
            }
        val dataSource = HikariDataSource(hikariConfig)
        Flyway
            .configure()
            .dataSource(dataSource)
            .locations("classpath:db/migration")
            .load()
            .migrate()
        val database = Database.connect(dataSource)
        // Undo Database.connect's side effect on the process-wide default -- see class KDoc.
        TransactionManager.defaultDatabase = ambientDefault
        return database
    }
}
