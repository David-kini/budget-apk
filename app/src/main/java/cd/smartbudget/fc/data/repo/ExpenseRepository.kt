package cd.smartbudget.fc.data.repo

import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.OperationStatus
import cd.smartbudget.fc.core.OverdraftMode
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.db.entity.ExpenseEntity
import cd.smartbudget.fc.data.db.entity.TransferEntity
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.Confirmation
import cd.smartbudget.fc.domain.CoverCandidate
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.domain.RejectReason
import cd.smartbudget.fc.domain.SystemClock
import java.util.UUID

/**
 * Saisie d'une dépense.
 * @param acceptOverdraft confirmation de l'utilisateur en mode WARN.
 * @param coverFromCategoryId catégorie choisie pour couvrir le manque en mode TRANSFER.
 */
data class ExpenseRequest(
    val clientRequestId: String,
    val amount: Long,
    val categoryId: Long,
    val reason: String,
    val expenseDate: Long,
    val description: String? = null,
    val beneficiary: String? = null,
    val attachmentPath: String? = null,
    val acceptOverdraft: Boolean = false,
    val coverFromCategoryId: Long? = null,
)

class ExpenseRepository(
    private val db: AppDatabase,
    private val settings: SettingsRepository,
    private val clock: Clock = SystemClock,
) {

    fun observeExpenses() = db.expenseDao().observeAll()

    /**
     * Enregistre une dépense selon le mode de dépassement configuré.
     * Refus ou demande de confirmation : AUCUN solde n'est modifié.
     */
    suspend fun recordExpense(request: ExpenseRequest): OperationResult {
        validate(request)?.let { return it }
        val mode = settings.overdraftMode()
        return try {
            db.transactional { recordInTransaction(request, mode, correctsExpenseId = null) }
        } catch (e: Exception) {
            db.expenseDao().getByClientRequestId(request.clientRequestId)
                ?.let { OperationResult.AlreadyRecorded(it.id) } ?: throw e
        }
    }

    /** Annule une dépense (et le transfert de couverture qui lui est éventuellement lié). Elle reste dans l'historique. */
    suspend fun cancelExpense(id: Long, reason: String): OperationResult {
        if (reason.isBlank()) return rejected(RejectReason.INVALID_INPUT, "Indiquez le motif de l'annulation.")
        return db.transactional { cancelInTransaction(id, reason.trim()) }
    }

    /**
     * Corrige une dépense : annulation de l'ancienne + nouvelle dépense liée, dans une seule transaction.
     * Si la nouvelle dépense est refusée ou demande une confirmation, l'ancienne reste active.
     */
    suspend fun correctExpense(originalId: Long, replacement: ExpenseRequest, reason: String): OperationResult {
        if (reason.isBlank()) return rejected(RejectReason.INVALID_INPUT, "Indiquez le motif de la correction.")
        validate(replacement)?.let { return it }
        val mode = settings.overdraftMode()
        return db.transactional {
            db.expenseDao().getByClientRequestId(replacement.clientRequestId)?.let {
                return@transactional OperationResult.AlreadyRecorded(it.id)
            }
            val cancel = cancelInTransaction(originalId, "Correction : ${reason.trim()}")
            if (cancel !is OperationResult.Recorded) abortWith(cancel)
            val result = recordInTransaction(replacement, mode, correctsExpenseId = originalId)
            if (result !is OperationResult.Recorded) abortWith(result)
            OperationResult.Recorded(result.id, "Dépense corrigée. L'ancienne dépense est conservée dans l'historique comme annulée.")
        }
    }

    // ------------------------------------------------------------------

    private suspend fun recordInTransaction(r: ExpenseRequest, mode: OverdraftMode, correctsExpenseId: Long?): OperationResult {
        db.expenseDao().getByClientRequestId(r.clientRequestId)?.let { return OperationResult.AlreadyRecorded(it.id) }

        val category = db.categoryDao().getById(r.categoryId)
            ?: return rejected(RejectReason.NOT_FOUND, "Catégorie introuvable.")
        val balances = db.balancesById()
        val available = balances[category.id]?.balance() ?: 0L
        val shortfall = r.amount - maxOf(available, 0L)

        var overdraftAccepted = false
        var cover: Pair<Long, Long>? = null // (catégorie source, montant)

        if (r.amount > available) {
            when (mode) {
                OverdraftMode.REFUSE -> return rejected(
                    RejectReason.INSUFFICIENT_BALANCE,
                    "Solde insuffisant dans « ${category.name} » : disponible ${Money.format(available)}, " +
                        "dépense ${Money.format(r.amount)}. Dépense refusée, aucun solde n'a été modifié.",
                )
                OverdraftMode.WARN -> {
                    if (!r.acceptOverdraft) {
                        return OperationResult.NeedsConfirmation(
                            Confirmation.Overdraft(category.name, available, r.amount - available)
                        )
                    }
                    overdraftAccepted = true
                }
                OverdraftMode.TRANSFER -> {
                    val candidates = balances.values
                        .filter { it.categoryId != category.id && it.balance() >= shortfall }
                        .map { CoverCandidate(it.categoryId, it.name, it.balance()) }
                    val sourceId = r.coverFromCategoryId
                        ?: return OperationResult.NeedsConfirmation(
                            Confirmation.CoverByTransfer(category.name, available, shortfall, candidates)
                        )
                    if (candidates.none { it.categoryId == sourceId }) {
                        return rejected(
                            RejectReason.INSUFFICIENT_BALANCE,
                            "La catégorie choisie ne peut pas couvrir ${Money.format(shortfall)}.",
                        )
                    }
                    cover = sourceId to shortfall
                }
            }
        }

        val coverTransfer = cover
        val now = clock.nowMillis()
        val expenseId = db.expenseDao().insert(
            ExpenseEntity(
                uuid = UUID.randomUUID().toString(),
                amount = r.amount,
                categoryId = category.id,
                reason = r.reason.trim(),
                expenseDate = r.expenseDate,
                description = r.description.cleanOptional(),
                beneficiary = r.beneficiary.cleanOptional(),
                attachmentPath = r.attachmentPath.cleanOptional(),
                status = OperationStatus.ACTIVE,
                correctsExpenseId = correctsExpenseId,
                overdraftAccepted = overdraftAccepted,
                clientRequestId = r.clientRequestId,
                createdAt = now,
            )
        )
        coverTransfer?.let { (sourceId, amount) ->
            val transferId = db.transferDao().insert(
                TransferEntity(
                    uuid = UUID.randomUUID().toString(),
                    fromCategoryId = sourceId,
                    toCategoryId = category.id,
                    amount = amount,
                    transferDate = r.expenseDate,
                    reason = "Couverture de la dépense : ${r.reason.trim()}",
                    linkedExpenseId = expenseId,
                    status = OperationStatus.ACTIVE,
                    clientRequestId = "${r.clientRequestId}:cover",
                    createdAt = now,
                )
            )
            db.audit(now, "transfer", transferId, "create_cover", "${Money.format(amount)} vers « ${category.name} »")
        }
        db.audit(
            now, "expense", expenseId, if (correctsExpenseId == null) "create" else "correct",
            "${Money.format(r.amount)} — ${category.name} — ${r.reason.trim()}" + if (overdraftAccepted) " (dépassement accepté)" else "",
        )
        val message = when {
            coverTransfer != null -> "Dépense enregistrée. ${Money.format(coverTransfer.second)} ont été transférés pour la couvrir."
            overdraftAccepted -> "Dépense enregistrée. Attention : le solde de « ${category.name} » est maintenant négatif."
            else -> "Dépense de ${Money.format(r.amount)} enregistrée."
        }
        return OperationResult.Recorded(expenseId, message)
    }

    private suspend fun cancelInTransaction(id: Long, reason: String): OperationResult {
        val expense = db.expenseDao().getById(id) ?: return rejected(RejectReason.NOT_FOUND, "Dépense introuvable.")
        if (expense.status != OperationStatus.ACTIVE) {
            return rejected(RejectReason.ALREADY_CANCELLED, "Cette dépense est déjà annulée.")
        }
        val now = clock.nowMillis()
        if (db.expenseDao().cancel(id, now, reason) != 1) {
            return rejected(RejectReason.ALREADY_CANCELLED, "Cette dépense est déjà annulée.")
        }
        db.transferDao().getActiveLinkedToExpense(id).forEach { t ->
            db.transferDao().cancel(t.id, now, "Dépense liée annulée")
            db.audit(now, "transfer", t.id, "cancel", "Couverture annulée avec la dépense $id")
        }
        db.audit(now, "expense", id, "cancel", "${Money.format(expense.amount)} — motif : $reason")
        return OperationResult.Recorded(id, "Dépense annulée. Elle reste visible dans l'historique.")
    }

    private suspend fun validate(r: ExpenseRequest): OperationResult.Rejected? {
        val basic = when {
            r.clientRequestId.isBlank() -> "Identifiant de saisie manquant."
            r.amount <= 0 -> "Le montant de la dépense doit être supérieur à 0."
            r.amount > Money.MAX_AMOUNT -> "Montant trop élevé."
            r.reason.isBlank() -> "Indiquez le motif de la dépense."
            r.reason.trim().length > 120 -> "Le motif ne doit pas dépasser 120 caractères."
            r.expenseDate > clock.todayEpochDay() -> "La date de la dépense ne peut pas être dans le futur."
            r.coverFromCategoryId != null && r.coverFromCategoryId == r.categoryId -> "La catégorie de couverture doit être différente."
            else -> null
        }
        if (basic != null) return rejected(RejectReason.INVALID_INPUT, basic)
        val category = db.categoryDao().getById(r.categoryId)
            ?: return rejected(RejectReason.NOT_FOUND, "Choisissez une catégorie valide.")
        if (!category.isActive) {
            return rejected(RejectReason.CATEGORY_INACTIVE, "La catégorie « ${category.name} » est désactivée.")
        }
        return null
    }
}
