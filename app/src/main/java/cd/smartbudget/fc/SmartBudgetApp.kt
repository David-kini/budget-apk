package cd.smartbudget.fc

import android.app.Application
import cd.smartbudget.fc.di.AppContainer

class SmartBudgetApp : Application() {
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}
