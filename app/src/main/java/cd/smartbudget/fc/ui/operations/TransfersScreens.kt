package cd.smartbudget.fc.ui.operations

import android.widget.Toast
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cd.smartbudget.fc.core.DateRange
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.data.db.entity.CategoryBalanceRow
import cd.smartbudget.fc.data.db.entity.OperationRow
import cd.smartbudget.fc.data.repo.CategoryRepository
import cd.smartbudget.fc.data.repo.HistoryRepository
import cd.smartbudget.fc.data.repo.TransferRepository
import cd.smartbudget.fc.data.repo.TransferRequest
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.domain.SystemClock
import cd.smartbudget.fc.ui.appViewModel
import cd.smartbudget.fc.ui.components.AmountField
import cd.smartbudget.fc.ui.components.BackTopBar
import cd.smartbudget.fc.ui.components.DateField
import cd.smartbudget.fc.ui.components.EmptyState
import cd.smartbudget.fc.ui.components.SectionHeader
import cd.smartbudget.fc.ui.components.userMessage
import cd.smartbudget.fc.ui.history.OperationActions
import cd.smartbudget.fc.ui.history.OperationListItem
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.util.UUID

// ------------------------------------------------------------------ Liste des transferts

class TransfersViewModel(history: HistoryRepository) : ViewModel() {
    val transfers: StateFlow<List<OperationRow>> = history.observe(DateRange.ALL.fromDay, DateRange.ALL.toDay)
        .map { rows -> rows.filter { it.type == "TRANSFER" } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())
}

@Composable
fun TransfersScreen(onBack: () -> Unit, onNewTransfer: () -> Unit) {
    val vm = appViewModel { TransfersViewModel(it.historyRepository) }
    val rows by vm.transfers.collectAsStateWithLifecycle()
    Scaffold(
        topBar = { BackTopBar("Transferts entre catégories", onBack) },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = onNewTransfer, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Transfert") })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
            item {
                Text(
                    "Un transfert déplace de l'argent d'une catégorie à une autre. Ce n'est ni un revenu ni une dépense : " +
                        "le solde total ne change pas.",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
            if (rows.isEmpty()) item { EmptyState("Aucun transfert enregistré.") }
            items(rows, key = { it.id }) { row -> OperationActions(row) { OperationListItem(row, onClick = it) } }
        }
    }
}

// ------------------------------------------------------------------ Nouveau transfert

class NewTransferViewModel(
    private val transfers: TransferRepository,
    categories: CategoryRepository,
    initialFromId: Long?,
    clock: Clock = SystemClock,
) : ViewModel() {
    private val requestId = UUID.randomUUID().toString()
    val today = clock.todayEpochDay()
    var fromId by mutableStateOf(initialFromId)
    var toId by mutableStateOf<Long?>(null)
    var amount by mutableStateOf("")
    var date by mutableStateOf(today)
    var reason by mutableStateOf("")
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var successMessage by mutableStateOf<String?>(null)
        private set

    val balances: StateFlow<List<CategoryBalanceRow>> =
        categories.observeBalances().stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun submit() {
        if (saving) return
        val value = Money.parse(amount)
        val from = fromId
        val to = toId
        if (value == null || value <= 0) { error = "Saisissez un montant valide."; return }
        if (from == null || to == null) { error = "Choisissez la catégorie source et la catégorie de destination."; return }
        saving = true
        error = null
        viewModelScope.launch {
            val r = transfers.transfer(TransferRequest(requestId, from, to, value, date, reason))
            saving = false
            when (r) {
                is OperationResult.Recorded, is OperationResult.AlreadyRecorded -> successMessage = r.userMessage()
                else -> error = r.userMessage()
            }
        }
    }
}

@Composable
fun NewTransferScreen(initialFromId: Long?, onDone: () -> Unit) {
    val vm = appViewModel { NewTransferViewModel(it.transferRepository, it.categoryRepository, initialFromId) }
    val balances by vm.balances.collectAsStateWithLifecycle()
    val context = LocalContext.current
    LaunchedEffect(vm.successMessage) {
        vm.successMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            onDone()
        }
    }
    Scaffold(topBar = { BackTopBar("Nouveau transfert", onDone) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AmountField(vm.amount, { vm.amount = it }, "Montant à transférer")
            SectionHeader("Depuis")
            Card(Modifier.fillMaxWidth()) {
                CategoryPicker(balances.filter { it.balance() > 0 }, vm.fromId, { vm.fromId = it }, Modifier.padding(8.dp))
            }
            SectionHeader("Vers")
            Card(Modifier.fillMaxWidth()) {
                CategoryPicker(balances.filter { it.isActive && it.categoryId != vm.fromId }, vm.toId, { vm.toId = it }, Modifier.padding(8.dp))
            }
            DateField(vm.date, { vm.date = it }, "Date", maxDay = vm.today)
            OutlinedTextField(
                value = vm.reason, onValueChange = { vm.reason = it.take(120) },
                label = { Text("Motif") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(4.dp))
            Button(onClick = vm::submit, enabled = !vm.saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (vm.saving) CircularProgressIndicator(Modifier.height(20.dp)) else Text("Transférer")
            }
        }
    }
}
