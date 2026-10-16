package cd.smartbudget.fc.core

import java.time.DayOfWeek
import java.time.LocalDate
import java.time.format.DateTimeFormatter
import java.time.format.TextStyle
import java.time.temporal.TemporalAdjusters
import java.util.Locale

object Dates {
    private val SHORT = DateTimeFormatter.ofPattern("dd/MM/yyyy")
    private val LONG = DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.FRENCH)
    private const val MILLIS_PER_DAY = 86_400_000L

    fun format(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(SHORT)

    fun formatLong(epochDay: Long): String = LocalDate.ofEpochDay(epochDay).format(LONG)
        .replaceFirstChar { it.titlecase(Locale.FRENCH) }

    fun formatDateTime(millis: Long): String =
        java.time.Instant.ofEpochMilli(millis).atZone(java.time.ZoneId.systemDefault())
            .format(DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm"))

    fun monthLabel(year: Int, month: Int): String =
        java.time.Month.of(month).getDisplayName(TextStyle.SHORT, Locale.FRENCH).trimEnd('.')
            .replaceFirstChar { it.titlecase(Locale.FRENCH) } + " " + (year % 100).toString().padStart(2, '0')

    /** Le sélecteur de date Material renvoie minuit UTC en millisecondes. */
    fun epochDayFromPickerMillis(millis: Long): Long = Math.floorDiv(millis, MILLIS_PER_DAY)

    fun pickerMillisFromEpochDay(epochDay: Long): Long = epochDay * MILLIS_PER_DAY
}

/** Intervalle de jours inclusif. */
data class DateRange(val fromDay: Long, val toDay: Long) {
    companion object {
        val ALL = DateRange(-1_000_000L, 1_000_000L)
    }
}

/** Filtres de période du tableau de bord et de l'historique. */
enum class PeriodFilter(val label: String) {
    TODAY("Aujourd'hui"),
    WEEK("Cette semaine"),
    MONTH("Ce mois"),
    YEAR("Cette année"),
    ALL("Tout"),
    CUSTOM("Période…");

    /** [custom] est utilisé uniquement pour CUSTOM. */
    fun range(today: Long, custom: DateRange? = null): DateRange {
        val d = LocalDate.ofEpochDay(today)
        return when (this) {
            TODAY -> DateRange(today, today)
            WEEK -> DateRange(d.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY)).toEpochDay(), today)
            MONTH -> DateRange(d.withDayOfMonth(1).toEpochDay(), today)
            YEAR -> DateRange(d.withDayOfYear(1).toEpochDay(), today)
            ALL -> DateRange.ALL
            CUSTOM -> custom ?: DateRange(d.withDayOfMonth(1).toEpochDay(), today)
        }
    }
}
