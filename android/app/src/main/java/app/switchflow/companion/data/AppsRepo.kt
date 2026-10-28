package app.switchflow.companion.data

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.ApplicationInfo
import android.content.pm.PackageInfo
import android.content.pm.PackageInstaller
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.os.Build
import android.provider.Settings
import app.switchflow.companion.SwitchFlowApp
import app.switchflow.companion.net.ProtocolJson
import kotlinx.serialization.Serializable
import java.io.File

/** An app on this phone that can be copied to another Android: its installer files, nothing of its data. */
data class InstalledApp(
    val pkg: String,
    val label: String,
    val versionCode: Long,
    val versionName: String,
    val minSdk: Int,
    val apks: List<File>,
    val size: Long,
)

/**
 * Apps, Android to Android. Only installer files (base.apk + splits) are copied: logins, chats and
 * game saves live in each app's private storage, which no other app can read.
 */
class AppsRepo(private val context: Context) {
    private val pm get() = context.packageManager

    /** Apps the user installed and can open from the home screen; system apps are left out. */
    fun list(): List<InstalledApp> {
        val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        @Suppress("DEPRECATION")
        val pkgs = pm.queryIntentActivities(launcher, 0).map { it.activityInfo.packageName }.toSet() - context.packageName
        return pkgs.mapNotNull { pkg ->
            val info = runCatching { packageInfo(pkg) }.getOrNull() ?: return@mapNotNull null
            val ai = info.applicationInfo ?: return@mapNotNull null
            if (ai.flags and ApplicationInfo.FLAG_SYSTEM != 0) return@mapNotNull null
            val apks = (listOf(ai.sourceDir) + ai.splitSourceDirs.orEmpty()).map(::File)
            // Apps on SD cards or protected by old forward-locking are not readable.
            if (apks.isEmpty() || !apks.all { it.canRead() }) return@mapNotNull null
            InstalledApp(
                pkg = pkg,
                label = pm.getApplicationLabel(ai).toString().ifBlank { pkg },
                versionCode = versionCode(info),
                versionName = info.versionName.orEmpty(),
                minSdk = if (Build.VERSION.SDK_INT >= 24) ai.minSdkVersion else 0,
                apks = apks,
                size = apks.sumOf { it.length() },
            )
        }.sortedBy { it.label.lowercase() }
    }

    fun icon(pkg: String, sizePx: Int): Bitmap? = runCatching { toBitmap(pm.getApplicationIcon(pkg), sizePx) }.getOrNull()

    /** Installed version, or null when the app is not on this phone. */
    fun installedVersion(pkg: String): Long? = runCatching { versionCode(packageInfo(pkg)) }.getOrNull()

    @Suppress("DEPRECATION")
    private fun packageInfo(pkg: String): PackageInfo =
        if (Build.VERSION.SDK_INT >= 33) pm.getPackageInfo(pkg, PackageManager.PackageInfoFlags.of(0)) else pm.getPackageInfo(pkg, 0)

    @Suppress("DEPRECATION")
    private fun versionCode(info: PackageInfo): Long = if (Build.VERSION.SDK_INT >= 28) info.longVersionCode else info.versionCode.toLong()

    companion object {
        fun toBitmap(d: Drawable, size: Int): Bitmap {
            val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
            val c = Canvas(bmp)
            d.setBounds(0, 0, size, size)
            d.draw(c)
            return bmp
        }
    }
}

/** A received app waiting to be installed: files under filesDir/sf-apps/<pkg>/. */
@Serializable
data class InboxApp(val pkg: String, val label: String, val versionCode: Long, val parts: Int, val received: List<Int> = emptyList()) {
    val complete get() = received.toSet().size == parts
}

/**
 * Received installer files and installing them. Android asks the user to confirm every
 * install (and once, to let SwitchFlow install apps at all).
 */
class AppInbox(private val context: Context) {
    private val root get() = File(context.filesDir, "sf-apps").apply { mkdirs() }

    private fun dir(pkg: String) = File(root, safe(pkg))

    private fun safe(pkg: String) = pkg.filter { it.isLetterOrDigit() || it == '.' || it == '_' }.take(150)

    fun partFile(pkg: String, part: Int): File = File(dir(pkg).apply { mkdirs() }, "part$part.apk")

    private fun metaFile(pkg: String) = File(dir(pkg), "meta.json")

    @Synchronized
    fun markReceived(pkg: String, label: String, versionCode: Long, part: Int, parts: Int) {
        val prev = read(pkg)?.takeIf { it.versionCode == versionCode && it.parts == parts }
        val next = InboxApp(pkg, label, versionCode, parts, ((prev?.received ?: emptyList()) + part).distinct().sorted())
        metaFile(pkg).writeText(ProtocolJson.encodeToString(InboxApp.serializer(), next))
    }

    private fun read(pkg: String): InboxApp? =
        runCatching { ProtocolJson.decodeFromString(InboxApp.serializer(), metaFile(pkg).readText()) }.getOrNull()

    @Synchronized
    fun list(): List<InboxApp> = root.listFiles().orEmpty().filter { it.isDirectory }.mapNotNull { read(it.name) }.filter { it.complete }.sortedBy { it.label.lowercase() }

    /** The icon inside the received installer file, before the app is installed. */
    fun icon(pkg: String, sizePx: Int): Bitmap? {
        val f = File(dir(pkg), "part0.apk")
        if (!f.exists()) return null
        val pm = context.packageManager
        @Suppress("DEPRECATION")
        val ai = pm.getPackageArchiveInfo(f.path, 0)?.applicationInfo ?: return null
        ai.sourceDir = f.path
        ai.publicSourceDir = f.path
        return runCatching { AppsRepo.toBitmap(ai.loadIcon(pm), sizePx) }.getOrNull()
    }

    @Synchronized
    fun remove(pkg: String) {
        dir(pkg).deleteRecursively()
    }

    /** False until the user lets SwitchFlow install apps (Android 8+ "Install unknown apps"). */
    fun canInstall(): Boolean = Build.VERSION.SDK_INT < 26 || context.packageManager.canRequestPackageInstalls()

    fun unknownSourcesSettings(): Intent =
        Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, android.net.Uri.parse("package:${context.packageName}"))

    /** Starts the system install; the outcome arrives in [InstallResultReceiver]. */
    fun install(app: InboxApp) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            setAppPackageName(app.pkg)
        }
        val id = installer.createSession(params)
        installer.openSession(id).use { session ->
            for (part in 0 until app.parts) {
                val f = partFile(app.pkg, part)
                session.openWrite("part$part.apk", 0, f.length()).use { out ->
                    f.inputStream().use { it.copyTo(out, 1 shl 20) }
                    session.fsync(out)
                }
            }
            val result = Intent(context, InstallResultReceiver::class.java).putExtra(InstallResultReceiver.EXTRA_PKG, app.pkg)
            // Mutable: the installer adds the status and the confirmation intent to it.
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or (if (Build.VERSION.SDK_INT >= 31) PendingIntent.FLAG_MUTABLE else 0)
            session.commit(PendingIntent.getBroadcast(context, id, result, flags).intentSender)
        }
    }
}

/** Receives the installer's answers: shows its confirmation screen, then reports the result. */
class InstallResultReceiver : BroadcastReceiver() {
    companion object {
        const val EXTRA_PKG = "app.switchflow.companion.PKG"
    }

    override fun onReceive(context: Context, intent: Intent) {
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: return
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val direct = (context.applicationContext as SwitchFlowApp).direct
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= 33) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION") intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            if (confirm != null) {
                runCatching { context.startActivity(confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
                    .onFailure { direct.onInstallResult(pkg, false, "confirm_failed") }
            }
            return
        }
        val ok = status == PackageInstaller.STATUS_SUCCESS
        val code = when (status) {
            PackageInstaller.STATUS_SUCCESS -> null
            PackageInstaller.STATUS_FAILURE_ABORTED -> "aborted"
            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE -> "incompatible"
            PackageInstaller.STATUS_FAILURE_CONFLICT -> "conflict"
            PackageInstaller.STATUS_FAILURE_STORAGE -> "insufficient_storage"
            PackageInstaller.STATUS_FAILURE_INVALID -> "invalid"
            else -> "failed"
        }
        direct.onInstallResult(pkg, ok, code)
    }
}
