package se.lumen.browser

import android.Manifest
import android.annotation.SuppressLint
import android.app.AlertDialog
import android.content.ClipData
import android.content.ClipboardManager
import android.content.pm.ShortcutInfo
import android.content.pm.ShortcutManager
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.graphics.drawable.Icon
import android.os.Handler
import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.print.PrintAttributes
import android.print.PrintManager
import android.speech.RecognizerIntent
import android.webkit.WebStorage
import com.google.mlkit.vision.codescanner.GmsBarcodeScanning
import android.os.Looper
import androidx.webkit.WebViewFeature as WVF
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.util.Base64
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import androidx.swiperefreshlayout.widget.SwipeRefreshLayout
import java.io.ByteArrayOutputStream
import android.app.Activity
import android.app.DownloadManager
import android.app.SearchManager
import android.content.ActivityNotFoundException
import android.content.Intent
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
import androidx.core.content.FileProvider
import androidx.webkit.ProfileStore
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature
import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import kotlin.concurrent.thread
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
    private val boxes = LinkedHashMap<String, SwipeRefreshLayout>()
    private val mediaPlaying = ConcurrentHashMap<String, Boolean>()
    private val pageHost = ConcurrentHashMap<String, String>()
    private var darkDefault = false
    private var pendingVoice: String? = null
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
        File(cacheDir, "updates/lumen.apk").delete()
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
                checkUpdate(false)
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

    private fun open(url: String) = js("window.lumenNewTab?lumenNewTab(${JSONObject.quote(url)},false):(window.lumenOpen&&lumenOpen(${JSONObject.quote(url)}))")

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
    private fun tab(id: String, priv: Boolean = false, space: String = ""): WebView = tabs.getOrPut(id) {
        val w = WebView(this)
        val profile = when {
            priv -> PRIVATE_PROFILE
            space.isNotEmpty() && space != "main" -> "space-$space"
            else -> null
        }
        if (priv) privateTabs.add(id)
        if (profile != null && privateProfiles) {
            try {
                ProfileStore.getInstance().getOrCreateProfile(profile)
                WebViewCompat.setProfile(w, profile)
            } catch (e: Throwable) { }
        }
        setup(w)
        w.addJavascriptInterface(object {
            @JavascriptInterface
            fun state(playing: Boolean) {
                mediaPlaying[id] = playing
                if (!mediaPlaying.values.any { it }) runOnUiThread { stopService(Intent(this@MainActivity, AudioService::class.java)) }
            }
        }, "LumenMedia")
        try {
            if (WVF.isFeatureSupported(WVF.DOCUMENT_START_SCRIPT)) WebViewCompat.addDocumentStartJavaScript(w, BG_JS, setOf("*"))
        } catch (e: Throwable) { }
        w.setOnLongClickListener { longPress(id, w) }
        applyDark(w, darkDefault)
        if (priv) {
            w.settings.cacheMode = WebSettings.LOAD_NO_CACHE
            w.settings.saveFormData = false
        } else {
            try { CookieManager.getInstance().setAcceptThirdPartyCookies(w, true) } catch (e: Throwable) { }
        }
        w.setBackgroundColor(Color.WHITE)
        val box = SwipeRefreshLayout(this)
        box.setColorSchemeColors(Color.parseColor("#2f7bff"), Color.parseColor("#e0218a"), Color.parseColor("#ff9a2e"))
        box.setOnRefreshListener { w.reload() }
        box.addView(w, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        box.visibility = View.GONE
        boxes[id] = box
        w.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val url = request.url.toString()
                if (url.startsWith("http:") || url.startsWith("https:")) return false
                openExternal(url)
                return true
            }

            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? {
                if (request.isForMainFrame) { request.url.host?.let { pageHost[id] = it }; return null }
                if (Blocker.pageAllowed(pageHost[id]) || !Blocker.blocked(request.url.host)) return null
                blockedCount.getOrPut(id) { AtomicInteger() }.incrementAndGet()
                if (emitPending.put(id, true) == null) ui.postDelayed({ emitPending.remove(id); emit(id) }, 400)
                return WebResourceResponse("text/plain", "utf-8", ByteArrayInputStream(ByteArray(0)))
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                Uri.parse(url).host?.let { pageHost[id] = it }
                blockedCount[id]?.set(0)
                emit(id)
            }
            override fun onPageFinished(view: WebView, url: String) {
                box.isRefreshing = false
                if (!WVF.isFeatureSupported(WVF.DOCUMENT_START_SCRIPT)) view.evaluateJavascript(BG_JS, null)
                emit(id)
                ui.postDelayed({ thumbnail(id) }, 1500)
            }
            override fun doUpdateVisitedHistory(view: WebView, url: String, isReload: Boolean) = emit(id)
        }
        w.webChromeClient = object : WebChromeClient() {
            override fun onProgressChanged(view: WebView, newProgress: Int) = emit(id)
            override fun onReceivedTitle(view: WebView, title: String?) = emit(id)
            override fun onShowCustomView(view: View, callback: CustomViewCallback) = enterFullscreen(view, callback)
            override fun onHideCustomView() = exitFullscreen()
            override fun onPermissionRequest(request: PermissionRequest) = askPermission(request)
            override fun onGeolocationPermissionsShowPrompt(origin: String, callback: GeolocationPermissions.Callback) = askGeo(origin, callback)
        }
        w.setFindListener { active, count, done ->
            if (done) js("window.lumenFind&&lumenFind(${JSONObject().put("tab", id).put("index", if (count > 0) active + 1 else 0).put("count", count)})")
        }
        w.setDownloadListener { url, userAgent, contentDisposition, mimeType, _ -> startDownload(url, userAgent, contentDisposition, mimeType) }
        root.addView(box, params())
        w
    }

    private fun params() = FrameLayout.LayoutParams(area[2].coerceAtLeast(1), area[3].coerceAtLeast(1)).apply {
        gravity = Gravity.TOP or Gravity.START
        leftMargin = area[0]
        topMargin = area[1]
    }

    private fun applyLayout() {
        for (b in boxes.values) b.layoutParams = params()
        applyVisibility()
    }

    private fun applyVisibility() {
        for ((id, b) in boxes) {
            b.visibility = if (showContent && id == current && customView == null) View.VISIBLE else View.GONE
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

    private fun startDownload(url: String, userAgent: String?, contentDisposition: String?, mimeType: String?) {
        try {
            val name = URLUtil.guessFileName(url, contentDisposition, mimeType)
            val req = DownloadManager.Request(Uri.parse(url))
                .setTitle(name)
                .addRequestHeader("User-Agent", userAgent ?: browserUserAgent)
                .addRequestHeader("Cookie", CookieManager.getInstance().getCookie(url) ?: "")
                .setNotificationVisibility(DownloadManager.Request.VISIBILITY_VISIBLE_NOTIFY_COMPLETED)
                .setDestinationInExternalPublicDir(Environment.DIRECTORY_DOWNLOADS, name)
            if (!mimeType.isNullOrEmpty()) req.setMimeType(mimeType)
            (getSystemService(DOWNLOAD_SERVICE) as DownloadManager).enqueue(req)
            js("window.lumenDownloadStarted&&lumenDownloadStarted(${JSONObject.quote(name)})")
        } catch (e: Exception) {
            openExternal(url)
        }
    }

    /* ---------- Håll inne på länkar och bilder ---------- */
    private fun longPress(id: String, w: WebView): Boolean {
        val r = w.hitTestResult
        var link: String? = null
        var img: String? = null
        when (r.type) {
            WebView.HitTestResult.SRC_ANCHOR_TYPE -> link = r.extra
            WebView.HitTestResult.IMAGE_TYPE -> img = r.extra
            WebView.HitTestResult.SRC_IMAGE_ANCHOR_TYPE -> {
                img = r.extra
                val msg = Handler(Looper.getMainLooper()).obtainMessage()
                w.requestFocusNodeHref(msg)
                link = msg.data.getString("url")
            }
            else -> return false
        }
        val items = ArrayList<Pair<String, () -> Unit>>()
        if (!link.isNullOrEmpty() && (link.startsWith("http:") || link.startsWith("https:"))) {
            val l = link
            items.add("Öppna i ny flik" to { js("window.lumenNewTab&&lumenNewTab(${JSONObject.quote(l)},false)") })
            items.add("Öppna i privat flik" to { js("window.lumenNewTab&&lumenNewTab(${JSONObject.quote(l)},true)") })
            items.add("Kopiera länk" to { copy(l) })
            items.add("Dela länk" to { share(l, "") })
        }
        if (!img.isNullOrEmpty() && (img.startsWith("http:") || img.startsWith("https:"))) {
            val i = img
            items.add("Öppna bild i ny flik" to { js("window.lumenNewTab&&lumenNewTab(${JSONObject.quote(i)},false)") })
            items.add("Spara bild" to { startDownload(i, null, null, null) })
            items.add("Kopiera bildadress" to { copy(i) })
        }
        if (items.isEmpty()) return false
        val title = (link ?: img)?.let { Uri.parse(it).host } ?: ""
        AlertDialog.Builder(this)
            .setTitle(title)
            .setItems(items.map { it.first }.toTypedArray()) { _, which -> items[which].second() }
            .show()
        return true
    }

    private fun copy(text: String) {
        (getSystemService(CLIPBOARD_SERVICE) as ClipboardManager).setPrimaryClip(ClipData.newPlainText("Länk", text))
        Toast.makeText(this, "Kopierad", Toast.LENGTH_SHORT).show()
    }

    private fun share(url: String, title: String) {
        try {
            val i = Intent(Intent.ACTION_SEND).setType("text/plain").putExtra(Intent.EXTRA_TEXT, url)
            if (title.isNotEmpty()) i.putExtra(Intent.EXTRA_SUBJECT, title)
            startActivity(Intent.createChooser(i, "Dela"))
        } catch (e: Exception) { }
    }

    /** Lägg en genväg till sidan på hemskärmen. */
    private fun addToHome(id: String) {
        val w = tabs[id] ?: return
        val url = w.url ?: return
        val host = Uri.parse(url).host?.removePrefix("www.") ?: "Sida"
        val label = (w.title?.takeIf { it.isNotBlank() } ?: host).take(24)
        val size = 192
        val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
        val c = Canvas(bmp)
        val fav = w.favicon
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        if (fav != null && fav.width >= 24) {
            p.color = Color.WHITE
            c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 44f, 44f, p)
            val s = 112
            c.drawBitmap(Bitmap.createScaledBitmap(fav, s, s, true), ((size - s) / 2).toFloat(), ((size - s) / 2).toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
        } else {
            val hue = (host.hashCode() and 0x7fffffff) % 360
            p.color = Color.HSVToColor(floatArrayOf(hue.toFloat(), .62f, .82f))
            c.drawRoundRect(RectF(0f, 0f, size.toFloat(), size.toFloat()), 44f, 44f, p)
            p.color = Color.WHITE
            p.textSize = 96f
            p.typeface = Typeface.DEFAULT_BOLD
            p.textAlign = Paint.Align.CENTER
            c.drawText(label.take(1).uppercase(), size / 2f, size / 2f + 34f, p)
        }
        val sm = getSystemService(ShortcutManager::class.java)
        if (sm == null || !sm.isRequestPinShortcutSupported) {
            Toast.makeText(this, "Hemskärmen stöder inte genvägar", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(this, MainActivity::class.java).setAction(Intent.ACTION_VIEW).setData(Uri.parse(url))
        val info = ShortcutInfo.Builder(this, "site-" + url.hashCode())
            .setShortLabel(label)
            .setIcon(Icon.createWithBitmap(bmp))
            .setIntent(intent)
            .build()
        sm.requestPinShortcut(info, null)
    }

    /** Liten bild av sidan till den visuella historiken. */
    private fun thumbnail(id: String) {
        val w = tabs[id] ?: return
        if (id != current || !showContent || w.width <= 0 || w.height <= 0 || privateTabs.contains(id)) return
        try {
            val scale = 240f / w.width
            val bmp = Bitmap.createBitmap(240, (w.height * scale).toInt().coerceIn(1, 400), Bitmap.Config.RGB_565)
            val c = Canvas(bmp)
            c.scale(scale, scale)
            c.translate(-w.scrollX.toFloat(), -w.scrollY.toFloat())
            w.draw(c)
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 62, out)
            bmp.recycle()
            val data = "data:image/jpeg;base64," + Base64.encodeToString(out.toByteArray(), Base64.NO_WRAP)
            js("window.lumenThumb&&lumenThumb(${JSONObject().put("tab", id).put("url", w.url ?: "").put("data", data)})")
        } catch (e: Throwable) { }
    }

    /* ---------- Video i helskärm ---------- */
    private var customView: View? = null
    private var customCallback: WebChromeClient.CustomViewCallback? = null

    private fun enterFullscreen(view: View, callback: WebChromeClient.CustomViewCallback) {
        if (customView != null) { callback.onCustomViewHidden(); return }
        customView = view
        customCallback = callback
        view.setBackgroundColor(Color.BLACK)
        root.addView(view, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        applyVisibility()
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = (View.SYSTEM_UI_FLAG_FULLSCREEN or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY or View.SYSTEM_UI_FLAG_LAYOUT_STABLE)
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
    }

    private fun exitFullscreen() {
        val v = customView ?: return
        root.removeView(v)
        customView = null
        customCallback?.onCustomViewHidden()
        customCallback = null
        @Suppress("DEPRECATION")
        window.decorView.systemUiVisibility = View.SYSTEM_UI_FLAG_VISIBLE
        requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
        applyVisibility()
    }

    /* ---------- Kamera, mikrofon och plats ---------- */
    private var pendingWebPermission: PermissionRequest? = null
    private var pendingGeo: Pair<String, GeolocationPermissions.Callback>? = null
    private val perms by lazy { getSharedPreferences("perms", Context.MODE_PRIVATE) }
    private val allowedOrigins = object {
        operator fun get(k: String): Boolean? = if (perms.contains(k)) perms.getBoolean(k, false) else null
        operator fun set(k: String, v: Boolean) { perms.edit().putBoolean(k, v).apply() }
    }

    private fun applyDark(w: WebView, on: Boolean) {
        try {
            if (WVF.isFeatureSupported(WVF.ALGORITHMIC_DARKENING)) WebSettingsCompat.setAlgorithmicDarkeningAllowed(w.settings, on)
        } catch (e: Throwable) { }
    }

    /* ---------- Skaka för att öppna stängd flik ---------- */
    private var lastShake = 0L
    private val shake = object : SensorEventListener {
        override fun onSensorChanged(e: SensorEvent) {
            val g = Math.sqrt((e.values[0] * e.values[0] + e.values[1] * e.values[1] + e.values[2] * e.values[2]).toDouble()) / SensorManager.GRAVITY_EARTH
            val now = System.currentTimeMillis()
            if (g > 2.8 && now - lastShake > 2000) {
                lastShake = now
                js("window.lumenShake&&lumenShake()")
            }
        }
        override fun onAccuracyChanged(s: Sensor?, a: Int) {}
    }

    private fun askPermission(request: PermissionRequest) = runOnUiThread {
        val wanted = request.resources.filter {
            it == PermissionRequest.RESOURCE_VIDEO_CAPTURE || it == PermissionRequest.RESOURCE_AUDIO_CAPTURE ||
                it == PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID
        }
        if (wanted.isEmpty()) { request.deny(); return@runOnUiThread }
        val host = request.origin.host ?: "Sidan"
        val what = buildList {
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted) add("kameran")
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in wanted) add("mikrofonen")
            if (PermissionRequest.RESOURCE_PROTECTED_MEDIA_ID in wanted) add("skyddad video")
        }.joinToString(" och ")
        val grant = {
            val perms = ArrayList<String>()
            if (PermissionRequest.RESOURCE_VIDEO_CAPTURE in wanted && checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.CAMERA)
            if (PermissionRequest.RESOURCE_AUDIO_CAPTURE in wanted && checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) perms.add(Manifest.permission.RECORD_AUDIO)
            if (perms.isEmpty()) request.grant(wanted.toTypedArray())
            else { pendingWebPermission = request; requestPermissions(perms.toTypedArray(), REQ_MEDIA) }
        }
        val key = "$host|$what"
        when (allowedOrigins[key]) {
            true -> grant()
            false -> request.deny()
            null -> AlertDialog.Builder(this)
                .setTitle("$host vill använda $what")
                .setPositiveButton("Tillåt") { _, _ -> allowedOrigins[key] = true; grant() }
                .setNegativeButton("Neka") { _, _ -> allowedOrigins[key] = false; request.deny() }
                .setOnCancelListener { request.deny() }
                .show()
        }
    }

    private fun askGeo(origin: String, callback: GeolocationPermissions.Callback) = runOnUiThread {
        val host = Uri.parse(origin).host ?: origin
        val go = {
            if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED) callback.invoke(origin, true, false)
            else { pendingGeo = origin to callback; requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQ_GEO) }
        }
        when (allowedOrigins["$host|plats"]) {
            true -> go()
            false -> callback.invoke(origin, false, false)
            null -> AlertDialog.Builder(this)
                .setTitle("$host vill veta var du är")
                .setPositiveButton("Tillåt") { _, _ -> allowedOrigins["$host|plats"] = true; go() }
                .setNegativeButton("Neka") { _, _ -> allowedOrigins["$host|plats"] = false; callback.invoke(origin, false, false) }
                .setOnCancelListener { callback.invoke(origin, false, false) }
                .show()
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        val ok = grantResults.isNotEmpty() && grantResults.any { it == PackageManager.PERMISSION_GRANTED }
        if (requestCode == REQ_MEDIA) {
            pendingWebPermission?.let { r ->
                if (ok) r.grant(r.resources) else r.deny()
            }
            pendingWebPermission = null
        } else if (requestCode == REQ_GEO) {
            pendingGeo?.let { (o, cb) -> cb.invoke(o, ok, false) }
            pendingGeo = null
        }
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

    private val desktopUserAgent by lazy {
        val ver = Regex("Chrome/([\\d.]+)").find(browserUserAgent)?.groupValues?.get(1) ?: "124.0.0.0"
        "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/$ver Safari/537.36"
    }

    private fun result(req: String, json: String) = js("window.lumenResult&&lumenResult(${JSONObject.quote(req)},$json)")

    inner class Bridge {
        private val density get() = resources.displayMetrics.density

        @JavascriptInterface
        fun show(id: String, url: String, priv: Boolean, space: String) = runOnUiThread {
            val w = tab(id, priv, space)
            if (url.isNotEmpty() && w.url != url) w.loadUrl(url)
            current = id
            applyVisibility()
            emit(id)
        }

        @JavascriptInterface
        fun close(id: String) = runOnUiThread {
            boxes.remove(id)?.let { root.removeView(it) }
            tabs.remove(id)?.let {
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
        fun checkUpdate() = runOnUiThread { this@MainActivity.checkUpdate(true) }

        @JavascriptInterface
        fun update() = runOnUiThread {
            if (pendingInstall) installUpdate() else downloadUpdate()
        }

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

        /** Kör ett skript i fliken och skicka tillbaka resultatet. */
        @JavascriptInterface
        fun run(id: String, code: String, req: String) = runOnUiThread {
            val w = tabs[id]
            if (w == null) result(req, "null") else w.evaluateJavascript(code) { r -> result(req, r ?: "null") }
        }

        /** Hämta en sida i bakgrunden (för bevakning, förslag och städning). */
        @JavascriptInterface
        fun fetch(url: String, req: String) {
            thread(name = "lumen-fetch") {
                val o = JSONObject()
                try {
                    var u = URL(url)
                    fun open(x: URL): HttpURLConnection = (x.openConnection() as HttpURLConnection).apply {
                        instanceFollowRedirects = false
                        connectTimeout = 9000
                        readTimeout = 12000
                        setRequestProperty("User-Agent", browserUserAgent)
                        setRequestProperty("Accept-Language", "sv-SE,sv;q=0.9,en;q=0.8")
                    }
                    var c = open(u)
                    var hops = 0
                    while (c.responseCode in 300..399 && hops < 5) {
                        val loc = c.getHeaderField("Location") ?: break
                        c.disconnect()
                        u = URL(u, loc)
                        hops++
                        c = open(u)
                    }
                    o.put("status", c.responseCode)
                    o.put("url", u.toString())
                    val stream = if (c.responseCode < 400) c.inputStream else c.errorStream
                    val bytes = stream?.use { s ->
                        val buf = java.io.ByteArrayOutputStream()
                        val b = ByteArray(32 * 1024)
                        var total = 0
                        while (total < 1_500_000) { val r = s.read(b); if (r < 0) break; buf.write(b, 0, r); total += r }
                        buf.toByteArray()
                    } ?: ByteArray(0)
                    o.put("text", String(bytes, Charsets.UTF_8))
                    c.disconnect()
                } catch (e: Exception) {
                    o.put("status", 0)
                    o.put("text", "")
                }
                result(req, o.toString())
            }
        }

        @JavascriptInterface
        fun find(id: String, q: String) = runOnUiThread { tabs[id]?.findAllAsync(q) }

        @JavascriptInterface
        fun findNext(id: String, forward: Boolean) = runOnUiThread { tabs[id]?.findNext(forward) }

        @JavascriptInterface
        fun findClear(id: String) = runOnUiThread { tabs[id]?.clearMatches() }

        @JavascriptInterface
        fun share(url: String, title: String) = runOnUiThread { this@MainActivity.share(url, title) }

        @JavascriptInterface
        fun setAdAllow(json: String) {
            Blocker.allowed = try {
                val a = org.json.JSONArray(json); (0 until a.length()).map { a.getString(it).lowercase() }.toSet()
            } catch (e: Exception) { emptySet() }
        }

        @JavascriptInterface
        fun sitePerms(req: String) {
            val arr = org.json.JSONArray()
            for ((k, v) in perms.all) {
                val parts = k.split("|")
                if (parts.size == 2) arr.put(JSONObject().put("host", parts[0]).put("what", parts[1]).put("allowed", v == true))
            }
            result(req, arr.toString())
        }

        @JavascriptInterface
        fun resetSite(host: String) = runOnUiThread {
            val ed = perms.edit()
            for (k in perms.all.keys) if (k.startsWith("$host|")) ed.remove(k)
            ed.apply()
            val cm = CookieManager.getInstance()
            for (h in listOf(host, "www.$host", host.removePrefix("www."))) {
                val url = "https://$h"
                cm.getCookie(url)?.split(";")?.forEach { c ->
                    val name = c.substringBefore("=").trim()
                    if (name.isNotEmpty()) {
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/")
                        cm.setCookie(url, "$name=; Max-Age=0; Path=/; Domain=.${host.removePrefix("www.")}")
                    }
                }
                WebStorage.getInstance().deleteOrigin(url)
            }
            cm.flush()
        }

        @JavascriptInterface
        fun setDark(id: String, on: Boolean) = runOnUiThread { tabs[id]?.let { applyDark(it, on) } }

        @JavascriptInterface
        fun setDarkDefault(on: Boolean) = runOnUiThread { darkDefault = on }

        @JavascriptInterface
        fun setZoom(id: String, pct: Int) = runOnUiThread { tabs[id]?.settings?.textZoom = pct.coerceIn(50, 250) }

        @JavascriptInterface
        fun voice(req: String) = runOnUiThread {
            try {
                pendingVoice = req
                startActivityForResult(Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_WEB_SEARCH)
                    .putExtra(RecognizerIntent.EXTRA_LANGUAGE, "sv-SE")
                    .putExtra(RecognizerIntent.EXTRA_PROMPT, "Säg vad du vill söka efter"), REQ_VOICE)
            } catch (e: Exception) {
                pendingVoice = null
                result(req, "null")
                Toast.makeText(this@MainActivity, "Röstigenkänning finns inte på telefonen", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun scanQr(req: String) = runOnUiThread {
            try {
                GmsBarcodeScanning.getClient(this@MainActivity).startScan()
                    .addOnSuccessListener { result(req, JSONObject.quote(it.rawValue ?: "")) }
                    .addOnFailureListener { result(req, "null") }
                    .addOnCanceledListener { result(req, "null") }
            } catch (e: Throwable) { result(req, "null") }
        }

        @JavascriptInterface
        fun print(id: String) = runOnUiThread {
            val w = tabs[id] ?: return@runOnUiThread
            val name = (w.title?.takeIf { it.isNotBlank() } ?: "Lumen").replace(Regex("[\\/:*?\"<>|]"), " ").take(60)
            (getSystemService(PRINT_SERVICE) as PrintManager).print(name, w.createPrintDocumentAdapter(name), PrintAttributes.Builder().build())
        }

        @JavascriptInterface
        fun addToHome(id: String) = runOnUiThread { this@MainActivity.addToHome(id) }

        @JavascriptInterface
        fun downloads(req: String) {
            thread(name = "lumen-downloads") {
                val arr = org.json.JSONArray()
                try {
                    val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                    dm.query(DownloadManager.Query()).use { c ->
                        while (c.moveToNext()) {
                            val o = JSONObject()
                            o.put("id", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_ID)).toString())
                            o.put("name", c.getString(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TITLE)) ?: "")
                            o.put("status", when (c.getInt(c.getColumnIndexOrThrow(DownloadManager.COLUMN_STATUS))) {
                                DownloadManager.STATUS_SUCCESSFUL -> "done"
                                DownloadManager.STATUS_FAILED -> "failed"
                                DownloadManager.STATUS_PAUSED -> "paused"
                                else -> "running"
                            })
                            o.put("got", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_BYTES_DOWNLOADED_SO_FAR)))
                            o.put("total", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_TOTAL_SIZE_BYTES)))
                            o.put("ts", c.getLong(c.getColumnIndexOrThrow(DownloadManager.COLUMN_LAST_MODIFIED_TIMESTAMP)))
                            arr.put(o)
                        }
                    }
                } catch (e: Exception) { }
                result(req, arr.toString())
            }
        }

        @JavascriptInterface
        fun openDownload(id: String) = runOnUiThread {
            try {
                val dm = getSystemService(DOWNLOAD_SERVICE) as DownloadManager
                val n = id.toLong()
                val uri = dm.getUriForDownloadedFile(n) ?: return@runOnUiThread
                val mime = dm.getMimeTypeForDownloadedFile(n) ?: "*/*"
                startActivity(Intent(Intent.ACTION_VIEW).setDataAndType(uri, mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK))
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Ingen app kan öppna filen", Toast.LENGTH_SHORT).show()
            }
        }

        @JavascriptInterface
        fun setDesktop(id: String, on: Boolean) = runOnUiThread {
            tabs[id]?.let {
                it.settings.userAgentString = if (on) desktopUserAgent else browserUserAgent
                it.settings.useWideViewPort = true
                it.settings.loadWithOverviewMode = true
                it.reload()
            }
        }
    }

    /* ---------- Uppdateringar ---------- */
    private var updateUrl: String? = null
    private var updateVersion = ""
    private var pendingInstall = false
    private var lastCheck = 0L
    @Volatile private var downloading = false

    private fun currentVersionCode(): Int = try {
        val info = packageManager.getPackageInfo(packageName, 0)
        if (Build.VERSION.SDK_INT >= 28) info.longVersionCode.toInt()
        else @Suppress("DEPRECATION") info.versionCode
    } catch (e: Exception) { 0 }

    private fun sendUpdate(state: String, extra: (JSONObject) -> Unit = {}) {
        val o = JSONObject()
        o.put("state", state)
        o.put("version", updateVersion)
        extra(o)
        js("window.lumenUpdate&&lumenUpdate($o)")
    }

    private fun checkUpdate(manual: Boolean) {
        lastCheck = System.currentTimeMillis()
        if (manual) sendUpdate("checking")
        thread(name = "lumen-update-check") {
            try {
                val c = URL(RELEASES_API).openConnection() as HttpURLConnection
                c.setRequestProperty("Accept", "application/vnd.github+json")
                c.connectTimeout = 10000
                c.readTimeout = 15000
                if (c.responseCode != 200) {
                    if (manual) sendUpdate("error")
                    c.disconnect()
                    return@thread
                }
                val j = JSONObject(c.inputStream.bufferedReader().readText())
                c.disconnect()
                val tag = j.optString("tag_name")
                val n = tag.substringAfterLast('.').toIntOrNull() ?: 0
                var url: String? = null
                val assets = j.optJSONArray("assets")
                if (assets != null) for (i in 0 until assets.length()) {
                    val a = assets.getJSONObject(i)
                    if (a.optString("name") == "Lumen-android.apk") url = a.optString("browser_download_url")
                }
                if (n > currentVersionCode() && url != null) {
                    updateUrl = url
                    updateVersion = tag.removePrefix("v")
                    sendUpdate("available")
                } else if (manual) sendUpdate("latest")
            } catch (e: Exception) {
                if (manual) sendUpdate("error")
            }
        }
    }

    private fun updateFile() = File(File(cacheDir, "updates").apply { mkdirs() }, "lumen.apk")

    private fun downloadUpdate() {
        val url = updateUrl ?: return checkUpdate(true)
        if (downloading) return
        downloading = true
        sendUpdate("downloading") { it.put("progress", 0) }
        thread(name = "lumen-update-download") {
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                c.instanceFollowRedirects = true
                c.connectTimeout = 15000
                c.readTimeout = 30000
                val total = c.contentLength
                val file = updateFile()
                var got = 0L
                var lastP = -1
                c.inputStream.use { input ->
                    file.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val r = input.read(buf)
                            if (r < 0) break
                            out.write(buf, 0, r)
                            got += r
                            if (total > 0) {
                                val p = (got * 100 / total).toInt()
                                if (p >= lastP + 5) {
                                    lastP = p
                                    sendUpdate("downloading") { it.put("progress", p) }
                                }
                            }
                        }
                    }
                }
                c.disconnect()
                downloading = false
                runOnUiThread { installUpdate() }
            } catch (e: Exception) {
                downloading = false
                sendUpdate("error")
            }
        }
    }

    private fun installUpdate() {
        val file = updateFile()
        if (!file.exists()) return
        if (Build.VERSION.SDK_INT >= 26 && !packageManager.canRequestPackageInstalls()) {
            pendingInstall = true
            sendUpdate("permission")
            try {
                startActivity(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:$packageName")))
            } catch (e: Exception) { }
            return
        }
        pendingInstall = false
        try {
            val uri = FileProvider.getUriForFile(this, "$packageName.files", file)
            val i = Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(i)
            sendUpdate("installing")
        } catch (e: Exception) {
            sendUpdate("error")
        }
    }

    override fun onResume() {
        super.onResume()
        (getSystemService(SENSOR_SERVICE) as SensorManager).let { sm ->
            sm.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)?.let { sm.registerListener(shake, it, SensorManager.SENSOR_DELAY_UI) }
        }
        stopService(Intent(this, AudioService::class.java))
        if (pendingInstall && (Build.VERSION.SDK_INT < 26 || packageManager.canRequestPackageInstalls())) installUpdate()
        else if (uiReady && System.currentTimeMillis() - lastCheck > 6 * 60 * 60 * 1000L) checkUpdate(false)
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
        if (requestCode == REQ_VOICE) {
            val text = data?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)?.firstOrNull()
            pendingVoice?.let { result(it, if (resultCode == RESULT_OK && text != null) JSONObject.quote(text) else "null") }
            pendingVoice = null
            return
        }
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
        if (customView != null) { exitFullscreen(); return }
        ui.evaluateJavascript("window.lumenBack?lumenBack():false") { result ->
            if (result != "true") moveTaskToBack(true)
        }
    }

    override fun onPause() {
        super.onPause()
        (getSystemService(SENSOR_SERVICE) as SensorManager).unregisterListener(shake)
        if (mediaPlaying.values.any { it }) {
            try {
                val title = tabs[current ?: ""]?.title ?: ""
                startForegroundService(Intent(this, AudioService::class.java).putExtra("title", title))
            } catch (e: Exception) { }
        }
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
        private const val REQ_MEDIA = 43
        private const val REQ_GEO = 44
        private const val REQ_VOICE = 45
        /** Låter ljud och video fortsätta när Lumen ligger i bakgrunden, och rapporterar uppspelning. */
        private const val BG_JS = "(function(){try{if(window.__lumenBg)return;window.__lumenBg=1;" +
            "Object.defineProperty(document,'hidden',{get:function(){return false},configurable:true});" +
            "Object.defineProperty(document,'visibilityState',{get:function(){return 'visible'},configurable:true});" +
            "var stop=function(e){e.stopImmediatePropagation()};window.addEventListener('visibilitychange',stop,true);document.addEventListener('visibilitychange',stop,true);" +
            "var rep=function(){try{var a=[].slice.call(document.querySelectorAll('video,audio')).some(function(m){return !m.paused&&!m.ended&&!m.muted&&m.readyState>2});LumenMedia.state(a)}catch(x){}};" +
            "['play','playing','pause','ended','volumechange','emptied'].forEach(function(t){document.addEventListener(t,rep,true)});window.addEventListener('pagehide',function(){try{LumenMedia.state(false)}catch(x){}})}catch(e){}})();"
        private const val PRIVATE_PROFILE = "lumen-private"
        private const val RELEASES_API = "https://api.github.com/repos/theruup-stack/Lumen-releases/releases/latest"
    }
}
