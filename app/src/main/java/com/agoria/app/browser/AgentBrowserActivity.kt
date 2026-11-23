package com.agoria.app.browser

import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.view.Gravity
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.ComponentActivity

/**
 * A browser window that belongs to the office rather than to the phone.
 *
 * The user signs in here once with the account the agents should use. The cookies live in this
 * app's WebView data, so the office keeps that session without touching the accounts signed in to
 * the rest of the device and without the app ever seeing a password.
 */
class AgentBrowserActivity : ComponentActivity() {

    private lateinit var webView: WebView
    private lateinit var banner: TextView

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val root = FrameLayout(this)
        webView = WebView(this)
        banner = TextView(this).apply {
            text = "Office browser. Sign in once with the account the office should use."
            setTextColor(Color.WHITE)
            setBackgroundColor(Color.parseColor("#1F2530"))
            gravity = Gravity.CENTER_VERTICAL
            setPadding(28, 22, 28, 22)
            textSize = 12f
        }

        root.addView(
            webView,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.MATCH_PARENT,
            ),
        )
        root.addView(
            banner,
            FrameLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
                Gravity.TOP,
            ),
        )
        setContentView(root)

        CookieManager.getInstance().setAcceptCookie(true)
        CookieManager.getInstance().setAcceptThirdPartyCookies(webView, true)

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            setSupportMultipleWindows(false)
            userAgentString = userAgentString.replace("; wv", "")
        }
        webView.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest,
            ): Boolean = false

            override fun onPageFinished(view: WebView, url: String?) {
                banner.text = "Office browser. Sign in once with the account the office should use."
            }
        }

        val startUrl = intent.getStringExtra(EXTRA_URL)
            ?.takeIf { it.startsWith("http://") || it.startsWith("https://") }
            ?: "https://accounts.google.com/ServiceLogin"
        webView.loadUrl(startUrl)
    }

    override fun onPause() {
        CookieManager.getInstance().flush()
        super.onPause()
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        if (webView.canGoBack()) webView.goBack() else super.onBackPressed()
    }

    override fun onDestroy() {
        CookieManager.getInstance().flush()
        webView.destroy()
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "com.agoria.app.browser.URL"

        fun intent(context: Context, url: String): Intent =
            Intent(context, AgentBrowserActivity::class.java)
                .putExtra(EXTRA_URL, url)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }
}
