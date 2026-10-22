package app.switchflow.companion

import android.Manifest
import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.getValue
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.switchflow.companion.net.CableLink
import app.switchflow.companion.ui.CompanionScreen
import app.switchflow.companion.ui.SwitchFlowTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private const val ACTION_CABLE_PERMISSION = "app.switchflow.companion.CABLE_PERMISSION"

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
        if (savedInstanceState == null) {
            handleTestPairingLink(intent)
            handleAccessory(intent)
        }
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
        handleAccessory(intent)
    }

    /** Cable transfer: the system opened SwitchFlow for the PC's accessory, or answered our request. */
    private fun handleAccessory(intent: Intent?) {
        val action = intent?.action ?: return
        if (action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED && action != ACTION_CABLE_PERMISSION) return
        val accessory = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        }
        accessory?.let(controller::onAccessory)
    }

    /** The cable was connected while the app was closed and the system prompt was dismissed: ask again. */
    private fun checkConnectedAccessory() {
        val usb = getSystemService(UsbManager::class.java) ?: return
        val accessory = usb.accessoryList?.firstOrNull(CableLink::isOurs) ?: return
        if (usb.hasPermission(accessory)) {
            controller.onAccessory(accessory)
        } else {
            val back = Intent(this, MainActivity::class.java).setAction(ACTION_CABLE_PERMISSION)
            // Mutable: the system adds the accessory and the answer to this intent.
            val pending = PendingIntent.getActivity(this, 0, back, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(accessory, pending)
        }
    }

    override fun onResume() {
        super.onResume()
        checkConnectedAccessory()
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
