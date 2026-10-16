@file:OptIn(ExperimentalLayoutApi::class)

package cd.smartbudget.fc.ui.operations

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.Percent
import cd.smartbudget.fc.data.db.entity.CurrentShareRow
import cd.smartbudget.fc.data.repo.CategoryRepository
import cd.smartbudget.fc.data.repo.IncomeRepository
import cd.smartbudget.fc.data.repo.IncomeRequest
import cd.smartbudget.fc.domain.AllocationCalculator
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.domain.SystemClock
import cd.smartbudget.fc.ui.appViewModel
import cd.smartbudget.fc.ui.components.AmountField
import cd.smartbudget.fc.ui.components.BackTopBar
import cd.smartbudget.fc.ui.components.CategoryDot
import cd.smartbudget.fc.ui.components.DateField
import cd.smartbudget.fc.ui.components.LabeledValue
import cd.smartbudget.fc.ui.components.SectionHeader
import cd.smartbudget.fc.ui.components.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

val INCOME_SOURCES = listOf("Salaire", "Activité commerciale", "Prestation informatique", "Don", "Autre revenu")

class NewIncomeViewModel(
    private val incomes: IncomeRepository,
    categories: CategoryRepository,
    private val clock: Clock = SystemClock,
) : ViewModel() {
    /** Identifiant unique de CETTE saisie : un double clic ou une nouvelle tentative ne crée pas de doublon. */
    private val requestId = UUID.randomUUID().toString()

    val today = clock.todayEpochDay()
    var amount by mutableStateOf("")
    var source by mutableStateOf(INCOME_SOURCES.first())
    var date by mutableStateOf(today)
    var description by mutableStateOf("")
    var reference by mutableStateOf("")
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var successMessage by mutableStateOf<String?>(null)
        private set

    val shares: StateFlow<List<CurrentShareRow>> =
        categories.observeCurrentShares().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Aperçu de la répartition (même calcul que l'enregistrement). */
    fun preview(shares: List<CurrentShareRow>): List<Pair<CurrentShareRow, Long>> {
        val value = Money.parse(amount) ?: return emptyList()
        if (value <= 0 || shares.sumOf { it.basisPoints } != Percent.FULL) return emptyList()
        val parts = AllocationCalculator.allocate(value, shares.map { AllocationCalculator.Share(it.categoryId, it.basisPoints, it.sortOrder) })
            .associateBy { it.categoryId }
        return shares.map { it to (parts[it.categoryId]?.amount ?: 0L) }
    }

    fun submit() {
        if (saving) return
        val value = Money.parse(amount)
        if (value == null || value <= 0) {
            error = "Saisissez un montant valide (ex. 100 000)."
            return
        }
        saving = true
        error = null
        viewModelScope.launch {
            val result = incomes.recordIncome(IncomeRequest(requestId, value, source, date, description, reference))
            saving = false
            when (result) {
                is OperationResult.Recorded, is OperationResult.AlreadyRecorded -> successMessage = result.userMessage()
                else -> error = result.userMessage()
            }
        }
    }
}

@Composable
fun NewIncomeScreen(onDone: () -> Unit) {
    val vm = appViewModel { NewIncomeViewModel(it.incomeRepository, it.categoryRepository) }
    val shares by vm.shares.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(vm.successMessage) {
        vm.successMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            onDone()
        }
    }

    Scaffold(topBar = { BackTopBar("Nouvelle entrée", onDone) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AmountField(vm.amount, { vm.amount = it }, "Montant reçu")
            Text("Source de l'argent", style = MaterialTheme.typography.labelLarge)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                INCOME_SOURCES.forEach { s ->
                    FilterChip(selected = vm.source == s, onClick = { vm.source = s }, label = { Text(s) })
                }
            }
            OutlinedTextField(
                value = vm.source, onValueChange = { vm.source = it.take(60) },
                label = { Text("Source (modifiable)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            DateField(vm.date, { vm.date = it }, "Date de réception", maxDay = vm.today)
            OutlinedTextField(
                value = vm.description, onValueChange = { vm.description = it.take(200) },
                label = { Text("Description (facultatif)") }, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.reference, onValueChange = { vm.reference = it.take(60) },
                label = { Text("Référence (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )

            val preview = vm.preview(shares)
            if (preview.isNotEmpty()) {
                SectionHeader("Répartition automatique")
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        preview.forEach { (share, value) ->
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CategoryDot(share.colorArgb)
                                Spacer(Modifier.width(8.dp))
                                LabeledValue("${share.name} (${Percent.format(share.basisPoints)})") {
                                    Text(Money.format(value))
                                }
                            }
                        }
                        LabeledValue("Total") { Text(Money.format(preview.sumOf { it.second }), style = MaterialTheme.typography.titleSmall) }
                    }
                }
            }

            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(4.dp))
            Button(onClick = vm::submit, enabled = !vm.saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (vm.saving) CircularProgressIndicator(Modifier.height(20.dp)) else Text("Enregistrer et répartir")
            }
        }
    }
}
