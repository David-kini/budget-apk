package cd.smartbudget.fc.domain

import java.time.LocalDate
import java.time.ZoneId

/** Source de temps (remplaçable dans les tests). */
interface Clock {
    fun nowMillis(): Long
    fun todayEpochDay(): Long
}

object SystemClock : Clock {
    override fun nowMillis(): Long = System.currentTimeMillis()
    override fun todayEpochDay(): Long = LocalDate.now(ZoneId.systemDefault()).toEpochDay()
}
