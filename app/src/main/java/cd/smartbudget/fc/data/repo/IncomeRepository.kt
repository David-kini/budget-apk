package cd.smartbudget.fc.data.repo

import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.OperationStatus
import cd.smartbudget.fc.core.Percent
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.db.entity.AllocationEntity
import cd.smartbudget.fc.data.db.entity.IncomeEntity
import cd.smartbudget.fc.domain.AllocationCalculator
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.Confirmation
import cd.smartbudget.fc.domain.NegativeImpact
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.domain.RejectReason
import cd.smartbudget.fc.domain.SystemClock
import java.util.UUID

/** Saisie d'une entrée d'argent. [clientRequestId] est généré à l'ouverture du formulaire (anti double-clic). */
data class IncomeRequest(
    val clientRequestId: String,
    val amount: Long,
    val source: String,
    val receivedDate: Long,
    val description: String? = null,
    val reference: String? = null,
)

class IncomeRepository(private val db: AppDatabase, private val clock: Clock = SystemClock) {

    fun observeIncomes() = db.incomeDao().observeAll()

    suspend fun allocationsOf(incomeId: Long) = db.allocationDao().getForIncome(incomeId)

    /**
     * Enregistre une entrée et sa répartition dans UNE transaction :
     * vérification → entrée → pourcentages actifs → calcul → allocations → contrôle → historique (audit).
     * En cas d'erreur, rien n'est conservé. Une même demande envoyée deux fois n'est enregistrée qu'une fois.
     */
    suspend fun recordIncome(request: IncomeRequest): OperationResult {
        validate(request)?.let { return it }
        return try {
            db.transactional { recordInTransaction(request, correctsIncomeId = null) }
        } catch (e: Exception) {
            // Course rare : la contrainte UNIQUE sur clientRequestId a joué → la demande est déjà enregistrée.
            db.incomeDao().getByClientRequestId(request.clientRequestId)
                ?.let { OperationResult.AlreadyRecorded(it.id) } ?: throw e
        }
    }

    /**
     * Annule une entrée : ses allocations ne comptent plus dans les soldes.
     * Si cela rend des soldes négatifs (argent déjà dépensé), une confirmation est demandée ([force] = true pour valider).
     */
    suspend fun cancelIncome(id: Long, reason: String, force: Boolean = false): OperationResult {
        if (reason.isBlank()) return rejected(RejectReason.INVALID_INPUT, "Indiquez le motif de l'annulation.")
        return db.transactional { cancelInTransaction(id, reason.trim(), force) }
    }

    /**
     * Corrige une entrée : annulation de l'ancienne + nouvelle entrée liée, répartie avec les pourcentages ACTUELS.
     * L'opération est atomique : si la nouvelle entrée est invalide, l'ancienne reste active.
     */
    suspend fun correctIncome(originalId: Long, replacement: IncomeRequest, reason: String, force: Boolean = false): OperationResult {
        if (reason.isBlank()) return rejected(RejectReason.INVALID_INPUT, "Indiquez le motif de la correction.")
        validate(replacement)?.let { return it }
        return db.transactional {
            db.incomeDao().getByClientRequestId(replacement.clientRequestId)?.let {
                return@transactional OperationResult.AlreadyRecorded(it.id)
            }
            val negativeBefore = db.balanceDao().getBalances().filter { it.balance() < 0 }.map { it.categoryId }.toSet()
            val cancel = cancelInTransaction(originalId, "Correction : ${reason.trim()}", force = true)
            if (cancel !is OperationResult.Recorded) abortWith(cancel)
            val result = recordInTransaction(replacement, correctsIncomeId = originalId)
            if (result !is OperationResult.Recorded) abortWith(result)
            if (!force) {
                val negatives = db.balanceDao().getBalances().filter { it.balance() < 0 }
                if (negatives.any { it.categoryId !in negativeBefore }) {
                    abortWith(
                        OperationResult.NeedsConfirmation(
                            Confirmation.NegativeBalances(negatives.map { NegativeImpact(it.categoryId, it.name, it.balance()) })
                        )
                    )
                }
            }
            OperationResult.Recorded(result.id, "Entrée corrigée. L'ancienne entrée est conservée dans l'historique comme annulée.")
        }
    }

    // ------------------------------------------------------------------

    private suspend fun recordInTransaction(request: IncomeRequest, correctsIncomeId: Long?): OperationResult {
        db.incomeDao().getByClientRequestId(request.clientRequestId)?.let {
            return OperationResult.AlreadyRecorded(it.id)
        }
        val configuration = db.budgetConfigurationDao().getCurrent()
            ?: return rejected(RejectReason.NO_CONFIGURATION, "Aucune répartition active n'est définie.")
        val items = db.budgetConfigurationDao().getItems(configuration.id)
        val categories = db.categoryDao().getAll().associateBy { it.id }
        if (items.sumOf { it.basisPoints } != Percent.FULL || items.any { categories[it.categoryId]?.isActive != true }) {
            return rejected(
                RejectReason.INVALID_CONFIGURATION,
                "La répartition active n'est pas valide (total différent de 100 % ou catégorie désactivée). Corrigez les pourcentages.",
            )
        }

        val parts = AllocationCalculator.allocate(
            request.amount,
            items.map { AllocationCalculator.Share(it.categoryId, it.basisPoints, categories.getValue(it.categoryId).sortOrder) },
        )

        val now = clock.nowMillis()
        val incomeId = db.incomeDao().insert(
            IncomeEntity(
                uuid = UUID.randomUUID().toString(),
                amount = request.amount,
                source = request.source.trim(),
                receivedDate = request.receivedDate,
                description = request.description.cleanOptional(),
                reference = request.reference.cleanOptional(),
                configurationId = configuration.id,
                status = OperationStatus.ACTIVE,
                correctsIncomeId = correctsIncomeId,
                clientRequestId = request.clientRequestId,
                createdAt = now,
            )
        )
        db.allocationDao().insertAll(parts.map { AllocationEntity(incomeId = incomeId, categoryId = it.categoryId, basisPoints = it.basisPoints, amount = it.amount) })

        // Contrôle final : la somme des allocations relue en base doit être égale au montant reçu.
        val stored = db.allocationDao().sumForIncome(incomeId)
        if (stored != request.amount) error("Incohérence de répartition : $stored ≠ ${request.amount}")

        db.audit(
            now, "income", incomeId, if (correctsIncomeId == null) "create" else "correct",
            "${Money.format(request.amount)} — ${request.source.trim()}" +
                parts.joinToString(prefix = " | ", separator = "; ") {
                    "${categories.getValue(it.categoryId).name} ${Percent.format(it.basisPoints)} = ${Money.format(it.amount)}"
                },
        )
        return OperationResult.Recorded(incomeId, "Entrée de ${Money.format(request.amount)} enregistrée et répartie.")
    }

    private suspend fun cancelInTransaction(id: Long, reason: String, force: Boolean): OperationResult {
        val income = db.incomeDao().getById(id) ?: return rejected(RejectReason.NOT_FOUND, "Entrée introuvable.")
        if (income.status != OperationStatus.ACTIVE) {
            return rejected(RejectReason.ALREADY_CANCELLED, "Cette entrée est déjà annulée.")
        }
        val balances = db.balancesById()
        if (!force) {
            val impacts = db.allocationDao().getForIncome(id).mapNotNull { a ->
                val row = balances.getValue(a.categoryId)
                val after = row.balance() - a.amount
                if (after < 0) NegativeImpact(a.categoryId, row.name, after) else null
            }
            if (impacts.isNotEmpty()) return OperationResult.NeedsConfirmation(Confirmation.NegativeBalances(impacts))
        }
        val now = clock.nowMillis()
        if (db.incomeDao().cancel(id, now, reason) != 1) {
            return rejected(RejectReason.ALREADY_CANCELLED, "Cette entrée est déjà annulée.")
        }
        db.audit(now, "income", id, "cancel", "${Money.format(income.amount)} — motif : $reason")
        return OperationResult.Recorded(id, "Entrée annulée. Elle reste visible dans l'historique.")
    }

    private fun validate(r: IncomeRequest): OperationResult.Rejected? = when {
        r.clientRequestId.isBlank() -> rejected(RejectReason.INVALID_INPUT, "Identifiant de saisie manquant.")
        r.amount <= 0 -> rejected(RejectReason.INVALID_INPUT, "Le montant reçu doit être supérieur à 0.")
        r.amount > Money.MAX_AMOUNT -> rejected(RejectReason.INVALID_INPUT, "Montant trop élevé.")
        r.source.isBlank() -> rejected(RejectReason.INVALID_INPUT, "Indiquez la source de l'argent.")
        r.source.trim().length > 60 -> rejected(RejectReason.INVALID_INPUT, "La source ne doit pas dépasser 60 caractères.")
        r.receivedDate > clock.todayEpochDay() -> rejected(RejectReason.INVALID_INPUT, "La date de réception ne peut pas être dans le futur.")
        else -> null
    }
}
