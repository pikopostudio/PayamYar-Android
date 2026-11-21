package ir.pikopo.payamyar

import android.annotation.SuppressLint
import android.app.Activity
import android.os.Bundle
import android.os.Build
import android.view.WindowInsets
import android.webkit.JavascriptInterface
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import org.json.JSONObject

/**
 * Local-only WebView UI + native TDLib bridge. Remote pages cannot be opened inside the bridge.
 * All Telegram requests and secrets are handled natively, never in app.js/localStorage.
 */
class MainActivity : Activity() {
    private lateinit var web: WebView
    private lateinit var engine: TelegramEngine
    private lateinit var secure: SecureConfig
    private lateinit var journal: CampaignStore
    private var pageReady = false

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        secure = SecureConfig(this)
        journal = CampaignStore(this)
        engine = TelegramEngine(this, journal) { type, data -> emit(type, data) }
        web = WebView(this)
        web.settings.javaScriptEnabled = true
        web.settings.domStorageEnabled = true
        web.settings.allowFileAccess = true
        web.settings.allowContentAccess = false
        web.settings.allowFileAccessFromFileURLs = false
        web.settings.allowUniversalAccessFromFileURLs = false
        web.settings.blockNetworkLoads = true // Prevent any external/third-party page in this trusted JS bridge.
        web.settings.defaultTextEncodingName = "UTF-8"
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView?, request: WebResourceRequest?): Boolean {
                return request?.url?.toString() != "file:///android_asset/index.html"
            }
        }
        web.addJavascriptInterface(Bridge(), "PayamYar")
        if (Build.VERSION.SDK_INT >= 35) {
            web.setOnApplyWindowInsetsListener { view, insets ->
                val bars = insets.getInsets(WindowInsets.Type.systemBars())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
        setContentView(web)
        web.loadUrl("file:///android_asset/index.html")
    }

    private fun emit(type: String, data: JSONObject) {
        runOnUiThread {
            if (!pageReady) return@runOnUiThread
            val payload = JSONObject().put("type", type).put("data", data).toString()
            web.evaluateJavascript("window.nativeEvent(${JSONObject.quote(payload)})", null)
        }
    }

    inner class Bridge {
        @JavascriptInterface fun uiReady() = runOnUiThread {
            pageReady = true
            val creds = secure.read()
            emit("config", JSONObject().put("hasSavedCredentials", creds != null))
            if (creds != null) engine.start(creds.first, creds.second)
        }
        @JavascriptInterface fun connect(id: String, hash: String, phone: String) = runOnUiThread {
            val parsed = id.toIntOrNull()
            if (parsed == null || parsed <= 0 || hash.length !in 20..128) {
                emit("error", JSONObject().put("message", "شناسه API معتبر نیست")); return@runOnUiThread
            }
            secure.write(parsed, hash)
            engine.start(parsed, hash, phone)
        }
        @JavascriptInterface fun sendPhone(value: String) = runOnUiThread { engine.sendPhone(value) }
        @JavascriptInterface fun sendCode(value: String) = runOnUiThread { engine.sendCode(value) }
        @JavascriptInterface fun sendPassword(value: String) = runOnUiThread { engine.sendPassword(value) }
        @JavascriptInterface fun sendEmail(value: String) = runOnUiThread { engine.sendEmail(value) }
        @JavascriptInterface fun sendEmailCode(value: String) = runOnUiThread { engine.sendEmailCode(value) }
        @JavascriptInterface fun loadContacts() = runOnUiThread { engine.loadContacts() }
        @JavascriptInterface fun getCampaignStatus() = runOnUiThread { engine.summary() }
        @JavascriptInterface fun submitCampaign(payload: String) = runOnUiThread {
            try { engine.submitCampaign(JSONObject(payload)) }
            catch (_: Exception) { emit("error", JSONObject().put("message", "درخواست کمپین نامعتبر است")) }
        }
        @JavascriptInterface fun cancelCampaign() = runOnUiThread { engine.cancelCampaign() }
        @JavascriptInterface fun logout() = runOnUiThread { engine.logout { secure.clear() } }
        @JavascriptInterface fun resetConnection() = runOnUiThread { engine.resetConnection { secure.clear() } }
    }

    @Deprecated("Maintains previous screen as an Android app instead of navigating to an unknown URL")
    override fun onBackPressed() { moveTaskToBack(true) }

    override fun onDestroy() {
        web.removeJavascriptInterface("PayamYar")
        web.destroy()
        super.onDestroy()
    }
}
