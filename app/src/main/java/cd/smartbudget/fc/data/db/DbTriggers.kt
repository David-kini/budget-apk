package cd.smartbudget.fc.data.db

import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Contraintes d'intégrité appliquées directement par SQLite, en plus des contrôles applicatifs.
 * Même un bug dans le code Kotlin ne peut pas contourner ces règles.
 *
 *  - montants strictement positifs (entrées, dépenses, transferts) ;
 *  - statut limité à ACTIVE / CANCELLED, et une opération annulée ne peut pas être réactivée ;
 *  - montant, catégorie et date d'une opération validée sont immuables (correction = annulation + nouvelle opération) ;
 *  - allocations et configurations de pourcentages immuables ;
 *  - suppression interdite des opérations financières (garde-fous retirés uniquement pendant
 *    une restauration atomique, dans la même transaction).
 */
object DbTriggers {

    private const val STATUS_OK = "('ACTIVE','CANCELLED')"

    /** Règles permanentes. */
    private val INTEGRITY = listOf(
        // --- Insertions valides ---
        """
        CREATE TRIGGER IF NOT EXISTS trg_incomes_insert_check BEFORE INSERT ON incomes
        WHEN NEW.amount <= 0 OR NEW.status NOT IN $STATUS_OK
        BEGIN SELECT RAISE(ABORT, 'income_invalid'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_expenses_insert_check BEFORE INSERT ON expenses
        WHEN NEW.amount <= 0 OR NEW.status NOT IN $STATUS_OK
        BEGIN SELECT RAISE(ABORT, 'expense_invalid'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_transfers_insert_check BEFORE INSERT ON transfers
        WHEN NEW.amount <= 0 OR NEW.status NOT IN $STATUS_OK OR NEW.fromCategoryId = NEW.toCategoryId
        BEGIN SELECT RAISE(ABORT, 'transfer_invalid'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_allocations_insert_check BEFORE INSERT ON allocations
        WHEN NEW.amount < 0 OR NEW.basisPoints < 0 OR NEW.basisPoints > 10000
        BEGIN SELECT RAISE(ABORT, 'allocation_invalid'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_config_items_insert_check BEFORE INSERT ON budget_configuration_items
        WHEN NEW.basisPoints < 0 OR NEW.basisPoints > 10000
        BEGIN SELECT RAISE(ABORT, 'configuration_item_invalid'); END
        """,

        // --- Immuabilité des opérations validées ---
        """
        CREATE TRIGGER IF NOT EXISTS trg_incomes_immutable BEFORE UPDATE ON incomes
        WHEN NEW.amount <> OLD.amount OR NEW.configurationId <> OLD.configurationId
          OR NEW.receivedDate <> OLD.receivedDate OR NEW.uuid <> OLD.uuid
          OR NEW.clientRequestId <> OLD.clientRequestId
          OR NEW.correctsIncomeId IS NOT OLD.correctsIncomeId
          OR NEW.status NOT IN $STATUS_OK
          OR (OLD.status = 'CANCELLED' AND NEW.status <> 'CANCELLED')
        BEGIN SELECT RAISE(ABORT, 'income_immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_expenses_immutable BEFORE UPDATE ON expenses
        WHEN NEW.amount <> OLD.amount OR NEW.categoryId <> OLD.categoryId
          OR NEW.expenseDate <> OLD.expenseDate OR NEW.uuid <> OLD.uuid
          OR NEW.clientRequestId <> OLD.clientRequestId
          OR NEW.correctsExpenseId IS NOT OLD.correctsExpenseId
          OR NEW.status NOT IN $STATUS_OK
          OR (OLD.status = 'CANCELLED' AND NEW.status <> 'CANCELLED')
        BEGIN SELECT RAISE(ABORT, 'expense_immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_transfers_immutable BEFORE UPDATE ON transfers
        WHEN NEW.amount <> OLD.amount OR NEW.fromCategoryId <> OLD.fromCategoryId
          OR NEW.toCategoryId <> OLD.toCategoryId OR NEW.transferDate <> OLD.transferDate
          OR NEW.uuid <> OLD.uuid OR NEW.clientRequestId <> OLD.clientRequestId
          OR NEW.status NOT IN $STATUS_OK
          OR (OLD.status = 'CANCELLED' AND NEW.status <> 'CANCELLED')
        BEGIN SELECT RAISE(ABORT, 'transfer_immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_allocations_immutable BEFORE UPDATE ON allocations
        BEGIN SELECT RAISE(ABORT, 'allocation_immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_config_items_immutable BEFORE UPDATE ON budget_configuration_items
        BEGIN SELECT RAISE(ABORT, 'configuration_item_immutable'); END
        """,
        """
        CREATE TRIGGER IF NOT EXISTS trg_configurations_immutable BEFORE UPDATE ON budget_configurations
        WHEN NEW.uuid <> OLD.uuid OR NEW.createdAt <> OLD.createdAt
        BEGIN SELECT RAISE(ABORT, 'configuration_immutable'); END
        """,
    )

    /** Garde-fous anti-suppression : retirés puis recréés dans la transaction de restauration. */
    val DELETE_GUARD_NAMES = listOf(
        "trg_incomes_no_delete",
        "trg_expenses_no_delete",
        "trg_transfers_no_delete",
        "trg_allocations_no_delete",
        "trg_configurations_no_delete",
        "trg_config_items_no_delete",
    )

    private val DELETE_GUARDS = listOf(
        "CREATE TRIGGER IF NOT EXISTS trg_incomes_no_delete BEFORE DELETE ON incomes BEGIN SELECT RAISE(ABORT, 'income_delete_forbidden'); END",
        "CREATE TRIGGER IF NOT EXISTS trg_expenses_no_delete BEFORE DELETE ON expenses BEGIN SELECT RAISE(ABORT, 'expense_delete_forbidden'); END",
        "CREATE TRIGGER IF NOT EXISTS trg_transfers_no_delete BEFORE DELETE ON transfers BEGIN SELECT RAISE(ABORT, 'transfer_delete_forbidden'); END",
        "CREATE TRIGGER IF NOT EXISTS trg_allocations_no_delete BEFORE DELETE ON allocations BEGIN SELECT RAISE(ABORT, 'allocation_delete_forbidden'); END",
        "CREATE TRIGGER IF NOT EXISTS trg_configurations_no_delete BEFORE DELETE ON budget_configurations BEGIN SELECT RAISE(ABORT, 'configuration_delete_forbidden'); END",
        "CREATE TRIGGER IF NOT EXISTS trg_config_items_no_delete BEFORE DELETE ON budget_configuration_items BEGIN SELECT RAISE(ABORT, 'configuration_item_delete_forbidden'); END",
    )

    /** Crée tous les triggers (idempotent). */
    fun createAll(db: SupportSQLiteDatabase) {
        INTEGRITY.forEach { db.execSQL(it.trimIndent()) }
        createDeleteGuards(db)
    }

    fun createDeleteGuards(db: SupportSQLiteDatabase) {
        DELETE_GUARDS.forEach { db.execSQL(it) }
    }

    /** À n'appeler QUE dans une transaction de restauration, suivie de [createDeleteGuards]. */
    fun dropDeleteGuards(db: SupportSQLiteDatabase) {
        DELETE_GUARD_NAMES.forEach { db.execSQL("DROP TRIGGER IF EXISTS $it") }
    }
}
