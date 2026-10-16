package cd.smartbudget.fc

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import cd.smartbudget.fc.data.db.DatabaseSeeder
import cd.smartbudget.fc.ui.navigation.AppNavHost
import cd.smartbudget.fc.ui.theme.SmartBudgetTheme
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        val container = (application as SmartBudgetApp).container
        setContent {
            SmartBudgetTheme {
                Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.background) {
                    var state by remember { mutableStateOf<StartupState>(StartupState.Loading) }
                    LaunchedEffect(Unit) {
                        state = try {
                            withContext(Dispatchers.IO) {
                                // Ouvre la base chiffrée et crée les catégories par défaut au premier lancement.
                                DatabaseSeeder.seedIfEmpty(container.database)
                            }
                            StartupState.Ready
                        } catch (e: Exception) {
                            StartupState.Failed(e.javaClass.simpleName)
                        }
                    }
                    when (val s = state) {
                        StartupState.Loading -> SplashScreen(null)
                        is StartupState.Failed -> SplashScreen(s.reason)
                        StartupState.Ready -> AppNavHost()
                    }
                }
            }
        }
    }
}

private sealed interface StartupState {
    data object Loading : StartupState
    data object Ready : StartupState
    data class Failed(val reason: String) : StartupState
}

/** Écran de démarrage (et d'erreur d'ouverture de la base). */
@Composable
private fun SplashScreen(error: String?) {
    Column(
        Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("SmartBudget FC", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.primary)
        Text("Gestion financière personnelle", style = MaterialTheme.typography.bodyMedium)
        Spacer(Modifier.height(32.dp))
        if (error == null) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Ouverture de vos données chiffrées…", style = MaterialTheme.typography.bodySmall)
        } else {
            Text(
                "Impossible d'ouvrir la base de données ($error). Vos données ne sont pas supprimées. " +
                    "Redémarrez l'application ; si le problème persiste, restaurez une sauvegarde.",
                color = MaterialTheme.colorScheme.error,
                textAlign = TextAlign.Center,
            )
        }
    }
}
