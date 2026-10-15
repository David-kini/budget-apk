package cd.smartbudget.fc.domain

import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.Percent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MoneyTest {

    @Test
    fun parse_accepts_common_inputs() {
        assertEquals(10_000_000L, Money.parse("100000"))
        assertEquals(10_000_000L, Money.parse("100 000"))
        assertEquals(10_000_000L, Money.parse("100 000 FC"))
        assertEquals(123_450L, Money.parse("1 234,50"))
        assertEquals(123_450L, Money.parse("1234.5"))
        assertEquals(0L, Money.parse("0"))
    }

    @Test
    fun parse_rejects_invalid_inputs() {
        assertNull(Money.parse(""))
        assertNull(Money.parse("-5"))
        assertNull(Money.parse("12,345"))
        assertNull(Money.parse("abc"))
        assertNull(Money.parse("99999999999999999"))
    }

    @Test
    fun format_uses_french_grouping_and_hides_zero_decimals() {
        assertEquals("100 000 FC", Money.format(10_000_000))
        assertEquals("1 234,50 FC", Money.format(123_450))
        assertEquals("-5 000 FC", Money.format(-500_000))
        assertEquals("0 FC", Money.format(0))
    }

    @Test
    fun percent_parse_and_format() {
        assertEquals(2_000, Percent.parse("20"))
        assertEquals(1_250, Percent.parse("12,5"))
        assertEquals(1_225, Percent.parse("12.25 %"))
        assertNull(Percent.parse("101"))
        assertNull(Percent.parse("-1"))
        assertEquals("20 %", Percent.format(2_000))
        assertEquals("12,5 %", Percent.format(1_250))
        assertEquals("12,25 %", Percent.format(1_225))
    }
}
