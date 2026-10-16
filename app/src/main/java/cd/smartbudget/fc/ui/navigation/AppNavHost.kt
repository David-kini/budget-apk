package cd.smartbudget.fc.ui.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.List
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Menu
import androidx.compose.material.icons.filled.Star
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import cd.smartbudget.fc.ui.categories.CategoriesScreen
import cd.smartbudget.fc.ui.categories.CategoryDetailScreen
import cd.smartbudget.fc.ui.categories.PercentagesScreen
import cd.smartbudget.fc.ui.dashboard.DashboardScreen
import cd.smartbudget.fc.ui.history.HistoryScreen
import cd.smartbudget.fc.ui.more.AboutScreen
import cd.smartbudget.fc.ui.more.MoreScreen
import cd.smartbudget.fc.ui.more.SavingsScreen
import cd.smartbudget.fc.ui.more.SettingsScreen
import cd.smartbudget.fc.ui.operations.NewExpenseScreen
import cd.smartbudget.fc.ui.operations.NewIncomeScreen
import cd.smartbudget.fc.ui.operations.NewTransferScreen
import cd.smartbudget.fc.ui.operations.TransfersScreen

object Routes {
    const val DASHBOARD = "dashboard"
    const val HISTORY = "history"
    const val CATEGORIES = "categories"
    const val MORE = "more"
    const val NEW_INCOME = "income/new"
    const val NEW_EXPENSE = "expense/new?categoryId={categoryId}"
    const val CATEGORY = "category/{categoryId}"
    const val PERCENTAGES = "percentages"
    const val TRANSFERS = "transfers"
    const val NEW_TRANSFER = "transfer/new?fromId={fromId}"
    const val SAVINGS = "savings"
    const val SETTINGS = "settings"
    const val ABOUT = "about"

    fun newExpense(categoryId: Long? = null) = "expense/new?categoryId=${categoryId ?: -1}"
    fun category(id: Long) = "category/$id"
    fun newTransfer(fromId: Long? = null) = "transfer/new?fromId=${fromId ?: -1}"
}

private data class Tab(val route: String, val label: String, val icon: ImageVector)

private val tabs = listOf(
    Tab(Routes.DASHBOARD, "Accueil", Icons.Filled.Home),
    Tab(Routes.HISTORY, "Historique", Icons.AutoMirrored.Filled.List),
    Tab(Routes.CATEGORIES, "Catégories", Icons.Filled.Star),
    Tab(Routes.MORE, "Plus", Icons.Filled.Menu),
)

@Composable
fun AppNavHost(navController: NavHostController = rememberNavController()) {
    val backStack by navController.currentBackStackEntryAsState()
    val currentRoute = backStack?.destination?.route
    val showBar = tabs.any { it.route == currentRoute }

    Scaffold(
        bottomBar = {
            if (showBar) {
                NavigationBar {
                    tabs.forEach { tab ->
                        NavigationBarItem(
                            selected = currentRoute == tab.route,
                            onClick = {
                                navController.navigate(tab.route) {
                                    popUpTo(navController.graph.findStartDestination().id) { saveState = true }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = { Icon(tab.icon, contentDescription = tab.label) },
                            label = { Text(tab.label, maxLines = 1, softWrap = false, style = androidx.compose.material3.MaterialTheme.typography.labelSmall) },
                        )
                    }
                }
            }
        },
    ) { padding ->
        val nav = navController
        val back: () -> Unit = { nav.popBackStack() }
        NavHost(navController = nav, startDestination = Routes.DASHBOARD, modifier = Modifier.padding(bottom = padding.calculateBottomPadding())) {
            composable(Routes.DASHBOARD) {
                DashboardScreen(
                    onNewIncome = { nav.navigate(Routes.NEW_INCOME) },
                    onNewExpense = { nav.navigate(Routes.newExpense()) },
                    onOpenCategory = { nav.navigate(Routes.category(it)) },
                    onOpenHistory = { nav.navigate(Routes.HISTORY) },
                )
            }
            composable(Routes.HISTORY) { HistoryScreen() }
            composable(Routes.CATEGORIES) {
                CategoriesScreen(
                    onOpenCategory = { nav.navigate(Routes.category(it)) },
                    onEditPercentages = { nav.navigate(Routes.PERCENTAGES) },
                )
            }
            composable(Routes.MORE) {
                MoreScreen(
                    onTransfers = { nav.navigate(Routes.TRANSFERS) },
                    onSavings = { nav.navigate(Routes.SAVINGS) },
                    onPercentages = { nav.navigate(Routes.PERCENTAGES) },
                    onSettings = { nav.navigate(Routes.SETTINGS) },
                    onAbout = { nav.navigate(Routes.ABOUT) },
                )
            }
            composable(Routes.NEW_INCOME) { NewIncomeScreen(onDone = back) }
            composable(
                Routes.NEW_EXPENSE,
                arguments = listOf(navArgument("categoryId") { type = NavType.LongType; defaultValue = -1L }),
            ) { entry ->
                val id = entry.arguments?.getLong("categoryId")?.takeIf { it > 0 }
                NewExpenseScreen(initialCategoryId = id, onDone = back)
            }
            composable(
                Routes.CATEGORY,
                arguments = listOf(navArgument("categoryId") { type = NavType.LongType }),
            ) { entry ->
                CategoryDetailScreen(
                    categoryId = entry.arguments?.getLong("categoryId") ?: 0L,
                    onBack = back,
                    onNewExpense = { nav.navigate(Routes.newExpense(it)) },
                    onTransferFrom = { nav.navigate(Routes.newTransfer(it)) },
                    onEditPercentages = { nav.navigate(Routes.PERCENTAGES) },
                )
            }
            composable(Routes.PERCENTAGES) { PercentagesScreen(onBack = back) }
            composable(Routes.TRANSFERS) { TransfersScreen(onBack = back, onNewTransfer = { nav.navigate(Routes.newTransfer()) }) }
            composable(
                Routes.NEW_TRANSFER,
                arguments = listOf(navArgument("fromId") { type = NavType.LongType; defaultValue = -1L }),
            ) { entry ->
                NewTransferScreen(initialFromId = entry.arguments?.getLong("fromId")?.takeIf { it > 0 }, onDone = back)
            }
            composable(Routes.SAVINGS) { SavingsScreen(onBack = back, onOpenCategory = { nav.navigate(Routes.category(it)) }) }
            composable(Routes.SETTINGS) { SettingsScreen(onBack = back) }
            composable(Routes.ABOUT) { AboutScreen(onBack = back) }
        }
    }
}
