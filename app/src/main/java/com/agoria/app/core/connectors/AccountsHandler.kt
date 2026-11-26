package com.agoria.app.core.connectors

import android.webkit.CookieManager
import com.agoria.app.browser.BrowserHub
import com.agoria.app.data.ConnectorEntity
import com.agoria.app.data.Repository
import com.agoria.app.util.Files
import com.agoria.app.util.Notifier
import com.agoria.app.util.Time
import org.json.JSONObject
import java.io.File
import java.net.URLDecoder
import java.time.Instant
import java.time.LocalDateTime
import java.time.OffsetDateTime
import java.time.ZoneId

/** Accounts ("Office Browser") connector — WebView-based sign-in and page interaction per agent. */

class AccountsHandler(
    private val repo: Repository,
    private val context: android.content.Context,
) : ConnectorHandler {
    override val connectorId = "accounts"
    override val description = "Office browser — sign in to mail, calendar and other account-based services. The app never sees passwords."
    override val risk = "medium"

    override fun describeActions(): String =
        "accounts actions:\n- list: Show browser session info.\n- signin: Open sign-in page.\n- email: Draft an email.\n- event: Add a calendar event.\n- open_url: Open a URL.\n- read: Read page text.\n- view: Show the agent's browser on screen.\n- click, fill, submit: Interact with page elements.\n- screenshot, extract, tab, download, wait, scroll: Page automation."

    override fun canonicalAction(action: String): String =
        when (action.trim().lowercase().replace('-', '_').replace(' ', '_')) {
            "list" -> "list"
            "signin" -> "signin"
            "email" -> "email"
            "event" -> "event"
            "open_url", "open", "browse" -> "open_url"
            "read" -> "read"
            "view" -> "view"
            "click" -> "click"
            "fill" -> "fill"
            "submit" -> "submit"
            "screenshot" -> "screenshot"
            "extract" -> "extract"
            "tab" -> "tab"
            "download" -> "download"
            "wait" -> "wait"
            "scroll" -> "scroll"
            else -> action.trim().lowercase()
        }

    override fun missingConfig(config: JSONObject?): String? = null

    override suspend fun execute(
        connector: ConnectorEntity,
        config: JSONObject?,
        action: String,
        args: JSONObject,
        agentId: String?,
    ): HandlerResult = when (action) {
        "list" -> browserSession(agentId)
        "signin" -> openInOfficeBrowser(
            agentId,
            connector,
            args.optString("url").trim().ifBlank { "https://accounts.google.com/ServiceLogin" },
            "Opened the office browser sign-in page for this agent. The session is kept for the office only.",
        )
        "email" -> draftEmail(agentId, connector, args)
        "event" -> addEvent(agentId, connector, args)
        "open_url", "open", "browse" -> openLink(agentId, connector, args)
        "read" -> readBrowser(agentId)
        "view" -> {
            val id = agentId ?: return HandlerResult(true, "Open Browsers to watch an agent's page.")
            BrowserHub.show(id)
            HandlerResult(true, "Showing this agent's browser on screen.")
        }
        "click" -> clickElement(agentId, connector, args)
        "fill" -> fillElement(agentId, connector, args)
        "submit" -> submitForm(agentId, connector, args)
        "screenshot" -> takeScreenshot(agentId, connector, args)
        "extract" -> extractData(agentId, connector, args)
        "tab" -> manageTab(agentId, connector, args)
        "download" -> downloadFile(agentId, connector, args)
        "wait" -> waitForCondition(agentId, connector, args)
        "scroll" -> scrollElement(agentId, connector, args)
        else -> HandlerResult(
            false,
            "Unknown Office Browser action '$action'. Use list_actions to see them: " +
                "list, signin, email, event, open_url, read, view, click, fill, submit, screenshot, extract, tab, download, wait, scroll.",
        )
    }

    private fun readBrowser(agentId: String?): HandlerResult {
        if (agentId == null) return HandlerResult(false, "This action needs an agent context.")
        var text: String? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        BrowserHub.readPage(agentId) { value ->
            text = value
            latch.countDown()
        }
        latch.await(4, java.util.concurrent.TimeUnit.SECONDS)
        val value = text?.trim()
        return if (value.isNullOrBlank()) {
            HandlerResult(
                true,
                "No page text to read yet. Open a page first with open_url; it loads in the background.",
            )
        } else {
            HandlerResult(true, value.take(3500))
        }
    }

    private suspend fun clickElement(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): HandlerResult {
        if (agentId == null) return HandlerResult(false, "This action needs an agent context.")
        val selector = args.optString("selector").trim().ifBlank { return HandlerResult(false, "Missing selector") }
        val textMatch = args.optString("text").trim()
        val waitForNav = args.optBoolean("waitForNavigation", true)

        val script = buildClickScript(selector, textMatch, waitForNav)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            val extra = textMatch.ifBlank { " ($selector)" } ?: ""
            val navMsg = if (waitForNav) " Waited for navigation." else ""
            HandlerResult(true, "Clicked element$extra.$navMsg")
        } else {
            HandlerResult(false, "Click failed: ${result.error}")
        }
    }

    private suspend fun fillElement(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): HandlerResult {
        if (agentId == null) return HandlerResult(false, "This action needs an agent context.")
        val selector = args.optString("selector").trim().ifBlank { return HandlerResult(false, "Missing selector") }
        val value = args.optString("value").ifBlank { return HandlerResult(false, "Missing value") }
        val isSelect = args.optBoolean("isSelect", false)

        val script = buildFillScript(selector, value, isSelect)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            val preview = value.take(100)
            HandlerResult(true, "Filled $selector with: $preview")
        } else {
            HandlerResult(false, "Fill failed: ${result.error}")
        }
    }

    private suspend fun submitForm(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): HandlerResult {
        if (agentId == null) return HandlerResult(false, "This action needs an agent context.")
        val selector = args.optString("selector").trim().ifBlank { return HandlerResult(false, "Missing selector") }
        val waitForNav = args.optBoolean("waitForNavigation", true)

        val script = buildSubmitScript(selector, waitForNav)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            val navMsg = if (waitForNav) " Waited for navigation." else ""
            HandlerResult(true, "Submitted form $selector.$navMsg")
        } else {
            HandlerResult(false, "Submit failed: ${result.error}")
        }
    }

    private suspend fun takeScreenshot(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): HandlerResult {
        if (agentId == null) return HandlerResult(false, "This action needs an agent context.")
        val fullPage = args.optBoolean("fullPage", false)

        val result = executeBrowserScript(agentId, buildScreenshotScript(fullPage))
        return if (result.ok) {
            HandlerResult(true, "Screenshot captured (base64 PNG).")
        } else {
            HandlerResult(false, "Screenshot failed: ${result.error}")
        }
    }

    private suspend fun extractData(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): HandlerResult {
        if (agentId == null) return HandlerResult(false, "This action needs an agent context.")
        val selector = args.optString("selector").trim().ifBlank { return HandlerResult(false, "Missing selector") }
        val type = args.optString("type", "auto").trim().lowercase()

        val script = buildExtractScript(selector, type)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            HandlerResult(true, result.summary)
        } else {
            HandlerResult(false, "Extract failed: ${result.error}")
        }
    }

    private suspend fun manageTab(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): HandlerResult {
        if (agentId == null) return HandlerResult(false, "This action needs an agent context.")
        val tabAction = args.optString("action", "new").trim().lowercase()
        val url = args.optString("url").trim()
        val tabId = args.optString("tabId").trim()

        return when (tabAction) {
            "new" -> {
                if (url.isBlank()) return HandlerResult(false, "Missing url for new tab")
                openInOfficeBrowser(agentId, connector, url, "Opened new tab: $url", show = false)
            }
            "switch" -> {
                if (tabId.isBlank()) return HandlerResult(false, "Missing tabId for switch")
                if (url.isNotBlank()) {
                    openInOfficeBrowser(agentId, connector, url, "Switched to tab with URL: $url", show = false)
                } else {
                    HandlerResult(false, "Tab switching not fully implemented; use open_url with new URL")
                }
            }
            "close" -> {
                HandlerResult(false, "Close tab not implemented; navigate away instead")
            }
            "list" -> {
                browserSession(agentId)
            }
            else -> HandlerResult(false, "Unknown tab action: $tabAction. Use new, switch, close, or list.")
        }
    }

    private suspend fun downloadFile(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): HandlerResult {
        if (agentId == null) return HandlerResult(false, "This action needs an agent context.")
        val url = args.optString("url").trim().ifBlank { return HandlerResult(false, "Missing url") }
        val filename = args.optString("filename", "download").trim()

        val script = buildDownloadScript(url, filename)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            HandlerResult(true, "Download initiated for $filename from $url")
        } else {
            HandlerResult(false, "Download failed: ${result.error}")
        }
    }

    private suspend fun waitForCondition(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): HandlerResult {
        if (agentId == null) return HandlerResult(false, "This action needs an agent context.")
        val selector = args.optString("selector").trim()
        val textMatch = args.optString("text").trim()
        val timeoutMs = args.optLong("timeoutMs", 10000).coerceIn(1000, 60000)
        val condition = args.optString("condition", "element").trim().lowercase()

        val script = buildWaitScript(selector, textMatch, condition, timeoutMs)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            val extra = selector.ifBlank { " (text: $textMatch)" } ?: ""
            HandlerResult(true, "Wait condition met: $condition$extra")
        } else {
            HandlerResult(false, "Wait failed: ${result.error}")
        }
    }

    private suspend fun scrollElement(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): HandlerResult {
        if (agentId == null) return HandlerResult(false, "This action needs an agent context.")
        val direction = args.optString("direction", "down").trim().lowercase()
        val pixels = args.optInt("pixels", 500).coerceIn(10, 10000)
        val selector = args.optString("selector").trim()
        val behavior = args.optString("behavior", "smooth").trim().lowercase()

        val script = buildScrollScript(direction, pixels, selector, behavior)
        val result = executeBrowserScript(agentId, script)
        return if (result.ok) {
            val target = selector.ifBlank { "by ${direction} $pixels px" } ?: ""
            HandlerResult(true, "Scrolled $target")
        } else {
            HandlerResult(false, "Scroll failed: ${result.error}")
        }
    }

    // ========== BROWSER SCRIPT HELPERS ==========

    private data class ScriptResult(val ok: Boolean, val summary: String, val error: String? = null)

    private suspend fun executeBrowserScript(agentId: String, script: String): ScriptResult {
        var result: ScriptResult? = null
        val latch = java.util.concurrent.CountDownLatch(1)
        BrowserHub.executeScript(agentId, script) { value ->
            result = ScriptResult(true, value ?: "Script executed (no return value)")
            latch.countDown()
        }
        val completed = latch.await(15, java.util.concurrent.TimeUnit.SECONDS)
        if (!completed) {
            return ScriptResult(false, "Script execution timed out")
        }
        return result ?: ScriptResult(false, "No result from script execution")
    }

    private fun buildClickScript(selector: String, textMatch: String, waitForNav: Boolean): String {
        val waitScript = if (waitForNav) """
                    return new Promise(resolve => {
                        var resolved = false;
                        var timeout = setTimeout(() => { resolved = true; resolve('Clicked (no navigation detected)'); }, 5000);
                        window.addEventListener('beforeunload', function() { clearTimeout(timeout); resolved = true; resolve('Clicked and navigation started'); }, {once: true});
                        var lastUrl = window.location.href;
                        var checkInterval = setInterval(() => {
                            if (window.location.href !== lastUrl) {
                                clearTimeout(timeout);
                                clearInterval(checkInterval);
                                if (!resolved) { resolved = true; resolve('Clicked and SPA navigation detected'); }
                            }
                        }, 100);
                    });""" else "return 'Clicked (no wait for navigation)';"
        return """
            (function() {
                try {
                    var element = null;
                    if ('$textMatch'.length > 0) {
                        var elements = document.querySelectorAll('*');
                        for (var i = 0; i < elements.length; i++) {
                            var el = elements[i];
                            if (el.textContent && el.textContent.trim().includes('$textMatch')) {
                                element = el;
                                break;
                            }
                        }
                    } else {
                        element = document.querySelector('$selector');
                    }
                    if (!element) {
                        return 'Element not found: ' + ('$textMatch'.length > 0 ? 'text=\"$textMatch\"' : 'selector=\"$selector\"');
                    }
                    element.click();
                    $waitScript
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """.trimIndent()
    }

    private fun buildFillScript(selector: String, value: String, isSelect: Boolean): String {
        val escapedValue = value.replace("'", "\\'").replace("\n", "\\n").replace("\r", "")
        return if (isSelect) """
            (function() {
                try {
                    var select = document.querySelector('$selector');
                    if (!select) return 'Select element not found: $selector';
                    if (select.tagName !== 'SELECT') return 'Element is not a SELECT: $selector';
                    var found = false;
                    for (var i = 0; i < select.options.length; i++) {
                        if (select.options[i].value === '$escapedValue' || select.options[i].text === '$escapedValue') {
                            select.selectedIndex = i;
                            found = true;
                            break;
                        }
                    }
                    if (!found) {
                        select.value = '$escapedValue';
                    }
                    select.dispatchEvent(new Event('change', {bubbles: true}));
                    return 'Filled select: $escapedValue';
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """ else """
            (function() {
                try {
                    var input = document.querySelector('$selector');
                    if (!input) return 'Input element not found: $selector';
                    var tag = input.tagName.toLowerCase();
                    if (tag === 'input' || tag === 'textarea') {
                        input.value = '$escapedValue';
                        input.dispatchEvent(new Event('input', {bubbles: true}));
                        input.dispatchEvent(new Event('change', {bubbles: true}));
                        return 'Filled input: $escapedValue';
                    } else if (input.isContentEditable) {
                        input.textContent = '$escapedValue';
                        input.dispatchEvent(new Event('input', {bubbles: true}));
                        return 'Filled contenteditable: $escapedValue';
                    } else {
                        return 'Element is not fillable: $selector';
                    }
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """
    }

    private fun buildSubmitScript(selector: String, waitForNav: Boolean): String {
        val waitScript = if (waitForNav) """
                    return new Promise(resolve => {
                        var resolved = false;
                        var timeout = setTimeout(() => { resolved = true; resolve('Submitted (no navigation detected)'); }, 5000);
                        window.addEventListener('beforeunload', function() { clearTimeout(timeout); resolved = true; resolve('Submitted and navigation started'); }, {once: true});
                        var lastUrl = window.location.href;
                        var checkInterval = setInterval(() => {
                            if (window.location.href !== lastUrl) {
                                clearTimeout(timeout);
                                clearInterval(checkInterval);
                                if (!resolved) { resolved = true; resolve('Submitted and SPA navigation detected'); }
                            }
                        }, 100);
                    });""" else "return 'Submitted (no wait for navigation)';"
        return """
            (function() {
                try {
                    var form = document.querySelector('$selector');
                    if (!form) return 'Form not found: $selector';
                    if (form.tagName !== 'FORM') return 'Element is not a FORM: $selector';
                    form.submit();
                    $waitScript
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """.trimIndent()
    }

    private fun buildScreenshotScript(fullPage: Boolean): String {
        return if (fullPage) """
            (function() {
                try {
                    return new Promise(async (resolve) => {
                        var originalScroll = window.scrollY;
                        var height = document.body.scrollHeight;
                        var width = document.body.scrollWidth;
                        window.scrollTo(0, 0);
                        await new Promise(r => setTimeout(r, 100));
                        var canvas = document.createElement('canvas');
                        canvas.width = width;
                        canvas.height = height;
                        var ctx = canvas.getContext('2d');
                        resolve('Full page screenshot requires html2canvas library. Use fullPage=false for viewport capture.');
                    });
                } catch (e) {
                    resolve('Error: ' + e.message);
                }
            })();
        """ else """
            (function() {
                try {
                    return new Promise((resolve) => {
                        html2canvas(document.body).then(function(canvas) {
                            var dataUrl = canvas.toDataURL('image/png');
                            resolve(dataUrl);
                        }).catch(function(err) {
                            resolve('Screenshot requires html2canvas. Error: ' + err.message);
                        });
                    });
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """
    }

    private fun buildExtractScript(selector: String, type: String): String {
        return """
            (function() {
                try {
                    var elements = document.querySelectorAll('$selector');
                    if (elements.length === 0) return 'No elements found for selector: $selector';
                    var results = [];
                    var extractType = '$type';
                    elements.forEach(function(el) {
                        if (extractType === 'table' || (extractType === 'auto' && el.tagName === 'TABLE')) {
                            var rows = el.querySelectorAll('tr');
                            var tableData = [];
                            rows.forEach(function(row) {
                                var cells = row.querySelectorAll('th, td');
                                var rowData = [];
                                cells.forEach(function(cell) {
                                    rowData.push(cell.textContent.trim());
                                });
                                tableData.push(rowData);
                            });
                            results.push({type: 'table', selector: '$selector', data: tableData});
                        } else if (extractType === 'links' || (extractType === 'auto' && el.tagName === 'A')) {
                            var links = el.querySelectorAll('a[href]');
                            var linkData = [];
                            links.forEach(function(a) {
                                linkData.push({text: a.textContent.trim(), href: a.href});
                            });
                            results.push({type: 'links', selector: '$selector', data: linkData});
                        } else {
                            results.push({
                                tag: el.tagName,
                                id: el.id,
                                class: el.className,
                                text: el.textContent.trim().substring(0, 500),
                                html: el.outerHTML.substring(0, 2000)
                            });
                        }
                    });
                    return JSON.stringify(results);
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """.trimIndent()
    }

    private fun buildDownloadScript(url: String, filename: String): String {
        return """
            (function() {
                try {
                    var a = document.createElement('a');
                    a.href = '$url';
                    a.download = '$filename';
                    document.body.appendChild(a);
                    a.click();
                    document.body.removeChild(a);
                    return 'Download triggered for $filename';
                } catch (e) {
                    return 'Error: ' + e.message;
                }
            })();
        """.trimIndent()
    }

    private fun buildScrollScript(direction: String, pixels: Int, selector: String, behavior: String): String {
        return when {
            selector.isNotBlank() -> """
                (function() {
                    try {
                        var element = document.querySelector('$selector');
                        if (!element) return 'Element not found for scroll: $selector';
                        element.scrollIntoView({behavior: '$behavior', block: 'center'});
                        return 'Scrolled to element: $selector';
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent()
            direction == "top" -> """
                (function() {
                    try {
                        window.scrollTo({top: 0, behavior: '$behavior'});
                        return 'Scrolled to top';
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent()
            direction == "bottom" -> """
                (function() {
                    try {
                        window.scrollTo({top: document.body.scrollHeight, behavior: '$behavior'});
                        return 'Scrolled to bottom';
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent()
            direction == "up" -> """
                (function() {
                    try {
                        window.scrollBy({top: -$pixels, behavior: '$behavior'});
                        return 'Scrolled up $pixels px';
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent()
            else -> """
                (function() {
                    try {
                        window.scrollBy({top: $pixels, behavior: '$behavior'});
                        return 'Scrolled down $pixels px';
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent()
        }
    }

    private fun buildWaitScript(selector: String, textMatch: String, condition: String, timeoutMs: Long): String {
        return when (condition) {
            "networkidle" -> """
                (function() {
                    try {
                        return new Promise((resolve) => {
                            var timeout = setTimeout(() => resolve('Network idle timeout after \${timeoutMs}ms'), ${timeoutMs});
                            var pending = 0;
                            var origXHR = window.XMLHttpRequest;
                            window.XMLHttpRequest = function() {
                                var xhr = new origXHR();
                                var origOpen = xhr.open;
                                xhr.open = function() {
                                    pending++;
                                    return origOpen.apply(this, arguments);
                                };
                                xhr.addEventListener('loadend', function() {
                                    pending--;
                                    if (pending === 0) {
                                        clearTimeout(timeout);
                                        resolve('Network idle');
                                    }
                                });
                                return xhr;
                            };
                            var origFetch = window.fetch;
                            window.fetch = function() {
                                pending++;
                                return origFetch.apply(this, arguments).then(r => { pending--; if (pending === 0) { clearTimeout(timeout); resolve('Network idle'); } return r; });
                            };
                            if (pending === 0) { clearTimeout(timeout); resolve('Network already idle'); }
                        });
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent()
            "text" -> if (textMatch.isNotBlank()) """
                (function() {
                    try {
                        return new Promise((resolve) => {
                            var timeout = setTimeout(() => resolve('Text wait timeout after \${timeoutMs}ms'), ${timeoutMs});
                            var check = function() {
                                if (document.body.textContent.includes('$textMatch')) {
                                    clearTimeout(timeout);
                                    resolve('Text found: $textMatch');
                                }
                            };
                            check();
                            var observer = new MutationObserver(check);
                            observer.observe(document.body, {childList: true, subtree: true, characterData: true});
                        });
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent() else """
                (function() {
                    return 'Error: text condition requires text parameter';
                })();
            """.trimIndent()
            else -> if (selector.isNotBlank()) """
                (function() {
                    try {
                        return new Promise((resolve) => {
                            var timeout = setTimeout(() => resolve('Element wait timeout after \${timeoutMs}ms'), ${timeoutMs});
                            var check = function() {
                                var el = document.querySelector('$selector');
                                if (el) {
                                    clearTimeout(timeout);
                                    resolve('Element found: $selector');
                                }
                            };
                            check();
                            var observer = new MutationObserver(check);
                            observer.observe(document.body, {childList: true, subtree: true});
                        });
                    } catch (e) {
                        return 'Error: ' + e.message;
                    }
                })();
            """.trimIndent() else """
                (function() {
                    return 'Error: element condition requires selector parameter';
                })();
            """.trimIndent()
        }
    }

    private fun browserSession(agentId: String?): HandlerResult {
        val cookies = try {
            CookieManager.getInstance()
        } catch (_: Exception) {
            null
        }
        val google = cookies?.getCookie("https://accounts.google.com").orEmpty()
        val gmail = cookies?.getCookie("https://mail.google.com").orEmpty()
        val calendar = cookies?.getCookie("https://calendar.google.com").orEmpty()
        val mine = agentId?.let { BrowserHub.session(it) }
        val sb = StringBuilder("Office browser session:\n")
        sb.append("- Google sign-in: ").append(if (google.isNotBlank()) "signed in" else "not signed in yet").append('\n')
        sb.append("- Gmail: ").append(if (gmail.isNotBlank()) "reachable" else "will ask to sign in").append('\n')
        sb.append("- Google Calendar: ").append(if (calendar.isNotBlank()) "reachable" else "will ask to sign in").append('\n')
        if (mine != null) {
            sb.append("- This agent's page: ").append(mine.url.ifBlank { "none" })
                .append(if (mine.loading) " (loading)" else "").append('\n')
            if (mine.title.isNotBlank()) sb.append("- Page title: ").append(mine.title).append('\n')
            if (mine.error != null) sb.append("- Last error: ").append(mine.error).append('\n')
        }
        sb.append("\nPages load in this agent's own background browser. Use read to get the text, ")
        sb.append("view to put it on screen, or signin to sign the office in.")
        return HandlerResult(true, sb.toString().trim())
    }

    private suspend fun openInOfficeBrowser(
        agentId: String?,
        connector: ConnectorEntity,
        url: String,
        okSummary: String,
        show: Boolean = false,
    ): HandlerResult {
        val agent = agentId?.let { id -> repo.agent(id) }
        val id = agentId ?: "office"
        val name = agent?.name ?: "Office"
        val started = BrowserHub.open(id, name, url, show)
        return if (started) {
            HandlerResult(true, okSummary)
        } else {
            HandlerResult(false, "Could not open $url in the office browser.")
        }
    }

    private suspend fun draftEmail(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): HandlerResult {
        val to = args.optString("to").trim()
        val subject = args.optString("subject").trim()
        val body = args.optString("body")
        if (to.isBlank() && subject.isBlank() && body.isBlank()) {
            return HandlerResult(false, "Give the draft a recipient, a subject or a body.")
        }
        val url = "https://mail.google.com/mail/?view=cm&fs=1" +
            "&to=" + enc(to) + "&su=" + enc(subject) + "&body=" + enc(body)
        val recipient = to.ifBlank { "a recipient of your choice" }
        return openInOfficeBrowser(
            agentId,
            connector,
            url,
            "Drafted a Gmail message for $recipient in this agent's " +
                "background browser. Nothing is sent until the user opens Browsers, reviews it and presses send.",
        )
    }

    private suspend fun addEvent(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): HandlerResult {
        val title = args.optString("title").trim().ifBlank { return HandlerResult(false, "Missing event title") }
        val start = parseInstant(args.optString("start"))
            ?: return HandlerResult(false, "Missing start time. Use ISO-8601, for example 2026-11-20T10:00:00Z.")
        val end = parseInstant(args.optString("end")) ?: (start + 60 * 60 * 1000L)
        if (end <= start) return HandlerResult(false, "The event end must be after its start.")
        val location = args.optString("location")
        val description = args.optString("description")
        val url = "https://calendar.google.com/calendar/render?action=TEMPLATE" +
            "&text=" + enc(title) +
            "&dates=" + calendarStamp(start) + "/" + calendarStamp(end) +
            (if (location.isBlank()) "" else "&location=" + enc(location)) +
            (if (description.isBlank()) "" else "&details=" + enc(description))
        return openInOfficeBrowser(
            agentId,
            connector,
            url,
            "Opened '$title' in Google Calendar inside this agent's background browser. " +
                "The user confirms it there and it lands in the office account.",
        )
    }

    private suspend fun openLink(
        agentId: String?,
        connector: ConnectorEntity,
        args: JSONObject,
    ): HandlerResult {
        val raw = args.optString("url").trim().ifBlank { return HandlerResult(false, "Missing url") }
        if (!raw.startsWith("http://") && !raw.startsWith("https://")) {
            return HandlerResult(false, "Only http and https links can be opened.")
        }
        return openInOfficeBrowser(agentId, connector, raw, "Opened $raw in this agent's background browser.")
    }

    private fun calendarStamp(epochMillis: Long): String =
        Instant.ofEpochMilli(epochMillis)
            .atZone(ZoneId.of("UTC"))
            .format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"))

    private fun parseInstant(raw: String): Long? {
        val value = raw.trim()
        if (value.isBlank()) return null
        return try {
            Instant.parse(value).toEpochMilli()
        } catch (_: Exception) {
            try {
                OffsetDateTime.parse(value).toInstant().toEpochMilli()
            } catch (_: Exception) {
                try {
                    LocalDateTime.parse(value).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
                } catch (_: Exception) {
                    value.toLongOrNull()?.let { if (it < 100_000_000_000L) it * 1000 else it }
                }
            }
        }
    }

    private fun enc(value: String): String =
        java.net.URLEncoder.encode(value, "UTF-8").replace("+", "%20")

    override fun onSuccess(connector: ConnectorEntity) = ConnectorHandler.defaultOnSuccess(connector)
}
