package se.lumen.browser

import android.annotation.SuppressLint
import android.app.Activity
import android.app.DownloadManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.JavascriptInterface
import android.webkit.URLUtil
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.Toast
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Lumen för Android.
 *
 * Gränssnittet (adressfält, flikar, bokmärken, startsida) är samma HTML som
 * webbversionen och ligger i en WebView som täcker hela skärmen. Varje flik
 * har en egen WebView som läggs exakt över gränssnittets innehållsyta, så att
 * alla sidor öppnas inne i Lumen.
 */
class MainActivity : Activity() {

    private lateinit var root: FrameLayout
    private lateinit var ui: WebView
    private val tabs = LinkedHashMap<String, WebView>()
    private var current: String? = null
    private var showContent = false
    private var area = IntArray(4) // x, y, bredd, höjd i pixlar
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private var pendingUrl: String? = null
    private var uiReady = false
    private val privateTabs = HashSet<String>()
    private val blockedCount = ConcurrentHashMap<String, AtomicInteger>()
    private val emitPending = ConcurrentHashMap<String, Boolean>()
    private val privateProfiles by lazy {
        try { WebViewFeature.isFeatureSupported(WebViewFeature.MULTI_PROFILE) } catch (e: Throwable) { false }
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Blocker.init(this)
        root = FrameLayout(this)
        ui = WebView(this)
        setup(ui)
        ui.settings.allowFileAccess = true
        @Suppress("DEPRECATION")
        ui.settings.allowUniversalAccessFromFileURLs = true
        ui.setBackgroundColor(Color.TRANSPARENT)
        ui.addJavascriptInterface(Bridge(), "LumenHost")
        ui.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("file:")) return false
                if (url.startsWith("http:") || url.startsWith("https:")) {
                    js("window.lumenOpen&&lumenOpen(${JSONObject.quote(url)})")
                } else {
                    openExternal(url)
                }
                return true
            }

            override fun onPageFinished(view: WebView, url: String) {
                uiReady = true
                pendingUrl?.let { open(it) }
                pendingUrl = null
            }
        }
        ui.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                val pick = Intent(Intent.ACTION_GET_CONTENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                }
                return try {
                    startActivityForResult(Intent.createChooser(pick, "Välj fil"), PICK_FILE)
                    true
                } catch (e: ActivityNotFoundException) {
                    fileCallback = null
                    false
                }
            }
        }
        root.addView(ui, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        CookieManager.getInstance().setAcceptCookie(true)
        ui.loadUrl("file:///android_asset/ui/index.html")
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val url = when (intent?.action) {
            Intent.ACTION_VIEW -> intent.dataString
            Intent.ACTION_WEB_SEARCH -> intent.getStringExtra(SearchManager.QUERY)?.let {
                "https://duckduckgo.com/?q=" + Uri.encode(it)
            }
            else -> null
        } ?: return
        if (uiReady) open(url) else pendingUrl = url
    }

    private fun open(url: String) = js("window.lumenOpen&&lumenOpen(${JSONObject.quote(url)})")

    @SuppressLint("SetJavaScriptEnabled")
    private fun setup(w: WebView) {
        w.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            loadWithOverviewMode = true
            useWideViewPort = true
            builtInZoomControls = true
            displayZoomControls = false
            setSupportZoom(true)
            mediaPlaybackRequiresUserGesture = true
            mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
            setSupportMultipleWindows(false)
            // Visa Lumen som en vanlig mobil Chrome, så att t.ex. "Logga in med Google" fungerar.
            userAgentString = browserUserAgent
        }
        try {
            if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
                WebSettingsCompat.setRequestedWithHeaderOriginAllowList(w.settings, emptySet())
            }
        } catch (e: Throwable) { }
    }

    private val browserUserAgent by lazy {
        WebSettings.getDefaultUserAgent(this)
            .replace("; wv)", ")")
            .replace(Regex(" Version/\\d+(\\.\\d+)*"), "")
    }

    private fun js(code: String) {
        ui.post { ui.evaluateJavascript(code, null) }
    }

    @SuppressLint("SetJavaScriptEnabled")
    private fun tab(id: String, priv: Boolean = false): WebView = tabs.getOrPut(id) {
        val w = WebView(this)
        if (priv) {
            privateTabs.add(id)
            if (privateProfiles) {
                try {
                    ProfileStore.getInstance().getOrCreateProfile(PRIVATE_PROFILE)
                    WebViewCompat.setProfile(w, PRIVATE_PROFILE)
                } catch (e: Throwable) { }
            }
        }
        setup(w)
        if (priv) {
            w.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            w.settings.saveFormData = false
        } else {
            CookieManager.getInstance().setAcceptThirdPartyCookies(w, true)
        }
        w.setBackgroundColor(Color.WHITE)
        w.visibility = View.GONE
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http:") || url.startsWith("https:")) return false
                openExternal(url)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame || !Blocker.blocked(request.url.host)) return null
                blockedCount.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                if (emitPending.put(id, true) == null) ui.postDelayed({ emitPending.remove(id); emit(id) }, 400)
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                blockedCount[id]?.set(0)
                emit(id)
            }
            override fun onPageFinished(view: WebView, url: String) = emit(id)
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = emit(id)
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) = emit(id)
            override fun onReceivedTitle(view: WebView, title: String?) = emit(id)
        }
        w.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ ->
            try {
                val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
                val req = DownloadManager.Request(Uri.parse(url))
                    .setMimeType(mimeType)
                    .addRequestHeader("User-Agent", userAgent)
                    .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                    .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                    .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
                (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
                Toast.makeText(this, "Laddar ner $name", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                openExternal(url)
            }
        }
        root.addView(w, params())
        w
    }

    private fun params() = FrameLayout.LayoutParams(area[2].coerceAtLeast(1), area[3].coerceAtLeast(1)).apply {
        gravity = Gravity.TOP or Gravity.START
        leftMargin = area[0]
        topMargin = area[1]
    }

    private fun applyLayout() {
        for (w in tabs.values) w.layoutParams = params()
        applyVisibility()
    }

    private fun applyVisibility() {
        for ((id, w) in tabs) {
            w.visibility = if (showContent && id == current) View.VISIBLE else View.GONE
        }
    }

    private fun emit(id: String) {
        val w = tabs[id] ?: return
        val o = JSONObject()
        o.put("tab", id)
        o.put("url", w.url ?: "")
        o.put("title", w.title ?: "")
        o.put("back", w.canGoBack())
        o.put("fwd", w.canGoForward())
        o.put("progress", w.progress)
        o.put("blocked", blockedCount[id]?.get() ?: 0)
        js("window.lumenEvent&&lumenEvent($o)")
    }

    private fun openExternal(url: String) {
        try {
            val intent = if (url.startsWith("intent:")) Intent.parseUri(url, Intent.URI_INTENT_SCHEME)
            else Intent(Intent.ACTION_VIEW, Uri.parse(url))
            startActivity(intent)
        } catch (e: Exception) {
            Toast.makeText(this, "Kan inte öppna länken", Toast.LENGTH_SHORT).show()
        }
    }

    inner class Bridge {
        private val density get() = resources.displayMetrics.density

        @JavascriptInterface
        fun show(id: String, url: String, priv: Boolean) = runOnUiThread {
            val w = tab(id, priv)
            if (url.isNotEmpty() && w.url != url) w.loadUrl(url)
            current = id
            applyVisibility()
            emit(id)
        }

        @JavascriptInterface
        fun close(id: String) = runOnUiThread {
            tabs.remove(id)?.let {
                root.removeView(it)
                if (privateTabs.contains(id)) {
                    it.clearCache(true)
                    it.clearFormData()
                    it.clearHistory()
                }
                it.destroy()
            }
            blockedCount.remove(id)
            if (current == id) current = null
            if (privateTabs.remove(id) && privateTabs.isEmpty()) wipePrivate()
        }

        @JavascriptInterface
        fun setAdblock(on: Boolean) { Blocker.enabled = on }

        @JavascriptInterface
        fun version(): String = try {
            packageManager.getPackageInfo(packageName, 0).versionName ?: ""
        } catch (e: Exception) { "" }

        @JavascriptInterface
        fun back(id: String) = runOnUiThread { tabs[id]?.let { if (it.canGoBack()) it.goBack() } }

        @JavascriptInterface
        fun fwd(id: String) = runOnUiThread { tabs[id]?.let { if (it.canGoForward()) it.goForward() } }

        @JavascriptInterface
        fun reload(id: String) = runOnUiThread { tabs[id]?.reload() }

        @JavascriptInterface
        fun layout(x: Double, y: Double, w: Double, h: Double) = runOnUiThread {
            val d = density
            area = intArrayOf((x * d).toInt(), (y * d).toInt(), (w * d).toInt(), (h * d).toInt())
            applyLayout()
        }

        @JavascriptInterface
        fun visible(v: Boolean) = runOnUiThread {
            showContent = v
            applyVisibility()
        }
    }

    /** Raderar kakor och lagrad data från privata flikar. */
    private fun wipePrivate() {
        if (!privateProfiles) return
        try {
            val p = ProfileStore.getInstance().getProfile(PRIVATE_PROFILE) ?: return
            p.cookieManager.removeAllCookies(null)
            p.webStorage.deleteAllData()
        } catch (e: Throwable) { }
        ui.postDelayed({
            try { ProfileStore.getInstance().deleteProfile(PRIVATE_PROFILE) } catch (e: Throwable) { }
        }, 1500)
    }

    @Deprecated("Deprecated in Java")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == PICK_FILE) {
            fileCallback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(resultCode, data))
            fileCallback = null
            return
        }
        @Suppress("DEPRECATION")
        super.onActivityResult(requestCode, resultCode, data)
    }

    @Deprecated("Deprecated in Java")
    override fun onBackPressed() {
        ui.evaluateJavascript("window.lumenBack?lumenBack():false") { result ->
            if (result != "true") moveTaskToBack(true)
        }
    }

    override fun onPause() {
        super.onPause()
        CookieManager.getInstance().flush()
    }

    override fun onDestroy() {
        for (w in tabs.values) w.destroy()
        if (privateTabs.isNotEmpty()) { privateTabs.clear(); wipePrivate() }
        ui.destroy()
        super.onDestroy()
    }

    companion object {
        private const val PICK_FILE = 42
        private const val PRIVATE_PROFILE = "lumen-private"
    }
}
