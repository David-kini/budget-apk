package cd.smartbudget.fc.di

import android.content.Context
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.repo.CategoryRepository
import cd.smartbudget.fc.data.repo.DashboardRepository
import cd.smartbudget.fc.data.repo.ExpenseRepository
import cd.smartbudget.fc.data.repo.HistoryRepository
import cd.smartbudget.fc.data.repo.IncomeRepository
import cd.smartbudget.fc.data.repo.SettingsRepository
import cd.smartbudget.fc.data.repo.TransferRepository
import cd.smartbudget.fc.domain.ConsistencyChecker
import cd.smartbudget.fc.security.DatabaseKeyManager

/** Conteneur de dépendances de l'application (injection manuelle). */
class AppContainer(context: Context) {
    val appContext: Context = context.applicationContext

    val databaseKeyManager: DatabaseKeyManager by lazy { DatabaseKeyManager(appContext) }

    val database: AppDatabase by lazy {
        AppDatabase.build(appContext, databaseKeyManager.getOrCreatePassphrase())
    }

    val settingsRepository by lazy { SettingsRepository(database) }
    val categoryRepository by lazy { CategoryRepository(database) }
    val incomeRepository by lazy { IncomeRepository(database) }
    val expenseRepository by lazy { ExpenseRepository(database, settingsRepository) }
    val transferRepository by lazy { TransferRepository(database) }
    val historyRepository by lazy { HistoryRepository(database) }
    val consistencyChecker by lazy { ConsistencyChecker(database) }
    val dashboardRepository by lazy { DashboardRepository(database) }
}
