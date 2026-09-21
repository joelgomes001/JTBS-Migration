package com.example.jtbs.ui.web

import android.annotation.SuppressLint
import android.app.Activity
import android.content.pm.ActivityInfo
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.viewinterop.AndroidView
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL

@SuppressLint("SetJavaScriptEnabled")
@Composable
fun WebPlayerScreen(
  modifier: Modifier = Modifier
) {
  val context = LocalContext.current
  val activity = context as Activity

  val currentVersion = "9.0.2"
  var showUpdateDialog by remember { mutableStateOf(false) }
  var latestVersionName by remember { mutableStateOf("") }
  var releaseNotes by remember { mutableStateOf("") }
  var appDownloadUrl by remember { mutableStateOf("https://github.com/joelgomes001/jtbs-classic/releases/latest") }

  // Set orientation to Landscape for WebView Player
  LaunchedEffect(Unit) {
    val compActivity = context as? ComponentActivity
    compActivity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
  }

  // Periodic automatic update check (on startup and every hour)
  LaunchedEffect(Unit) {
    while (true) {
      try {
        val githubJsonStr = makeHttpGet("https://api.github.com/repos/joelgomes001/jtbs-classic/releases/latest")
        if (githubJsonStr != null) {
          val githubJson = JSONObject(githubJsonStr)
          val tag = githubJson.getString("tag_name")
          val body = githubJson.optString("body", "")
          
          if (isNewerVersion(currentVersion, tag)) {
            // Fetch update URL from Firestore Config dynamically
            val firestoreJsonStr = makeHttpGet("https://firestore.googleapis.com/v1/projects/jtbs-classic/databases/(default)/documents/config/main")
            var downloadUrl = "https://github.com/joelgomes001/jtbs-classic/releases/latest"
            if (firestoreJsonStr != null) {
              try {
                val firestoreJson = JSONObject(firestoreJsonStr)
                downloadUrl = firestoreJson
                  .getJSONObject("fields")
                  .getJSONObject("appDownloadUrl")
                  .getString("stringValue")
              } catch (e: Exception) {
                e.printStackTrace()
              }
            }
            
            latestVersionName = tag
            releaseNotes = body
            appDownloadUrl = downloadUrl
            showUpdateDialog = true
          }
        }
      } catch (e: Exception) {
        e.printStackTrace()
      }
      delay(3600000) // 1 hour
    }
  }

  // Read local player.html from assets once
  val htmlString = remember {
    try {
      context.assets.open("player.html").bufferedReader().use { it.readText() }
    } catch (e: Exception) {
      "Error loading player: ${e.message}"
    }
  }

  var webViewRef by remember { mutableStateOf<WebView?>(null) }

  val lifecycleOwner = LocalLifecycleOwner.current
  DisposableEffect(lifecycleOwner, webViewRef) {
    val observer = LifecycleEventObserver { _, event ->
      if (event == Lifecycle.Event.ON_PAUSE) {
        webViewRef?.let { wv ->
          wv.evaluateJavascript("javascript:stopPlayer()", null)
          wv.onPause()
        }
      } else if (event == Lifecycle.Event.ON_RESUME) {
        webViewRef?.let { wv ->
          wv.onResume()
          wv.loadDataWithBaseURL(
            "https://jtbsclassic.dpdns.org/",
            htmlString,
            "text/html",
            "UTF-8",
            null
          )
        }
      }
    }
    lifecycleOwner.lifecycle.addObserver(observer)
    onDispose {
      lifecycleOwner.lifecycle.removeObserver(observer)
    }
  }

  AndroidView(
    factory = { ctx ->
      WebView(ctx).apply {
        layoutParams = ViewGroup.LayoutParams(
          ViewGroup.LayoutParams.MATCH_PARENT,
          ViewGroup.LayoutParams.MATCH_PARENT
        )
        
        // Force hardware acceleration explicitly on the WebView instance for smooth rendering
        setLayerType(View.LAYER_TYPE_HARDWARE, null)
        
        webViewClient = WebViewClient()
        
        // WebChromeClient to enable HTML5 video fullscreen support
        webChromeClient = object : WebChromeClient() {
          private var customView: View? = null
          private var customViewCallback: CustomViewCallback? = null

          override fun onShowCustomView(view: View?, callback: CustomViewCallback?) {
            if (customView != null) {
              callback?.onCustomViewHidden()
              return
            }
            customView = view
            customViewCallback = callback
            
            // Overlay the fullscreen video custom view on top of the Activity layout
            val decorView = activity.window.decorView as FrameLayout
            decorView.addView(view, FrameLayout.LayoutParams(
              ViewGroup.LayoutParams.MATCH_PARENT,
              ViewGroup.LayoutParams.MATCH_PARENT
            ))
            
            // Hide the webview content while video occupies fullscreen
            this@apply.visibility = View.GONE
          }

          override fun onHideCustomView() {
            if (customView == null) return
            
            val decorView = activity.window.decorView as FrameLayout
            decorView.removeView(customView)
            customView = null
            customViewCallback?.onCustomViewHidden()
            customViewCallback = null
            
            // Bring back the webview visibility
            this@apply.visibility = View.VISIBLE
          }
        }
        
        settings.apply {
          javaScriptEnabled = true
          domStorageEnabled = true
          databaseEnabled = true
          loadWithOverviewMode = true
          useWideViewPort = true
          
          // Performance and caching settings
          cacheMode = WebSettings.LOAD_DEFAULT
          safeBrowsingEnabled = false
          
          // Set Desktop User-Agent to bypass YouTube/browser mobile unmuted autoplay restrictions
          userAgentString = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/122.0.0.0 Safari/537.36 JTBS-Android-App"
          
          mixedContentMode = WebSettings.MIXED_CONTENT_ALWAYS_ALLOW
          mediaPlaybackRequiresUserGesture = false
          allowFileAccess = true
          allowContentAccess = true
        }
        
        // Initial load of the player
        loadDataWithBaseURL(
            "https://jtbsclassic.dpdns.org/",
            htmlString,
            "text/html",
            "UTF-8",
            null
        )
        
        webViewRef = this
      }
    },
    update = { webView ->
      // URL is static, no updates needed
    },
    modifier = modifier.fillMaxSize()
  )

  DisposableEffect(Unit) {
    onDispose {
      val compActivity = context as? ComponentActivity
      compActivity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    }
  }

  if (showUpdateDialog) {
    AlertDialog(
      onDismissRequest = { showUpdateDialog = false },
      title = {
        Text(
          text = "Update Available",
          style = MaterialTheme.typography.titleLarge,
          fontWeight = FontWeight.Bold
        )
      },
      text = {
        val scrollState = rememberScrollState()
        Column(
          modifier = Modifier.verticalScroll(scrollState)
        ) {
          Text(
            text = "A new version of the app is available.",
            style = MaterialTheme.typography.bodyMedium
          )
          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "Current Version: $currentVersion",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
          )
          Text(
            text = "Latest Version: $latestVersionName",
            style = MaterialTheme.typography.bodySmall,
            fontWeight = FontWeight.Medium
          )
          if (releaseNotes.isNotEmpty()) {
            Spacer(modifier = Modifier.height(12.dp))
            Text(
              text = "Release Notes:",
              style = MaterialTheme.typography.bodySmall,
              fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(4.dp))
            Text(
              text = releaseNotes,
              style = MaterialTheme.typography.bodySmall,
              color = MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      },
      confirmButton = {
        TextButton(
          onClick = {
            showUpdateDialog = false
            try {
              val intent = Intent(Intent.ACTION_VIEW, Uri.parse(appDownloadUrl))
              context.startActivity(intent)
            } catch (e: Exception) {
              e.printStackTrace()
            }
          }
        ) {
          Text(
            text = "Update",
            fontWeight = FontWeight.Bold
          )
        }
      },
      dismissButton = {
        TextButton(
          onClick = { showUpdateDialog = false }
        ) {
          Text(text = "Later")
        }
      }
    )
  }
}

private suspend fun makeHttpGet(urlString: String): String? {
  return withContext(Dispatchers.IO) {
    var connection: HttpURLConnection? = null
    try {
      val url = URL(urlString)
      connection = url.openConnection() as HttpURLConnection
      connection.requestMethod = "GET"
      connection.connectTimeout = 10000
      connection.readTimeout = 10000
      connection.inputStream.bufferedReader().use { it.readText() }
    } catch (e: Exception) {
      e.printStackTrace()
      null
    } finally {
      connection?.disconnect()
    }
  }
}

private fun isNewerVersion(current: String, latest: String): Boolean {
  val cleanCurrent = current.trim().removePrefix("v").split(".")
  val cleanLatest = latest.trim().removePrefix("v").split(".")
  val maxLength = maxOf(cleanCurrent.size, cleanLatest.size)
  for (i in 0 until maxLength) {
    val currPart = cleanCurrent.getOrNull(i)?.toIntOrNull() ?: 0
    val latePart = cleanLatest.getOrNull(i)?.toIntOrNull() ?: 0
    if (latePart > currPart) return true
    if (latePart < currPart) return false
  }
  return false
}
