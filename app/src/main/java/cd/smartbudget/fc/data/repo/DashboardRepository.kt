package cd.smartbudget.fc.data.repo

import cd.smartbudget.fc.core.DateRange
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.OperationStatus
import cd.smartbudget.fc.core.PeriodFilter
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.db.entity.CategoryBalanceRow
import cd.smartbudget.fc.data.db.entity.OperationRow
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.SystemClock
import java.time.LocalDate

data class CategoryAmount(val categoryId: Long, val name: String, val colorArgb: Long, val amount: Long)

data class MonthPoint(val label: String, val income: Long, val expense: Long)

data class DashboardSummary(
    val range: DateRange,
    /** Entrées et dépenses actives de la période choisie. */
    val periodIncome: Long,
    val periodExpense: Long,
    /** Soldes actuels (toutes périodes). */
    val totalBalance: Long,
    val balances: List<CategoryBalanceRow>,
    /** Solde actuel des catégories d'épargne. */
    val savingsBalance: Long,
    /** Total affecté à l'épargne depuis le début (allocations d'entrées actives + transferts reçus). */
    val savingsAllocated: Long,
    val expensesByCategory: List<CategoryAmount>,
    val monthly: List<MonthPoint>,
    val monthIncome: Long,
    val monthExpense: Long,
    val recent: List<OperationRow>,
    val alerts: List<String>,
)

/** Calcule toutes les données du tableau de bord à partir de SQLite (aucun chiffre fictif). */
class DashboardRepository(private val db: AppDatabase, private val clock: Clock = SystemClock) {

    /** Émet à chaque changement des tables financières. */
    fun changes() = db.balanceDao().observeBalances()

    suspend fun summary(range: DateRange): DashboardSummary {
        val today = clock.todayEpochDay()
        val balances = db.balanceDao().getBalances()
        val byId = balances.associateBy { it.categoryId }

        val periodIncome = db.incomeDao().sumActiveBetween(range.fromDay, range.toDay)
        val periodExpense = db.expenseDao().sumActiveBetween(range.fromDay, range.toDay)

        val expensesByCategory = db.expenseDao().sumByCategoryBetween(range.fromDay, range.toDay)
            .mapNotNull { row -> byId[row.categoryId]?.let { CategoryAmount(it.categoryId, it.name, it.colorArgb, row.total) } }
            .sortedByDescending { it.amount }

        // Évolution sur les 6 derniers mois (mois en cours inclus).
        val start = LocalDate.ofEpochDay(today).withDayOfMonth(1).minusMonths(5)
        val incomes = db.incomeDao().getBetween(start.toEpochDay(), today).filter { it.status == OperationStatus.ACTIVE }
        val expenses = db.expenseDao().getBetween(start.toEpochDay(), today).filter { it.status == OperationStatus.ACTIVE }
        val monthly = (0 until 6).map { i ->
            val m = start.plusMonths(i.toLong())
            val from = m.toEpochDay()
            val to = m.plusMonths(1).toEpochDay() - 1
            MonthPoint(
                cd.smartbudget.fc.core.Dates.monthLabel(m.year, m.monthValue),
                incomes.filter { it.receivedDate in from..to }.sumOf { it.amount },
                expenses.filter { it.expenseDate in from..to }.sumOf { it.amount },
            )
        }
        val month = PeriodFilter.MONTH.range(today)

        val savings = balances.filter { it.isSavings }
        val alerts = buildList {
            balances.filter { it.balance() < 0 }.forEach {
                add("« ${it.name} » est en dépassement : ${Money.format(it.balance())}.")
            }
            balances.filter { it.isActive && it.spent > 0 && it.balance() >= 0 && it.balance() < it.allocated / 10 }
                .forEach { add("« ${it.name} » : moins de 10 % du budget restant (${Money.format(it.balance())}).") }
        }

        return DashboardSummary(
            range = range,
            periodIncome = periodIncome,
            periodExpense = periodExpense,
            totalBalance = balances.sumOf { it.balance() },
            balances = balances,
            savingsBalance = savings.sumOf { it.balance() },
            savingsAllocated = savings.sumOf { it.allocated + it.transferredIn },
            expensesByCategory = expensesByCategory,
            monthly = monthly,
            monthIncome = db.incomeDao().sumActiveBetween(month.fromDay, month.toDay),
            monthExpense = db.expenseDao().sumActiveBetween(month.fromDay, month.toDay),
            recent = db.operationDao().getBetween(DateRange.ALL.fromDay, DateRange.ALL.toDay).take(6),
            alerts = alerts,
        )
    }
}
