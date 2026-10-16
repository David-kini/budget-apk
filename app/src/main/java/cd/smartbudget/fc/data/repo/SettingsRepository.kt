package cd.smartbudget.fc.data.repo

import cd.smartbudget.fc.core.OverdraftMode
import cd.smartbudget.fc.core.SettingKeys
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.db.entity.AppSettingEntity
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.SystemClock
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map

class SettingsRepository(private val db: AppDatabase, private val clock: Clock = SystemClock) {

    suspend fun overdraftMode(): OverdraftMode =
        db.settingsDao().get(SettingKeys.OVERDRAFT_MODE)
            ?.let { value -> OverdraftMode.entries.firstOrNull { it.name == value } }
            ?: OverdraftMode.REFUSE

    fun observeOverdraftMode(): Flow<OverdraftMode> =
        db.settingsDao().observeAll().map { all ->
            all.firstOrNull { it.key == SettingKeys.OVERDRAFT_MODE }
                ?.let { s -> OverdraftMode.entries.firstOrNull { it.name == s.value } }
                ?: OverdraftMode.REFUSE
        }

    suspend fun setOverdraftMode(mode: OverdraftMode) {
        db.settingsDao().put(AppSettingEntity(SettingKeys.OVERDRAFT_MODE, mode.name))
        db.audit(clock.nowMillis(), "settings", null, "overdraft_mode", mode.name)
    }
}
