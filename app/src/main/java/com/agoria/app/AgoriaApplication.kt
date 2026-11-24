package com.agoria.app

import android.app.Application
import com.agoria.app.core.AgentRuntime
import com.agoria.app.core.BuildRunner
import com.agoria.app.core.ConnectorExecutor
import com.agoria.app.core.SkillExecutor
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
        database = AppDatabase.create(this)
        repository = Repository(database, this)
        appScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

        val llm = LlmClient(repository)
        this.llm = llm
        val buildRunner = BuildRunner(repository, llm, this)
        val connectorExecutor = ConnectorExecutor(repository, this)
        val skillExecutor = SkillExecutor(repository, this)
        runtime = AgentRuntime(
            repo = repository,
            llm = llm,
            buildRunner = buildRunner,
            connectorExecutorInstance = connectorExecutor,
            skillExecutorInstance = skillExecutor,
            context = this,
            scope = appScope,
        )

        appScope.launch {
            repository.seedIfNeeded()
            // One full snapshot a day, so no upgrade can ever leave the office with nothing.
            repository.writeAutoBackup()
        }

        updateManager = UpdateManager(this, appScope)
    }
}