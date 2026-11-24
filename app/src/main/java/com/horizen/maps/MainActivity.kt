package com.horizen.maps

import android.Manifest
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.view.ViewGroup
import android.webkit.CookieManager
import android.webkit.GeolocationPermissions
import android.webkit.PermissionRequest
import android.webkit.SslErrorHandler
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.net.http.SslError
import android.widget.TextView
import java.net.URI

internal fun isTrustedMapUrl(value: String): Boolean {
    return try {
        val uri = URI(value)
        uri.scheme.equals("https", ignoreCase = true) &&
            uri.host.equals("maps.horizensearch.com", ignoreCase = true) &&
            (uri.port == -1 || uri.port == 443) &&
            uri.rawUserInfo == null
    } catch (_: Exception) {
        false
    }
}

class MainActivity : Activity() {
    private var mapView: WebView? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        WebView.setWebContentsDebuggingEnabled(false)

        if (checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) != PackageManager.PERMISSION_GRANTED &&
            checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION,
                    Manifest.permission.ACCESS_COARSE_LOCATION
                ),
                LOCATION_PERMISSION_REQUEST
            )
        }

        val view = WebView(this)
        mapView = view

        view.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            setGeolocationEnabled(true)
            allowFileAccess = false
            allowContentAccess = false
            allowFileAccessFromFileURLs = false
            allowUniversalAccessFromFileURLs = false
            mixedContentMode = WebSettings.MIXED_CONTENT_NEVER_ALLOW
            javaScriptCanOpenWindowsAutomatically = false
            setSupportMultipleWindows(false)
            safeBrowsingEnabled = true
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
            builtInZoomControls = false
            displayZoomControls = false
        }

        CookieManager.getInstance().apply {
            setAcceptCookie(false)
            setAcceptThirdPartyCookies(view, false)
        }

        view.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(
                view: WebView,
                request: WebResourceRequest
            ): Boolean = !isTrustedMapUrl(request.url.toString())

            @Suppress("DEPRECATION")
            override fun shouldOverrideUrlLoading(view: WebView, url: String): Boolean {
                return !isTrustedMapUrl(url)
            }

            override fun onReceivedSslError(
                view: WebView,
                handler: SslErrorHandler,
                error: SslError
            ) {
                handler.cancel()
            }

            override fun onRenderProcessGone(
                view: WebView,
                detail: android.webkit.RenderProcessGoneDetail
            ): Boolean {
                (view.parent as? ViewGroup)?.removeView(view)
                view.destroy()
                if (mapView === view) mapView = null
                setContentView(TextView(this@MainActivity).apply {
                    text = "HorizonMaps stopped unexpectedly. Close and reopen the app."
                    textSize = 18f
                    setPadding(32, 32, 32, 32)
                })
                return true
            }
        }

        view.webChromeClient = object : WebChromeClient() {
            override fun onGeolocationPermissionsShowPrompt(
                origin: String?,
                callback: GeolocationPermissions.Callback?
            ) {
                val trustedOrigin = origin?.let(::isTrustedMapUrl) == true
                val hasLocationPermission =
                    checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED ||
                    checkSelfPermission(Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED

                callback?.invoke(origin, trustedOrigin && hasLocationPermission, false)
            }

            override fun onPermissionRequest(request: PermissionRequest) {
                request.deny()
            }

            override fun onCreateWindow(
                view: WebView?,
                isDialog: Boolean,
                isUserGesture: Boolean,
                resultMsg: android.os.Message?
            ): Boolean = false
        }

        setContentView(view)
        view.loadUrl(TRUSTED_ORIGIN)
    }

    override fun onResume() {
        super.onResume()
        mapView?.onResume()
    }

    override fun onPause() {
        mapView?.onPause()
        super.onPause()
    }

    @Deprecated("Deprecated by Android; retained for WebView back navigation")
    override fun onBackPressed() {
        val view = mapView
        if (view != null && view.canGoBack()) {
            view.goBack()
        } else {
            super.onBackPressed()
        }
    }

    override fun onDestroy() {
        mapView?.let { view ->
            view.stopLoading()
            view.clearHistory()
            (view.parent as? ViewGroup)?.removeView(view)
            view.destroy()
        }
        mapView = null
        super.onDestroy()
    }

    companion object {
        private const val TRUSTED_ORIGIN = "https://maps.horizensearch.com"
        private const val LOCATION_PERMISSION_REQUEST = 100
    }
}
