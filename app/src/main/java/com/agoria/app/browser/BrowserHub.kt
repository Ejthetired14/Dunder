package com.agoria.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.os.Handler
import android.os.Looper
import android.webkit.CookieManager
import android.webkit.WebResourceError
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * One browser per agent.
 *
 * Every agent gets its own [WebView], kept off screen. Pages load, JavaScript runs and the office
 * cookie jar is used, but a browsing agent never covers the user's screen. Opening it from the
 * Browsers screen shows the same page in a visible window.
 *
 * The cookie jar is shared by the whole office on purpose: the user signs in once, inside the
 * office browser, with the account the agents should work as. Nothing here reads the accounts
 * signed in to the phone, and the app never sees a password.
 */
object BrowserHub {

    data class Session(
        val agentId: String,
        val agentName: String,
        val url: String,
        val title: String,
        val loading: Boolean,
        val error: String?,
        val updatedAt: Long,
    )

    private val main = Handler(Looper.getMainLooper())
    private val views = mutableMapOf<String, WebView>()

    private var appContext: Context? = null

    private val _sessions = MutableStateFlow<List<Session>>(emptyList())
    val sessions: StateFlow<List<Session>> = _sessions.asStateFlow()

    /** The agent whose browser is currently shown full screen, if any. */
    private val _viewing = MutableStateFlow<String?>(null)
    val viewing: StateFlow<String?> = _viewing.asStateFlow()

    fun init(context: Context) {
        appContext = context.applicationContext
    }

    fun session(agentId: String): Session? = _sessions.value.firstOrNull { it.agentId == agentId }

    /**
     * Loads [url] in this agent's own browser. [show] opens it on screen; the default is to stay in
     * the background so the agent can work while the user does something else.
     */
    fun open(agentId: String, agentName: String, url: String, show: Boolean = false): Boolean {
        val clean = url.trim()
        if (!clean.startsWith("http://") && !clean.startsWith("https://")) return false
        update(agentId, agentName) { it.copy(url = clean, loading = true, error = null) }
        if (show) _viewing.value = agentId
        val task = Runnable {
            try {
                val view = views[agentId] ?: create(agentId).also { views[agentId] = it }
                view.loadUrl(clean)
            } catch (e: Throwable) {
                update(agentId, agentName) {
                    it.copy(loading = false, error = e.message ?: e.javaClass.simpleName)
                }
            }
        }
        if (Looper.myLooper() == Looper.getMainLooper()) task.run() else main.post(task)
        return true
    }

    fun show(agentId: String) {
        if (session(agentId) != null) _viewing.value = agentId
    }

    fun stopViewing() {
        _viewing.value = null
    }

    fun reload(agentId: String) {
        val view = views[agentId] ?: return
        main.post { view.reload() }
    }

    /** Runs [onText] with the visible text of the agent's current page, or null if it has none. */
    fun readPage(agentId: String, onText: (String?) -> Unit) {
        val view = views[agentId]
        if (view == null) {
            onText(null)
            return
        }
        main.post {
            try {
                view.evaluateJavascript(
                    "(function(){var b=document.body;return b?b.innerText:''})()",
                ) { value ->
                    val text = value
                        ?.removeSurrounding("\"")
                        ?.replace("\\n", "\n")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\u003C", "<")
                    onText(text?.take(4000))
                }
            } catch (_: Throwable) {
                onText(null)
            }
        }
    }

    /** Executes arbitrary JavaScript in the agent's WebView and returns the result via [onResult]. */
    fun executeScript(agentId: String, script: String, onResult: (String?) -> Unit) {
        val view = views[agentId]
        if (view == null) {
            onResult(null)
            return
        }
        main.post {
            try {
                view.evaluateJavascript(script) { value ->
                    val result = value
                        ?.removeSurrounding("\"")
                        ?.replace("\\n", "\n")
                        ?.replace("\\\"", "\"")
                        ?.replace("\\u003C", "<")
                    onResult(result)
                }
            } catch (e: Throwable) {
                onResult("Error: ${e.message}")
            }
        }
    }

    fun close(agentId: String) {
        val view = views.remove(agentId) ?: return
        if (_viewing.value == agentId) _viewing.value = null
        _sessions.value = _sessions.value.filterNot { it.agentId == agentId }
        main.post {
            try {
                view.stopLoading()
                view.loadUrl("about:blank")
                view.destroy()
            } catch (_: Throwable) {
                Unit
            }
        }
    }

    fun closeAll() {
        views.keys.toList().forEach { close(it) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun create(agentId: String): WebView {
        val context = appContext ?: throw IllegalStateException("BrowserHub is not initialised")
        val view = WebView(context)
        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)
            userAgentString = userAgentString.replace("; wv", "")
        }
        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(view, true)
        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = false

            override fun onPageFinished(view: WebView, url: String?) {
                update(agentId, null) {
                    it.copy(
                        url = url ?: it.url,
                        title = view.title.orEmpty(),
                        loading = false,
                    )
                }
                runCatching { CookieManager.getInstance().flush() }
            }

            override fun onReceivedError(
                view: WebView,
                request: WebResourceRequest,
                error: WebResourceError?,
            ) {
                if (!request.isForMainFrame) return
                update(agentId, null) {
                    it.copy(
                        loading = false,
                        error = error?.description?.toString() ?: "page failed to load",
                    )
                }
            }
        }
        return view
    }

    private fun update(agentId: String, agentName: String?, block: (Session) -> Session) {
        val now = System.currentTimeMillis()
        val existing = _sessions.value.firstOrNull { it.agentId == agentId }
        val base = existing ?: Session(
            agentId = agentId,
            agentName = agentName.orEmpty(),
            url = "",
            title = "",
            loading = false,
            error = null,
            updatedAt = now,
        )
        val renamed = if (agentName.isNullOrBlank()) base else base.copy(agentName = agentName)
        val next = block(renamed).copy(updatedAt = now)
        _sessions.value = _sessions.value.filterNot { it.agentId == agentId } + next
    }
}
