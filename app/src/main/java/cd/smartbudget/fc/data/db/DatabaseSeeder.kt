package cd.smartbudget.fc.data.db

import androidx.room.withTransaction
import cd.smartbudget.fc.core.OverdraftMode
import cd.smartbudget.fc.core.SettingKeys
import cd.smartbudget.fc.data.db.entity.AppSettingEntity
import cd.smartbudget.fc.data.db.entity.AuditLogEntity
import cd.smartbudget.fc.data.db.entity.BudgetConfigurationEntity
import cd.smartbudget.fc.data.db.entity.BudgetConfigurationItemEntity
import cd.smartbudget.fc.data.db.entity.CategoryEntity
import java.util.UUID

/** Données initiales créées au tout premier démarrage (base vide uniquement). */
object DatabaseSeeder {

    data class DefaultCategory(
        val name: String,
        val basisPoints: Int,
        val colorArgb: Long,
        val isSavings: Boolean = false,
    )

    val DEFAULT_CATEGORIES = listOf(
        DefaultCategory("Créole", 2_000, 0xFF00897BL),
        DefaultCategory("Maison", 2_000, 0xFF1E88E5L),
        DefaultCategory("Dîme", 1_000, 0xFF8E24AAL),
        DefaultCategory("Épargne", 3_000, 0xFF43A047L, isSavings = true),
        DefaultCategory("Petites dépenses", 2_000, 0xFFFB8C00L),
    )

    /** Retourne true si les données initiales ont été créées, false si la base contenait déjà des catégories. */
    suspend fun seedIfEmpty(db: AppDatabase, now: Long = System.currentTimeMillis()): Boolean =
        db.withTransaction {
            if (db.categoryDao().count() > 0) return@withTransaction false

            val configId = db.budgetConfigurationDao().insert(
                BudgetConfigurationEntity(
                    uuid = UUID.randomUUID().toString(),
                    createdAt = now,
                    isCurrent = true,
                    note = "Configuration initiale",
                )
            )
            val items = DEFAULT_CATEGORIES.mapIndexed { index, def ->
                val categoryId = db.categoryDao().insert(
                    CategoryEntity(
                        uuid = UUID.randomUUID().toString(),
                        name = def.name,
                        colorArgb = def.colorArgb,
                        sortOrder = index,
                        isActive = true,
                        isSavings = def.isSavings,
                        createdAt = now,
                        updatedAt = now,
                    )
                )
                BudgetConfigurationItemEntity(configId, categoryId, def.basisPoints)
            }
            db.budgetConfigurationDao().insertItems(items)

            db.settingsDao().put(AppSettingEntity(SettingKeys.OVERDRAFT_MODE, OverdraftMode.REFUSE.name))
            db.settingsDao().put(AppSettingEntity(SettingKeys.CURRENCY, "FC"))
            db.settingsDao().put(AppSettingEntity(SettingKeys.SEEDED_AT, now.toString()))
            db.auditDao().insert(
                AuditLogEntity(at = now, entityType = "system", action = "seed", details = "Catégories par défaut créées")
            )
            true
        }
}
