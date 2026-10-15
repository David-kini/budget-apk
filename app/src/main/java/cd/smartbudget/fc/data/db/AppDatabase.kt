package cd.smartbudget.fc.data.db

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import cd.smartbudget.fc.data.db.dao.AllocationDao
import cd.smartbudget.fc.data.db.dao.AuditDao
import cd.smartbudget.fc.data.db.dao.BalanceDao
import cd.smartbudget.fc.data.db.dao.BlacklistDao
import cd.smartbudget.fc.data.db.dao.BudgetConfigurationDao
import cd.smartbudget.fc.data.db.dao.CategoryDao
import cd.smartbudget.fc.data.db.dao.ExpenseDao
import cd.smartbudget.fc.data.db.dao.IncomeDao
import cd.smartbudget.fc.data.db.dao.SettingsDao
import cd.smartbudget.fc.data.db.dao.TransferDao
import cd.smartbudget.fc.data.db.entity.AllocationEntity
import cd.smartbudget.fc.data.db.entity.AppSettingEntity
import cd.smartbudget.fc.data.db.entity.AuditLogEntity
import cd.smartbudget.fc.data.db.entity.BlacklistEntity
import cd.smartbudget.fc.data.db.entity.BlacklistHistoryEntity
import cd.smartbudget.fc.data.db.entity.BudgetConfigurationEntity
import cd.smartbudget.fc.data.db.entity.BudgetConfigurationItemEntity
import cd.smartbudget.fc.data.db.entity.CategoryEntity
import cd.smartbudget.fc.data.db.entity.ExpenseEntity
import cd.smartbudget.fc.data.db.entity.IncomeEntity
import cd.smartbudget.fc.data.db.entity.TransferEntity
import net.zetetic.database.sqlcipher.SupportOpenHelperFactory

@Database(
    entities = [
        CategoryEntity::class,
        BudgetConfigurationEntity::class,
        BudgetConfigurationItemEntity::class,
        IncomeEntity::class,
        AllocationEntity::class,
        ExpenseEntity::class,
        TransferEntity::class,
        BlacklistEntity::class,
        BlacklistHistoryEntity::class,
        AppSettingEntity::class,
        AuditLogEntity::class,
    ],
    version = AppDatabase.SCHEMA_VERSION,
    exportSchema = true,
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun categoryDao(): CategoryDao
    abstract fun budgetConfigurationDao(): BudgetConfigurationDao
    abstract fun incomeDao(): IncomeDao
    abstract fun allocationDao(): AllocationDao
    abstract fun expenseDao(): ExpenseDao
    abstract fun transferDao(): TransferDao
    abstract fun balanceDao(): BalanceDao
    abstract fun blacklistDao(): BlacklistDao
    abstract fun settingsDao(): SettingsDao
    abstract fun auditDao(): AuditDao

    companion object {
        const val SCHEMA_VERSION = 1
        const val NAME = "smartbudget.db"

        /** Crée les triggers à la création ET à chaque ouverture (filet de sécurité, idempotent). */
        val TRIGGERS_CALLBACK = object : Callback() {
            override fun onCreate(db: SupportSQLiteDatabase) {
                DbTriggers.createAll(db)
            }

            override fun onOpen(db: SupportSQLiteDatabase) {
                db.execSQL("PRAGMA foreign_keys = ON")
                DbTriggers.createAll(db)
            }
        }

        /**
         * Base chiffrée par SQLCipher. [passphrase] provient de DatabaseKeyManager (protégée par Android Keystore).
         * Pas de fallbackToDestructiveMigration : une mise à jour ne doit JAMAIS effacer les données.
         */
        fun build(context: Context, passphrase: ByteArray): AppDatabase {
            System.loadLibrary("sqlcipher")
            return Room.databaseBuilder(context.applicationContext, AppDatabase::class.java, NAME)
                .openHelperFactory(SupportOpenHelperFactory(passphrase))
                .addCallback(TRIGGERS_CALLBACK)
                .addMigrations(*Migrations.ALL)
                .build()
        }

        /** Base en mémoire, non chiffrée, réservée aux tests. */
        fun inMemoryForTests(context: Context): AppDatabase =
            Room.inMemoryDatabaseBuilder(context, AppDatabase::class.java)
                .addCallback(TRIGGERS_CALLBACK)
                .allowMainThreadQueries()
                .build()
    }
}

/**
 * Migrations de schéma. Chaque nouvelle version DOIT ajouter ici une migration explicite
 * et un test MigrationTestHelper (le schéma JSON de chaque version est exporté dans app/schemas/).
 * Version 1 : schéma initial, aucune migration.
 */
object Migrations {
    val ALL: Array<Migration> = arrayOf()
}
