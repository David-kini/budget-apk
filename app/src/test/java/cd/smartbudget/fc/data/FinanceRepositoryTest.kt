package cd.smartbudget.fc.data

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.OperationStatus
import cd.smartbudget.fc.core.OverdraftMode
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.db.DatabaseSeeder
import cd.smartbudget.fc.data.repo.CategoryRepository
import cd.smartbudget.fc.data.repo.ExpenseRepository
import cd.smartbudget.fc.data.repo.ExpenseRequest
import cd.smartbudget.fc.data.repo.HistoryRepository
import cd.smartbudget.fc.data.repo.IncomeRepository
import cd.smartbudget.fc.data.repo.IncomeRequest
import cd.smartbudget.fc.data.repo.SettingsRepository
import cd.smartbudget.fc.data.repo.TransferRepository
import cd.smartbudget.fc.data.repo.TransferRequest
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.Confirmation
import cd.smartbudget.fc.domain.ConsistencyChecker
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.domain.RejectReason
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.UUID

@RunWith(AndroidJUnit4::class)
class FinanceRepositoryTest {

    private class FakeClock : Clock {
        var now = 1_000_000L
        override fun nowMillis(): Long = now++
        override fun todayEpochDay(): Long = TODAY
    }

    companion object {
        const val TODAY = 20_740L
    }

    private lateinit var db: AppDatabase
    private val clock = FakeClock()
    private lateinit var settings: SettingsRepository
    private lateinit var categories: CategoryRepository
    private lateinit var incomes: IncomeRepository
    private lateinit var expenses: ExpenseRepository
    private lateinit var transfers: TransferRepository
    private lateinit var history: HistoryRepository
    private lateinit var checker: ConsistencyChecker
    private lateinit var id: Map<String, Long>

    @Before
    fun setUp() = runBlocking {
        db = AppDatabase.inMemoryForTests(ApplicationProvider.getApplicationContext<Context>())
        DatabaseSeeder.seedIfEmpty(db, now = 1L)
        settings = SettingsRepository(db, clock)
        categories = CategoryRepository(db, clock)
        incomes = IncomeRepository(db, clock)
        expenses = ExpenseRepository(db, settings, clock)
        transfers = TransferRepository(db, clock)
        history = HistoryRepository(db)
        checker = ConsistencyChecker(db)
        id = db.categoryDao().getAll().associate { it.name to it.id }
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ---------------- Outils ----------------

    private fun req() = UUID.randomUUID().toString()
    private fun fc(v: Long) = Money.fromFc(v)

    private suspend fun income(amountFc: Long, requestId: String = req()) =
        incomes.recordIncome(IncomeRequest(requestId, fc(amountFc), "Salaire", TODAY))

    private suspend fun expense(amountFc: Long, category: String, accept: Boolean = false, coverFrom: String? = null, requestId: String = req()) =
        expenses.recordExpense(
            ExpenseRequest(requestId, fc(amountFc), id.getValue(category), "Achat", TODAY,
                acceptOverdraft = accept, coverFromCategoryId = coverFrom?.let { id.getValue(it) })
        )

    private suspend fun balanceFc(category: String): Long =
        db.balanceDao().getBalances().single { it.name == category }.balance() / 100

    private suspend fun assertConsistent() {
        val report = checker.check()
        assertTrue("Incohérences : ${report.errors}", report.isConsistent)
    }

    private fun OperationResult.recordedId(): Long {
        assertTrue("Attendu Recorded, obtenu $this", this is OperationResult.Recorded)
        return (this as OperationResult.Recorded).id
    }

    // ---------------- Entrées et répartition ----------------

    @Test
    fun income_of_100_000_FC_is_split_and_persisted_with_applied_percentages() = runBlocking {
        val incomeId = income(100_000).recordedId()
        val allocations = incomes.allocationsOf(incomeId).associate { a -> a.categoryId to (a.basisPoints to a.amount / 100) }
        assertEquals(2_000 to 20_000L, allocations[id.getValue("Créole")])
        assertEquals(2_000 to 20_000L, allocations[id.getValue("Maison")])
        assertEquals(1_000 to 10_000L, allocations[id.getValue("Dîme")])
        assertEquals(3_000 to 30_000L, allocations[id.getValue("Épargne")])
        assertEquals(2_000 to 20_000L, allocations[id.getValue("Petites dépenses")])
        assertEquals(30_000L, balanceFc("Épargne"))
        assertConsistent()
    }

    @Test
    fun incomes_of_250_000_and_1_000_000_FC_accumulate_correctly() = runBlocking {
        income(250_000).recordedId()
        income(1_000_000).recordedId()
        assertEquals(250_000L, balanceFc("Créole"))
        assertEquals(125_000L, balanceFc("Dîme"))
        assertEquals(375_000L, balanceFc("Épargne"))
        assertEquals(fc(1_250_000), db.incomeDao().sumActive())
        assertConsistent()
    }

    @Test
    fun same_request_sent_twice_is_recorded_only_once() = runBlocking {
        val requestId = req()
        val first = income(100_000, requestId).recordedId()
        val second = income(100_000, requestId)
        assertEquals(OperationResult.AlreadyRecorded(first), second)
        assertEquals(fc(100_000), db.incomeDao().sumActive())
        assertEquals(5, db.allocationDao().getAll().size)
    }

    @Test
    fun invalid_income_is_rejected_without_any_write() = runBlocking {
        val zero = incomes.recordIncome(IncomeRequest(req(), 0, "Salaire", TODAY))
        val future = incomes.recordIncome(IncomeRequest(req(), fc(10), "Salaire", TODAY + 1))
        val noSource = incomes.recordIncome(IncomeRequest(req(), fc(10), "  ", TODAY))
        listOf(zero, future, noSource).forEach { assertTrue(it is OperationResult.Rejected) }
        assertEquals(0, db.incomeDao().getAll().size)
    }

    // ---------------- Pourcentages ----------------

    @Test
    fun configuration_below_or_above_100_percent_is_refused_and_current_one_kept() = runBlocking {
        val before = db.budgetConfigurationDao().getCurrent()!!.id
        val low = categories.saveConfiguration(mapOf(id.getValue("Créole") to 5_000, id.getValue("Maison") to 4_000))
        val high = categories.saveConfiguration(mapOf(id.getValue("Créole") to 6_000, id.getValue("Maison") to 5_000))
        assertTrue(low is OperationResult.Rejected && low.reason == RejectReason.INVALID_CONFIGURATION)
        assertTrue(high is OperationResult.Rejected && high.reason == RejectReason.INVALID_CONFIGURATION)
        assertEquals(before, db.budgetConfigurationDao().getCurrent()!!.id)
    }

    @Test
    fun changing_percentages_never_recomputes_past_incomes() = runBlocking {
        val first = income(100_000).recordedId()
        categories.saveConfiguration(
            mapOf(
                id.getValue("Créole") to 2_500, id.getValue("Maison") to 2_500, id.getValue("Dîme") to 1_000,
                id.getValue("Épargne") to 2_000, id.getValue("Petites dépenses") to 2_000,
            )
        ).recordedId()
        val second = income(100_000).recordedId()

        val firstEpargne = incomes.allocationsOf(first).single { it.categoryId == id.getValue("Épargne") }
        val secondEpargne = incomes.allocationsOf(second).single { it.categoryId == id.getValue("Épargne") }
        assertEquals(3_000, firstEpargne.basisPoints)
        assertEquals(fc(30_000), firstEpargne.amount)
        assertEquals(2_000, secondEpargne.basisPoints)
        assertEquals(fc(20_000), secondEpargne.amount)
        assertEquals(50_000L, balanceFc("Épargne"))
        assertConsistent()
    }

    @Test
    fun one_category_can_receive_100_percent_of_every_income() = runBlocking {
        val epargne = id.getValue("Épargne")
        // 100 % sur une seule catégorie, 0 % pour toutes les autres : configuration valide.
        categories.saveConfiguration(id.values.associateWith { if (it == epargne) 10_000 else 0 }).recordedId()

        val incomeId = income(250_000).recordedId()
        val allocations = incomes.allocationsOf(incomeId)
        assertEquals(1, allocations.size) // aucune ligne à 0 FC pour les autres catégories
        assertEquals(epargne, allocations.single().categoryId)
        assertEquals(10_000, allocations.single().basisPoints)
        assertEquals(250_000L, balanceFc("Épargne"))
        assertEquals(0L, balanceFc("Créole"))
        assertEquals(0L, balanceFc("Maison"))

        // Les autres catégories restent actives et utilisables (dépense refusée faute de solde, sans erreur).
        val result = expense(1_000, "Maison")
        assertTrue(result is OperationResult.Rejected && result.reason == RejectReason.INSUFFICIENT_BALANCE)
        assertConsistent()
    }

    @Test
    fun new_category_receives_nothing_until_given_a_percentage() = runBlocking {
        val newId = categories.addCategory("Transport", 0xFF546E7AL).recordedId()
        income(100_000).recordedId()
        assertEquals(0L, balanceFc("Transport"))
        // Une catégorie neuve sans historique peut être supprimée.
        assertTrue(categories.deleteCategory(newId) is OperationResult.Recorded)
    }

    @Test
    fun category_with_history_cannot_be_deleted_and_allocated_category_cannot_be_deactivated() = runBlocking {
        income(100_000).recordedId()
        val delete = categories.deleteCategory(id.getValue("Maison"))
        assertTrue(delete is OperationResult.Rejected && delete.reason == RejectReason.HAS_HISTORY)
        val deactivate = categories.setActive(id.getValue("Maison"), false)
        assertTrue(deactivate is OperationResult.Rejected && deactivate.reason == RejectReason.STILL_ALLOCATED)
        val duplicate = categories.addCategory("maison", 0xFF000000L)
        assertTrue(duplicate is OperationResult.Rejected && duplicate.reason == RejectReason.DUPLICATE_NAME)
    }

    // ---------------- Dépenses ----------------

    @Test
    fun valid_expense_reduces_the_category_balance() = runBlocking {
        income(100_000).recordedId()
        assertEquals(20_000L, balanceFc("Petites dépenses"))
        expense(5_000, "Petites dépenses").recordedId()
        assertEquals(15_000L, balanceFc("Petites dépenses"))
        assertConsistent()
    }

    @Test
    fun refuse_mode_rejects_expense_above_balance_and_changes_nothing() = runBlocking {
        income(100_000).recordedId()
        val result = expense(25_000, "Petites dépenses")
        assertTrue(result is OperationResult.Rejected && result.reason == RejectReason.INSUFFICIENT_BALANCE)
        assertEquals(20_000L, balanceFc("Petites dépenses"))
        assertEquals(0, db.expenseDao().getAll().size)
    }

    @Test
    fun warn_mode_asks_confirmation_then_allows_negative_balance() = runBlocking {
        settings.setOverdraftMode(OverdraftMode.WARN)
        income(100_000).recordedId()
        val ask = expense(25_000, "Petites dépenses")
        assertTrue(ask is OperationResult.NeedsConfirmation)
        val overdraft = (ask as OperationResult.NeedsConfirmation).confirmation as Confirmation.Overdraft
        assertEquals(fc(5_000), overdraft.shortfall)
        assertEquals(0, db.expenseDao().getAll().size)

        expense(25_000, "Petites dépenses", accept = true).recordedId()
        assertEquals(-5_000L, balanceFc("Petites dépenses"))
        assertTrue(db.expenseDao().getAll().single().overdraftAccepted)
        val report = checker.check()
        assertTrue(report.isConsistent)
        assertEquals(1, report.warnings.size)
    }

    @Test
    fun transfer_mode_covers_the_shortfall_with_a_separate_transfer() = runBlocking {
        settings.setOverdraftMode(OverdraftMode.TRANSFER)
        income(100_000).recordedId()
        val ask = expense(25_000, "Petites dépenses")
        val cover = (ask as OperationResult.NeedsConfirmation).confirmation as Confirmation.CoverByTransfer
        assertEquals(fc(5_000), cover.shortfall)
        assertTrue(cover.candidates.any { it.name == "Épargne" })

        expense(25_000, "Petites dépenses", coverFrom = "Épargne").recordedId()
        assertEquals(0L, balanceFc("Petites dépenses"))
        assertEquals(25_000L, balanceFc("Épargne"))
        val transfer = db.transferDao().getAll().single()
        assertEquals(fc(5_000), transfer.amount)
        // Un transfert n'est pas un revenu.
        assertEquals(fc(100_000), db.incomeDao().sumActive())
        assertConsistent()
    }

    // ---------------- Transferts ----------------

    @Test
    fun manual_transfer_moves_money_without_creating_income() = runBlocking {
        income(100_000).recordedId()
        transfers.transfer(TransferRequest(req(), id.getValue("Épargne"), id.getValue("Maison"), fc(10_000), TODAY, "Loyer")).recordedId()
        assertEquals(20_000L, balanceFc("Épargne"))
        assertEquals(30_000L, balanceFc("Maison"))
        assertEquals(fc(100_000), db.incomeDao().sumActive())
        val tooMuch = transfers.transfer(TransferRequest(req(), id.getValue("Dîme"), id.getValue("Maison"), fc(10_001), TODAY, "Test"))
        assertTrue(tooMuch is OperationResult.Rejected && tooMuch.reason == RejectReason.INSUFFICIENT_BALANCE)
        assertConsistent()
    }

    // ---------------- Corrections et historique ----------------

    @Test
    fun expense_correction_cancels_original_and_keeps_both_in_history() = runBlocking {
        income(100_000).recordedId()
        val original = expense(5_000, "Petites dépenses").recordedId()
        val corrected = expenses.correctExpense(
            original,
            ExpenseRequest(req(), fc(3_000), id.getValue("Petites dépenses"), "Achat corrigé", TODAY),
            "Montant mal saisi",
        ).recordedId()

        assertEquals(17_000L, balanceFc("Petites dépenses"))
        assertEquals(OperationStatus.CANCELLED, db.expenseDao().getById(original)!!.status)
        assertEquals(original, db.expenseDao().getById(corrected)!!.correctsExpenseId)

        val rows = history.get().filter { it.type == "EXPENSE" }
        assertEquals(2, rows.size)
        assertEquals(setOf(OperationStatus.ACTIVE, OperationStatus.CANCELLED), rows.map { it.status }.toSet())
        assertTrue(db.auditDao().getAll().any { it.entityType == "expense" && it.action == "cancel" })
        assertConsistent()
    }

    @Test
    fun refused_correction_leaves_the_original_expense_active() = runBlocking {
        income(100_000).recordedId()
        val original = expense(5_000, "Petites dépenses").recordedId()
        val result = expenses.correctExpense(
            original,
            ExpenseRequest(req(), fc(50_000), id.getValue("Petites dépenses"), "Trop cher", TODAY),
            "Essai",
        )
        assertTrue(result is OperationResult.Rejected)
        assertEquals(OperationStatus.ACTIVE, db.expenseDao().getById(original)!!.status)
        assertEquals(1, db.expenseDao().getAll().size)
        assertEquals(15_000L, balanceFc("Petites dépenses"))
    }

    @Test
    fun cancelling_a_spent_income_requires_confirmation() = runBlocking {
        val incomeId = income(100_000).recordedId()
        expense(20_000, "Petites dépenses").recordedId()
        val ask = incomes.cancelIncome(incomeId, "Erreur")
        assertTrue(ask is OperationResult.NeedsConfirmation)
        assertEquals(OperationStatus.ACTIVE, db.incomeDao().getById(incomeId)!!.status)

        incomes.cancelIncome(incomeId, "Erreur", force = true).recordedId()
        assertEquals(-20_000L, balanceFc("Petites dépenses"))
        assertConsistent()
    }

    @Test
    fun history_lists_incomes_expenses_and_transfers_newest_first() = runBlocking {
        income(100_000).recordedId()
        expense(1_000, "Maison").recordedId()
        transfers.transfer(TransferRequest(req(), id.getValue("Épargne"), id.getValue("Dîme"), fc(500), TODAY, "Don")).recordedId()
        val rows = history.get()
        assertEquals(listOf("TRANSFER", "EXPENSE", "INCOME"), rows.map { it.type })
        assertEquals("Dîme", rows.first().targetCategoryName)
    }

    // ---------------- Cohérence ----------------

    @Test
    fun consistency_checker_detects_a_corrupted_allocation() = runBlocking {
        val incomeId = income(100_000).recordedId()
        assertConsistent()
        // Corruption volontaire hors application : une allocation supplémentaire.
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO categories (uuid, name, colorArgb, sortOrder, isActive, isSavings, createdAt, updatedAt) " +
                "VALUES ('x', 'Intrus', 0, 99, 1, 0, 0, 0)"
        )
        db.openHelper.writableDatabase.execSQL(
            "INSERT INTO allocations (incomeId, categoryId, basisPoints, amount) " +
                "SELECT $incomeId, id, 0, 100 FROM categories WHERE name = 'Intrus'"
        )
        val report = checker.check()
        assertTrue(!report.isConsistent)
        assertTrue(report.errors.any { it.contains("Entrée n°$incomeId") })
    }
}
