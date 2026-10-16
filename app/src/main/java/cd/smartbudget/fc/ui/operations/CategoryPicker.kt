package cd.smartbudget.fc.ui.operations

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.selectable
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import cd.smartbudget.fc.data.db.entity.CategoryBalanceRow
import cd.smartbudget.fc.ui.components.CategoryDot
import cd.smartbudget.fc.ui.components.MoneyText

/** Liste de catégories sélectionnables, avec leur solde actuel. */
@Composable
fun CategoryPicker(
    categories: List<CategoryBalanceRow>,
    selectedId: Long?,
    onSelect: (Long) -> Unit,
    modifier: Modifier = Modifier,
) {
    Column(modifier) {
        categories.forEach { c ->
            Row(
                Modifier.fillMaxWidth()
                    .selectable(selected = c.categoryId == selectedId, onClick = { onSelect(c.categoryId) }, role = Role.RadioButton)
                    .padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                RadioButton(selected = c.categoryId == selectedId, onClick = null)
                Spacer(Modifier.width(8.dp))
                CategoryDot(c.colorArgb)
                Spacer(Modifier.width(8.dp))
                Text(c.name, modifier = Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                MoneyText(c.balance(), style = MaterialTheme.typography.bodyMedium)
            }
        }
    }
}
