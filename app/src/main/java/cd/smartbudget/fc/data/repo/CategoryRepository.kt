package cd.smartbudget.fc.data.repo

import cd.smartbudget.fc.core.Percent
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.db.entity.BudgetConfigurationEntity
import cd.smartbudget.fc.data.db.entity.BudgetConfigurationItemEntity
import cd.smartbudget.fc.data.db.entity.CategoryEntity
import cd.smartbudget.fc.domain.BudgetRules
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.domain.RejectReason
import cd.smartbudget.fc.domain.SystemClock
import java.util.UUID

/** Gestion des catégories et des configurations de pourcentages. */
class CategoryRepository(private val db: AppDatabase, private val clock: Clock = SystemClock) {

    fun observeCategories() = db.categoryDao().observeAll()
    fun observeCurrentShares() = db.budgetConfigurationDao().observeCurrentShares()
    fun observeBalances() = db.balanceDao().observeBalances()

    suspend fun addCategory(name: String, colorArgb: Long, isSavings: Boolean = false): OperationResult {
        val clean = name.trim()
        validateName(clean)?.let { return it }
        return db.transactional {
            if (db.categoryDao().findByName(clean) != null) {
                return@transactional rejected(RejectReason.DUPLICATE_NAME, "Une catégorie « $clean » existe déjà.")
            }
            val now = clock.nowMillis()
            val id = db.categoryDao().insert(
                CategoryEntity(
                    uuid = UUID.randomUUID().toString(),
                    name = clean,
                    colorArgb = colorArgb,
                    sortOrder = db.categoryDao().maxSortOrder() + 1,
                    isActive = true,
                    isSavings = isSavings,
                    createdAt = now,
                    updatedAt = now,
                )
            )
            db.audit(now, "category", id, "create", clean)
            OperationResult.Recorded(
                id,
                "Catégorie « $clean » créée. Attribuez-lui un pourcentage dans la répartition (elle est à 0 % pour l'instant).",
            )
        }
    }

    /** Renommer, changer la couleur ou le statut « épargne ». L'historique conserve l'identifiant. */
    suspend fun updateCategory(id: Long, name: String, colorArgb: Long, isSavings: Boolean): OperationResult {
        val clean = name.trim()
        validateName(clean)?.let { return it }
        return db.transactional {
            val current = db.categoryDao().getById(id)
                ?: return@transactional rejected(RejectReason.NOT_FOUND, "Catégorie introuvable.")
            val sameName = db.categoryDao().findByName(clean)
            if (sameName != null && sameName.id != id) {
                return@transactional rejected(RejectReason.DUPLICATE_NAME, "Une catégorie « $clean » existe déjà.")
            }
            val now = clock.nowMillis()
            db.categoryDao().update(current.copy(name = clean, colorArgb = colorArgb, isSavings = isSavings, updatedAt = now))
            val details = if (current.name != clean) "renommée « ${current.name} » → « $clean »" else "modifiée"
            db.audit(now, "category", id, "update", details)
            OperationResult.Recorded(id, "Catégorie mise à jour.")
        }
    }

    /**
     * Désactiver une catégorie : elle ne reçoit plus de nouvelles entrées. Son historique et son solde restent visibles
     * (le solde peut être transféré). Refusé tant qu'elle a un pourcentage > 0 dans la répartition active.
     */
    suspend fun setActive(id: Long, active: Boolean): OperationResult = db.transactional {
        val current = db.categoryDao().getById(id)
            ?: return@transactional rejected(RejectReason.NOT_FOUND, "Catégorie introuvable.")
        if (!active) {
            val share = db.budgetConfigurationDao().getCurrentShares().firstOrNull { it.categoryId == id }
            if (share != null && share.basisPoints > 0) {
                return@transactional rejected(
                    RejectReason.STILL_ALLOCATED,
                    "« ${current.name} » reçoit encore ${Percent.format(share.basisPoints)} des entrées. " +
                        "Mettez d'abord son pourcentage à 0 % et répartissez-le sur les autres catégories.",
                )
            }
        }
        val now = clock.nowMillis()
        db.categoryDao().update(current.copy(isActive = active, updatedAt = now))
        db.audit(now, "category", id, if (active) "activate" else "deactivate", current.name)
        OperationResult.Recorded(id, if (active) "Catégorie réactivée." else "Catégorie désactivée.")
    }

    /** Suppression définitive, uniquement si la catégorie n'a aucun historique. Sinon : la désactiver. */
    suspend fun deleteCategory(id: Long): OperationResult = db.transactional {
        val current = db.categoryDao().getById(id)
            ?: return@transactional rejected(RejectReason.NOT_FOUND, "Catégorie introuvable.")
        if (db.categoryDao().usageCount(id) > 0) {
            return@transactional rejected(
                RejectReason.HAS_HISTORY,
                "« ${current.name} » possède un historique (répartitions, dépenses ou transferts). " +
                    "Pour préserver cet historique, elle ne peut pas être supprimée : désactivez-la.",
            )
        }
        db.categoryDao().delete(current)
        db.audit(clock.nowMillis(), "category", id, "delete", current.name)
        OperationResult.Recorded(id, "Catégorie supprimée.")
    }

    /** Réorganise les catégories selon l'ordre donné. */
    suspend fun reorder(orderedIds: List<Long>): OperationResult = db.transactional {
        val all = db.categoryDao().getAll().map { it.id }.toSet()
        if (orderedIds.toSet() != all || orderedIds.size != all.size) {
            return@transactional rejected(RejectReason.INVALID_INPUT, "La liste des catégories à réordonner est incomplète.")
        }
        val now = clock.nowMillis()
        orderedIds.forEachIndexed { index, id -> db.categoryDao().updateSortOrder(id, index, now) }
        db.audit(now, "category", null, "reorder", orderedIds.joinToString(","))
        OperationResult.Recorded(0, "Ordre des catégories enregistré.")
    }

    /**
     * Enregistre une nouvelle répartition pour les FUTURES entrées. Les entrées passées gardent leurs pourcentages.
     * @param shares pourcentage (points de base) de chaque catégorie active ; les catégories absentes valent 0 %.
     */
    suspend fun saveConfiguration(shares: Map<Long, Int>, note: String? = null): OperationResult = db.transactional {
        val active = db.categoryDao().getActive()
        val inputs = active.map { BudgetRules.ShareInput(it.id, shares[it.id] ?: 0) }
        val unknown = shares.keys - active.map { it.id }.toSet()
        if (unknown.any { (shares[it] ?: 0) > 0 }) {
            return@transactional rejected(
                RejectReason.INVALID_CONFIGURATION,
                "Une catégorie désactivée ou inconnue ne peut pas recevoir de pourcentage.",
            )
        }
        when (val v = BudgetRules.validate(inputs, active.map { it.id }.toSet())) {
            is BudgetRules.Validation.Invalid -> return@transactional rejected(RejectReason.INVALID_CONFIGURATION, v.message)
            BudgetRules.Validation.Valid -> Unit
        }
        val newItems = inputs.filter { it.basisPoints > 0 }.associate { it.categoryId to it.basisPoints }

        val current = db.budgetConfigurationDao().getCurrent()
        if (current != null) {
            val currentItems = db.budgetConfigurationDao().getItems(current.id).associate { it.categoryId to it.basisPoints }
            if (currentItems == newItems) {
                return@transactional OperationResult.Recorded(current.id, "Répartition inchangée.")
            }
        }

        val now = clock.nowMillis()
        db.budgetConfigurationDao().clearCurrent()
        val configId = db.budgetConfigurationDao().insert(
            BudgetConfigurationEntity(uuid = UUID.randomUUID().toString(), createdAt = now, isCurrent = true, note = note.cleanOptional())
        )
        db.budgetConfigurationDao().insertItems(newItems.map { (catId, bp) -> BudgetConfigurationItemEntity(configId, catId, bp) })
        val nameById = active.associate { it.id to it.name }
        db.audit(
            now, "configuration", configId, "create",
            newItems.entries.joinToString("; ") { "${nameById[it.key]} ${Percent.format(it.value)}" },
        )
        OperationResult.Recorded(configId, "Nouvelle répartition enregistrée. Elle s'applique aux prochaines entrées uniquement.")
    }

    private fun validateName(name: String): OperationResult.Rejected? = when {
        name.isEmpty() -> rejected(RejectReason.INVALID_INPUT, "Le nom de la catégorie est obligatoire.")
        name.length > 40 -> rejected(RejectReason.INVALID_INPUT, "Le nom ne doit pas dépasser 40 caractères.")
        else -> null
    }
}
