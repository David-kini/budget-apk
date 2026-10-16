package cd.smartbudget.fc.ui.components

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.data.repo.CategoryAmount
import cd.smartbudget.fc.data.repo.MonthPoint

/** Anneau de répartition (dépenses par catégorie, ou budgets) avec légende. */
@Composable
fun DonutChart(items: List<CategoryAmount>, centerLabel: String, centerValue: Long, modifier: Modifier = Modifier) {
    val total = items.sumOf { it.amount.coerceAtLeast(0) }
    val track = MaterialTheme.colorScheme.surfaceVariant
    Row(modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(140.dp), contentAlignment = Alignment.Center) {
            Canvas(Modifier.size(140.dp)) {
                val stroke = 22.dp.toPx()
                val inset = stroke / 2
                val arcSize = Size(size.width - stroke, size.height - stroke)
                drawArc(track, 0f, 360f, false, Offset(inset, inset), arcSize, style = Stroke(stroke))
                if (total > 0) {
                    var start = -90f
                    items.filter { it.amount > 0 }.forEach { item ->
                        // Proportion calculée pour l'affichage uniquement (aucun calcul financier ici).
                        val sweep = (item.amount.toDouble() / total.toDouble() * 360.0).toFloat()
                        drawArc(Color(item.colorArgb.toInt()), start, (sweep - 1.5f).coerceAtLeast(0.5f), false,
                            Offset(inset, inset), arcSize, style = Stroke(stroke))
                        start += sweep
                    }
                }
            }
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text(centerLabel, style = MaterialTheme.typography.labelSmall)
                Text(Money.format(centerValue), style = MaterialTheme.typography.labelLarge, maxLines = 1)
            }
        }
        Spacer(Modifier.width(16.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            items.forEach { item ->
                Row(verticalAlignment = Alignment.CenterVertically) {
                    CategoryDot(item.colorArgb, 10)
                    Spacer(Modifier.width(8.dp))
                    Text(item.name, style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f),
                        maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(Money.format(item.amount), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    }
}

/** Barres mensuelles : entrées (vert) et dépenses (rouge). */
@Composable
fun MonthlyBars(points: List<MonthPoint>, modifier: Modifier = Modifier) {
    val incomeColor = Color(0xFF2E7D32)
    val expenseColor = Color(0xFFC62828)
    val max = points.maxOfOrNull { maxOf(it.income, it.expense) }?.coerceAtLeast(1) ?: 1
    Column(modifier.fillMaxWidth()) {
        Row(
            Modifier.fillMaxWidth().height(140.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.Bottom,
        ) {
            points.forEach { p ->
                Row(Modifier.weight(1f).fillMaxHeight(), horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.Bottom) {
                    Bar(p.income, max, incomeColor)
                    Spacer(Modifier.width(3.dp))
                    Bar(p.expense, max, expenseColor)
                }
            }
        }
        Row(Modifier.fillMaxWidth().padding(top = 4.dp)) {
            points.forEach { p ->
                Text(p.label, style = MaterialTheme.typography.labelSmall, modifier = Modifier.weight(1f),
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center, maxLines = 1)
            }
        }
        Row(Modifier.padding(top = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            Box(Modifier.size(10.dp).padding(0.dp)) { Canvas(Modifier.size(10.dp)) { drawRect(incomeColor) } }
            Text("  Entrées    ", style = MaterialTheme.typography.labelSmall)
            Box(Modifier.size(10.dp)) { Canvas(Modifier.size(10.dp)) { drawRect(expenseColor) } }
            Text("  Dépenses", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun Bar(value: Long, max: Long, color: Color) {
    val fraction = (value.toDouble() / max.toDouble()).toFloat().coerceIn(0f, 1f)
    Canvas(Modifier.width(10.dp).fillMaxHeight()) {
        val h = size.height * fraction
        if (h > 0f) {
            drawRoundRect(color, Offset(0f, size.height - h), Size(size.width, h), CornerRadius(3.dp.toPx()))
        }
    }
}
