@file:OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)

package cd.smartbudget.fc.ui.categories

import android.widget.Toast
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.KeyboardArrowUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Card
import androidx.compose.material3.Checkbox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExtendedFloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.background
import androidx.lifecycle.ViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewModelScope
import cd.smartbudget.fc.core.Percent
import cd.smartbudget.fc.data.db.entity.CategoryBalanceRow
import cd.smartbudget.fc.data.repo.CategoryRepository
import cd.smartbudget.fc.domain.OperationResult
import cd.smartbudget.fc.ui.appViewModel
import cd.smartbudget.fc.ui.components.CategoryDot
import cd.smartbudget.fc.ui.components.EmptyState
import cd.smartbudget.fc.ui.components.MoneyText
import cd.smartbudget.fc.ui.components.userMessage
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

val CATEGORY_COLORS = listOf(
    0xFF00897BL, 0xFF1E88E5L, 0xFF8E24AAL, 0xFF43A047L, 0xFFFB8C00L,
    0xFFE53935L, 0xFF6D4C41L, 0xFF546E7AL, 0xFFD81B60L, 0xFFFDD835L, 0xFF3949ABL, 0xFF00ACC1L,
)

data class CategoryListItem(val balance: CategoryBalanceRow, val basisPoints: Int)

class CategoriesViewModel(private val repo: CategoryRepository) : ViewModel() {
    val items: StateFlow<List<CategoryListItem>> =
        combine(repo.observeBalances(), repo.observeCurrentShares()) { balances, shares ->
            val bp = shares.associate { it.categoryId to it.basisPoints }
            balances.map { CategoryListItem(it, bp[it.categoryId] ?: 0) }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    suspend fun add(name: String, color: Long, savings: Boolean) = repo.addCategory(name, color, savings)

    fun move(id: Long, up: Boolean, onResult: (OperationResult) -> Unit) {
        val ids = items.value.map { it.balance.categoryId }.toMutableList()
        val i = ids.indexOf(id)
        val j = if (up) i - 1 else i + 1
        if (i < 0 || j !in ids.indices) return
        ids[i] = ids[j].also { ids[j] = ids[i] }
        viewModelScope.launch { onResult(repo.reorder(ids)) }
    }
}

@Composable
fun CategoriesScreen(onOpenCategory: (Long) -> Unit, onEditPercentages: () -> Unit) {
    val vm = appViewModel { CategoriesViewModel(it.categoryRepository) }
    val items by vm.items.collectAsStateWithLifecycle()
    var adding by remember { mutableStateOf(false) }
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val total = items.sumOf { it.basisPoints }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = { Text("Catégories") },
                actions = { TextButton(onClick = onEditPercentages) { Text("Pourcentages") } },
            )
        },
        floatingActionButton = {
            ExtendedFloatingActionButton(onClick = { adding = true }, icon = { Icon(Icons.Filled.Add, null) }, text = { Text("Catégorie") })
        },
    ) { padding ->
        LazyColumn(Modifier.fillMaxSize().padding(padding), contentPadding = PaddingValues(16.dp, 8.dp, 16.dp, 96.dp)) {
            item {
                Text(
                    "Répartition active : ${Percent.format(total)}" + if (total != Percent.FULL) " — à corriger" else "",
                    style = MaterialTheme.typography.titleSmall,
                    color = if (total != Percent.FULL) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface,
                )
                Spacer(Modifier.height(8.dp))
            }
            if (items.isEmpty()) item { EmptyState("Aucune catégorie.") }
            items(items, key = { it.balance.categoryId }) { item ->
                val c = item.balance
                Card(Modifier.fillMaxWidth().padding(vertical = 4.dp).clickable { onOpenCategory(c.categoryId) }) {
                    Row(Modifier.padding(start = 14.dp, top = 8.dp, bottom = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                        CategoryDot(c.colorArgb, 14)
                        Spacer(Modifier.width(10.dp))
                        Column(Modifier.weight(1f)) {
                            Text(c.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                (if (c.isActive) Percent.format(item.basisPoints) else "Désactivée") + if (c.isSavings) " · épargne" else "",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        MoneyText(c.balance())
                        Column {
                            IconButton(onClick = { vm.move(c.categoryId, true) { r -> if (r !is OperationResult.Recorded) Toast.makeText(context, r.userMessage(), Toast.LENGTH_SHORT).show() } }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.KeyboardArrowUp, contentDescription = "Monter")
                            }
                            IconButton(onClick = { vm.move(c.categoryId, false) { r -> if (r !is OperationResult.Recorded) Toast.makeText(context, r.userMessage(), Toast.LENGTH_SHORT).show() } }, modifier = Modifier.size(36.dp)) {
                                Icon(Icons.Filled.KeyboardArrowDown, contentDescription = "Descendre")
                            }
                        }
                    }
                }
            }
        }
    }

    if (adding) {
        CategoryEditDialog(
            title = "Nouvelle catégorie",
            initialName = "", initialColor = CATEGORY_COLORS[items.size % CATEGORY_COLORS.size], initialSavings = false,
            onDismiss = { adding = false },
            onSave = { name, color, savings ->
                scope.launch {
                    val r = vm.add(name, color, savings)
                    Toast.makeText(context, r.userMessage(), Toast.LENGTH_LONG).show()
                    if (r is OperationResult.Recorded) adding = false
                }
            },
        )
    }
}

/** Dialogue de création / modification : nom, couleur, catégorie d'épargne. */
@Composable
fun CategoryEditDialog(
    title: String,
    initialName: String,
    initialColor: Long,
    initialSavings: Boolean,
    onDismiss: () -> Unit,
    onSave: (String, Long, Boolean) -> Unit,
) {
    var name by remember { mutableStateOf(initialName) }
    var color by remember { mutableStateOf(initialColor) }
    var savings by remember { mutableStateOf(initialSavings) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                OutlinedTextField(name, { name = it.take(40) }, label = { Text("Nom") }, singleLine = true, modifier = Modifier.fillMaxWidth())
                Text("Couleur", style = MaterialTheme.typography.labelLarge)
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    CATEGORY_COLORS.forEach { c ->
                        Box(
                            Modifier.size(32.dp).clip(CircleShape).background(Color(c.toInt()))
                                .border(if (c == color) 3.dp else 0.dp, MaterialTheme.colorScheme.onSurface, CircleShape)
                                .clickable { color = c }
                        )
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.clickable { savings = !savings }) {
                    Checkbox(checked = savings, onCheckedChange = { savings = it })
                    Text("Compter comme épargne")
                }
            }
        },
        confirmButton = { TextButton(enabled = name.isNotBlank(), onClick = { onSave(name, color, savings) }) { Text("Enregistrer") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Annuler") } },
    )
}

@Composable
fun SmallEditButton(onClick: () -> Unit) {
    OutlinedButton(onClick = onClick) {
        Icon(Icons.Filled.Edit, contentDescription = null, modifier = Modifier.size(18.dp))
        Spacer(Modifier.width(6.dp))
        Text("Modifier")
    }
}
