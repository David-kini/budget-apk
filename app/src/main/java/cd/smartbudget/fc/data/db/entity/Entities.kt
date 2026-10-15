package cd.smartbudget.fc.data.db.entity

import androidx.room.ColumnInfo
import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index
import androidx.room.PrimaryKey

/*
 * Schéma SmartBudget FC — version 1.
 *
 * Conventions :
 *  - montants : Long en centimes de FC ;
 *  - pourcentages : Int en points de base (10 000 = 100 %) ;
 *  - dates métier : Long en jours depuis l'époque (LocalDate.toEpochDay) ;
 *  - horodatages techniques : Long en millisecondes (System.currentTimeMillis) ;
 *  - uuid : identifiant stable et portable, utilisé par la sauvegarde/restauration ;
 *  - clientRequestId : clé d'idempotence (protège contre les doubles clics).
 *
 * Les clés étrangères sont en RESTRICT : l'historique ne peut pas être cassé.
 * Les règles d'immuabilité sont complétées par des triggers SQLite (voir DbTriggers).
 */

@Entity(
    tableName = "categories",
    indices = [Index(value = ["uuid"], unique = true), Index(value = ["sortOrder"])],
)
data class CategoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val name: String,
    val colorArgb: Long,
    val sortOrder: Int,
    val isActive: Boolean = true,
    /** Catégorie comptée dans « Épargne » sur le tableau de bord. */
    val isSavings: Boolean = false,
    val createdAt: Long,
    val updatedAt: Long,
)

/** Une configuration de répartition. Elle est immuable : un changement crée une nouvelle configuration. */
@Entity(
    tableName = "budget_configurations",
    indices = [Index(value = ["uuid"], unique = true), Index(value = ["isCurrent"])],
)
data class BudgetConfigurationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val createdAt: Long,
    val isCurrent: Boolean,
    val note: String? = null,
)

@Entity(
    tableName = "budget_configuration_items",
    primaryKeys = ["configurationId", "categoryId"],
    foreignKeys = [
        ForeignKey(
            entity = BudgetConfigurationEntity::class,
            parentColumns = ["id"], childColumns = ["configurationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"], childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [Index(value = ["categoryId"])],
)
data class BudgetConfigurationItemEntity(
    val configurationId: Long,
    val categoryId: Long,
    val basisPoints: Int,
)

@Entity(
    tableName = "incomes",
    foreignKeys = [
        ForeignKey(
            entity = BudgetConfigurationEntity::class,
            parentColumns = ["id"], childColumns = ["configurationId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = IncomeEntity::class,
            parentColumns = ["id"], childColumns = ["correctsIncomeId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["clientRequestId"], unique = true),
        Index(value = ["receivedDate"]),
        Index(value = ["status"]),
        Index(value = ["configurationId"]),
        Index(value = ["correctsIncomeId"]),
    ],
)
data class IncomeEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val amount: Long,
    val source: String,
    val receivedDate: Long,
    val description: String? = null,
    val reference: String? = null,
    /** Configuration de pourcentages appliquée au moment de l'entrée. */
    val configurationId: Long,
    val status: String,
    val cancelledAt: Long? = null,
    val cancelReason: String? = null,
    /** Si cette entrée corrige une entrée annulée. */
    val correctsIncomeId: Long? = null,
    val clientRequestId: String,
    val createdAt: Long,
)

/** Part d'une entrée affectée à une catégorie. Immuable. */
@Entity(
    tableName = "allocations",
    foreignKeys = [
        ForeignKey(
            entity = IncomeEntity::class,
            parentColumns = ["id"], childColumns = ["incomeId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"], childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["incomeId", "categoryId"], unique = true),
        Index(value = ["categoryId"]),
    ],
)
data class AllocationEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val incomeId: Long,
    val categoryId: Long,
    /** Pourcentage réellement appliqué (copié, jamais recalculé). */
    val basisPoints: Int,
    val amount: Long,
)

@Entity(
    tableName = "expenses",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"], childColumns = ["categoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ExpenseEntity::class,
            parentColumns = ["id"], childColumns = ["correctsExpenseId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["clientRequestId"], unique = true),
        Index(value = ["categoryId"]),
        Index(value = ["expenseDate"]),
        Index(value = ["status"]),
        Index(value = ["correctsExpenseId"]),
    ],
)
data class ExpenseEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val amount: Long,
    val categoryId: Long,
    val reason: String,
    val expenseDate: Long,
    val description: String? = null,
    val beneficiary: String? = null,
    /** Chemin relatif d'une pièce justificative copiée dans le stockage privé de l'application. */
    val attachmentPath: String? = null,
    val status: String,
    val cancelledAt: Long? = null,
    val cancelReason: String? = null,
    val correctsExpenseId: Long? = null,
    /** Vrai si la dépense a été acceptée alors qu'elle dépassait le solde (mode WARN). */
    val overdraftAccepted: Boolean = false,
    val clientRequestId: String,
    val createdAt: Long,
)

/** Mouvement interne entre deux catégories. Ce n'est ni un revenu ni une dépense. */
@Entity(
    tableName = "transfers",
    foreignKeys = [
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"], childColumns = ["fromCategoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = CategoryEntity::class,
            parentColumns = ["id"], childColumns = ["toCategoryId"],
            onDelete = ForeignKey.RESTRICT,
        ),
        ForeignKey(
            entity = ExpenseEntity::class,
            parentColumns = ["id"], childColumns = ["linkedExpenseId"],
            onDelete = ForeignKey.RESTRICT,
        ),
    ],
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["clientRequestId"], unique = true),
        Index(value = ["fromCategoryId"]),
        Index(value = ["toCategoryId"]),
        Index(value = ["transferDate"]),
        Index(value = ["status"]),
        Index(value = ["linkedExpenseId"]),
    ],
)
data class TransferEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val fromCategoryId: Long,
    val toCategoryId: Long,
    val amount: Long,
    val transferDate: Long,
    val reason: String,
    /** Dépense qui a motivé ce transfert (mode TRANSFER), si applicable. */
    val linkedExpenseId: Long? = null,
    val status: String,
    val cancelledAt: Long? = null,
    val cancelReason: String? = null,
    val clientRequestId: String,
    val createdAt: Long,
)

@Entity(
    tableName = "blacklist_items",
    indices = [
        Index(value = ["uuid"], unique = true),
        Index(value = ["label"]),
        Index(value = ["itemType"]),
        Index(value = ["isActive"]),
    ],
)
data class BlacklistEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val uuid: String,
    val label: String,
    val itemType: String,
    val reason: String,
    val addedAt: Long,
    val isActive: Boolean = true,
    val notes: String? = null,
    val updatedAt: Long,
)

@Entity(
    tableName = "blacklist_history",
    foreignKeys = [
        ForeignKey(
            entity = BlacklistEntity::class,
            parentColumns = ["id"], childColumns = ["itemId"],
            onDelete = ForeignKey.CASCADE,
        ),
    ],
    indices = [Index(value = ["itemId"])],
)
data class BlacklistHistoryEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val itemId: Long,
    val action: String,
    val details: String? = null,
    val at: Long,
)

@Entity(tableName = "app_settings")
data class AppSettingEntity(
    @PrimaryKey val key: String,
    val value: String,
)

/** Journal d'audit applicatif (stocké dans la base chiffrée, jamais dans Logcat). */
@Entity(
    tableName = "audit_log",
    indices = [Index(value = ["at"]), Index(value = ["entityType", "entityId"])],
)
data class AuditLogEntity(
    @PrimaryKey(autoGenerate = true) val id: Long = 0,
    val at: Long,
    val entityType: String,
    val entityId: Long? = null,
    val action: String,
    val details: String? = null,
)

/** Ligne de solde calculée par catégorie (jamais stockée). */
data class CategoryBalanceRow(
    val categoryId: Long,
    val name: String,
    val colorArgb: Long,
    val sortOrder: Int,
    val isActive: Boolean,
    val isSavings: Boolean,
    val allocated: Long,
    val spent: Long,
    @ColumnInfo(name = "transferredIn") val transferredIn: Long,
    @ColumnInfo(name = "transferredOut") val transferredOut: Long,
) {
    /** Règle comptable unique : allocations − dépenses + transferts reçus − transferts envoyés. */
    fun balance(): Long = allocated - spent + transferredIn - transferredOut
}

/** Pourcentage courant d'une catégorie (jointure configuration courante ↔ catégories). */
data class CurrentShareRow(
    val categoryId: Long,
    val name: String,
    val colorArgb: Long,
    val sortOrder: Int,
    val isActive: Boolean,
    val basisPoints: Int,
)
