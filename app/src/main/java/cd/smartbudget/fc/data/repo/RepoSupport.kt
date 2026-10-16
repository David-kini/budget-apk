package cd.smartbudget.fc.data.repo

import androidx.room.withTransaction
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.db.entity.AuditLogEntity
import cd.smartbudget.fc.data.db.entity.CategoryBalanceRow
import cd.smartbudget.fc.domain.AbortTransaction
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.domain.RejectReason

/**
 * Exécute [block] dans une transaction. Si le bloc lève [AbortTransaction], tout est annulé
 * et le résultat métier porté par l'exception est retourné.
 */
internal suspend fun AppDatabase.transactional(block: suspend () -> OperationResult): OperationResult =
    try {
        withTransaction { block() }
    } catch (abort: AbortTransaction) {
        abort.result
    }

/** Annule la transaction en cours en retournant [result] à l'appelant de [transactional]. */
internal fun abortWith(result: OperationResult): Nothing = throw AbortTransaction(result)

internal fun rejected(reason: RejectReason, message: String) = OperationResult.Rejected(reason, message)

internal suspend fun AppDatabase.audit(at: Long, entityType: String, entityId: Long?, action: String, details: String?) {
    auditDao().insert(AuditLogEntity(at = at, entityType = entityType, entityId = entityId, action = action, details = details))
}

internal suspend fun AppDatabase.balancesById(): Map<Long, CategoryBalanceRow> =
    balanceDao().getBalances().associateBy { it.categoryId }

internal fun String?.cleanOptional(): String? = this?.trim()?.takeIf { it.isNotEmpty() }
