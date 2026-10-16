package cd.smartbudget.fc.domain

import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.Percent

/**
 * Répartition d'un montant entre catégories, au centime près, sans nombres flottants.
 *
 * Méthode du plus fort reste (Hamilton) :
 *  1. chaque catégorie reçoit la partie entière de montant × pb ÷ 10 000 ;
 *  2. les centimes restants (toujours moins que le nombre de catégories) sont attribués un par un
 *     aux plus grands restes ; à égalité de reste : ordre d'affichage croissant, puis identifiant croissant.
 *
 * Garanties : somme des parts = montant reçu ; chaque part s'écarte de moins d'un centime de la valeur exacte ;
 * le résultat ne dépend pas de l'ordre de la liste fournie.
 */
object AllocationCalculator {

    data class Share(val categoryId: Long, val basisPoints: Int, val sortOrder: Int)

    data class Part(val categoryId: Long, val basisPoints: Int, val amount: Long)

    fun allocate(amount: Long, shares: List<Share>): List<Part> {
        require(amount > 0) { "Le montant doit être strictement positif" }
        require(amount <= Money.MAX_AMOUNT) { "Montant trop élevé" }
        require(shares.isNotEmpty()) { "Aucune catégorie dans la répartition" }
        require(shares.all { it.basisPoints in 0..Percent.FULL }) { "Pourcentage hors limites" }
        require(shares.map { it.categoryId }.toSet().size == shares.size) { "Catégorie en double" }
        require(shares.sumOf { it.basisPoints } == Percent.FULL) { "La somme des pourcentages doit être 100 %" }

        val sorted = shares.sortedWith(compareBy<Share> { it.sortOrder }.thenBy { it.categoryId })
        val base = LongArray(sorted.size)
        val remainder = LongArray(sorted.size)
        sorted.forEachIndexed { i, share ->
            val product = amount * share.basisPoints // ≤ 1e14 × 1e4 : pas de dépassement de Long
            base[i] = product / Percent.FULL
            remainder[i] = product % Percent.FULL
        }

        var leftover = amount - base.sum()
        val byRemainder = sorted.indices.sortedWith(
            compareByDescending<Int> { remainder[it] }
                .thenBy { sorted[it].sortOrder }
                .thenBy { sorted[it].categoryId }
        )
        var k = 0
        while (leftover > 0) {
            base[byRemainder[k]] += 1
            leftover--
            k++
        }

        val parts = sorted.mapIndexed { i, share -> Part(share.categoryId, share.basisPoints, base[i]) }
        check(parts.sumOf { it.amount } == amount) { "Erreur interne de répartition" }
        return parts
    }
}
