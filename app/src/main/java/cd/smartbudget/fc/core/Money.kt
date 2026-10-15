package cd.smartbudget.fc.core

/**
 * Représentation monétaire de l'application.
 *
 * Tous les montants sont des [Long] exprimés en CENTIMES de franc congolais (1 FC = 100 centimes).
 * Aucun Float/Double n'intervient dans les calculs financiers.
 */
object Money {
    const val CENTIMES_PER_FC = 100L

    /** Plafond d'un montant unique : 1 000 milliards de FC (évite tout dépassement de Long dans les calculs). */
    const val MAX_AMOUNT = 1_000_000_000_000L * CENTIMES_PER_FC

    fun fromFc(fc: Long): Long = Math.multiplyExact(fc, CENTIMES_PER_FC)

    /**
     * Analyse une saisie utilisateur ("100000", "100 000", "1 234,50", "1234.5").
     * Retourne null si la saisie est invalide, négative, ou a plus de 2 décimales.
     */
    fun parse(input: String): Long? {
        val cleaned = input.trim()
            .replace("FC", "", ignoreCase = true)
            .replace(" ", "")
            .replace(" ", "")
            .replace(" ", "")
            .replace(',', '.')
        if (cleaned.isEmpty()) return null
        if (!Regex("^\\d+(\\.\\d{0,2})?$").matches(cleaned)) return null
        val parts = cleaned.split('.')
        val whole = parts[0].trimStart('0').ifEmpty { "0" }
        if (whole.length > 13) return null
        val fraction = parts.getOrNull(1).orEmpty().padEnd(2, '0')
        val value = whole.toLong() * CENTIMES_PER_FC + fraction.toLong()
        return if (value > MAX_AMOUNT) null else value
    }

    /** Formate en "100 000 FC" ou "1 234,50 FC" (décimales affichées seulement si non nulles). */
    fun format(centimes: Long, withCurrency: Boolean = true): String {
        val negative = centimes < 0
        val abs = if (centimes == Long.MIN_VALUE) Long.MAX_VALUE else kotlin.math.abs(centimes)
        val whole = abs / CENTIMES_PER_FC
        val fraction = abs % CENTIMES_PER_FC
        val grouped = groupThousands(whole)
        val body = if (fraction == 0L) grouped else "$grouped,${fraction.toString().padStart(2, '0')}"
        val sign = if (negative) "-" else ""
        return if (withCurrency) "$sign$body FC" else "$sign$body"
    }

    private fun groupThousands(value: Long): String {
        val digits = value.toString()
        val sb = StringBuilder()
        digits.forEachIndexed { index, c ->
            if (index > 0 && (digits.length - index) % 3 == 0) sb.append(' ')
            sb.append(c)
        }
        return sb.toString()
    }
}

/**
 * Pourcentages exprimés en POINTS DE BASE : 10 000 = 100 %, 2 000 = 20 %, 1 250 = 12,5 %.
 */
object Percent {
    const val FULL = 10_000

    fun format(basisPoints: Int): String {
        val whole = basisPoints / 100
        val fraction = basisPoints % 100
        return when {
            fraction == 0 -> "$whole %"
            fraction % 10 == 0 -> "$whole,${fraction / 10} %"
            else -> "$whole,${fraction.toString().padStart(2, '0')} %"
        }
    }

    /** "20", "12,5", "12.25" → points de base ; null si invalide ou hors [0 ; 100]. */
    fun parse(input: String): Int? {
        val cleaned = input.trim().replace("%", "").replace(" ", "").replace(',', '.')
        if (!Regex("^\\d{1,3}(\\.\\d{0,2})?$").matches(cleaned)) return null
        val parts = cleaned.split('.')
        val value = parts[0].toInt() * 100 + parts.getOrNull(1).orEmpty().padEnd(2, '0').toInt()
        return if (value in 0..FULL) value else null
    }
}
