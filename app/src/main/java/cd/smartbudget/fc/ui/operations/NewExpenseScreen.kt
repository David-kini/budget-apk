package cd.smartbudget.fc.ui.operations

import android.content.Context
import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.data.db.entity.CategoryBalanceRow
import cd.smartbudget.fc.data.repo.CategoryRepository
import cd.smartbudget.fc.data.repo.ExpenseRepository
import cd.smartbudget.fc.data.repo.ExpenseRequest
import cd.smartbudget.fc.domain.Clock
import cd.smartbudget.fc.domain.Confirmation
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.domain.SystemClock
import cd.smartbudget.fc.ui.appViewModel
import cd.smartbudget.fc.ui.components.AmountField
import cd.smartbudget.fc.ui.components.BackTopBar
import cd.smartbudget.fc.ui.components.ConfirmDialog
import cd.smartbudget.fc.ui.components.DateField
import cd.smartbudget.fc.ui.components.SectionHeader
import cd.smartbudget.fc.ui.components.userMessage
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import java.util.UUID

class NewExpenseViewModel(
    private val expenses: ExpenseRepository,
    categories: CategoryRepository,
    private val appContext: Context,
    initialCategoryId: Long?,
    clock: Clock = SystemClock,
) : ViewModel() {
    private val requestId = UUID.randomUUID().toString()

    val today = clock.todayEpochDay()
    var amount by mutableStateOf("")
    var categoryId by mutableStateOf(initialCategoryId)
    var reason by mutableStateOf("")
    var date by mutableStateOf(today)
    var description by mutableStateOf("")
    var beneficiary by mutableStateOf("")
    var attachmentPath by mutableStateOf<String?>(null)
        private set
    var attachmentName by mutableStateOf<String?>(null)
        private set
    var saving by mutableStateOf(false)
        private set
    var error by mutableStateOf<String?>(null)
        private set
    var confirmation by mutableStateOf<Confirmation?>(null)
        private set
    var successMessage by mutableStateOf<String?>(null)
        private set

    val activeCategories: StateFlow<List<CategoryBalanceRow>> = categories.observeBalances()
        .map { rows -> rows.filter { it.isActive } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun submit(acceptOverdraft: Boolean = false, coverFrom: Long? = null) {
        if (saving) return
        val value = Money.parse(amount)
        val cat = categoryId
        when {
            value == null || value <= 0 -> { error = "Saisissez un montant valide."; return }
            cat == null -> { error = "Choisissez la catégorie de la dépense."; return }
            reason.isBlank() -> { error = "Indiquez le motif de la dépense."; return }
        }
        saving = true
        error = null
        confirmation = null
        viewModelScope.launch {
            val result = expenses.recordExpense(
                ExpenseRequest(
                    clientRequestId = requestId, amount = value!!, categoryId = cat!!, reason = reason, expenseDate = date,
                    description = description, beneficiary = beneficiary, attachmentPath = attachmentPath,
                    acceptOverdraft = acceptOverdraft, coverFromCategoryId = coverFrom,
                )
            )
            saving = false
            when (result) {
                is OperationResult.Recorded, is OperationResult.AlreadyRecorded -> successMessage = result.userMessage()
                is OperationResult.NeedsConfirmation -> confirmation = result.confirmation
                is OperationResult.Rejected -> error = result.message
            }
        }
    }

    fun dismissConfirmation() {
        confirmation = null
    }

    /** Copie la pièce justificative dans le stockage privé de l'application (incluse dans les sauvegardes). */
    fun attach(uri: Uri) {
        viewModelScope.launch {
            try {
                val (path, name) = withContext(Dispatchers.IO) {
                    val resolver = appContext.contentResolver
                    val type = resolver.getType(uri).orEmpty()
                    val ext = when {
                        type.contains("pdf") -> "pdf"
                        type.contains("png") -> "png"
                        type.contains("jpeg") || type.contains("jpg") -> "jpg"
                        type.contains("webp") -> "webp"
                        else -> "bin"
                    }
                    val dir = File(appContext.filesDir, "attachments").apply { mkdirs() }
                    val file = File(dir, "${UUID.randomUUID()}.$ext")
                    resolver.openInputStream(uri)?.use { input ->
                        var total = 0L
                        file.outputStream().use { out ->
                            val buffer = ByteArray(64 * 1024)
                            while (true) {
                                val n = input.read(buffer)
                                if (n < 0) break
                                total += n
                                if (total > MAX_ATTACHMENT_BYTES) {
                                    out.close(); file.delete()
                                    throw IllegalStateException("Fichier trop volumineux (10 Mo maximum).")
                                }
                                out.write(buffer, 0, n)
                            }
                        }
                    } ?: throw IllegalStateException("Fichier illisible.")
                    "attachments/${file.name}" to (uri.lastPathSegment ?: file.name)
                }
                attachmentPath?.let { old -> File(appContext.filesDir, old).delete() }
                attachmentPath = path
                attachmentName = name
            } catch (e: Exception) {
                error = e.message ?: "Impossible de joindre ce fichier."
            }
        }
    }

    fun removeAttachment() {
        attachmentPath?.let { File(appContext.filesDir, it).delete() }
        attachmentPath = null
        attachmentName = null
    }

    companion object {
        const val MAX_ATTACHMENT_BYTES = 10L * 1024 * 1024
    }
}

@Composable
fun NewExpenseScreen(initialCategoryId: Long?, onDone: () -> Unit) {
    val vm = appViewModel {
        NewExpenseViewModel(it.expenseRepository, it.categoryRepository, it.appContext, initialCategoryId)
    }
    val categories by vm.activeCategories.collectAsStateWithLifecycle()
    val context = LocalContext.current
    val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> uri?.let(vm::attach) }

    LaunchedEffect(vm.successMessage) {
        vm.successMessage?.let {
            Toast.makeText(context, it, Toast.LENGTH_LONG).show()
            onDone()
        }
    }

    Scaffold(topBar = { BackTopBar("Nouvelle dépense", onDone) }) { padding ->
        Column(
            Modifier.fillMaxSize().padding(padding).imePadding().verticalScroll(rememberScrollState()).padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            AmountField(vm.amount, { vm.amount = it }, "Montant")
            SectionHeader("Catégorie")
            Card(Modifier.fillMaxWidth()) {
                CategoryPicker(categories, vm.categoryId, { vm.categoryId = it }, Modifier.padding(horizontal = 8.dp, vertical = 4.dp))
            }
            OutlinedTextField(
                value = vm.reason, onValueChange = { vm.reason = it.take(120) },
                label = { Text("Motif") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            DateField(vm.date, { vm.date = it }, "Date", maxDay = vm.today)
            OutlinedTextField(
                value = vm.beneficiary, onValueChange = { vm.beneficiary = it.take(80) },
                label = { Text("Bénéficiaire (facultatif)") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
            )
            OutlinedTextField(
                value = vm.description, onValueChange = { vm.description = it.take(200) },
                label = { Text("Description (facultatif)") }, modifier = Modifier.fillMaxWidth(),
            )
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedButton(onClick = { picker.launch(arrayOf("image/*", "application/pdf")) }) {
                    Text(if (vm.attachmentPath == null) "Joindre un justificatif" else "Remplacer le justificatif")
                }
                if (vm.attachmentPath != null) TextButton(onClick = vm::removeAttachment) { Text("Retirer") }
            }
            vm.attachmentName?.let { Text("Pièce jointe : $it", style = MaterialTheme.typography.bodySmall) }

            vm.error?.let { Text(it, color = MaterialTheme.colorScheme.error) }
            Spacer(Modifier.height(4.dp))
            Button(onClick = { vm.submit() }, enabled = !vm.saving, modifier = Modifier.fillMaxWidth().height(52.dp)) {
                if (vm.saving) CircularProgressIndicator(Modifier.height(20.dp)) else Text("Enregistrer la dépense")
            }
        }
    }

    when (val c = vm.confirmation) {
        is Confirmation.Overdraft -> ConfirmDialog(
            title = "Solde insuffisant",
            text = "« ${c.categoryName} » dispose de ${Money.format(c.available)}. Cette dépense la mettra en dépassement de " +
                "${Money.format(c.shortfall)}. Voulez-vous quand même l'enregistrer ?",
            confirmLabel = "Enregistrer quand même",
            onConfirm = { vm.submit(acceptOverdraft = true) },
            onDismiss = vm::dismissConfirmation,
        )
        is Confirmation.CoverByTransfer -> CoverDialog(c, onConfirm = { vm.submit(coverFrom = it) }, onDismiss = vm::dismissConfirmation)
        else -> Unit
    }
}

@Composable
private fun CoverDialog(c: Confirmation.CoverByTransfer, onConfirm: (Long) -> Unit, onDismiss: () -> Unit) {
    var selected by remember { mutableStateOf(c.candidates.firstOrNull()?.categoryId) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Couvrir le manque") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    "« ${c.categoryName} » dispose de ${Money.format(c.available)}. " +
                        "Il manque ${Money.format(c.shortfall)}. Un transfert sera enregistré depuis la catégorie choisie."
                )
                if (c.candidates.isEmpty()) {
                    Text("Aucune catégorie ne dispose d'un solde suffisant.", color = MaterialTheme.colorScheme.error)
                } else {
                    CategoryPicker(
                        categories = c.candidates.map {
                            CategoryBalanceRow(it.categoryId, it.name, 0xFF9E9E9EL, 0, true, false, it.balance, 0, 0, 0)
                        },
                        selectedId = selected,
                        onSelect = { selected = it },
                    )
                }
            }
        },
        confirmButton = {
            TextButton(enabled = selected != null, onClick = { selected?.let(onConfirm) }) { Text("Transférer et enregistrer") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}
