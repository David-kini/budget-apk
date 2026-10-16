package cd.smartbudget.fc.data.repo

import cd.smartbudget.fc.core.Dates
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.OperationStatus
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.db.DbTriggers
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.Confirmation
import cd.smartbudget.fc.domain.NegativeImpact
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.domain.RejectReason
import cd.smartbudget.fc.domain.SystemClock
import java.io.File

/**
 * Suppression DÉFINITIVE d'une opération saisie par erreur.
 *
 * - Toujours après confirmation de l'utilisateur (côté écran).
 * - Jamais silencieuse : la date, le montant et le libellé supprimés sont inscrits dans le journal d'audit.
 * - Atomique : les garde-fous SQLite anti-suppression ne sont levés que dans la transaction, puis recréés.
 * - Une entrée supprimée emporte ses allocations ; une dépense emporte son transfert de couverture et sa pièce jointe.
 * - Refusée si l'opération a été corrigée par une autre (supprimer d'abord la correction).
 */
class DeletionRepository(
    private val db: AppDatabase,
    private val filesDir: File?,
    private val clock: Clock = SystemClock,
) {

    suspend fun deleteIncome(id: Long, force: Boolean = false): OperationResult = db.transactional {
        val income = db.incomeDao().getById(id)
            ?: return@transactional rejected(RejectReason.NOT_FOUND, "Entrée introuvable (déjà supprimée ?).")
        if (db.incomeDao().countCorrections(id) > 0) {
            return@transactional rejected(
                RejectReason.HAS_HISTORY,
                "Cette entrée a été corrigée par une autre entrée. Supprimez d'abord la correction.",
            )
        }
        val allocations = db.allocationDao().getForIncome(id)
        if (income.status == OperationStatus.ACTIVE && !force) {
            val balances = db.balancesById()
            val impacts = allocations.mapNotNull { a ->
                val row = balances.getValue(a.categoryId)
                val after = row.balance() - a.amount
                if (after < 0) NegativeImpact(a.categoryId, row.name, after) else null
            }
            if (impacts.isNotEmpty()) return@transactional OperationResult.NeedsConfirmation(Confirmation.NegativeBalances(impacts))
        }
        withDeleteGuardsLifted {
            db.allocationDao().deleteForIncome(id)
            check(db.incomeDao().deleteById(id) == 1) { "Suppression de l'entrée impossible" }
        }
        db.audit(
            clock.nowMillis(), "income", id, "delete",
            "Supprimée (erreur de saisie) : ${Money.format(income.amount)} — ${income.source} — ${Dates.format(income.receivedDate)}" +
                " — statut ${income.status} — ${allocations.size} allocation(s)",
        )
        OperationResult.Recorded(id, "Entrée supprimée définitivement.")
    }

    suspend fun deleteExpense(id: Long): OperationResult {
        var attachment: String? = null
        val result = db.transactional {
            val expense = db.expenseDao().getById(id)
                ?: return@transactional rejected(RejectReason.NOT_FOUND, "Dépense introuvable (déjà supprimée ?).")
            if (db.expenseDao().countCorrections(id) > 0) {
                return@transactional rejected(
                    RejectReason.HAS_HISTORY,
                    "Cette dépense a été corrigée par une autre dépense. Supprimez d'abord la correction.",
                )
            }
            val covers = db.transferDao().getAllLinkedToExpense(id)
            withDeleteGuardsLifted {
                covers.forEach { db.transferDao().deleteById(it.id) }
                check(db.expenseDao().deleteById(id) == 1) { "Suppression de la dépense impossible" }
            }
            val categoryName = db.categoryDao().getById(expense.categoryId)?.name ?: "?"
            db.audit(
                clock.nowMillis(), "expense", id, "delete",
                "Supprimée (erreur de saisie) : ${Money.format(expense.amount)} — $categoryName — ${expense.reason} — " +
                    "${Dates.format(expense.expenseDate)} — statut ${expense.status}" +
                    if (covers.isNotEmpty()) " — transfert(s) de couverture supprimé(s) : ${covers.size}" else "",
            )
            attachment = expense.attachmentPath
            OperationResult.Recorded(id, "Dépense supprimée définitivement.")
        }
        // La pièce jointe n'est effacée qu'une fois la suppression validée en base.
        if (result is OperationResult.Recorded) {
            attachment?.let { path -> filesDir?.let { File(it, path).delete() } }
        }
        return result
    }

    suspend fun deleteTransfer(id: Long, force: Boolean = false): OperationResult = db.transactional {
        val t = db.transferDao().getById(id)
            ?: return@transactional rejected(RejectReason.NOT_FOUND, "Transfert introuvable (déjà supprimé ?).")
        if (t.linkedExpenseId != null) {
            return@transactional rejected(
                RejectReason.INVALID_INPUT,
                "Ce transfert couvre une dépense : supprimez la dépense, son transfert sera supprimé avec elle.",
            )
        }
        if (t.status == OperationStatus.ACTIVE && !force) {
            val target = db.balancesById().getValue(t.toCategoryId)
            val after = target.balance() - t.amount
            if (after < 0) {
                return@transactional OperationResult.NeedsConfirmation(
                    Confirmation.NegativeBalances(listOf(NegativeImpact(target.categoryId, target.name, after)))
                )
            }
        }
        withDeleteGuardsLifted {
            check(db.transferDao().deleteById(id) == 1) { "Suppression du transfert impossible" }
        }
        db.audit(
            clock.nowMillis(), "transfer", id, "delete",
            "Supprimé (erreur de saisie) : ${Money.format(t.amount)} — ${t.reason} — ${Dates.format(t.transferDate)} — statut ${t.status}",
        )
        OperationResult.Recorded(id, "Transfert supprimé définitivement.")
    }

    /** À appeler uniquement dans une transaction : retire puis recrée les garde-fous anti-suppression. */
    private suspend fun withDeleteGuardsLifted(block: suspend () -> Unit) {
        val sql = db.openHelper.writableDatabase
        DbTriggers.dropDeleteGuards(sql)
        try {
            block()
        } finally {
            DbTriggers.createDeleteGuards(sql)
        }
    }
}
