package cd.smartbudget.fc.ui.history

import android.widget.Toast
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.ShoppingCart
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.unit.dp
import cd.smartbudget.fc.core.Dates
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.OperationStatus
import cd.smartbudget.fc.data.db.entity.OperationRow
import cd.smartbudget.fc.data.repo.ExpenseRequest
import cd.smartbudget.fc.data.repo.IncomeRequest
import cd.smartbudget.fc.di.AppContainer
import cd.smartbudget.fc.domain.Confirmation
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.ui.appContainer
import cd.smartbudget.fc.ui.components.AmountField
import cd.smartbudget.fc.ui.components.ConfirmDialog
import cd.smartbudget.fc.ui.components.ReasonDialog
import cd.smartbudget.fc.ui.components.userMessage
import androidx.compose.material3.OutlinedTextField
import kotlinx.coroutines.launch
import java.util.UUID

fun OperationRow.typeLabel(): String = when (type) {
    "INCOME" -> "Entrée"
    "EXPENSE" -> "Dépense"
    else -> "Transfert"
}

/** Ligne d'historique : entrée (+), dépense (−), transfert (⇄). Les opérations annulées sont barrées. */
@Composable
fun OperationListItem(row: OperationRow, onClick: (() -> Unit)? = null) {
    val cancelled = row.status == OperationStatus.CANCELLED
    val (icon, sign, color) = when (row.type) {
        "INCOME" -> Triple(Icons.Filled.Add, "+", Color(0xFF2E7D32))
        "EXPENSE" -> Triple(Icons.Filled.ShoppingCart, "−", MaterialTheme.colorScheme.error)
        else -> Triple(Icons.Filled.Refresh, "", MaterialTheme.colorScheme.primary)
    }
    val subtitle = buildString {
        append(Dates.format(row.date))
        when (row.type) {
            "EXPENSE" -> append(" · ${row.categoryName}")
            "TRANSFER" -> append(" · ${row.categoryName} → ${row.targetCategoryName}")
            else -> row.reference?.let { append(" · Réf. $it") }
        }
        if (cancelled) append(" · ANNULÉE")
    }
    ListItem(
        modifier = if (onClick != null) Modifier.clickable(onClick = onClick) else Modifier,
        leadingContent = { Icon(icon, contentDescription = row.typeLabel(), tint = color) },
        headlineContent = {
            Text(row.label, textDecoration = if (cancelled) TextDecoration.LineThrough else null, maxLines = 1)
        },
        supportingContent = { Text(subtitle, style = MaterialTheme.typography.bodySmall) },
        trailingContent = {
            Text(
                "$sign${Money.format(row.amount)}",
                color = if (cancelled) MaterialTheme.colorScheme.onSurfaceVariant else color,
                fontWeight = FontWeight.SemiBold,
                textDecoration = if (cancelled) TextDecoration.LineThrough else null,
            )
        },
    )
}

private enum class Dialog { DETAILS, CANCEL, CORRECT }

/**
 * Enveloppe une ligne d'historique : un appui ouvre le détail, avec annulation et correction tracées.
 */
@Composable
fun OperationActions(row: OperationRow, content: @Composable (onClick: () -> Unit) -> Unit) {
    val container = appContainer()
    val scope = rememberCoroutineScope()
    val context = LocalContext.current
    var dialog by remember { mutableStateOf<Dialog?>(null) }
    var pendingForce by remember { mutableStateOf<(suspend () -> OperationResult)?>(null) }
    var forceText by remember { mutableStateOf("") }

    fun toast(result: OperationResult) {
        Toast.makeText(context, result.userMessage(), Toast.LENGTH_LONG).show()
    }

    /** Affiche le résultat ; si une confirmation est possible, propose de forcer via [retry]. */
    fun handle(result: OperationResult, retry: suspend () -> OperationResult) {
        val c = (result as? OperationResult.NeedsConfirmation)?.confirmation
        val text = when (c) {
            is Confirmation.NegativeBalances -> "Cette opération rendra des soldes négatifs : " +
                c.impacts.joinToString { "${it.name} (${Money.format(it.balanceAfter)})" } + ". Continuer ?"
            is Confirmation.Overdraft -> "« ${c.categoryName} » sera en dépassement de ${Money.format(c.shortfall)}. Continuer ?"
            else -> null
        }
        if (text != null) {
            forceText = text
            pendingForce = retry
        } else {
            toast(result)
        }
    }

    content { dialog = Dialog.DETAILS }

    when (dialog) {
        Dialog.DETAILS -> AlertDialog(
            onDismissRequest = { dialog = null },
            title = { Text("${row.typeLabel()} — ${Money.format(row.amount)}") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("Date : ${Dates.formatLong(row.date)}")
                    Text("Libellé : ${row.label}")
                    row.categoryName?.let { Text(if (row.type == "TRANSFER") "De : $it" else "Catégorie : $it") }
                    row.targetCategoryName?.let { Text("Vers : $it") }
                    row.description?.let { Text("Description : $it") }
                    row.reference?.let { Text(if (row.type == "EXPENSE") "Bénéficiaire : $it" else "Référence : $it") }
                    Text("Enregistrée le ${Dates.formatDateTime(row.createdAt)}")
                    Text("Statut : " + if (row.status == OperationStatus.ACTIVE) "active" else "annulée")
                }
            },
            confirmButton = {
                if (row.status == OperationStatus.ACTIVE) {
                    TextButton(onClick = { dialog = Dialog.CANCEL }) { Text("Annuler l'opération") }
                }
            },
            dismissButton = {
                Column {
                    if (row.status == OperationStatus.ACTIVE && row.type != "TRANSFER") {
                        TextButton(onClick = { dialog = Dialog.CORRECT }) { Text("Corriger le montant") }
                    }
                    TextButton(onClick = { dialog = null }) { Text("Fermer") }
                }
            },
        )
        Dialog.CANCEL -> ReasonDialog(
            title = "Annuler cette ${row.typeLabel().lowercase()}",
            explanation = "L'opération ne sera pas supprimée : elle restera dans l'historique comme « annulée » et ne comptera plus dans les soldes.",
            confirmLabel = "Confirmer l'annulation",
            onConfirm = { reason ->
                dialog = null
                scope.launch {
                    val r = cancel(container, row, reason, force = false)
                    handle(r) { cancel(container, row, reason, force = true) }
                }
            },
            onDismiss = { dialog = null },
        )
        Dialog.CORRECT -> CorrectDialog(row, onDismiss = { dialog = null }) { newAmount, reason ->
            dialog = null
            scope.launch {
                val r = correct(container, row, newAmount, reason, force = false)
                handle(r) { correct(container, row, newAmount, reason, force = true) }
            }
        }
        null -> Unit
    }

    pendingForce?.let { retry ->
        ConfirmDialog(
            title = "Soldes négatifs",
            text = forceText,
            confirmLabel = "Continuer",
            onConfirm = {
                pendingForce = null
                scope.launch { toast(retry()) }
            },
            onDismiss = { pendingForce = null },
        )
    }
}

@Composable
private fun CorrectDialog(row: OperationRow, onDismiss: () -> Unit, onConfirm: (Long, String) -> Unit) {
    var amount by remember { mutableStateOf(Money.format(row.amount, withCurrency = false)) }
    var reason by remember { mutableStateOf("") }
    val parsed = Money.parse(amount)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Corriger le montant") },
        text = {
            Column {
                Text(
                    "L'opération actuelle sera annulée et remplacée par une nouvelle, liée à l'ancienne. " +
                        if (row.type == "INCOME") "La nouvelle entrée est répartie avec les pourcentages actuels." else "",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(8.dp))
                AmountField(amount, { amount = it }, "Nouveau montant", error = if (parsed == null || parsed <= 0) "Montant invalide" else null)
                OutlinedTextField(reason, { reason = it.take(120) }, label = { Text("Motif (obligatoire)") }, modifier = Modifier.fillMaxWidth())
            }
        },
        confirmButton = {
            TextButton(
                enabled = parsed != null && parsed > 0 && parsed != row.amount && reason.isNotBlank(),
                onClick = { onConfirm(parsed!!, reason) },
            ) { Text("Corriger") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

private suspend fun cancel(c: AppContainer, row: OperationRow, reason: String, force: Boolean): OperationResult = when (row.type) {
    "INCOME" -> c.incomeRepository.cancelIncome(row.id, reason, force)
    "EXPENSE" -> c.expenseRepository.cancelExpense(row.id, reason)
    else -> c.transferRepository.cancelTransfer(row.id, reason, force)
}

private suspend fun correct(c: AppContainer, row: OperationRow, newAmount: Long, reason: String, force: Boolean): OperationResult {
    val db = c.database
    return when (row.type) {
        "INCOME" -> {
            val original = db.incomeDao().getById(row.id) ?: return OperationResult.Rejected(
                cd.smartbudget.fc.domain.RejectReason.NOT_FOUND, "Entrée introuvable.")
            c.incomeRepository.correctIncome(
                row.id,
                IncomeRequest(UUID.randomUUID().toString(), newAmount, original.source, original.receivedDate, original.description, original.reference),
                reason, force,
            )
        }
        else -> {
            val original = db.expenseDao().getById(row.id) ?: return OperationResult.Rejected(
                cd.smartbudget.fc.domain.RejectReason.NOT_FOUND, "Dépense introuvable.")
            c.expenseRepository.correctExpense(
                row.id,
                ExpenseRequest(
                    UUID.randomUUID().toString(), newAmount, original.categoryId, original.reason, original.expenseDate,
                    original.description, original.beneficiary, original.attachmentPath, acceptOverdraft = force,
                ),
                reason,
            )
        }
    }
}
