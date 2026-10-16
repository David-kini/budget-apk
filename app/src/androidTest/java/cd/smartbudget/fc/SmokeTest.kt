package cd.smartbudget.fc

import android.graphics.Bitmap
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.captureToImage
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import cd.smartbudget.fc.core.Money
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * Test de bout en bout sur un vrai Android (émulateur) :
 * base SQLCipher + Keystore réels, saisie d'une entrée et d'une dépense par l'interface,
 * vérification des soldes affichés, captures d'écran de chaque écran principal.
 */
@OptIn(ExperimentalTestApi::class)
@RunWith(AndroidJUnit4::class)
class SmokeTest {

    @get:Rule
    val rule = createAndroidComposeRule<MainActivity>()

    private val shotsDir: File by lazy {
        val ctx = InstrumentationRegistry.getInstrumentation().targetContext
        File(ctx.getExternalFilesDir(null), "screens").apply { mkdirs() }
    }

    private fun shot(name: String) {
        rule.waitForIdle()
        val bitmap = rule.onRoot().captureToImage().asAndroidBitmap()
        File(shotsDir, "$name.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
    }

    private fun waitForText(text: String, timeout: Long = 20_000) {
        rule.waitUntilAtLeastOneExists(hasText(text), timeout)
    }

    @Test
    fun record_income_and_expense_through_the_ui() {
        waitForText("Solde disponible total")
        shot("01_tableau_de_bord_vide")

        // Nouvelle entrée de 100 000 FC
        rule.onNodeWithText("Entrée", useUnmergedNode = true).performClick()
        waitForText("Montant reçu")
        rule.onNodeWithText("Montant reçu").performTextInput("100000")
        waitForText("Répartition automatique")
        shot("02_nouvelle_entree")
        rule.onNodeWithText("Enregistrer et répartir", useUnmergedNode = true).performScrollTo().performClick()

        waitForText("Solde disponible total")
        waitForText(Money.format(Money.fromFc(30_000))) // Épargne : 30 %
        shot("03_tableau_de_bord_apres_entree")

        // Dépense de 5 000 FC dans « Petites dépenses »
        rule.onNodeWithText("Dépense", useUnmergedNode = true).performClick()
        waitForText("Nouvelle dépense")
        rule.onNodeWithText("Montant").performTextInput("5000")
        rule.onNodeWithText("Petites dépenses", useUnmergedNode = true).performScrollTo().performClick()
        rule.onNodeWithText("Motif").performScrollTo().performTextInput("Pain et sucre")
        shot("04_nouvelle_depense")
        rule.onNodeWithText("Enregistrer la dépense", useUnmergedNode = true).performScrollTo().performClick()

        waitForText("Solde disponible total")
        waitForText(Money.format(Money.fromFc(95_000)))
        waitForText(Money.format(Money.fromFc(15_000)))
        shot("05_tableau_de_bord_apres_depense")

        // Historique
        rule.onNodeWithText("Historique", useUnmergedNode = true).performClick()
        waitForText("Historique des opérations")
        waitForText("Pain et sucre")
        shot("06_historique")

        // Catégories
        rule.onNodeWithText("Catégories", useUnmergedNode = true).performClick()
        waitForText("Répartition active : 100 %")
        shot("07_categories")
        rule.onNodeWithText("Pourcentages", useUnmergedNode = true).performClick()
        waitForText("Répartition des entrées")
        shot("08_pourcentages")

        // Vérification finale en base (soldes calculés)
        val db = (rule.activity.application as SmartBudgetApp).container.database
        val total = kotlinx.coroutines.runBlocking { db.balanceDao().getBalances().sumOf { it.balance() } }
        assertEquals(Money.fromFc(95_000), total)
        assertEquals(1, rule.onAllNodesWithText("Total : 100 %").fetchSemanticsNodes().size)
    }
}
