package cd.smartbudget.fc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.Card
import androidx.compose.material3.CenterAlignedTopAppBar
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import cd.smartbudget.fc.core.Money
import cd.smartbudget.fc.core.Percent
import cd.smartbudget.fc.data.db.DatabaseSeeder
import cd.smartbudget.fc.ui.theme.SmartBudgetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Étape 2 : écran PROVISOIRE de vérification de la base de données (données réelles SQLite).
 * Il sera remplacé à l'étape 4 par la navigation complète (tableau de bord, entrées, dépenses…).
 */
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val db = (application as SmartBudgetApp).container.database
        setContent {
            SmartBudgetTheme {
                DatabaseStatusScreen(db)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun DatabaseStatusScreen(db: cd.smartbudget.fc.data.db.AppDatabase) {
    var status by remember { mutableStateOf("Initialisation de la base chiffrée…") }
    LaunchedEffect(Unit) {
        status = try {
            val created = withContext(Dispatchers.IO) { DatabaseSeeder.seedIfEmpty(db) }
            if (created) "Base créée avec les catégories par défaut" else "Base chiffrée ouverte"
        } catch (e: Exception) {
            "Erreur d'ouverture de la base : ${e.javaClass.simpleName}"
        }
    }
    val shares by db.budgetConfigurationDao().observeCurrentShares().collectAsStateWithLifecycle(emptyList())
    val balances by db.balanceDao().observeBalances().collectAsStateWithLifecycle(emptyList())
    val balanceById = balances.associateBy { it.categoryId }

    Scaffold(
        topBar = { CenterAlignedTopAppBar(title = { Text("SmartBudget FC") }) },
    ) { padding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(padding).padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text(status, style = MaterialTheme.typography.bodyMedium)
                Spacer(Modifier.padding(4.dp))
                Text(
                    "Répartition active : ${Percent.format(shares.sumOf { it.basisPoints })}",
                    style = MaterialTheme.typography.titleMedium,
                )
            }
            items(shares, key = { it.categoryId }) { share ->
                Card(Modifier.fillMaxWidth()) {
                    Row(Modifier.padding(16.dp), verticalAlignment = Alignment.CenterVertically) {
                        Box(Modifier.size(14.dp).background(Color(share.colorArgb.toInt()), CircleShape))
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(share.name, fontWeight = FontWeight.SemiBold)
                            Text(
                                "Solde : ${Money.format(balanceById[share.categoryId]?.balance() ?: 0L)}",
                                style = MaterialTheme.typography.bodySmall,
                            )
                        }
                        Text(Percent.format(share.basisPoints), style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
            item {
                Text(
                    "Version de développement — étape 2 (base de données). Les écrans complets arrivent à l'étape 4.",
                    style = MaterialTheme.typography.bodySmall,
                    modifier = Modifier.padding(vertical = 16.dp),
                )
            }
        }
    }
}
