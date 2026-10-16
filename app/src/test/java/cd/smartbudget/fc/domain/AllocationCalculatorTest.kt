package cd.smartbudget.fc.domain

import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.domain.AllocationCalculator.Share
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.random.Random

class AllocationCalculatorTest {

    /** Répartition par défaut : Créole 20, Maison 20, Dîme 10, Épargne 30, Petites dépenses 20. */
    private val defaults = listOf(
        Share(1, 2_000, 0), Share(2, 2_000, 1), Share(3, 1_000, 2), Share(4, 3_000, 3), Share(5, 2_000, 4),
    )

    private fun fcByCategory(amountFc: Long): Map<Long, Long> =
        AllocationCalculator.allocate(Money.fromFc(amountFc), defaults).associate { it.categoryId to it.amount / 100 }

    @Test
    fun entree_de_100_000_FC() {
        assertEquals(mapOf(1L to 20_000L, 2L to 20_000L, 3L to 10_000L, 4L to 30_000L, 5L to 20_000L), fcByCategory(100_000))
    }

    @Test
    fun entree_de_250_000_FC() {
        assertEquals(mapOf(1L to 50_000L, 2L to 50_000L, 3L to 25_000L, 4L to 75_000L, 5L to 50_000L), fcByCategory(250_000))
    }

    @Test
    fun entree_de_1_000_000_FC() {
        assertEquals(mapOf(1L to 200_000L, 2L to 200_000L, 3L to 100_000L, 4L to 300_000L, 5L to 200_000L), fcByCategory(1_000_000))
    }

    @Test
    fun applied_percentages_are_copied_into_each_part() {
        val parts = AllocationCalculator.allocate(Money.fromFc(100_000), defaults)
        assertEquals(listOf(2_000, 2_000, 1_000, 3_000, 2_000), parts.map { it.basisPoints })
    }

    @Test
    fun one_centime_goes_to_the_largest_remainder() {
        // 0,01 FC : seule Épargne (30 %, plus grand reste) reçoit le centime.
        val parts = AllocationCalculator.allocate(1, defaults).associate { it.categoryId to it.amount }
        assertEquals(mapOf(1L to 0L, 2L to 0L, 3L to 0L, 4L to 1L, 5L to 0L), parts)
    }

    @Test
    fun thirds_rounding_is_deterministic_and_exact() {
        val thirds = listOf(Share(10, 3_333, 0), Share(11, 3_333, 1), Share(12, 3_334, 2))
        val parts = AllocationCalculator.allocate(100, thirds).map { it.amount } // 1 FC = 100 centimes
        assertEquals(listOf(33L, 33L, 34L), parts)

        // Égalité de reste : l'ordre d'affichage départage (la 1re catégorie reçoit le centime).
        val equal = listOf(Share(20, 5_000, 0), Share(21, 5_000, 1))
        assertEquals(listOf(1L, 0L), AllocationCalculator.allocate(1, equal).map { it.amount })
    }

    @Test
    fun odd_amount_with_default_shares_sums_exactly() {
        val amount = Money.parse("12 345,67")!!
        val parts = AllocationCalculator.allocate(amount, defaults)
        assertEquals(amount, parts.sumOf { it.amount })
    }

    @Test
    fun result_does_not_depend_on_input_order() {
        val amount = 987_654_321L
        val reference = AllocationCalculator.allocate(amount, defaults)
        repeat(20) { seed ->
            assertEquals(reference, AllocationCalculator.allocate(amount, defaults.shuffled(Random(seed))))
        }
    }

    @Test
    fun random_amounts_and_shares_never_create_or_lose_money() {
        val random = Random(2026)
        repeat(2_000) {
            val n = random.nextInt(1, 9)
            val cuts = (1 until n).map { random.nextInt(0, 10_001) }.sorted()
            val bounds = listOf(0) + cuts + listOf(10_000)
            val shares = (0 until n).map { i -> Share(i.toLong() + 1, bounds[i + 1] - bounds[i], i) }
            val amount = random.nextLong(1, Money.MAX_AMOUNT + 1)
            val parts = AllocationCalculator.allocate(amount, shares)
            assertEquals(amount, parts.sumOf { it.amount })
            parts.zip(shares).forEach { (part, share) ->
                val exactTimes10000 = amount * share.basisPoints
                val diff = part.amount * 10_000 - exactTimes10000
                assertTrue("écart d'au plus 1 centime", diff > -10_000 && diff < 10_000)
            }
        }
    }

    @Test
    fun maximum_amount_does_not_overflow() {
        val parts = AllocationCalculator.allocate(Money.MAX_AMOUNT, defaults)
        assertEquals(Money.MAX_AMOUNT, parts.sumOf { it.amount })
    }

    @Test
    fun single_category_takes_everything() {
        val parts = AllocationCalculator.allocate(Money.fromFc(1_000_000), listOf(Share(4, 10_000, 3)))
        assertEquals(listOf(AllocationCalculator.Part(4, 10_000, Money.fromFc(1_000_000))), parts)
    }

    @Test(expected = IllegalArgumentException::class)
    fun shares_below_100_percent_are_rejected() {
        AllocationCalculator.allocate(10_000, listOf(Share(1, 5_000, 0), Share(2, 4_000, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun shares_above_100_percent_are_rejected() {
        AllocationCalculator.allocate(10_000, listOf(Share(1, 6_000, 0), Share(2, 5_000, 1)))
    }

    @Test(expected = IllegalArgumentException::class)
    fun zero_amount_is_rejected() {
        AllocationCalculator.allocate(0, defaults)
    }
}
