@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalCoroutinesApi::class)

package cd.smartbudget.fc.ui.dashboard

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cd.smartbudget.fc.core.DateRange
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.PeriodFilter
import cd.smartbudget.fc.data.repo.CategoryAmount
import cd.smartbudget.fc.data.repo.DashboardRepository
import cd.smartbudget.fc.data.repo.DashboardSummary
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.SystemClock
import cd.smartbudget.fc.ui.appViewModel
import cd.smartbudget.fc.ui.components.CategoryDot
import cd.smartbudget.fc.ui.components.DonutChart
import cd.smartbudget.fc.ui.components.EmptyState
import cd.smartbudget.fc.ui.components.LabeledValue
import cd.smartbudget.fc.ui.components.MoneyText
import cd.smartbudget.fc.ui.components.MonthlyBars
import cd.smartbudget.fc.ui.components.PeriodSelector
import cd.smartbudget.fc.ui.components.SectionHeader
import cd.smartbudget.fc.ui.history.OperationListItem
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn

data class PeriodSelection(val filter: PeriodFilter, val custom: DateRange?)

class DashboardViewModel(private val repo: DashboardRepository, private val clock: Clock = SystemClock) : ViewModel() {
    val today: Long get() = clock.todayEpochDay()
    val period = MutableStateFlow(PeriodSelection(PeriodFilter.MONTH, null))

    val summary: StateFlow<DashboardSummary?> =
        combine(repo.changes(), period) { _, p -> p }
            .mapLatest { p -> repo.summary(p.filter.range(clock.todayEpochDay(), p.custom)) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    fun select(filter: PeriodFilter, custom: DateRange?) {
        period.value = PeriodSelection(filter, custom)
    }
}

@Composable
fun DashboardScreen(
    onNewIncome: () -> Unit,
    onNewExpense: () -> Unit,
    onOpenCategory: (Long) -> Unit,
    onOpenHistory: () -> Unit,
) {
    val vm = appViewModel { DashboardViewModel(it.dashboardRepository) }
    val summary by vm.summary.collectAsStateWithLifecycle()
    val period by vm.period.collectAsStateWithLifecycle()

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("SmartBudget FC") }) },
        floatingActionButton = {
            Column(horizontalAlignment = Alignment.End, verticalArrangement = Arrangement.spacedBy(12.dp)) {
                ExtendedFloatingActionButton(
                    onClick = onNewExpense,
                    icon = { Icon(Icons.Filled.ShoppingCart, contentDescription = null) },
                    text = { Text("Dépense") },
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.onErrorContainer,
                )
                ExtendedFloatingActionButton(
                    onClick = onNewIncome,
                    icon = { Icon(Icons.Filled.Add, contentDescription = null) },
                    text = { Text("Entrée") },
                )
            }
        },
    ) { padding ->
        val s = summary
        if (s == null) {
            Column(Modifier.fillMaxSize().padding(padding), verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally) { CircularProgressIndicator() }
            return@Scaffold
        }
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding),
            contentPadding = PaddingValues(start = 16.dp, end = 16.dp, bottom = 160.dp),
        ) {
            item {
                PeriodSelector(period.filter, period.custom, vm.today, onSelect = vm::select)
                Spacer(Modifier.height(8.dp))
                TotalsCard(s)
            }
            if (s.alerts.isNotEmpty()) {
                item { AlertsCard(s.alerts) }
            }
            item {
                SectionHeader("Soldes par catégorie")
            }
            items(s.balances.filter { it.isActive || it.balance() != 0L }, key = { it.categoryId }) { row ->
                Card(
                    Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenCategory(row.categoryId) },
                ) {
                    Column(Modifier.padding(14.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            CategoryDot(row.colorArgb)
                            Spacer(Modifier.width(10.dp))
                            Text(row.name + if (!row.isActive) " (désactivée)" else "", modifier = Modifier.weight(1f),
                                fontWeight = FontWeight.Medium)
                            MoneyText(row.balance())
                        }
                        val received = row.allocated + row.transferredIn
                        if (received > 0) {
                            Spacer(Modifier.height(8.dp))
                            val used = (received - row.balance()).coerceAtLeast(0)
                            LinearProgressIndicator(
                                progress = { (used.toDouble() / received.toDouble()).toFloat().coerceIn(0f, 1f) },
                                modifier = Modifier.fillMaxWidth(),
                                color = Color(row.colorArgb.toInt()),
                            )
                            Text(
                                "Utilisé ${Money.format(used)} sur ${Money.format(received)}",
                                style = MaterialTheme.typography.labelSmall,
                                modifier = Modifier.padding(top = 4.dp),
                            )
                        }
                    }
                }
            }
            item {
                SectionHeader("Répartition des budgets (soldes actuels)")
                val budget = s.balances.filter { it.balance() > 0 }
                    .map { CategoryAmount(it.categoryId, it.name, it.colorArgb, it.balance()) }
                if (budget.isEmpty()) EmptyState("Aucun solde positif pour l'instant. Enregistrez une entrée d'argent.")
                else DonutChart(budget, "Disponible", s.totalBalance)
            }
            item {
                SectionHeader("Dépenses par catégorie (période)")
                if (s.expensesByCategory.isEmpty()) EmptyState("Aucune dépense sur cette période.")
                else DonutChart(s.expensesByCategory, "Dépensé", s.periodExpense)
            }
            item {
                SectionHeader("Évolution sur 6 mois")
                MonthlyBars(s.monthly)
            }
            item {
                SectionHeader("Résumé du mois")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        LabeledValue("Entrées du mois") { MoneyText(s.monthIncome) }
                        LabeledValue("Dépenses du mois") { MoneyText(s.monthExpense) }
                        LabeledValue("Différence") { MoneyText(s.monthIncome - s.monthExpense) }
                    }
                }
            }
            item {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    SectionHeader("Dernières opérations", Modifier.weight(1f))
                    TextButton(onClick = onOpenHistory) { Text("Tout voir") }
                }
            }
            if (s.recent.isEmpty()) {
                item { EmptyState("Aucune opération enregistrée.") }
            } else {
                items(s.recent, key = { "${it.type}-${it.id}" }) { OperationListItem(it) }
            }
        }
    }
}

@Composable
private fun TotalsCard(s: DashboardSummary) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text("Solde disponible total", style = MaterialTheme.typography.labelLarge)
            MoneyText(s.totalBalance, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(12.dp))
            LabeledValue("Entrées (période)") { MoneyText(s.periodIncome) }
            LabeledValue("Dépenses (période)") { MoneyText(s.periodExpense) }
            LabeledValue("Épargne disponible") { MoneyText(s.savingsBalance) }
            LabeledValue("Total affecté à l'épargne") { MoneyText(s.savingsAllocated) }
        }
    }
}

@Composable
private fun AlertsCard(alerts: List<String>) {
    Card(
        Modifier.fillMaxWidth().padding(top = 12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
    ) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Filled.Warning, contentDescription = null, tint = MaterialTheme.colorScheme.onErrorContainer)
                Spacer(Modifier.width(8.dp))
                Text("Alertes", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.onErrorContainer)
            }
            alerts.forEach { Text("• $it", color = MaterialTheme.colorScheme.onErrorContainer, style = MaterialTheme.typography.bodySmall) }
        }
    }
}
