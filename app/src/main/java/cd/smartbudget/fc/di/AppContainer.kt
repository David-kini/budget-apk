package cd.smartbudget.fc.di

import android.content.Context
import cd.smartbudget.fc.data.db.AppDatabase
import cd.smartbudget.fc.security.DatabaseKeyManager

/** Conteneur de dépendances de l'application (injection manuelle). */
class AppContainer(context: Context) {
    private val appContext = context.applicationContext

    val databaseKeyManager: DatabaseKeyManager by lazy { DatabaseKeyManager(appContext) }

    val database: AppDatabase by lazy {
        AppDatabase.build(appContext, databaseKeyManager.getOrCreatePassphrase())
    }
}
