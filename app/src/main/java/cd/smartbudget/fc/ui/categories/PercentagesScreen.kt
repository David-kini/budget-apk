package cd.smartbudget.fc.ui.categories

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.Percent
import cd.smartbudget.fc.data.db.entity.CategoryEntity
import cd.smartbudget.fc.data.repo.CategoryRepository
import cd.smartbudget.fc.domain.BudgetRules
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.ui.appViewModel
import cd.smartbudget.fc.ui.components.BackTopBar
import cd.smartbudget.fc.ui.components.CategoryDot
import cd.smartbudget.fc.ui.components.userMessage
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

class PercentagesViewModel(private val repo: CategoryRepository) : ViewModel() {
    var categories by mutableStateOf<List<CategoryEntity>>(emptyList())
        private set
    /** Saisie texte par catégorie (ex. "20", "12,5"). */
    val inputs = mutableStateMapOf<Long, String>()
    var loaded by mutableStateOf(false)
        private set
    var saving by mutableStateOf(false)
        private set
    var message by mutableStateOf<String?>(null)
        private set
    var saved by mutableStateOf(false)
        private set

    init {
        viewModelScope.launch {
            val active = repo.observeCategories().first().filter { it.isActive }
            val shares = repo.observeCurrentShares().first().associate { it.categoryId to it.basisPoints }
            categories = active
            active.forEach { inputs[it.id] = Percent.format(shares[it.id] ?: 0).removeSuffix(" %") }
            loaded = true
        }
    }

    fun parsed(): Map<Long, Int?> = categories.associate { it.id to Percent.parse(inputs[it.id].orEmpty().ifBlank { "0" }) }

    fun validation(): BudgetRules.Validation {
        val p = parsed()
        if (p.values.any { it == null }) return BudgetRules.Validation.Invalid("Un pourcentage est invalide (ex. 20 ou 12,5).")
        return BudgetRules.validate(p.map { BudgetRules.ShareInput(it.key, it.value!!) }, categories.map { it.id }.toSet())
    }

    fun save() {
        if (saving) return
        val p = parsed()
        if (p.values.any { it == null }) {
            message = "Un pourcentage est invalide."
            return
        }
        saving = true
        viewModelScope.launch {
            val r = repo.saveConfiguration(p.mapValues { it.value!! })
            saving = false
            message = r.userMessage()
            saved = r is OperationResult.Recorded
        }
    }
}

@Composable
fun PercentagesScreen(onBack: () -> Unit) {
    val vm = appViewModel { PercentagesViewModel(it.categoryRepository) }
    val context = LocalContext.current
    LaunchedEffect(vm.saved) {
        if (vm.saved) {
            Toast.makeText(context, vm.message, Toast.LENGTH_LONG).show()
            onBack()
        }
    }
    val parsed = vm.parsed()
    val total = parsed.values.sumOf { it ?: 0 }
    val validation = vm.validation()

    Scaffold(topBar = { BackTopBar("Répartition des entrées", onBack) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Text(
                "Les nouveaux pourcentages s'appliqueront uniquement aux PROCHAINES entrées. " +
                    "Les répartitions déjà enregistrées ne sont jamais recalculées.",
                style = MaterialTheme.typography.bodySmall,
            )
            if (!vm.loaded) Text("Chargement…")
            vm.categories.forEach { c ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryDot(c.colorArgb, 14)
                    Spacer(Modifier.width(10.dp))
                    Text(c.name, modifier = Modifier.weight(1f))
                    OutlinedTextField(
                        value = vm.inputs[c.id].orEmpty(),
                        onValueChange = { v -> vm.inputs[c.id] = v.filter { it.isDigit() || it == ',' || it == '.' }.take(6) },
                        suffix = { Text("%") },
                        singleLine = true,
                        isError = parsed[c.id] == null,
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
                        modifier = Modifier.width(120.dp),
                    )
                }
            }
            val ok = validation is BudgetRules.Validation.Valid
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (ok) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.errorContainer
                ),
            ) {
                Column(Modifier.padding(16.dp)) {
                    Text("Total : ${Percent.format(total)}", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                    Text(
                        if (validation is BudgetRules.Validation.Invalid) validation.message
                        else "Exemple : une entrée de 100 000 FC donnera " + vm.categories.joinToString { c ->
                            "${c.name} ${Money.format(Money.fromFc(100_000) * (parsed[c.id] ?: 0) / Percent.FULL)}"
                        } + ".",
                        style = MaterialTheme.typography.bodySmall,
                    )
                }
            }
            vm.message?.takeIf { !vm.saved }?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(4.dp))
            Button(onClick = vm::save, enabled = ok && !vm.saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                Text("Valider la répartition")
            }
        }
    }
}
