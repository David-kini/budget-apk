package cd.smartbudget.fc.domain

import cd.smartbudget.fc.domain.BudgetRules.ShareInput
import cd.smartbudget.fc.domain.BudgetRules.Validation
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class BudgetRulesTest {
    private val active = setOf(1L, 2L, 3L)

    @Test
    fun exactly_100_percent_is_valid() {
        assertEquals(Validation.Valid, BudgetRules.validate(listOf(ShareInput(1, 5_000), ShareInput(2, 2_500), ShareInput(3, 2_500)), active))
    }

    @Test
    fun a_single_category_at_100_percent_is_valid() {
        assertEquals(Validation.Valid, BudgetRules.validate(listOf(ShareInput(1, 10_000), ShareInput(2, 0), ShareInput(3, 0)), active))
    }

    @Test
    fun below_100_percent_gives_explicit_message() {
        val v = BudgetRules.validate(listOf(ShareInput(1, 5_000), ShareInput(2, 2_000), ShareInput(3, 2_500)), active)
        assertTrue(v is Validation.Invalid)
        assertEquals("Total : 95 %. Il manque 5 % pour atteindre 100 %.", (v as Validation.Invalid).message)
    }

    @Test
    fun above_100_percent_gives_explicit_message() {
        val v = BudgetRules.validate(listOf(ShareInput(1, 6_000), ShareInput(2, 2_500), ShareInput(3, 2_500)), active)
        assertTrue(v is Validation.Invalid)
        assertTrue((v as Validation.Invalid).message.startsWith("Total : 110 %. Il y a 10 % de trop"))
    }

    @Test
    fun inactive_or_unknown_category_is_rejected() {
        val v = BudgetRules.validate(listOf(ShareInput(1, 5_000), ShareInput(9, 5_000)), active)
        assertTrue(v is Validation.Invalid)
    }
}
