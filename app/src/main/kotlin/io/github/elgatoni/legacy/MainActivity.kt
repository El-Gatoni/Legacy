package io.github.elgatoni.legacy

import android.annotation.SuppressLint
import android.content.ContentValues
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.MediaStore
import android.util.Base64
import android.webkit.*
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewClientCompat

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private var fileChooserCallback: ValueCallback<Array<Uri>>? = null

    // Lanceur pour le sélecteur de fichier (import JSON/CSV)
    private val filePickerLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        fileChooserCallback?.onReceiveValue(if (uri != null) arrayOf(uri) else emptyArray())
        fileChooserCallback = null
    }

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        webView = WebView(this)
        setContentView(webView)

        // Servir les assets via une origine HTTPS stable → localStorage persiste entre les sessions
        val assetLoader = WebViewAssetLoader.Builder()
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        webView.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            allowFileAccess = true
            mediaPlaybackRequiresUserGesture = false
        }

        webView.webViewClient = object : WebViewClientCompat() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest) =
                assetLoader.shouldInterceptRequest(request.url)
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest) = false
        }

        webView.webChromeClient = object : WebChromeClient() {
            // Gère l'ouverture du sélecteur de fichier (import)
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileChooserCallback?.onReceiveValue(emptyArray())
                fileChooserCallback = callback
                val mimeTypes = params.acceptTypes
                    .flatMap { it.split(",") }
                    .map { it.trim() }
                    .filter { it.isNotEmpty() }
                    .map { extToMime(it) }
                    .filter { !it.startsWith(".") }
                    .distinct()
                    .toTypedArray()
                    .ifEmpty { arrayOf("*/*") }
                filePickerLauncher.launch(mimeTypes)
                return true
            }
            override fun onPermissionRequest(request: PermissionRequest) =
                request.grant(request.resources)
        }

        // Intercepte les téléchargements blob: (export JSON/CSV)
        webView.setDownloadListener { url, _, contentDisposition, mimeType, _ ->
            val filename = parseFilename(contentDisposition, mimeType)
            when {
                url.startsWith("blob:") ->
                    webView.evaluateJavascript("""
                        (async () => {
                            const r = await fetch('${url.replace("'", "\\'")}');
                            const b = await r.blob();
                            const fr = new FileReader();
                            fr.onloadend = () => Android.saveFile(fr.result, '$filename', '$mimeType');
                            fr.readAsDataURL(b);
                        })();
                    """.trimIndent(), null)
                url.startsWith("data:") -> {
                    val bytes = Base64.decode(url.substringAfter(","), Base64.DEFAULT)
                    saveToDownloads(bytes, filename, mimeType)
                }
            }
        }

        // Interface JavaScript → Android pour sauvegarder les exports
        webView.addJavascriptInterface(object {
            @JavascriptInterface
            fun saveFile(dataUrl: String, filename: String, mimeType: String) {
                val bytes = Base64.decode(dataUrl.substringAfter(","), Base64.DEFAULT)
                saveToDownloads(bytes, filename, mimeType)
            }
        }, "Android")

        webView.loadUrl("https://appassets.androidplatform.net/assets/index.html")

        // Bouton retour : demande confirmation avant de quitter
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                AlertDialog.Builder(this@MainActivity)
                    .setMessage("Quitter Legacy ?")
                    .setPositiveButton("Quitter") { _, _ -> finish() }
                    .setNegativeButton("Annuler", null)
                    .show()
            }
        })
    }

    private fun saveToDownloads(bytes: ByteArray, filename: String, mimeType: String) {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                val cv = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, filename)
                    put(MediaStore.Downloads.MIME_TYPE, mimeType)
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, cv) ?: return
                contentResolver.openOutputStream(uri)?.use { it.write(bytes) }
                cv.clear(); cv.put(MediaStore.Downloads.IS_PENDING, 0)
                contentResolver.update(uri, cv, null, null)
            } else {
                val dir = getExternalFilesDir(android.os.Environment.DIRECTORY_DOWNLOADS) ?: return
                dir.mkdirs()
                java.io.File(dir, filename).writeBytes(bytes)
            }
            runOnUiThread {
                Toast.makeText(this, "✓ $filename enregistré dans Téléchargements", Toast.LENGTH_LONG).show()
            }
        } catch (e: Exception) {
            runOnUiThread {
                Toast.makeText(this, "Erreur lors de l'enregistrement", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun extToMime(type: String): String = when (type.lowercase()) {
        ".json", "json" -> "application/json"
        ".csv", "csv"   -> "text/csv"
        ".txt", "txt"   -> "text/plain"
        else            -> type
    }

    private fun parseFilename(contentDisposition: String?, mimeType: String?): String {
        contentDisposition
            ?.let { Regex("""filename\*?=["']?(?:UTF-8'')?([^;"'\n]+)""").find(it)?.groupValues?.get(1)?.trim() }
            ?.takeIf { it.isNotEmpty() }
            ?.let { return it }
        return when {
            mimeType?.contains("json") == true -> "legacy-export.json"
            mimeType?.contains("csv")  == true -> "legacy-export.csv"
            else -> "legacy-export.bin"
        }
    }
}
