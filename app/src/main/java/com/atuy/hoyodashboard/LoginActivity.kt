package com.atuy.hoyodashboard

import android.app.Activity
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebChromeClient
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView

class LoginActivity : ComponentActivity() {
    private var browser: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val manager = CookieManager.getInstance()
        manager.setAcceptCookie(true)
        setContent {
            MaterialTheme {
                Column(Modifier.fillMaxSize().safeDrawingPadding()) {
                    Row(Modifier.fillMaxWidth()) {
                        TextButton(onClick = { finish() }) { Text("戻る") }
                        TextButton(onClick = {
                            browser?.loadUrl("https://www.hoyolab.com/")
                        }) { Text("HoYoLAB") }
                        TextButton(onClick = {
                            browser?.loadUrl("https://www.miyoushe.com/")
                        }) { Text("米游社") }
                        Button(onClick = { finishLogin() }) { Text("連携する") }
                    }
                    Text("HoYoLABまたは米游社にログイン後、連携するを押してください。", Modifier.padding(horizontal = 12.dp))
                    AndroidView(
                        factory = { context ->
                            WebView(context).also { view ->
                                browser = view
                                view.settings.javaScriptEnabled = true
                                view.settings.domStorageEnabled = true
                                view.settings.allowFileAccess = false
                                view.settings.allowContentAccess = false
                                view.settings.mixedContentMode = android.webkit.WebSettings.MIXED_CONTENT_NEVER_ALLOW
                                view.webChromeClient = WebChromeClient()
                                view.webViewClient = WebViewClient()
                                manager.setAcceptThirdPartyCookies(view, true)
                                view.loadUrl("https://www.hoyolab.com/")
                            }
                        },
                        modifier = Modifier.weight(1f).fillMaxWidth()
                    )
                }
            }
        }
    }

    private fun finishLogin() {
        val manager = CookieManager.getInstance()
        manager.flush()
        val allowed = setOf(
            "ltuid", "ltoken", "ltuid_v2", "ltoken_v2", "ltmid", "ltmid_v2",
            "account_id", "account_id_v2", "account_mid_v2", "stuid",
            "cookie_token", "cookie_token_v2", "stoken", "stoken_v2",
            "login_ticket", "login_ticket_token", "e_nap_token", "e_hkrpg_token", "e_lrsag"
        )
        val cookies = linkedMapOf<String, String>()
        listOf(
            "https://www.hoyolab.com", "https://hoyolab.com",
            "https://account.hoyoverse.com", "https://api-os-takumi.mihoyo.com",
            "https://api-takumi.mihoyo.com", "https://api-takumi-record.mihoyo.com",
            "https://sg-public-api.hoyolab.com", "https://sg-act-public-api.hoyolab.com",
            "https://passport-api.mihoyo.com", "https://www.miyoushe.com",
            "https://bbs.mihoyo.com", "https://account.mihoyo.com"
        ).forEach { site ->
            manager.getCookie(site)?.split(';')?.forEach { part ->
                val separator = part.indexOf('=')
                if (separator > 0) {
                    val name = part.substring(0, separator).trim()
                    val value = part.substring(separator + 1).trim()
                    if (name in allowed && value.isNotBlank()) cookies[name] = value
                }
            }
        }
        val valid = (cookies.containsKey("ltuid_v2") && cookies.containsKey("ltoken_v2")) ||
            (cookies.containsKey("ltuid") && cookies.containsKey("ltoken")) ||
            (cookies.containsKey("stoken_v2") && cookies.containsKey("ltmid_v2"))
        if (!valid) {
            Toast.makeText(this, "HoYoLABにログインしてください", Toast.LENGTH_LONG).show()
            return
        }
        SecureCookieStore.save(this, cookies.entries.joinToString("; ") { it.key + "=" + it.value })
        setResult(Activity.RESULT_OK)
        finish()
    }

    override fun onDestroy() {
        browser?.stopLoading()
        browser?.destroy()
        browser = null
        super.onDestroy()
    }
}
