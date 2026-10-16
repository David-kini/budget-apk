package cd.smartbudget.fc.domain

import cd.smartbudget.fc.core.Percent

/** Règles de validation d'une configuration de pourcentages. */
object BudgetRules {

    data class ShareInput(val categoryId: Long, val basisPoints: Int)

    sealed interface Validation {
        data object Valid : Validation
        data class Invalid(val message: String) : Validation
    }

    /**
     * @param shares pourcentages saisis pour chaque catégorie ACTIVE (0 % autorisé).
     * @param activeCategoryIds catégories actives existantes.
     */
    fun validate(shares: List<ShareInput>, activeCategoryIds: Set<Long>): Validation {
        if (shares.isEmpty()) return Validation.Invalid("Aucune catégorie active.")
        if (shares.map { it.categoryId }.toSet().size != shares.size) {
            return Validation.Invalid("Une catégorie apparaît deux fois.")
        }
        if (shares.any { it.categoryId !in activeCategoryIds }) {
            return Validation.Invalid("Une catégorie inconnue ou désactivée figure dans la répartition.")
        }
        if (shares.any { it.basisPoints !in 0..Percent.FULL }) {
            return Validation.Invalid("Chaque pourcentage doit être compris entre 0 % et 100 %.")
        }
        val total = shares.sumOf { it.basisPoints }
        return when {
            total < Percent.FULL -> Validation.Invalid(
                "Total : ${Percent.format(total)}. Il manque ${Percent.format(Percent.FULL - total)} pour atteindre 100 %."
            )
            total > Percent.FULL -> Validation.Invalid(
                "Total : ${Percent.format(total)}. Il y a ${Percent.format(total - Percent.FULL)} de trop : le total doit être exactement 100 %."
            )
            else -> Validation.Valid
        }
    }
}
