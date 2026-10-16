package cd.smartbudget.fc.data.repo

import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.db.entity.OperationRow
import kotlinx.coroutines.flow.Flow

/** Historique complet des opérations (entrées, dépenses, transferts), y compris les opérations annulées. */
class HistoryRepository(private val db: AppDatabase) {

    fun observe(fromDay: Long = Long.MIN_VALUE / 2, toDay: Long = Long.MAX_VALUE / 2): Flow<List<OperationRow>> =
        db.operationDao().observeBetween(fromDay, toDay)

    suspend fun get(fromDay: Long = Long.MIN_VALUE / 2, toDay: Long = Long.MAX_VALUE / 2): List<OperationRow> =
        db.operationDao().getBetween(fromDay, toDay)
}
