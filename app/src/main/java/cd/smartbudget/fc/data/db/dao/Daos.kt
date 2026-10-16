package cd.smartbudget.fc.data.db.dao

import androidx.room.Dao
import androidx.room.Delete
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Update
import cd.smartbudget.fc.data.db.entity.AllocationEntity
import cd.smartbudget.fc.data.db.entity.AppSettingEntity
import cd.smartbudget.fc.data.db.entity.AuditLogEntity
import cd.smartbudget.fc.data.db.entity.BlacklistEntity
import cd.smartbudget.fc.data.db.entity.BlacklistHistoryEntity
import cd.smartbudget.fc.data.db.entity.BudgetConfigurationEntity
import cd.smartbudget.fc.data.db.entity.BudgetConfigurationItemEntity
import cd.smartbudget.fc.data.db.entity.CategoryBalanceRow
import cd.smartbudget.fc.data.db.entity.CategoryEntity
import cd.smartbudget.fc.data.db.entity.CurrentShareRow
import cd.smartbudget.fc.data.db.entity.ExpenseEntity
import cd.smartbudget.fc.data.db.entity.IncomeEntity
import cd.smartbudget.fc.data.db.entity.OperationRow
import cd.smartbudget.fc.data.db.entity.TransferEntity
import kotlinx.coroutines.flow.Flow

@Dao
interface CategoryDao {
    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    fun observeAll(): Flow<List<CategoryEntity>>

    @Query("SELECT * FROM categories ORDER BY sortOrder, id")
    suspend fun getAll(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE isActive = 1 ORDER BY sortOrder, id")
    suspend fun getActive(): List<CategoryEntity>

    @Query("SELECT * FROM categories WHERE id = :id")
    suspend fun getById(id: Long): CategoryEntity?

    @Query("SELECT * FROM categories WHERE name = :name COLLATE NOCASE LIMIT 1")
    suspend fun findByName(name: String): CategoryEntity?

    @Query("SELECT COUNT(*) FROM categories")
    suspend fun count(): Int

    @Query("SELECT COALESCE(MAX(sortOrder), -1) FROM categories")
    suspend fun maxSortOrder(): Int

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(category: CategoryEntity): Long

    @Update
    suspend fun update(category: CategoryEntity)

    @Delete
    suspend fun delete(category: CategoryEntity)

    @Query("UPDATE categories SET sortOrder = :sortOrder, updatedAt = :at WHERE id = :id")
    suspend fun updateSortOrder(id: Long, sortOrder: Int, at: Long)

    /** Nombre d'enregistrements qui référencent cette catégorie (si > 0, suppression interdite). */
    @Query(
        """
        SELECT (SELECT COUNT(*) FROM allocations WHERE categoryId = :id)
             + (SELECT COUNT(*) FROM expenses WHERE categoryId = :id)
             + (SELECT COUNT(*) FROM transfers WHERE fromCategoryId = :id OR toCategoryId = :id)
             + (SELECT COUNT(*) FROM budget_configuration_items WHERE categoryId = :id)
        """
    )
    suspend fun usageCount(id: Long): Int
}

@Dao
interface BudgetConfigurationDao {
    @Query("SELECT * FROM budget_configurations WHERE isCurrent = 1 LIMIT 1")
    suspend fun getCurrent(): BudgetConfigurationEntity?

    @Query("SELECT COUNT(*) FROM budget_configurations WHERE isCurrent = 1")
    suspend fun countCurrent(): Int

    @Query("SELECT * FROM budget_configurations ORDER BY createdAt DESC, id DESC")
    fun observeAll(): Flow<List<BudgetConfigurationEntity>>

    @Query("SELECT * FROM budget_configurations ORDER BY id")
    suspend fun getAll(): List<BudgetConfigurationEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(configuration: BudgetConfigurationEntity): Long

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertItems(items: List<BudgetConfigurationItemEntity>)

    @Query("UPDATE budget_configurations SET isCurrent = 0 WHERE isCurrent = 1")
    suspend fun clearCurrent()

    @Query("SELECT * FROM budget_configuration_items WHERE configurationId = :configurationId")
    suspend fun getItems(configurationId: Long): List<BudgetConfigurationItemEntity>

    @Query("SELECT * FROM budget_configuration_items ORDER BY configurationId, categoryId")
    suspend fun getAllItems(): List<BudgetConfigurationItemEntity>

    @Query(
        """
        SELECT c.id AS categoryId, c.name AS name, c.colorArgb AS colorArgb, c.sortOrder AS sortOrder,
               c.isActive AS isActive, i.basisPoints AS basisPoints
        FROM budget_configuration_items i
        JOIN budget_configurations b ON b.id = i.configurationId
        JOIN categories c ON c.id = i.categoryId
        WHERE b.isCurrent = 1
        ORDER BY c.sortOrder, c.id
        """
    )
    fun observeCurrentShares(): Flow<List<CurrentShareRow>>

    @Query(
        """
        SELECT c.id AS categoryId, c.name AS name, c.colorArgb AS colorArgb, c.sortOrder AS sortOrder,
               c.isActive AS isActive, i.basisPoints AS basisPoints
        FROM budget_configuration_items i
        JOIN budget_configurations b ON b.id = i.configurationId
        JOIN categories c ON c.id = i.categoryId
        WHERE b.isCurrent = 1
        ORDER BY c.sortOrder, c.id
        """
    )
    suspend fun getCurrentShares(): List<CurrentShareRow>
}

@Dao
interface IncomeDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(income: IncomeEntity): Long

    @Query("SELECT * FROM incomes WHERE id = :id")
    suspend fun getById(id: Long): IncomeEntity?

    @Query("SELECT * FROM incomes WHERE clientRequestId = :requestId LIMIT 1")
    suspend fun getByClientRequestId(requestId: String): IncomeEntity?

    @Query("SELECT * FROM incomes ORDER BY receivedDate DESC, id DESC")
    fun observeAll(): Flow<List<IncomeEntity>>

    @Query("SELECT * FROM incomes ORDER BY id")
    suspend fun getAll(): List<IncomeEntity>

    @Query("SELECT * FROM incomes WHERE receivedDate BETWEEN :fromDay AND :toDay ORDER BY receivedDate, id")
    suspend fun getBetween(fromDay: Long, toDay: Long): List<IncomeEntity>

    @Query(
        """
        SELECT COALESCE(SUM(amount), 0) FROM incomes
        WHERE status = 'ACTIVE' AND receivedDate BETWEEN :fromDay AND :toDay
        """
    )
    suspend fun sumActiveBetween(fromDay: Long, toDay: Long): Long

    @Query("SELECT COALESCE(SUM(amount), 0) FROM incomes WHERE status = 'ACTIVE'")
    suspend fun sumActive(): Long

    /** Annule une entrée active. Retourne 1 si l'annulation a eu lieu, 0 sinon. */
    @Query(
        """
        UPDATE incomes SET status = 'CANCELLED', cancelledAt = :at, cancelReason = :reason
        WHERE id = :id AND status = 'ACTIVE'
        """
    )
    suspend fun cancel(id: Long, at: Long, reason: String): Int
}

@Dao
interface AllocationDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertAll(allocations: List<AllocationEntity>)

    @Query("SELECT * FROM allocations WHERE incomeId = :incomeId ORDER BY id")
    suspend fun getForIncome(incomeId: Long): List<AllocationEntity>

    @Query("SELECT * FROM allocations ORDER BY id")
    suspend fun getAll(): List<AllocationEntity>

    @Query("SELECT COALESCE(SUM(amount), 0) FROM allocations WHERE incomeId = :incomeId")
    suspend fun sumForIncome(incomeId: Long): Long
}

@Dao
interface ExpenseDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(expense: ExpenseEntity): Long

    @Query("SELECT * FROM expenses WHERE id = :id")
    suspend fun getById(id: Long): ExpenseEntity?

    @Query("SELECT * FROM expenses WHERE clientRequestId = :requestId LIMIT 1")
    suspend fun getByClientRequestId(requestId: String): ExpenseEntity?

    @Query("SELECT * FROM expenses ORDER BY expenseDate DESC, id DESC")
    fun observeAll(): Flow<List<ExpenseEntity>>

    @Query("SELECT * FROM expenses ORDER BY id")
    suspend fun getAll(): List<ExpenseEntity>

    @Query("SELECT * FROM expenses WHERE expenseDate BETWEEN :fromDay AND :toDay ORDER BY expenseDate, id")
    suspend fun getBetween(fromDay: Long, toDay: Long): List<ExpenseEntity>

    @Query(
        """
        SELECT COALESCE(SUM(amount), 0) FROM expenses
        WHERE status = 'ACTIVE' AND expenseDate BETWEEN :fromDay AND :toDay
        """
    )
    suspend fun sumActiveBetween(fromDay: Long, toDay: Long): Long

    @Query("SELECT COALESCE(SUM(amount), 0) FROM expenses WHERE status = 'ACTIVE'")
    suspend fun sumActive(): Long

    @Query(
        """
        UPDATE expenses SET status = 'CANCELLED', cancelledAt = :at, cancelReason = :reason
        WHERE id = :id AND status = 'ACTIVE'
        """
    )
    suspend fun cancel(id: Long, at: Long, reason: String): Int
}

@Dao
interface TransferDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(transfer: TransferEntity): Long

    @Query("SELECT * FROM transfers WHERE id = :id")
    suspend fun getById(id: Long): TransferEntity?

    @Query("SELECT * FROM transfers WHERE clientRequestId = :requestId LIMIT 1")
    suspend fun getByClientRequestId(requestId: String): TransferEntity?

    @Query("SELECT * FROM transfers ORDER BY transferDate DESC, id DESC")
    fun observeAll(): Flow<List<TransferEntity>>

    @Query("SELECT * FROM transfers ORDER BY id")
    suspend fun getAll(): List<TransferEntity>

    @Query("SELECT * FROM transfers WHERE linkedExpenseId = :expenseId AND status = 'ACTIVE'")
    suspend fun getActiveLinkedToExpense(expenseId: Long): List<TransferEntity>

    @Query(
        """
        UPDATE transfers SET status = 'CANCELLED', cancelledAt = :at, cancelReason = :reason
        WHERE id = :id AND status = 'ACTIVE'
        """
    )
    suspend fun cancel(id: Long, at: Long, reason: String): Int
}

internal const val BALANCES_SQL = """
            SELECT c.id AS categoryId, c.name AS name, c.colorArgb AS colorArgb, c.sortOrder AS sortOrder,
                   c.isActive AS isActive, c.isSavings AS isSavings,
                   COALESCE((SELECT SUM(a.amount) FROM allocations a
                             JOIN incomes i ON i.id = a.incomeId
                             WHERE a.categoryId = c.id AND i.status = 'ACTIVE'), 0) AS allocated,
                   COALESCE((SELECT SUM(e.amount) FROM expenses e
                             WHERE e.categoryId = c.id AND e.status = 'ACTIVE'), 0) AS spent,
                   COALESCE((SELECT SUM(t.amount) FROM transfers t
                             WHERE t.toCategoryId = c.id AND t.status = 'ACTIVE'), 0) AS transferredIn,
                   COALESCE((SELECT SUM(t.amount) FROM transfers t
                             WHERE t.fromCategoryId = c.id AND t.status = 'ACTIVE'), 0) AS transferredOut
            FROM categories c
            ORDER BY c.sortOrder, c.id
        """

/** Soldes calculés à partir des opérations — aucune valeur de solde n'est stockée. */
@Dao
interface BalanceDao {
    @Query(BALANCES_SQL)
    fun observeBalances(): Flow<List<CategoryBalanceRow>>

    @Query(BALANCES_SQL)
    suspend fun getBalances(): List<CategoryBalanceRow>
}

@Dao
interface BlacklistDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(item: BlacklistEntity): Long

    @Update
    suspend fun update(item: BlacklistEntity)

    @Delete
    suspend fun delete(item: BlacklistEntity)

    @Query("SELECT * FROM blacklist_items WHERE id = :id")
    suspend fun getById(id: Long): BlacklistEntity?

    @Query("SELECT * FROM blacklist_items ORDER BY addedAt DESC, id DESC")
    fun observeAll(): Flow<List<BlacklistEntity>>

    @Query("SELECT * FROM blacklist_items ORDER BY id")
    suspend fun getAll(): List<BlacklistEntity>

    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insertHistory(entry: BlacklistHistoryEntity): Long

    @Query("SELECT * FROM blacklist_history WHERE itemId = :itemId ORDER BY at DESC, id DESC")
    suspend fun getHistory(itemId: Long): List<BlacklistHistoryEntity>

    @Query("SELECT * FROM blacklist_history ORDER BY id")
    suspend fun getAllHistory(): List<BlacklistHistoryEntity>
}

@Dao
interface SettingsDao {
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun put(setting: AppSettingEntity)

    @Query("SELECT value FROM app_settings WHERE `key` = :key")
    suspend fun get(key: String): String?

    @Query("SELECT * FROM app_settings ORDER BY `key`")
    suspend fun getAll(): List<AppSettingEntity>

    @Query("SELECT * FROM app_settings ORDER BY `key`")
    fun observeAll(): Flow<List<AppSettingEntity>>
}

@Dao
interface AuditDao {
    @Insert(onConflict = OnConflictStrategy.ABORT)
    suspend fun insert(entry: AuditLogEntity): Long

    @Query("SELECT * FROM audit_log ORDER BY at DESC, id DESC LIMIT :limit")
    suspend fun recent(limit: Int): List<AuditLogEntity>

    @Query("SELECT * FROM audit_log ORDER BY id")
    suspend fun getAll(): List<AuditLogEntity>
}

internal const val OPERATIONS_SQL = """
    SELECT * FROM (
        SELECT 'INCOME' AS type, i.id AS id, i.receivedDate AS date, i.amount AS amount,
               NULL AS categoryId, NULL AS categoryName, NULL AS targetCategoryName,
               i.source AS label, i.description AS description, i.reference AS reference,
               i.status AS status, i.createdAt AS createdAt
        FROM incomes i
        UNION ALL
        SELECT 'EXPENSE', e.id, e.expenseDate, e.amount,
               e.categoryId, c.name, NULL,
               e.reason, e.description, e.beneficiary,
               e.status, e.createdAt
        FROM expenses e JOIN categories c ON c.id = e.categoryId
        UNION ALL
        SELECT 'TRANSFER', t.id, t.transferDate, t.amount,
               t.fromCategoryId, cf.name, ct.name,
               t.reason, NULL, NULL,
               t.status, t.createdAt
        FROM transfers t
        JOIN categories cf ON cf.id = t.fromCategoryId
        JOIN categories ct ON ct.id = t.toCategoryId
    )
    WHERE date BETWEEN :fromDay AND :toDay
    ORDER BY date DESC, createdAt DESC, id DESC
"""

/** Historique unifié des opérations (entrées, dépenses, transferts), annulées comprises. */
@Dao
interface OperationDao {
    @Query(OPERATIONS_SQL)
    fun observeBetween(fromDay: Long, toDay: Long): Flow<List<OperationRow>>

    @Query(OPERATIONS_SQL)
    suspend fun getBetween(fromDay: Long, toDay: Long): List<OperationRow>
}
