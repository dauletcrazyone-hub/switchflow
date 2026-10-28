package app.switchflow.companion

import android.app.PendingIntent
import android.content.Intent
import android.hardware.usb.UsbAccessory
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import app.switchflow.companion.core.CategoryPermissions
import app.switchflow.companion.core.Phase
import app.switchflow.companion.direct.DirectQr
import app.switchflow.companion.direct.DirectStage
import app.switchflow.companion.direct.UsbDirect
import app.switchflow.companion.net.CableLink
import app.switchflow.companion.ui.AllowAllCard
import app.switchflow.companion.ui.AllowAllScreen
import app.switchflow.companion.ui.AppHeader
import app.switchflow.companion.ui.CompanionScreen
import app.switchflow.companion.ui.DirectActions
import app.switchflow.companion.ui.DirectScreen
import app.switchflow.companion.ui.HomeScreen
import app.switchflow.companion.ui.MediaPickerScreen
import app.switchflow.companion.ui.ScreenFrame
import app.switchflow.companion.ui.SwitchFlowTheme
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions

private const val ACTION_CABLE_PERMISSION = "app.switchflow.companion.CABLE_PERMISSION"

class MainActivity : ComponentActivity() {
    private val controller get() = (application as SwitchFlowApp).controller
    private val direct get() = (application as SwitchFlowApp).direct

    /** The PC screen is open (from the home screen's "Computer" card). */
    private var pcOpen by mutableStateOf(false)

    private val scan = registerForActivityResult(ScanContract()) { result ->
        val raw = result.contents ?: return@registerForActivityResult
        val directActive = direct.state.value.stage != DirectStage.Idle
        when {
            DirectQr.isDirect(raw) -> direct.connectTo(raw)
            raw.trim().startsWith("switchflow://pair") -> {
                // The PC's code scanned from the phone-to-phone screen: pair with the PC instead.
                if (directActive) direct.leave()
                pcOpen = true
                controller.onQrScanned(raw)
            }
            directActive -> direct.connectTo(raw)
            else -> controller.onQrScanned(raw)
        }
    }

    private val permissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        controller.onPermissionsAnswered()
        direct.onPermissionsAnswered()
    }

    private val documents = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        // Keep read access for the whole transfer, even if the app restarts.
        uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        controller.onDocumentsPicked(uris)
    }

    private val directDocuments = registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris: List<Uri> ->
        uris.forEach { runCatching { contentResolver.takePersistableUriPermission(it, Intent.FLAG_GRANT_READ_URI_PERMISSION) } }
        direct.onDocumentsPicked(uris)
    }

    /** Permissions still missing; drives the "allow everything" screen and the home card. */
    private var missing by mutableStateOf(emptyList<String>())
    private var showAllowAll by mutableStateOf(false)
    private var askedBefore = emptyList<String>()

    private val allPermissions = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        val store = app.switchflow.companion.core.Store(this)
        store.allAsked = true
        showAllowAll = false
        val now = CategoryPermissions.missing(this)
        // Nothing changed: Android no longer shows the dialogs ("Don't ask again"). Open the app's settings.
        if (now.isNotEmpty() && now == askedBefore) openAppSettings()
        missing = now
        controller.onPermissionsAnswered()
        direct.onPermissionsAnswered()
    }

    private fun allowAll() {
        askedBefore = CategoryPermissions.missing(this)
        if (askedBefore.isEmpty()) { showAllowAll = false; return }
        allPermissions.launch(askedBefore.toTypedArray())
    }

    private fun openAppSettings() {
        runCatching {
            startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
        }
    }

    private fun launchScan(prompt: Int) {
        scan.launch(
            ScanOptions()
                .setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                .setPrompt(getString(prompt))
                .setBeepEnabled(false)
                .setOrientationLocked(false),
        )
    }

    private val iconPx get() = (44 * resources.displayMetrics.density).toInt()

    private val actions by lazy {
        DirectActions(
            send = { direct.startSend(); launchScan(R.string.scan_prompt_phone) },
            receive = direct::startReceive,
            openPc = { pcOpen = true },
            scan = { launchScan(R.string.scan_prompt_phone) },
            refreshQr = direct::startReceive,
            chooseRole = direct::chooseRole,
            allowRead = direct::requestReadAccess,
            allow = { ask -> permissions.launch(ask.permissions.toTypedArray()) },
            toggleCategory = direct::toggleCategory,
            pickDocuments = { directDocuments.launch(arrayOf("*/*")) },
            toggleApp = direct::toggleApp,
            selectAllApps = direct::selectAllApps,
            startSending = direct::send,
            cancel = direct::cancel,
            leave = direct::leave,
            installAll = direct::installAll,
            install = direct::install,
            removeApp = direct::removeFromInbox,
            allowInstalls = { runCatching { startActivity(direct.unknownSourcesIntent()) } },
            appIcon = { pkg -> direct.appIcon(pkg, iconPx) },
            inboxIcon = { pkg -> direct.inboxIcon(pkg, iconPx) },
            pickMedia = direct::openPicker,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        enableEdgeToEdge()
        super.onCreate(savedInstanceState)
        val store = app.switchflow.companion.core.Store(this)
        missing = CategoryPermissions.missing(this)
        // First start: one screen asks for everything (photos, videos, music, contacts, calendars, camera).
        showAllowAll = !store.allAsked && missing.isNotEmpty()
        direct.registerUsb()
        if (savedInstanceState == null) {
            handleTestPairingLink(intent)
            handleUsb(intent)
        }
        setContent {
            val state by controller.state.collectAsStateWithLifecycle()
            val directState by direct.state.collectAsStateWithLifecycle()
            SwitchFlowTheme {
                when {
                    showAllowAll -> ScreenFrame {
                        AllowAllScreen(onAllow = ::allowAll, onLater = {
                            store.allAsked = true
                            showAllowAll = false
                        })
                    }
                    directState.picking != null -> {
                        val c = directState.picking!!
                        val (items, albums) = remember(c) { direct.media(c) }
                        MediaPickerScreen(
                            category = c, items = items, albums = albums, initial = directState.picked[c],
                            thumbnail = direct::thumbnail,
                            onDone = { ids -> direct.setPicked(c, ids) },
                            onBack = direct::closePicker,
                        )
                    }
                    directState.stage != DirectStage.Idle -> ScreenFrame { DirectScreen(directState, actions) }
                    pcOpen || state.phase != Phase.Idle -> CompanionScreen(
                        state = state,
                        onScan = { launchScan(R.string.scan_prompt) },
                        onAllow = { ask -> permissions.launch(ask.permissions.toTypedArray()) },
                        onPickDocuments = { documents.launch(arrayOf("*/*")) },
                        onSkipDocuments = { controller.onDocumentsPicked(emptyList()) },
                        onDisconnect = controller::disconnect,
                        onDismissMessage = controller::dismissMessage,
                        onBack = if (state.phase == Phase.Idle) ({ pcOpen = false; controller.dismissMessage() }) else null,
                    )
                    else -> ScreenFrame {
                        AppHeader()
                        Spacer(Modifier.height(26.dp))
                        if (missing.isNotEmpty()) AllowAllCard(onAllow = ::allowAll)
                        HomeScreen(directState, actions)
                    }
                }
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleTestPairingLink(intent)
        handleUsb(intent)
    }

    /**
     * Cable: the system opened SwitchFlow for an accessory (the PC's, or another phone's), answered
     * our request, or — on the phone that is the USB host — brought the other phone in accessory mode.
     */
    private fun handleUsb(intent: Intent?) {
        val action = intent?.action ?: return
        if (action == UsbManager.ACTION_USB_DEVICE_ATTACHED) {
            val device = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let(direct::onUsbDevice)
            return
        }
        if (action != UsbManager.ACTION_USB_ACCESSORY_ATTACHED && action != ACTION_CABLE_PERMISSION) return
        val accessory = if (Build.VERSION.SDK_INT >= 33) {
            intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY, UsbAccessory::class.java)
        } else {
            @Suppress("DEPRECATION") intent.getParcelableExtra(UsbManager.EXTRA_ACCESSORY)
        } ?: return
        openAccessory(accessory)
    }

    private fun openAccessory(accessory: UsbAccessory) {
        if (UsbDirect.isOurAccessory(accessory)) direct.onAccessory(accessory) else controller.onAccessory(accessory)
    }

    /** The cable was connected while the app was closed and the system prompt was dismissed: ask again. */
    private fun checkConnectedAccessory() {
        val usb = getSystemService(UsbManager::class.java) ?: return
        val accessory = usb.accessoryList?.firstOrNull { CableLink.isOurs(it) || UsbDirect.isOurAccessory(it) } ?: return
        if (usb.hasPermission(accessory)) {
            openAccessory(accessory)
        } else {
            val back = Intent(this, MainActivity::class.java).setAction(ACTION_CABLE_PERMISSION)
            // Mutable: the system adds the accessory and the answer to this intent.
            val pending = PendingIntent.getActivity(this, 0, back, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE)
            usb.requestPermission(accessory, pending)
        }
    }

    override fun onResume() {
        super.onResume()
        missing = CategoryPermissions.missing(this)
        checkConnectedAccessory()
        direct.checkUsb()
        direct.refreshInbox()
    }

    /** Debug-only test hook (see src/debug/AndroidManifest.xml); release builds never pair from an intent. */
    private fun handleTestPairingLink(intent: Intent?) {
        if (!BuildConfig.DEBUG) return
        val data = intent?.data ?: return
        if (data.scheme == "switchflow") {
            val raw = data.toString()
            if (DirectQr.isDirect(raw)) direct.connectTo(raw) else controller.onQrScanned(raw)
        }
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
