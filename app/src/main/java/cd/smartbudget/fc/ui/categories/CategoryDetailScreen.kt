@file:OptIn(ExperimentalLayoutApi::class)

package cd.smartbudget.fc.ui.categories

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cd.smartbudget.fc.core.DateRange
import cd.smartbudget.fc.core.Dates
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.OperationStatus
import cd.smartbudget.fc.core.Percent
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.data.db.entity.CategoryAllocationRow
import cd.smartbudget.fc.data.db.entity.CategoryBalanceRow
import cd.smartbudget.fc.data.db.entity.CategoryEntity
import cd.smartbudget.fc.data.db.entity.OperationRow
import cd.smartbudget.fc.data.repo.CategoryRepository
import cd.smartbudget.fc.data.repo.HistoryRepository
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.ui.appViewModel
import cd.smartbudget.fc.ui.components.BackTopBar
import cd.smartbudget.fc.ui.components.CategoryDot
import cd.smartbudget.fc.ui.components.ConfirmDialog
import cd.smartbudget.fc.ui.components.EmptyState
import cd.smartbudget.fc.ui.components.LabeledValue
import cd.smartbudget.fc.ui.components.MoneyText
import cd.smartbudget.fc.ui.components.SectionHeader
import cd.smartbudget.fc.ui.components.userMessage
import cd.smartbudget.fc.ui.history.OperationActions
import cd.smartbudget.fc.ui.history.OperationListItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

data class CategoryDetail(
    val category: CategoryEntity?,
    val balance: CategoryBalanceRow?,
    val basisPoints: Int,
    val allocations: List<CategoryAllocationRow>,
    val operations: List<OperationRow>,
)

class CategoryDetailViewModel(
    private val categoryId: Long,
    private val repo: CategoryRepository,
    db: AppDatabase,
    history: HistoryRepository,
) : ViewModel() {
    val detail: StateFlow<CategoryDetail?> = combine(
        repo.observeCategories().map { list -> list.firstOrNull { it.id == categoryId } },
        repo.observeBalances().map { list -> list.firstOrNull { it.categoryId == categoryId } },
        repo.observeCurrentShares().map { list -> list.firstOrNull { it.categoryId == categoryId }?.basisPoints ?: 0 },
        db.allocationDao().observeForCategory(categoryId),
        history.observe(DateRange.ALL.fromDay, DateRange.ALL.toDay)
            .map { rows -> rows.filter { it.categoryId == categoryId || it.targetCategoryId == categoryId } },
    ) { category, balance, bp, allocations, operations ->
        CategoryDetail(category, balance, bp, allocations, operations)
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    suspend fun update(name: String, color: Long, savings: Boolean) = repo.updateCategory(categoryId, name, color, savings)
    suspend fun setActive(active: Boolean) = repo.setActive(categoryId, active)
    suspend fun delete() = repo.deleteCategory(categoryId)
}

@Composable
fun CategoryDetailScreen(
    categoryId: Long,
    onBack: () -> Unit,
    onNewExpense: (Long) -> Unit,
    onTransferFrom: (Long) -> Unit,
    onEditPercentages: () -> Unit,
) {
    val vm = appViewModel(key = "category-$categoryId") {
        CategoryDetailViewModel(categoryId, it.categoryRepository, it.database, it.historyRepository)
    }
    val detail by vm.detail.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var editing by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    fun show(r: OperationResult) = Toast.makeText(context, r.userMessage(), Toast.LENGTH_LONG).show()

    val d = detail
    val category = d?.category
    Scaffold(topBar = { BackTopBar(category?.name ?: "Catégorie", onBack) }) { padding ->
        if (d == null || category == null) {
            EmptyState(if (d == null) "Chargement…" else "Cette catégorie n'existe plus.", Modifier.padding(padding))
            return@Scaffold
        }
        val b = d.balance
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 32.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryDot(category.colorArgb, 16)
                            Spacer(Modifier.width(10.dp))
                            Text(
                                (if (category.isActive) "Pourcentage actuel : ${Percent.format(d.basisPoints)}" else "Catégorie désactivée") +
                                    if (category.isSavings) " · épargne" else "",
                                style = MaterialTheme.typography.labelLarge,
                            )
                        }
                        Spacer(Modifier.height(8.dp))
                        Text("Solde disponible", style = MaterialTheme.typography.labelMedium)
                        MoneyText(b?.balance() ?: 0L, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        LabeledValue("Montant affecté (entrées)") { MoneyText(b?.allocated ?: 0L) }
                        LabeledValue("Dépensé") { MoneyText(b?.spent ?: 0L) }
                        LabeledValue("Transferts reçus") { MoneyText(b?.transferredIn ?: 0L) }
                        LabeledValue("Transferts envoyés") { MoneyText(b?.transferredOut ?: 0L) }
                    }
                }
                Spacer(Modifier.height(12.dp))
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (category.isActive) Button(onClick = { onNewExpense(category.id) }) { Text("Dépense") }
                    if ((b?.balance() ?: 0L) > 0) OutlinedButton(onClick = { onTransferFrom(category.id) }) { Text("Transférer") }
                    SmallEditButton { editing = true }
                    OutlinedButton(onClick = onEditPercentages) { Text("Pourcentages") }
                    OutlinedButton(onClick = { scope.launch { show(vm.setActive(!category.isActive)) } }) {
                        Text(if (category.isActive) "Désactiver" else "Réactiver")
                    }
                    TextButton(onClick = { confirmDelete = true }) { Text("Supprimer", color = MaterialTheme.colorScheme.error) }
                }
            }
            item { SectionHeader("Montants affectés (${d.allocations.size})") }
            if (d.allocations.isEmpty()) item { EmptyState("Aucune entrée n'a encore été affectée à cette catégorie.") }
            items(d.allocations.take(50), key = { "a-${it.incomeId}" }) { a ->
                val cancelled = a.status == OperationStatus.CANCELLED
                ListItem(
                    headlineContent = {
                        Text("${a.source} · ${Percent.format(a.basisPoints)}", textDecoration = if (cancelled) TextDecoration.LineThrough else null)
                    },
                    supportingContent = { Text(Dates.format(a.receivedDate) + if (cancelled) " · entrée annulée" else "") },
                    trailingContent = { Text(Money.format(a.amount), fontWeight = FontWeight.SemiBold) },
                )
            }
            item { SectionHeader("Dépenses et transferts (${d.operations.size})") }
            if (d.operations.isEmpty()) item { EmptyState("Aucune dépense ni transfert.") }
            items(d.operations, key = { "o-${it.type}-${it.id}" }) { row ->
                OperationActions(row) { open -> OperationListItem(row, onClick = open) }
            }
        }
    }

    if (editing && category != null) {
        CategoryEditDialog(
            title = "Modifier la catégorie",
            initialName = category.name, initialColor = category.colorArgb, initialSavings = category.isSavings,
            onDismiss = { editing = false },
            onSave = { name, color, savings ->
                scope.launch {
                    val r = vm.update(name, color, savings)
                    show(r)
                    if (r is OperationResult.Recorded) editing = false
                }
            },
        )
    }
    if (confirmDelete && category != null) {
        ConfirmDialog(
            title = "Supprimer « ${category.name} » ?",
            text = "La suppression n'est possible que si la catégorie n'a aucun historique. Sinon, désactivez-la : son historique sera conservé.",
            confirmLabel = "Supprimer",
            onConfirm = {
                confirmDelete = false
                scope.launch {
                    val r = vm.delete()
                    show(r)
                    if (r is OperationResult.Recorded) onBack()
                }
            },
            onDismiss = { confirmDelete = false },
        )
    }
}
