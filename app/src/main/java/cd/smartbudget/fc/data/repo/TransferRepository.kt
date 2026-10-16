package cd.smartbudget.fc.data.repo

import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.OperationStatus
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.db.entity.TransferEntity
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.Confirmation
import cd.smartbudget.fc.domain.NegativeImpact
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.domain.RejectReason
import cd.smartbudget.fc.domain.SystemClock
import java.util.UUID

data class TransferRequest(
    val clientRequestId: String,
    val fromCategoryId: Long,
    val toCategoryId: Long,
    val amount: Long,
    val transferDate: Long,
    val reason: String,
)

/**
 * Transferts entre catégories. Un transfert ne crée ni ne détruit d'argent :
 * il n'est jamais compté comme un revenu ni comme une dépense.
 */
class TransferRepository(private val db: AppDatabase, private val clock: Clock = SystemClock) {

    fun observeTransfers() = db.transferDao().observeAll()

    suspend fun transfer(r: TransferRequest): OperationResult {
        val invalid = when {
            r.clientRequestId.isBlank() -> "Identifiant de saisie manquant."
            r.amount <= 0 -> "Le montant du transfert doit être supérieur à 0."
            r.amount > Money.MAX_AMOUNT -> "Montant trop élevé."
            r.fromCategoryId == r.toCategoryId -> "Choisissez deux catégories différentes."
            r.reason.isBlank() -> "Indiquez le motif du transfert."
            r.transferDate > clock.todayEpochDay() -> "La date du transfert ne peut pas être dans le futur."
            else -> null
        }
        if (invalid != null) return rejected(RejectReason.INVALID_INPUT, invalid)

        return try {
            db.transactional {
                db.transferDao().getByClientRequestId(r.clientRequestId)?.let {
                    return@transactional OperationResult.AlreadyRecorded(it.id)
                }
                val from = db.categoryDao().getById(r.fromCategoryId)
                    ?: return@transactional rejected(RejectReason.NOT_FOUND, "Catégorie source introuvable.")
                val to = db.categoryDao().getById(r.toCategoryId)
                    ?: return@transactional rejected(RejectReason.NOT_FOUND, "Catégorie de destination introuvable.")
                if (!to.isActive) {
                    return@transactional rejected(RejectReason.CATEGORY_INACTIVE, "La catégorie « ${to.name} » est désactivée.")
                }
                // Une catégorie désactivée peut encore être vidée vers une autre (source autorisée).
                val available = db.balancesById()[from.id]?.balance() ?: 0L
                if (r.amount > available) {
                    return@transactional rejected(
                        RejectReason.INSUFFICIENT_BALANCE,
                        "Solde insuffisant dans « ${from.name} » : disponible ${Money.format(available)}.",
                    )
                }
                val now = clock.nowMillis()
                val id = db.transferDao().insert(
                    TransferEntity(
                        uuid = UUID.randomUUID().toString(),
                        fromCategoryId = from.id,
                        toCategoryId = to.id,
                        amount = r.amount,
                        transferDate = r.transferDate,
                        reason = r.reason.trim(),
                        status = OperationStatus.ACTIVE,
                        clientRequestId = r.clientRequestId,
                        createdAt = now,
                    )
                )
                db.audit(now, "transfer", id, "create", "${Money.format(r.amount)} : « ${from.name} » → « ${to.name} »")
                OperationResult.Recorded(id, "Transfert de ${Money.format(r.amount)} de « ${from.name} » vers « ${to.name} » enregistré.")
            }
        } catch (e: Exception) {
            db.transferDao().getByClientRequestId(r.clientRequestId)
                ?.let { OperationResult.AlreadyRecorded(it.id) } ?: throw e
        }
    }

    /** Annule un transfert. Si la catégorie de destination a déjà dépensé cet argent, une confirmation est demandée. */
    suspend fun cancelTransfer(id: Long, reason: String, force: Boolean = false): OperationResult {
        if (reason.isBlank()) return rejected(RejectReason.INVALID_INPUT, "Indiquez le motif de l'annulation.")
        return db.transactional {
            val t = db.transferDao().getById(id)
                ?: return@transactional rejected(RejectReason.NOT_FOUND, "Transfert introuvable.")
            if (t.status != OperationStatus.ACTIVE) {
                return@transactional rejected(RejectReason.ALREADY_CANCELLED, "Ce transfert est déjà annulé.")
            }
            if (t.linkedExpenseId != null) {
                return@transactional rejected(
                    RejectReason.INVALID_INPUT,
                    "Ce transfert couvre une dépense : annulez ou corrigez la dépense pour l'annuler.",
                )
            }
            val target = db.balancesById().getValue(t.toCategoryId)
            val after = target.balance() - t.amount
            if (after < 0 && !force) {
                return@transactional OperationResult.NeedsConfirmation(
                    Confirmation.NegativeBalances(listOf(NegativeImpact(target.categoryId, target.name, after)))
                )
            }
            val now = clock.nowMillis()
            db.transferDao().cancel(id, now, reason.trim())
            db.audit(now, "transfer", id, "cancel", "${Money.format(t.amount)} — motif : ${reason.trim()}")
            OperationResult.Recorded(id, "Transfert annulé. Il reste visible dans l'historique.")
        }
    }
}
