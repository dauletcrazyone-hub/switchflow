package app.switchflow.companion

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.switchflow.companion.ui.CompanionScreen
import app.switchflow.companion.ui.SwitchFlowTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

class MainActivity : ComponentActivity() {
    private val controller get() = (application as SwitchFlowApp).controller

    private val scan = registerForActivityResult(ScanContract()) { result ->
        result.contents?.let(controller::onQrScanned)
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.onPermissionsAnswered()
    }

    private val documents = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        // Keep read access for the whole transfer, even if the app restarts.
        uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        controller.onDocumentsPicked(uris)
    }

    private val notifications = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = app.switchflow.companion.core.Store(this)
        if (Build.VERSION.SDK_INT >= 33 && !store.notificationsAsked) {
            store.notificationsAsked = true
            notifications.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
        if (savedInstanceState == null) handleTestPairingLink(intent)
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            SwitchFlowTheme {
                CompanionScreen(
                    state = state,
                    onScan = {
                        scan.launch(
                            ScanOptions()
                                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                                .setPrompt(getString(R.string.scan_prompt))
                                .setBeepEnabled(false)
                                .setOrientationLocked(false),
                        )
                    },
                    onAllow = { ask -> permissions.launch(ask.permissions.toTypedArray()) },
                    onPickDocuments = { documents.launch(arrayOf("*/*")) },
                    onSkipDocuments = { controller.onDocumentsPicked(emptyList()) },
                    onDisconnect = controller::disconnect,
                    onDismissMessage = controller::dismissMessage,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestPairingLink(intent)
    }

    /** Debug-only test hook (see src/debug/AndroidManifest.xml); release builds never pair from an intent. */
    private fun handleTestPairingLink(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val data = intent?.data ?: return
        if (data.scheme == "switchflow") controller.onQrScanned(data.toString())
    }

    override fun onStart() {
        super.onStart()
        controller.foreground = true
    }

    override fun onStop() {
        controller.foreground = false
        super.onStop()
    }
}
