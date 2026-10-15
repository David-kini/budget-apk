package cd.smartbudget.fc.data

import android.content.Context
import android.database.sqlite.SQLiteException
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cd.smartbudget.fc.core.OperationStatus
import cd.smartbudget.fc.core.OverdraftMode
import cd.smartbudget.fc.core.SettingKeys
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.db.DatabaseSeeder
import cd.smartbudget.fc.data.db.DbTriggers
import cd.smartbudget.fc.data.db.entity.AllocationEntity
import cd.smartbudget.fc.data.db.entity.ExpenseEntity
import cd.smartbudget.fc.data.db.entity.IncomeEntity
import cd.smartbudget.fc.data.db.entity.TransferEntity
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class DatabaseIntegrityTest {

    private lateinit var db: AppDatabase
    private var configId = 0L
    private lateinit var categoryIds: Map<String, Long>

    @Before
    fun setUp() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = AppDatabase.inMemoryForTests(context)
        assertTrue(DatabaseSeeder.seedIfEmpty(db, now = 1_000L))
        configId = db.budgetConfigurationDao().getCurrent()!!.id
        categoryIds = db.categoryDao().getAll().associate { it.name to it.id }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------- Outils ----------

    private fun uuid() = UUID.randomUUID().toString()

    private suspend fun insertIncome(amount: Long, requestId: String = uuid()): Long =
        db.incomeDao().insert(
            IncomeEntity(
                uuid = uuid(), amount = amount, source = "Salaire", receivedDate = 20_000,
                configurationId = configId, status = OperationStatus.ACTIVE,
                clientRequestId = requestId, createdAt = 1_000,
            )
        )

    private suspend fun insertExpense(amount: Long, categoryName: String): Long =
        db.expenseDao().insert(
            ExpenseEntity(
                uuid = uuid(), amount = amount, categoryId = categoryIds.getValue(categoryName),
                reason = "Test", expenseDate = 20_001, status = OperationStatus.ACTIVE,
                clientRequestId = uuid(), createdAt = 1_000,
            )
        )

    private suspend fun insertTransfer(amount: Long, from: String, to: String): Long =
        db.transferDao().insert(
            TransferEntity(
                uuid = uuid(), fromCategoryId = categoryIds.getValue(from), toCategoryId = categoryIds.getValue(to),
                amount = amount, transferDate = 20_002, reason = "Test", status = OperationStatus.ACTIVE,
                clientRequestId = uuid(), createdAt = 1_000,
            )
        )

    /** Alloue un montant selon la configuration seedée (montants exacts : multiples de 100 FC). */
    private suspend fun allocateDefault(incomeId: Long, amount: Long) {
        val items = db.budgetConfigurationDao().getItems(configId)
        db.allocationDao().insertAll(items.map {
            AllocationEntity(incomeId = incomeId, categoryId = it.categoryId, basisPoints = it.basisPoints,
                amount = amount * it.basisPoints / 10_000)
        })
    }

    private suspend fun assertSqlFails(block: suspend () -> Unit) {
        try {
            block()
            fail("L'opération aurait dû être refusée par SQLite")
        } catch (e: SQLiteException) {
            // attendu
        }
    }

    // ---------- Données initiales ----------

    @Test
    fun seed_creates_five_default_categories_totalling_100_percent() = runBlocking {
        val shares = db.budgetConfigurationDao().getCurrentShares()
        assertEquals(listOf("Créole", "Maison", "Dîme", "Épargne", "Petites dépenses"), shares.map { it.name })
        assertEquals(listOf(2000, 2000, 1000, 3000, 2000), shares.map { it.basisPoints })
        assertEquals(10_000, shares.sumOf { it.basisPoints })
        assertEquals(1, db.budgetConfigurationDao().countCurrent())
        assertEquals(OverdraftMode.REFUSE.name, db.settingsDao().get(SettingKeys.OVERDRAFT_MODE))
        assertTrue(db.categoryDao().getAll().single { it.name == "Épargne" }.isSavings)
    }

    @Test
    fun seed_is_not_repeated_on_non_empty_database() = runBlocking {
        assertFalse(DatabaseSeeder.seedIfEmpty(db))
        assertEquals(5, db.categoryDao().count())
    }

    // ---------- Soldes calculés ----------

    @Test
    fun balances_follow_the_accounting_rule() = runBlocking {
        val incomeId = insertIncome(10_000_000) // 100 000 FC
        allocateDefault(incomeId, 10_000_000)
        insertExpense(500_000, "Petites dépenses") // 5 000 FC
        insertTransfer(100_000, "Maison", "Petites dépenses") // 1 000 FC

        val byName = db.balanceDao().getBalances().associateBy { it.name }
        assertEquals(2_000_000L, byName.getValue("Créole").balance())
        assertEquals(1_900_000L, byName.getValue("Maison").balance())
        assertEquals(1_000_000L, byName.getValue("Dîme").balance())
        assertEquals(3_000_000L, byName.getValue("Épargne").balance())
        assertEquals(1_600_000L, byName.getValue("Petites dépenses").balance())

        // Invariant : Σ soldes = Σ entrées actives − Σ dépenses actives (les transferts s'annulent)
        val total = byName.values.sumOf { it.balance() }
        assertEquals(db.incomeDao().sumActive() - db.expenseDao().sumActive(), total)
    }

    @Test
    fun cancelled_operations_are_excluded_from_balances() = runBlocking {
        val incomeId = insertIncome(10_000_000)
        allocateDefault(incomeId, 10_000_000)
        val expenseId = insertExpense(500_000, "Maison")
        assertEquals(1, db.expenseDao().cancel(expenseId, 2_000, "Erreur de saisie"))

        val maison = db.balanceDao().getBalances().single { it.name == "Maison" }
        assertEquals(2_000_000L, maison.balance())

        assertEquals(1, db.incomeDao().cancel(incomeId, 2_000, "Doublon"))
        assertEquals(0L, db.balanceDao().getBalances().sumOf { it.balance() })
    }

    // ---------- Anti-doublon ----------

    @Test
    fun same_client_request_id_cannot_be_recorded_twice() = runBlocking {
        val requestId = uuid()
        insertIncome(1_000_000, requestId)
        assertSqlFails { insertIncome(1_000_000, requestId) }
        assertEquals(1_000_000L, db.incomeDao().sumActive())
    }

    // ---------- Triggers d'intégrité ----------

    @Test
    fun zero_or_negative_amounts_are_rejected() = runBlocking {
        assertSqlFails { insertIncome(0) }
        assertSqlFails { insertIncome(-100) }
        assertSqlFails { insertExpense(0, "Maison") }
        assertSqlFails { insertTransfer(0, "Maison", "Dîme") }
    }

    @Test
    fun transfer_to_same_category_is_rejected() = runBlocking {
        assertSqlFails { insertTransfer(100, "Maison", "Maison") }
    }

    @Test
    fun validated_amounts_are_immutable() = runBlocking {
        val incomeId = insertIncome(1_000_000)
        val expenseId = insertExpense(100_000, "Maison")
        val sql = db.openHelper.writableDatabase
        assertSqlFails { sql.execSQL("UPDATE incomes SET amount = 5 WHERE id = $incomeId") }
        assertSqlFails { sql.execSQL("UPDATE expenses SET amount = 5 WHERE id = $expenseId") }
        assertSqlFails {
            sql.execSQL("UPDATE expenses SET categoryId = ${categoryIds.getValue("Dîme")} WHERE id = $expenseId")
        }
        assertEquals(1_000_000L, db.incomeDao().getById(incomeId)!!.amount)
        assertEquals(100_000L, db.expenseDao().getById(expenseId)!!.amount)
    }

    @Test
    fun allocations_cannot_be_modified() = runBlocking {
        val incomeId = insertIncome(1_000_000)
        allocateDefault(incomeId, 1_000_000)
        val sql = db.openHelper.writableDatabase
        assertSqlFails { sql.execSQL("UPDATE allocations SET amount = 0 WHERE incomeId = $incomeId") }
        assertEquals(1_000_000L, db.allocationDao().sumForIncome(incomeId))
    }

    @Test
    fun cancelled_operation_cannot_be_reactivated() = runBlocking {
        val expenseId = insertExpense(100_000, "Maison")
        db.expenseDao().cancel(expenseId, 2_000, "test")
        val sql = db.openHelper.writableDatabase
        assertSqlFails { sql.execSQL("UPDATE expenses SET status = 'ACTIVE' WHERE id = $expenseId") }
        assertEquals(0, db.expenseDao().cancel(expenseId, 3_000, "deuxième annulation"))
    }

    @Test
    fun financial_operations_cannot_be_deleted() = runBlocking {
        val incomeId = insertIncome(1_000_000)
        allocateDefault(incomeId, 1_000_000)
        val expenseId = insertExpense(100_000, "Maison")
        val sql = db.openHelper.writableDatabase
        assertSqlFails { sql.execSQL("DELETE FROM expenses WHERE id = $expenseId") }
        assertSqlFails { sql.execSQL("DELETE FROM allocations WHERE incomeId = $incomeId") }
        assertSqlFails { sql.execSQL("DELETE FROM incomes WHERE id = $incomeId") }
        assertSqlFails { sql.execSQL("DELETE FROM budget_configurations") }
        assertNotNull(db.expenseDao().getById(expenseId))
    }

    @Test
    fun category_with_history_cannot_be_deleted() = runBlocking {
        insertExpense(100_000, "Maison")
        val maison = db.categoryDao().getById(categoryIds.getValue("Maison"))!!
        assertTrue(db.categoryDao().usageCount(maison.id) > 0)
        assertSqlFails { db.categoryDao().delete(maison) }
        assertNotNull(db.categoryDao().getById(maison.id))
    }

    @Test
    fun delete_guards_can_be_lifted_only_inside_a_transaction_that_restores_them() = runBlocking {
        val expenseId = insertExpense(100_000, "Maison")
        val sql = db.openHelper.writableDatabase

        // Transaction annulée : les garde-fous retirés reviennent automatiquement (DDL transactionnel).
        sql.beginTransaction()
        try {
            DbTriggers.dropDeleteGuards(sql)
            sql.execSQL("DELETE FROM expenses WHERE id = $expenseId")
        } finally {
            sql.endTransaction() // pas de setTransactionSuccessful → rollback
        }
        assertNotNull(db.expenseDao().getById(expenseId))
        assertSqlFails { sql.execSQL("DELETE FROM expenses WHERE id = $expenseId") }
    }
}
