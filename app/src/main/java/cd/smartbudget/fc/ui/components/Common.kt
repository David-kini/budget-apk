@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package cd.smartbudget.fc.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.DateRange
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.DatePicker
import androidx.compose.material3.DatePickerDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.SelectableDates
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.rememberDatePickerState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cd.smartbudget.fc.core.DateRange
import cd.smartbudget.fc.core.Dates
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.PeriodFilter
import cd.smartbudget.fc.domain.Confirmation
import cd.smartbudget.fc.domain.OperationResult

// ---------------------------------------------------------------- Textes et montants

@Composable
fun MoneyText(
    amount: Long,
    modifier: Modifier = Modifier,
    style: TextStyle = MaterialTheme.typography.bodyLarge,
    highlightNegative: Boolean = true,
    fontWeight: FontWeight? = FontWeight.SemiBold,
) {
    Text(
        Money.format(amount),
        modifier = modifier,
        style = style,
        fontWeight = fontWeight,
        color = if (highlightNegative && amount < 0) MaterialTheme.colorScheme.error else Color.Unspecified,
    )
}

@Composable
fun CategoryDot(colorArgb: Long, size: Int = 12) {
    Box(Modifier.size(size.dp).background(Color(colorArgb.toInt()), CircleShape))
}

@Composable
fun SectionHeader(title: String, modifier: Modifier = Modifier) {
    Text(
        title,
        style = MaterialTheme.typography.titleMedium,
        fontWeight = FontWeight.SemiBold,
        modifier = modifier.padding(top = 16.dp, bottom = 8.dp),
    )
}

@Composable
fun EmptyState(text: String, modifier: Modifier = Modifier) {
    Text(
        text,
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = modifier.fillMaxWidth().padding(24.dp),
    )
}

@Composable
fun BackTopBar(title: String, onBack: () -> Unit, actions: @Composable () -> Unit = {}) {
    TopAppBar(
        title = { Text(title) },
        navigationIcon = {
            IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Retour") }
        },
        actions = { actions() },
    )
}

// ---------------------------------------------------------------- Champs de saisie

@Composable
fun AmountField(
    value: String,
    onValueChange: (String) -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    error: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = { new -> onValueChange(new.filter { it.isDigit() || it == ',' || it == '.' || it == ' ' }) },
        label = { Text(label) },
        suffix = { Text("FC") },
        singleLine = true,
        isError = error != null,
        supportingText = error?.let { { Text(it) } },
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Decimal),
        modifier = modifier.fillMaxWidth(),
    )
}

/** Champ de date (sélecteur Material). Les dates postérieures à [maxDay] ne sont pas sélectionnables. */
@Composable
fun DateField(
    epochDay: Long,
    onChange: (Long) -> Unit,
    label: String,
    maxDay: Long? = null,
    modifier: Modifier = Modifier,
) {
    var open by remember { mutableStateOf(false) }
    Box(modifier.fillMaxWidth()) {
        OutlinedTextField(
            value = Dates.format(epochDay),
            onValueChange = {},
            readOnly = true,
            label = { Text(label) },
            trailingIcon = { Icon(Icons.Filled.DateRange, contentDescription = null) },
            modifier = Modifier.fillMaxWidth(),
        )
        // Couche cliquable au-dessus du champ en lecture seule.
        Box(Modifier.matchParentSize().clickable { open = true })
    }
    if (open) {
        val maxMillis = maxDay?.let { Dates.pickerMillisFromEpochDay(it) }
        val state = rememberDatePickerState(
            initialSelectedDateMillis = Dates.pickerMillisFromEpochDay(epochDay),
            selectableDates = object : SelectableDates {
                override fun isSelectableDate(utcTimeMillis: Long): Boolean = maxMillis == null || utcTimeMillis <= maxMillis
            },
        )
        DatePickerDialog(
            onDismissRequest = { open = false },
            confirmButton = {
                TextButton(onClick = {
                    state.selectedDateMillis?.let { onChange(Dates.epochDayFromPickerMillis(it)) }
                    open = false
                }) { Text("Valider") }
            },
            dismissButton = { TextButton(onClick = { open = false }) { Text("Annuler") } },
        ) {
            DatePicker(state = state)
        }
    }
}

// ---------------------------------------------------------------- Dialogues

@Composable
fun ConfirmDialog(
    title: String,
    text: String,
    confirmLabel: String,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
    dismissLabel: String = "Annuler",
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { Text(text) },
        confirmButton = { TextButton(onClick = onConfirm) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text(dismissLabel) } },
    )
}

/** Demande un motif (annulation, correction). */
@Composable
fun ReasonDialog(title: String, explanation: String, confirmLabel: String, onConfirm: (String) -> Unit, onDismiss: () -> Unit) {
    var reason by rememberSaveable { mutableStateOf("") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column {
                Text(explanation, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.height(12.dp))
                OutlinedTextField(
                    value = reason,
                    onValueChange = { reason = it.take(120) },
                    label = { Text("Motif (obligatoire)") },
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        },
        confirmButton = { TextButton(onClick = { onConfirm(reason) }, enabled = reason.isNotBlank()) { Text(confirmLabel) } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

// ---------------------------------------------------------------- Période

@Composable
fun PeriodSelector(
    selected: PeriodFilter,
    custom: DateRange?,
    today: Long,
    onSelect: (PeriodFilter, DateRange?) -> Unit,
    modifier: Modifier = Modifier,
) {
    var askCustom by remember { mutableStateOf(false) }
    FlowRow(modifier = modifier, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        PeriodFilter.entries.forEach { p ->
            FilterChip(
                selected = p == selected,
                onClick = { if (p == PeriodFilter.CUSTOM) askCustom = true else onSelect(p, null) },
                label = {
                    Text(
                        if (p == PeriodFilter.CUSTOM && selected == PeriodFilter.CUSTOM && custom != null) {
                            "${Dates.format(custom.fromDay)} → ${Dates.format(custom.toDay)}"
                        } else p.label
                    )
                },
            )
        }
    }
    if (askCustom) {
        val initial = custom ?: PeriodFilter.MONTH.range(today)
        var from by remember { mutableStateOf(initial.fromDay) }
        var to by remember { mutableStateOf(initial.toDay) }
        AlertDialog(
            onDismissRequest = { askCustom = false },
            title = { Text("Période personnalisée") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    DateField(from, { from = it }, "Du", maxDay = today)
                    DateField(to, { to = it }, "Au", maxDay = today)
                    if (from > to) Text("La date de début doit précéder la date de fin.", color = MaterialTheme.colorScheme.error)
                }
            },
            confirmButton = {
                TextButton(enabled = from <= to, onClick = {
                    onSelect(PeriodFilter.CUSTOM, DateRange(from, to))
                    askCustom = false
                }) { Text("Appliquer") }
            },
            dismissButton = { TextButton(onClick = { askCustom = false }) { Text("Annuler") } },
        )
    }
}

// ---------------------------------------------------------------- Résultats

/** Message lisible pour un résultat d'opération. */
fun OperationResult.userMessage(): String = when (this) {
    is OperationResult.Recorded -> message
    is OperationResult.AlreadyRecorded -> "Cette opération était déjà enregistrée : aucun doublon n'a été créé."
    is OperationResult.Rejected -> message
    is OperationResult.NeedsConfirmation -> when (val c = confirmation) {
        is Confirmation.Overdraft -> "Confirmation requise : dépassement de ${Money.format(c.shortfall)}."
        is Confirmation.CoverByTransfer -> "Choisissez une catégorie pour couvrir ${Money.format(c.shortfall)}."
        is Confirmation.NegativeBalances -> "Cette opération rendrait des soldes négatifs."
    }
}

@Composable
fun LabeledValue(label: String, modifier: Modifier = Modifier, value: @Composable () -> Unit) {
    Row(modifier.fillMaxWidth().padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
        Text(label, style = MaterialTheme.typography.bodyMedium, modifier = Modifier.weight(1f))
        value()
    }
}
