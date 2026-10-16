package cd.smartbudget.fc.domain

import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.OperationStatus
import cd.smartbudget.fc.core.Percent
import cd.smartbudget.fc.data.db.AppDatabase

/**
 * Vérifie la cohérence de toutes les données financières.
 * Utilisé à la demande (Paramètres) et automatiquement après chaque restauration.
 */
class ConsistencyChecker(private val db: AppDatabase) {

    data class Report(
        val errors: List<String>,
        val warnings: List<String>,
        val totalIncome: Long,
        val totalExpense: Long,
        val totalBalance: Long,
        val checkedIncomes: Int,
        val checkedExpenses: Int,
        val checkedTransfers: Int,
    ) {
        val isConsistent: Boolean get() = errors.isEmpty()
    }

    suspend fun check(): Report {
        val errors = mutableListOf<String>()
        val warnings = mutableListOf<String>()

        val categories = db.categoryDao().getAll().associateBy { it.id }
        val configurations = db.budgetConfigurationDao().getAll()
        val itemsByConfig = db.budgetConfigurationDao().getAllItems().groupBy { it.configurationId }
        val incomes = db.incomeDao().getAll()
        val allocationsByIncome = db.allocationDao().getAll().groupBy { it.incomeId }
        val expenses = db.expenseDao().getAll()
        val transfers = db.transferDao().getAll()

        // 1. Une seule répartition active, totalisant 100 %.
        val current = configurations.filter { it.isCurrent }
        if (current.size != 1) errors += "Il doit y avoir exactement une répartition active (trouvé : ${current.size})."
        configurations.forEach { c ->
            val total = itemsByConfig[c.id].orEmpty().sumOf { it.basisPoints }
            if (total != Percent.FULL) errors += "La répartition n°${c.id} totalise ${Percent.format(total)} au lieu de 100 %."
        }

        // 2. Chaque entrée : allocations = montant, pourcentages = ceux de sa configuration d'origine.
        incomes.forEach { income ->
            val allocations = allocationsByIncome[income.id].orEmpty()
            val sum = allocations.sumOf { it.amount }
            if (sum != income.amount) {
                errors += "Entrée n°${income.id} : allocations ${Money.format(sum)} ≠ montant ${Money.format(income.amount)}."
            }
            val expected = itemsByConfig[income.configurationId].orEmpty().associate { it.categoryId to it.basisPoints }
            val applied = allocations.associate { it.categoryId to it.basisPoints }
            if (expected != applied) {
                errors += "Entrée n°${income.id} : pourcentages appliqués différents de sa répartition d'origine."
            }
        }

        // 3. Références valides.
        expenses.forEach { e ->
            if (e.categoryId !in categories) errors += "Dépense n°${e.id} : catégorie inexistante."
            if (e.amount <= 0) errors += "Dépense n°${e.id} : montant invalide."
        }
        transfers.forEach { t ->
            if (t.fromCategoryId == t.toCategoryId) errors += "Transfert n°${t.id} : catégories identiques."
            if (t.fromCategoryId !in categories || t.toCategoryId !in categories) errors += "Transfert n°${t.id} : catégorie inexistante."
        }

        // 4. Invariant global : Σ soldes = Σ entrées actives − Σ dépenses actives.
        val totalIncome = incomes.filter { it.status == OperationStatus.ACTIVE }.sumOf { it.amount }
        val totalExpense = expenses.filter { it.status == OperationStatus.ACTIVE }.sumOf { it.amount }
        val balances = db.balanceDao().getBalances()
        val totalBalance = balances.sumOf { it.balance() }
        if (totalBalance != totalIncome - totalExpense) {
            errors += "Solde total ${Money.format(totalBalance)} ≠ entrées − dépenses ${Money.format(totalIncome - totalExpense)}."
        }

        // 5. Avertissements : soldes négatifs.
        balances.filter { it.balance() < 0 }.forEach {
            warnings += "Le solde de « ${it.name} » est négatif : ${Money.format(it.balance())}."
        }

        return Report(errors, warnings, totalIncome, totalExpense, totalBalance, incomes.size, expenses.size, transfers.size)
    }
}
