package com.agoria.app

import android.app.Application
import com.agoria.app.core.AgentRuntime
import com.agoria.app.core.BuildRunner
import com.agoria.app.core.ConnectorExecutor
import com.agoria.app.core.llm.LlmClient
import com.agoria.app.data.AppDatabase
import com.agoria.app.data.Repository
import com.agoria.app.util.Notifier
import com.agoria.app.util.UpdateManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

class AgoriaApplication : Application() {

    lateinit var database: AppDatabase
        private set

    lateinit var repository: Repository
        private set

    lateinit var runtime: AgentRuntime
        private set

    lateinit var llm: LlmClient
        private set

    lateinit var appScope: CoroutineScope
        private set

    lateinit var updateManager: UpdateManager
        private set

    override fun onCreate() {
        super.onCreate()
        Notifier.ensureChannels(this)
        com.agoria.app.browser.BrowserHub.init(this)
        // DATA-4: open the database off the main thread so migration work doesn't block startup.
        kotlinx.coroutines.GlobalScope.launch(kotlinx.coroutines.Dispatchers.IO) {
            database = AppDatabase.create(this@AgoriaApplication)
            repository = Repository(database, this@AgoriaApplication)
            appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

            val llm = LlmClient(repository)
            this.llm = llm
            val buildRunner = BuildRunner(repository, llm, this@AgoriaApplication)
            val connectorExecutor = ConnectorExecutor(repository, this@AgoriaApplication)
            runtime = AgentRuntime(
                repo = repository,
                llm = llm,
                buildRunner = buildRunner,
                connectorExecutorInstance = connectorExecutor,
                context = this@AgoriaApplication,
                scope = appScope,
            )

            appScope.launch {
                repository.seedIfNeeded()
                // One full snapshot a day, so no upgrade can ever leave the office with nothing.
                repository.writeAutoBackup()
                // DATA-2: prune stale logs, messages, approvals and diary on startup.
                repository.runRetentionJob()
            }

            updateManager = UpdateManager(this@AgoriaApplication, appScope)
        }
    }
}