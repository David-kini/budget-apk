@file:OptIn(ExperimentalMaterial3Api::class)

package cd.smartbudget.fc.ui.more

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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Star
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cd.smartbudget.fc.BuildConfig
import cd.smartbudget.fc.core.OverdraftMode
import cd.smartbudget.fc.data.db.entity.CategoryBalanceRow
import cd.smartbudget.fc.data.repo.CategoryRepository
import cd.smartbudget.fc.data.repo.SettingsRepository
import cd.smartbudget.fc.domain.ConsistencyChecker
import cd.smartbudget.fc.ui.appViewModel
import cd.smartbudget.fc.ui.components.BackTopBar
import cd.smartbudget.fc.ui.components.CategoryDot
import cd.smartbudget.fc.ui.components.EmptyState
import cd.smartbudget.fc.ui.components.LabeledValue
import cd.smartbudget.fc.ui.components.MoneyText
import cd.smartbudget.fc.ui.components.SectionHeader
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

// ------------------------------------------------------------------ Menu « Plus »

@Composable
fun MoreScreen(
    onTransfers: () -> Unit,
    onSavings: () -> Unit,
    onPercentages: () -> Unit,
    onSettings: () -> Unit,
    onAbout: () -> Unit,
) {
    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = { TopAppBar(title = { Text("Plus") }) },
    ) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState())) {
            MenuItem(Icons.Filled.Refresh, "Transferts entre catégories", "Déplacer de l'argent d'une catégorie à une autre", onTransfers)
            MenuItem(Icons.Filled.Star, "Gestion de l'épargne", "Solde et historique des catégories d'épargne", onSavings)
            MenuItem(Icons.Filled.CheckCircle, "Personnaliser les pourcentages", "Répartition automatique des entrées", onPercentages)
            MenuItem(Icons.Filled.Settings, "Paramètres", "Dépassement de solde, vérification des données", onSettings)
            MenuItem(Icons.Filled.Info, "À propos", "Version, confidentialité", onAbout)
            HorizontalDivider(Modifier.padding(vertical = 8.dp))
            Text(
                "Prochaines étapes du développement : code PIN, blacklist, exports Excel/PDF/Word, sauvegarde et restauration.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(16.dp),
            )
        }
    }
}

@Composable
private fun MenuItem(icon: ImageVector, title: String, subtitle: String, onClick: () -> Unit) {
    ListItem(
        modifier = Modifier.clickable(onClick = onClick),
        leadingContent = { Icon(icon, contentDescription = null) },
        headlineContent = { Text(title) },
        supportingContent = { Text(subtitle) },
    )
}

// ------------------------------------------------------------------ Épargne

class SavingsViewModel(repo: CategoryRepository) : ViewModel() {
    val savings: StateFlow<List<CategoryBalanceRow>> = repo.observeBalances()
        .map { rows -> rows.filter { it.isSavings } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun SavingsScreen(onBack: () -> Unit, onOpenCategory: (Long) -> Unit) {
    val vm = appViewModel { SavingsViewModel(it.categoryRepository) }
    val rows by vm.savings.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("Gestion de l'épargne", onBack) }) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp)) {
            item {
                Card(Modifier.fillMaxWidth(), colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)) {
                    Column(Modifier.padding(16.dp)) {
                        Text("Épargne disponible", style = MaterialTheme.typography.labelLarge)
                        MoneyText(rows.sumOf { it.balance() }, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
                        Spacer(Modifier.height(8.dp))
                        LabeledValue("Total affecté (entrées)") { MoneyText(rows.sumOf { it.allocated }) }
                        LabeledValue("Transferts reçus") { MoneyText(rows.sumOf { it.transferredIn }) }
                        LabeledValue("Retraits (dépenses)") { MoneyText(rows.sumOf { it.spent }) }
                        LabeledValue("Transferts envoyés") { MoneyText(rows.sumOf { it.transferredOut }) }
                    }
                }
                SectionHeader("Catégories d'épargne")
                Text(
                    "Une catégorie compte comme épargne si l'option « Compter comme épargne » est cochée dans sa fiche.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (rows.isEmpty()) item { EmptyState("Aucune catégorie d'épargne.") }
            items(rows, key = { it.categoryId }) { c ->
                ListItem(
                    modifier = Modifier.clickable { onOpenCategory(c.categoryId) },
                    leadingContent = { CategoryDot(c.colorArgb, 14) },
                    headlineContent = { Text(c.name) },
                    trailingContent = { MoneyText(c.balance()) },
                )
            }
        }
    }
}

// ------------------------------------------------------------------ Paramètres

class SettingsViewModel(private val settings: SettingsRepository, private val checker: ConsistencyChecker) : ViewModel() {
    val mode: StateFlow<OverdraftMode> =
        settings.observeOverdraftMode().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), OverdraftMode.REFUSE)
    var report by mutableStateOf<ConsistencyChecker.Report?>(null)
        private set
    var checking by mutableStateOf(false)
        private set

    fun setMode(m: OverdraftMode) {
        viewModelScope.launch { settings.setOverdraftMode(m) }
    }

    fun check() {
        if (checking) return
        checking = true
        viewModelScope.launch {
            report = checker.check()
            checking = false
        }
    }
}

@Composable
fun SettingsScreen(onBack: () -> Unit) {
    val vm = appViewModel { SettingsViewModel(it.settingsRepository, it.consistencyChecker) }
    val mode by vm.mode.collectAsStateWithLifecycle()
    Scaffold(topBar = { BackTopBar("Paramètres", onBack) }) { padding ->
        Column(Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp)) {
            SectionHeader("Dépense supérieure au solde")
            listOf(
                OverdraftMode.REFUSE to ("Refuser" to "La dépense est refusée ; aucun solde n'est modifié."),
                OverdraftMode.WARN to ("Autoriser avec avertissement" to "Une confirmation est demandée ; le solde devient négatif."),
                OverdraftMode.TRANSFER to ("Proposer un transfert" to "Le manque est couvert par une autre catégorie, après confirmation."),
            ).forEach { (m, texts) ->
                Row(
                    Modifier.fillMaxWidth().selectable(selected = mode == m, onClick = { vm.setMode(m) }, role = Role.RadioButton)
                        .padding(vertical = 8.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    RadioButton(selected = mode == m, onClick = null)
                    Spacer(Modifier.width(8.dp))
                    Column {
                        Text(texts.first, style = MaterialTheme.typography.bodyLarge)
                        Text(texts.second, style = MaterialTheme.typography.bodySmall)
                    }
                }
            }

            SectionHeader("Vérification des données")
            Text(
                "Contrôle que chaque entrée est entièrement répartie, que les pourcentages historiques sont intacts " +
                    "et que le solde total est égal aux entrées moins les dépenses.",
                style = MaterialTheme.typography.bodySmall,
            )
            Spacer(Modifier.height(8.dp))
            Button(onClick = vm::check, enabled = !vm.checking) { Text(if (vm.checking) "Vérification…" else "Vérifier la cohérence") }
            vm.report?.let { r ->
                Spacer(Modifier.height(12.dp))
                Card(
                    Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (r.isConsistent) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                    ),
                ) {
                    Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(if (r.isConsistent) Icons.Filled.CheckCircle else Icons.Filled.Warning, contentDescription = null)
                            Spacer(Modifier.width(8.dp))
                            Text(if (r.isConsistent) "Données cohérentes" else "${r.errors.size} incohérence(s) détectée(s)", fontWeight = FontWeight.SemiBold)
                        }
                        Text("${r.checkedIncomes} entrées, ${r.checkedExpenses} dépenses, ${r.checkedTransfers} transferts vérifiés.",
                            style = MaterialTheme.typography.bodySmall)
                        LabeledValue("Entrées actives") { MoneyText(r.totalIncome) }
                        LabeledValue("Dépenses actives") { MoneyText(r.totalExpense) }
                        LabeledValue("Solde total") { MoneyText(r.totalBalance) }
                        (r.errors + r.warnings).forEach { Text("• $it", style = MaterialTheme.typography.bodySmall) }
                    }
                }
            }
        }
    }
}

// ------------------------------------------------------------------ À propos

@Composable
fun AboutScreen(onBack: () -> Unit) {
    Scaffold(topBar = { BackTopBar("À propos", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text("SmartBudget FC", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
            Text("Version ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})")
            Text("Gestion financière personnelle en francs congolais : répartition automatique des entrées, dépenses par catégorie, transferts et historique complet.")
            SectionHeader("Confidentialité")
            Text("• L'application fonctionne entièrement hors ligne : elle ne demande pas l'accès à Internet.")
            Text("• Vos données restent sur ce téléphone, dans une base chiffrée (SQLCipher) dont la clé est protégée par Android Keystore.")
            Text("• Aucune publicité, aucun compte en ligne, aucune transmission de données.")
            SectionHeader("Règle de calcul")
            Text("Solde d'une catégorie = montants affectés par les entrées actives − dépenses actives + transferts reçus − transferts envoyés.")
            Text("Les montants sont calculés au centime près, sans arrondi approximatif : la somme des parts est toujours égale au montant reçu.")
        }
    }
}
