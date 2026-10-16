@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class, ExperimentalCoroutinesApi::class)

package cd.smartbudget.fc.ui.history

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cd.smartbudget.fc.core.DateRange
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.OperationStatus
import cd.smartbudget.fc.core.PeriodFilter
import cd.smartbudget.fc.data.db.entity.OperationRow
import cd.smartbudget.fc.data.repo.HistoryRepository
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.SystemClock
import cd.smartbudget.fc.ui.appViewModel
import cd.smartbudget.fc.ui.components.EmptyState
import cd.smartbudget.fc.ui.components.PeriodSelector
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.stateIn
import java.text.Normalizer

enum class TypeFilter(val label: String, val type: String?) {
    ALL("Tout", null), INCOME("Entrées", "INCOME"), EXPENSE("Dépenses", "EXPENSE"), TRANSFER("Transferts", "TRANSFER")
}

data class HistoryFilters(
    val period: PeriodFilter = PeriodFilter.ALL,
    val custom: DateRange? = null,
    val type: TypeFilter = TypeFilter.ALL,
    val showCancelled: Boolean = true,
    val query: String = "",
)

class HistoryViewModel(private val history: HistoryRepository, private val clock: Clock = SystemClock) : ViewModel() {
    val today get() = clock.todayEpochDay()
    val filters = MutableStateFlow(HistoryFilters())

    val rows: StateFlow<List<OperationRow>> = filters
        .flatMapLatest { f ->
            val range = f.period.range(clock.todayEpochDay(), f.custom)
            history.observe(range.fromDay, range.toDay)
        }
        .let { source -> combine(source, filters) { list, f -> list.filter { matches(it, f) } } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun update(transform: (HistoryFilters) -> HistoryFilters) {
        filters.value = transform(filters.value)
    }

    private fun matches(row: OperationRow, f: HistoryFilters): Boolean {
        if (f.type.type != null && row.type != f.type.type) return false
        if (!f.showCancelled && row.status == OperationStatus.CANCELLED) return false
        val q = normalize(f.query.trim())
        if (q.isEmpty()) return true
        val haystack = listOfNotNull(row.label, row.description, row.reference, row.categoryName, row.targetCategoryName,
            Money.format(row.amount, withCurrency = false).replace(" ", ""))
            .joinToString(" ") { normalize(it) }
        return q.split(' ').all { haystack.contains(it) }
    }

    private fun normalize(s: String) = Normalizer.normalize(s.lowercase(), Normalizer.Form.NFD).replace(Regex("\\p{M}+"), "")
}

@Composable
fun HistoryScreen() {
    val vm = appViewModel { HistoryViewModel(it.historyRepository) }
    val rows by vm.rows.collectAsStateWithLifecycle()
    val f by vm.filters.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Historique des opérations") }) },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(bottom = 24.dp)) {
            item {
                OutlinedTextField(
                    value = f.query,
                    onValueChange = { q -> vm.update { it.copy(query = q) } },
                    leadingIcon = { Icon(Icons.Filled.Search, contentDescription = null) },
                    label = { Text("Rechercher (motif, catégorie, montant…)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp),
                )
                PeriodSelector(f.period, f.custom, vm.today, onSelect = { p, c -> vm.update { it.copy(period = p, custom = c) } },
                    modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
                FlowRow(Modifier.padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    TypeFilter.entries.forEach { t ->
                        FilterChip(selected = f.type == t, onClick = { vm.update { it.copy(type = t) } }, label = { Text(t.label) })
                    }
                }
                Row(Modifier.fillMaxWidth().padding(horizontal = 16.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("Afficher les opérations annulées", modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
                    Switch(checked = f.showCancelled, onCheckedChange = { v -> vm.update { it.copy(showCancelled = v) } })
                }
                val active = rows.filter { it.status == OperationStatus.ACTIVE }
                Text(
                    "${rows.size} opération(s) · entrées ${Money.format(active.filter { it.type == "INCOME" }.sumOf { it.amount })} · " +
                        "dépenses ${Money.format(active.filter { it.type == "EXPENSE" }.sumOf { it.amount })}",
                    style = MaterialTheme.typography.labelMedium,
                    modifier = Modifier.padding(16.dp, 8.dp),
                )
                HorizontalDivider()
            }
            if (rows.isEmpty()) {
                item { EmptyState("Aucune opération ne correspond à ces critères.") }
            }
            items(rows, key = { "${it.type}-${it.id}" }) { row ->
                OperationActions(row) { open -> OperationListItem(row, onClick = open) }
            }
        }
    }
}
