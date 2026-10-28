package app.switchflow.companion.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.slideInHorizontally
import androidx.compose.animation.slideOutHorizontally
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.ArrowDownward
import androidx.compose.material.icons.rounded.ArrowUpward
import androidx.compose.material.icons.rounded.Cable
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Computer
import androidx.compose.material.icons.rounded.ErrorOutline
import androidx.compose.material.icons.rounded.InstallMobile
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material.icons.rounded.Refresh
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material.icons.rounded.Usb
import androidx.compose.material.icons.rounded.Wifi
import androidx.compose.material.icons.rounded.WifiOff
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.switchflow.companion.R
import app.switchflow.companion.core.PermissionAsk
import app.switchflow.companion.data.InstalledApp
import app.switchflow.companion.direct.DirectCategory
import app.switchflow.companion.direct.DirectIntent
import app.switchflow.companion.direct.DirectStage
import app.switchflow.companion.direct.DirectUi
import app.switchflow.companion.direct.InstallRow
import app.switchflow.companion.net.Category
import app.switchflow.companion.net.Permission
import com.google.zxing.BarcodeFormat
import com.google.zxing.EncodeHintType
import com.google.zxing.qrcode.QRCodeWriter
import com.google.zxing.qrcode.decoder.ErrorCorrectionLevel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/** Everything the phone-to-phone screens can ask the activity or the controller to do. */
class DirectActions(
    val send: () -> Unit,
    val receive: () -> Unit,
    val openPc: () -> Unit,
    val scan: () -> Unit,
    val refreshQr: () -> Unit,
    val chooseRole: (String) -> Unit,
    val allowRead: () -> Unit,
    val allow: (PermissionAsk) -> Unit,
    val toggleCategory: (String) -> Unit,
    val pickDocuments: () -> Unit,
    val toggleApp: (String) -> Unit,
    val selectAllApps: (Boolean) -> Unit,
    val startSending: () -> Unit,
    val cancel: () -> Unit,
    val leave: () -> Unit,
    val installAll: () -> Unit,
    val install: (String) -> Unit,
    val removeApp: (String) -> Unit,
    val allowInstalls: () -> Unit,
    val appIcon: (String) -> Bitmap?,
    val inboxIcon: (String) -> Bitmap?,
    /** Opens the photo/video grid for a category. */
    val pickMedia: (String) -> Unit,
)

// ------------------------------------------------------------------- home

@Composable
fun HomeScreen(state: DirectUi, a: DirectActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Text(stringResource(R.string.home_title), style = MaterialTheme.typography.headlineMedium)
        Text(stringResource(R.string.home_subtitle), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(4.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction(Modifier.weight(1f), stringResource(R.string.send), stringResource(R.string.send_hint), Icons.Rounded.ArrowUpward, SF.Accent, a.send)
            BigAction(Modifier.weight(1f), stringResource(R.string.receive), stringResource(R.string.receive_hint), Icons.Rounded.ArrowDownward, SF.Success, a.receive)
        }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 4.dp)) {
            Pill(stringResource(R.string.via_qr), SF.Accent2, Icons.Rounded.Wifi)
            Pill(stringResource(R.string.via_type_c), SF.Accent2, Icons.Rounded.Cable)
        }
        AnimatedVisibility(state.usbWaiting) {
            SfCard(tint = SF.Accent.copy(alpha = 0.14f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(Icons.Rounded.Usb, SF.Accent)
                    Spacer(Modifier.width(14.dp))
                    Text(stringResource(R.string.usb_waiting), style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
        if (state.inbox.isNotEmpty()) InboxCard(state, a)
        SfCard(onClick = a.openPc) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.Computer, SF.Accent2)
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.pc_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.pc_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)) {
            Icon(Icons.Rounded.Lock, null, tint = SF.Success, modifier = Modifier.size(14.dp))
            Spacer(Modifier.width(6.dp))
            Text(stringResource(R.string.direct_secure), style = MaterialTheme.typography.labelMedium, color = SF.Success)
        }
    }
}

@Composable
private fun BigAction(modifier: Modifier, title: String, hint: String, icon: androidx.compose.ui.graphics.vector.ImageVector, color: Color, onClick: () -> Unit) {
    val y = floatOffset()
    Surface(
        modifier = modifier.clip(RoundedCornerShape(28.dp)).bouncyClick(pressedScale = 0.94f, onClick = onClick),
        color = MaterialTheme.colorScheme.surface,
    ) {
        Column(Modifier.padding(18.dp)) {
            Box(
                Modifier
                    .size(60.dp)
                    .graphicsLayer { translationY = y }
                    .clip(RoundedCornerShape(20.dp))
                    .background(Brush.linearGradient(listOf(color, color.copy(alpha = 0.65f)))),
                contentAlignment = Alignment.Center,
            ) { Icon(icon, null, tint = Color.White, modifier = Modifier.size(30.dp)) }
            Spacer(Modifier.height(18.dp))
            Text(title, fontSize = 20.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(hint, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

// --------------------------------------------------------------- direct

@Composable
fun DirectScreen(state: DirectUi, a: DirectActions) {
    BackHandler {
        if (state.stage == DirectStage.Transferring || state.stage == DirectStage.Reconnecting) a.cancel() else a.leave()
    }
    // A transfer must not stall because the screen went dark.
    val view = LocalView.current
    val busy = state.stage == DirectStage.Transferring || state.stage == DirectStage.Reconnecting || state.stage == DirectStage.Waiting
    DisposableEffect(busy) {
        view.keepScreenOn = busy
        onDispose { view.keepScreenOn = false }
    }
    Column(Modifier.fillMaxWidth()) {
        if (state.stage != DirectStage.Transferring && state.stage != DirectStage.Reconnecting) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).bouncyClick(onClick = a.leave), contentAlignment = Alignment.Center) {
                    Icon(Icons.Rounded.ArrowBack, stringResource(R.string.back))
                }
                Spacer(Modifier.width(12.dp))
                Text(
                    stringResource(if (state.role == DirectIntent.RECEIVE) R.string.receive else if (state.role == DirectIntent.SEND) R.string.send else R.string.home_title),
                    style = MaterialTheme.typography.titleLarge,
                )
            }
            Spacer(Modifier.height(18.dp))
        }
        state.permissionAsk?.let { PermissionCard(it, a) }
        AnimatedContent(
            targetState = state.stage to state.role,
            transitionSpec = { (fadeIn(tween(280)) + slideInHorizontally(tween(320)) { it / 8 }) togetherWith (fadeOut(tween(160)) + slideOutHorizontally(tween(200)) { -it / 10 }) },
            label = "direct",
        ) { (stage, role) ->
            when (stage) {
                DirectStage.Waiting -> if (role == DirectIntent.RECEIVE) ReceiveCode(state, a) else ScanPrompt(a)
                DirectStage.Connecting, DirectStage.Idle -> Connecting()
                DirectStage.Undecided -> Undecided(state, a)
                DirectStage.Choosing -> if (role == DirectIntent.SEND) Choose(state, a) else WaitForSender(state)
                DirectStage.Transferring, DirectStage.Reconnecting -> Transfer(state, a)
                DirectStage.Finished -> Finished(state, a)
                DirectStage.Failed -> Failed(state, a)
            }
        }
    }
}

@Composable
private fun PermissionCard(ask: PermissionAsk, a: DirectActions) {
    SfCard(Modifier.padding(bottom = 14.dp), tint = SF.Accent.copy(alpha = 0.16f)) {
        Column {
            Text(stringResource(R.string.access_title_direct), style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            val cats = ask.categories.map { categoryName(it) }.joinToString(", ")
            Text(stringResource(if (ask.write) R.string.access_body_write else R.string.access_body, cats), style = MaterialTheme.typography.bodyMedium)
            Spacer(Modifier.height(14.dp))
            PrimaryButton(stringResource(R.string.allow)) { a.allow(ask) }
        }
    }
}

@Composable
private fun ReceiveCode(state: DirectUi, a: DirectActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        if (state.qr == null) {
            SfCard(tint = SF.Warning.copy(alpha = 0.14f)) {
                Column {
                    IconBadge(Icons.Rounded.WifiOff, SF.Warning)
                    Spacer(Modifier.height(12.dp))
                    Text(stringResource(R.string.no_network_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.no_network_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    SecondaryButton(stringResource(R.string.refresh), icon = Icons.Rounded.Refresh, onClick = a.refreshQr)
                }
            }
        } else {
            QrCard(state.qr)
            Spacer(Modifier.height(20.dp))
            Text(stringResource(R.string.qr_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
            Spacer(Modifier.height(6.dp))
            Text(stringResource(R.string.qr_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
        Spacer(Modifier.height(18.dp))
        HintRow(Icons.Rounded.Wifi, stringResource(R.string.qr_network))
        Spacer(Modifier.height(10.dp))
        HintRow(Icons.Rounded.Cable, stringResource(R.string.qr_cable))
        if (state.qr != null) {
            Spacer(Modifier.height(14.dp))
            TextButton(onClick = a.refreshQr) { Text(stringResource(R.string.refresh_code)) }
        }
    }
}

@Composable
private fun HintRow(icon: androidx.compose.ui.graphics.vector.ImageVector, text: String) {
    Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
        Icon(icon, null, tint = SF.Accent2, modifier = Modifier.size(20.dp))
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

/** The QR code on white, inside a slowly turning gradient ring. */
@Composable
private fun QrCard(text: String) {
    val matrix = remember(text) {
        QRCodeWriter().encode(text, BarcodeFormat.QR_CODE, 0, 0, mapOf(EncodeHintType.MARGIN to 0, EncodeHintType.ERROR_CORRECTION to ErrorCorrectionLevel.M))
    }
    val spin = rememberInfiniteTransition(label = "qr")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(6000, easing = LinearEasing)), label = "a")
    Box(Modifier.fillMaxWidth(0.82f).aspectRatio(1f), contentAlignment = Alignment.Center) {
        Box(
            Modifier.matchParentSize().rotate(angle).clip(RoundedCornerShape(36.dp))
                .background(Brush.sweepGradient(listOf(SF.Accent, SF.Accent2, SF.Success, SF.Accent))),
        )
        Box(Modifier.matchParentSize().padding(5.dp).clip(RoundedCornerShape(32.dp)).background(Color.White).padding(22.dp)) {
            Canvas(Modifier.matchParentSize()) {
                val n = matrix.width
                val cell = size.minDimension / n
                for (y in 0 until n) for (x in 0 until n) {
                    if (matrix.get(x, y)) {
                        drawRect(Color(0xFF0B1220), Offset(x * cell, y * cell), androidx.compose.ui.geometry.Size(cell + 0.5f, cell + 0.5f))
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanPrompt(a: DirectActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        TwoPhones(Modifier.padding(vertical = 18.dp))
        Text(stringResource(R.string.scan_title), style = MaterialTheme.typography.titleLarge, textAlign = TextAlign.Center)
        Spacer(Modifier.height(6.dp))
        Text(stringResource(R.string.scan_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        Spacer(Modifier.height(22.dp))
        PrimaryButton(stringResource(R.string.scan_qr), icon = Icons.Rounded.QrCodeScanner, onClick = a.scan)
        Spacer(Modifier.height(14.dp))
        HintRow(Icons.Rounded.Cable, stringResource(R.string.qr_cable))
    }
}

/** Two phones with data hopping between them. */
@Composable
private fun TwoPhones(modifier: Modifier = Modifier) {
    val t = rememberInfiniteTransition(label = "hop")
    val p by t.animateFloat(0f, 1f, infiniteRepeatable(tween(1400, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "p")
    val text2 = MaterialTheme.colorScheme.onSurfaceVariant
    Canvas(modifier.size(width = 220.dp, height = 120.dp)) {
        val w = size.width
        val h = size.height
        val phoneW = w * 0.22f
        val phoneH = h * 0.9f
        val r = androidx.compose.ui.geometry.CornerRadius(phoneW * 0.22f)
        drawRoundRect(text2, Offset(0f, (h - phoneH) / 2), androidx.compose.ui.geometry.Size(phoneW, phoneH), r, style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
        drawRoundRect(text2, Offset(w - phoneW, (h - phoneH) / 2), androidx.compose.ui.geometry.Size(phoneW, phoneH), r, style = androidx.compose.ui.graphics.drawscope.Stroke(5f))
        for (i in 0 until 3) {
            val q = ((p + i / 3f) % 1f)
            val x = phoneW + 14f + (w - 2 * phoneW - 28f) * q
            val alpha = (1f - kotlin.math.abs(q - 0.5f) * 2f).coerceIn(0f, 1f)
            drawCircle(SF.Accent.copy(alpha = alpha), 9f, Offset(x, h / 2))
        }
    }
}

@Composable
private fun Connecting() {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 30.dp)) {
        TwoPhones()
        Spacer(Modifier.height(20.dp))
        Text(stringResource(R.string.connecting_direct), style = MaterialTheme.typography.titleMedium)
    }
}

@Composable
private fun Undecided(state: DirectUi, a: DirectActions) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        PeerChip(state)
        Text(stringResource(R.string.cable_choose), style = MaterialTheme.typography.titleLarge)
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            BigAction(Modifier.weight(1f), stringResource(R.string.send_here), stringResource(R.string.send_hint), Icons.Rounded.ArrowUpward, SF.Accent) { a.chooseRole(DirectIntent.SEND) }
            BigAction(Modifier.weight(1f), stringResource(R.string.receive_here), stringResource(R.string.receive_hint), Icons.Rounded.ArrowDownward, SF.Success) { a.chooseRole(DirectIntent.RECEIVE) }
        }
    }
}

@Composable
private fun PeerChip(state: DirectUi) {
    val peer = state.peer ?: return
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(20.dp)).background(MaterialTheme.colorScheme.surface).padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(10.dp).clip(CircleShape).background(if (state.stage == DirectStage.Reconnecting) SF.Warning else SF.Success))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(peer.name, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(
                "${peer.model} · ${if (peer.platform == "ios") "iOS" else "Android"} ${peer.os}",
                style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1,
            )
        }
        Pill(stringResource(if (state.viaCable) R.string.via_cable else R.string.via_wifi), SF.Success, if (state.viaCable) Icons.Rounded.Cable else Icons.Rounded.Wifi)
    }
}

@Composable
private fun WaitForSender(state: DirectUi) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        PeerChip(state)
        TwoPhones(Modifier.padding(vertical = 26.dp))
        Text(stringResource(R.string.waiting_sender, state.peer?.name ?: ""), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
    }
}

// ----------------------------------------------------------------- choose

@Composable
private fun Choose(state: DirectUi, a: DirectActions) {
    var showApps by rememberSaveable { mutableStateOf(false) }
    if (showApps) {
        AppPicker(state, a) { showApps = false }
        return
    }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        PeerChip(state)
        Spacer(Modifier.height(2.dp))
        Text(stringResource(R.string.choose_title), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
        if (state.needsReadAccess) {
            SfCard(tint = SF.Accent.copy(alpha = 0.14f)) {
                Column {
                    Text(stringResource(R.string.read_access_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(4.dp))
                    Text(stringResource(R.string.read_access_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(12.dp))
                    PrimaryButton(stringResource(R.string.allow), onClick = a.allowRead)
                }
            }
        }
        if (state.scanning && state.categories.isEmpty()) {
            Box(Modifier.fillMaxWidth().padding(30.dp), contentAlignment = Alignment.Center) { CircularProgressIndicator(strokeWidth = 3.dp) }
        }
        for (row in state.categories) {
            val isDocs = row.category == Category.DOCUMENTS
            val enabled = row.count > 0
            val subtitle = when {
                row.permission == Permission.DENIED -> stringResource(R.string.no_access)
                row.permission == Permission.UNAVAILABLE -> stringResource(R.string.unavailable)
                isDocs && row.count == 0L -> stringResource(R.string.docs_hint)
                row.count == 0L -> stringResource(R.string.empty)
                row.category == Category.CONTACTS || row.category == Category.CALENDARS -> row.count.toString()
                row.category in state.picked -> stringResource(R.string.picked_of, state.chosenCount(row).toInt(), row.count.toInt()) + " · " + bytes(state.chosenBytes(row))
                else -> "${row.count} · ${bytes(row.bytes)}"
            }
            val pickable = enabled && (row.category == Category.PHOTOS || row.category == Category.VIDEOS)
            CategoryItem(row.category, subtitle, row.selected, enabled || isDocs, onPick = if (pickable) ({ a.pickMedia(row.category) }) else null) {
                if (isDocs) a.pickDocuments() else a.toggleCategory(row.category)
            }
        }
        if (state.appsAllowed) {
            val apps = state.apps
            val sub = when {
                apps == null -> stringResource(R.string.loading)
                state.selectedApps.isEmpty() -> stringResource(R.string.apps_none_selected, apps.size)
                else -> stringResource(R.string.apps_selected, state.selectedApps.size, apps.size)
            }
            CategoryItem(DirectCategory.APPS, sub, state.selectedApps.isNotEmpty(), apps != null, chevron = true) { showApps = true }
        } else {
            Text(stringResource(R.string.apps_only_android), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
        }
        Spacer(Modifier.height(8.dp))
        val count = state.selectedCount
        PrimaryButton(
            if (count > 0) stringResource(R.string.send_btn, bytes(state.selectedBytes)) else stringResource(R.string.nothing_selected),
            enabled = count > 0,
            icon = Icons.Rounded.ArrowUpward,
            onClick = a.startSending,
        )
        Text(stringResource(R.string.originals_safe), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 6.dp))
    }
}

@Composable
private fun CategoryItem(
    category: String,
    subtitle: String,
    selected: Boolean,
    enabled: Boolean,
    chevron: Boolean = false,
    onPick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    Row(
        Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(22.dp))
            .background(if (selected) categoryColor(category).copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface)
            .bouncyClick(enabled, 0.97f, onClick)
            .padding(14.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        IconBadge(categoryIcon(category), categoryColor(category))
        Spacer(Modifier.width(14.dp))
        Column(Modifier.weight(1f)) {
            Text(categoryName(category), style = MaterialTheme.typography.titleMedium)
            Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (onPick != null) {
            Box(
                Modifier.padding(end = 10.dp).clip(CircleShape).background(categoryColor(category).copy(alpha = 0.16f))
                    .bouncyClick(onClick = onPick).padding(horizontal = 12.dp, vertical = 7.dp),
            ) { Text(stringResource(R.string.pick_items), color = categoryColor(category), style = MaterialTheme.typography.labelMedium) }
        }
        if (chevron) {
            if (selected) CheckDot(true) else Icon(Icons.Rounded.ChevronRight, null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        } else {
            CheckDot(selected, enabled)
        }
    }
}

@Composable
private fun AppPicker(state: DirectUi, a: DirectActions, onDone: () -> Unit) {
    BackHandler(onBack = onDone)
    var query by rememberSaveable { mutableStateOf("") }
    val apps = state.apps.orEmpty()
    val shown = if (query.isBlank()) apps else apps.filter { it.label.contains(query, true) }
    Column(verticalArrangement = Arrangement.spacedBy(10.dp), modifier = Modifier.fillMaxWidth()) {
        Text(stringResource(R.string.cat_apps), style = MaterialTheme.typography.titleLarge, modifier = Modifier.padding(start = 4.dp))
        Text(stringResource(R.string.apps_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.padding(horizontal = 4.dp))
        OutlinedTextField(
            value = query,
            onValueChange = { query = it },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true,
            leadingIcon = { Icon(Icons.Rounded.Search, null) },
            placeholder = { Text(stringResource(R.string.search)) },
            shape = RoundedCornerShape(18.dp),
            keyboardOptions = KeyboardOptions.Default,
            colors = OutlinedTextFieldDefaults.colors(unfocusedContainerColor = MaterialTheme.colorScheme.surface, focusedContainerColor = MaterialTheme.colorScheme.surface),
        )
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.apps_selected, state.selectedApps.size, apps.size), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(1f).padding(start = 6.dp))
            val all = apps.isNotEmpty() && state.selectedApps.size == apps.size
            TextButton(onClick = { a.selectAllApps(!all) }) { Text(stringResource(if (all) R.string.clear_all else R.string.select_all)) }
        }
        LazyColumn(Modifier.fillMaxWidth().height(420.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            items(shown, key = { it.pkg }) { app -> AppRow(app, app.pkg in state.selectedApps, a) }
        }
        PrimaryButton(stringResource(R.string.done_btn), onClick = onDone)
    }
}

@Composable
private fun AppRow(app: InstalledApp, selected: Boolean, a: DirectActions) {
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface)
            .bouncyClick(pressedScale = 0.97f) { a.toggleApp(app.pkg) }.padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        AppIcon(app.pkg, a.appIcon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${app.versionName.ifBlank { "—" }} · ${bytes(app.size)}", style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        CheckDot(selected)
    }
}

@Composable
private fun AppIcon(pkg: String, load: (String) -> Bitmap?) {
    val bmp by produceState<Bitmap?>(null, pkg) { value = withContext(Dispatchers.IO) { runCatching { load(pkg) }.getOrNull() } }
    Box(Modifier.size(44.dp).clip(RoundedCornerShape(12.dp)).background(MaterialTheme.colorScheme.surfaceVariant), contentAlignment = Alignment.Center) {
        bmp?.let { Image(it.asImageBitmap(), null, Modifier.size(44.dp)) } ?: Icon(categoryIcon(DirectCategory.APPS), null, tint = categoryColor(DirectCategory.APPS), modifier = Modifier.size(22.dp))
    }
}

// --------------------------------------------------------------- transfer

@Composable
private fun Transfer(state: DirectUi, a: DirectActions) {
    var confirm by remember { mutableStateOf(false) }
    val p = state.progress
    val fraction = if (p.bytesTotal > 0) p.bytesDone.toFloat() / p.bytesTotal else if (p.itemsTotal > 0) p.itemsDone.toFloat() / p.itemsTotal else 0f
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        PeerChip(state)
        Spacer(Modifier.height(26.dp))
        ProgressRing(fraction, Modifier.fillMaxWidth(0.72f).aspectRatio(1f)) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Text("${(fraction * 100).toInt()}%", fontSize = 46.sp, fontWeight = FontWeight.SemiBold)
                Text(
                    stringResource(if (state.role == DirectIntent.SEND) R.string.sending else R.string.receiving),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        }
        Spacer(Modifier.height(24.dp))
        Text(stringResource(R.string.progress_count, p.itemsDone, p.itemsTotal), style = MaterialTheme.typography.titleMedium)
        Spacer(Modifier.height(4.dp))
        val speed = if (p.bytesPerSec > 0) " · " + stringResource(R.string.speed, bytes(p.bytesPerSec)) else ""
        val eta = if (p.bytesPerSec > 0 && p.bytesTotal > p.bytesDone) " · " + stringResource(R.string.eta, duration((p.bytesTotal - p.bytesDone) / p.bytesPerSec)) else ""
        Text(stringResource(R.string.progress_bytes, bytes(p.bytesDone), bytes(p.bytesTotal)) + speed + eta, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        AnimatedContent(p.current to p.currentCategory, label = "file") { (name, cat) ->
            if (name != null && cat != null) {
                Row(Modifier.fillMaxWidth().clip(RoundedCornerShape(18.dp)).background(MaterialTheme.colorScheme.surface).padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    IconBadge(categoryIcon(cat), categoryColor(cat), 34.dp)
                    Spacer(Modifier.width(12.dp))
                    Text(name, style = MaterialTheme.typography.bodyMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
            } else {
                Spacer(Modifier.height(58.dp))
            }
        }
        AnimatedVisibility(state.stage == DirectStage.Reconnecting, enter = expandVertically() + fadeIn(), exit = shrinkVertically() + fadeOut()) {
            SfCard(Modifier.padding(top = 12.dp), tint = SF.Warning.copy(alpha = 0.16f)) {
                Text(stringResource(R.string.reconnecting_direct), style = MaterialTheme.typography.bodyMedium)
            }
        }
        if (p.failed > 0) {
            Spacer(Modifier.height(8.dp))
            Text(stringResource(R.string.failed_count, p.failed), style = MaterialTheme.typography.labelMedium, color = SF.Danger)
        }
        Spacer(Modifier.height(16.dp))
        Text(stringResource(R.string.keep_open_direct), style = MaterialTheme.typography.labelMedium, color = SF.Warning, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        SecondaryButton(stringResource(R.string.cancel), color = SF.Danger) { confirm = true }
    }
    if (confirm) {
        AlertDialog(
            onDismissRequest = { confirm = false },
            title = { Text(stringResource(R.string.cancel_confirm)) },
            text = { Text(stringResource(R.string.cancel_confirm_body)) },
            confirmButton = { TextButton(onClick = { confirm = false; a.cancel() }) { Text(stringResource(R.string.cancel), color = SF.Danger) } },
            dismissButton = { TextButton(onClick = { confirm = false }) { Text(stringResource(R.string.keep_going)) } },
        )
    }
}

@Composable
private fun duration(seconds: Long): String = when {
    seconds < 60 -> stringResource(R.string.dur_sec, seconds.coerceAtLeast(1))
    seconds < 3600 -> stringResource(R.string.dur_min, (seconds + 59) / 60)
    else -> stringResource(R.string.dur_hour, seconds / 3600, (seconds % 3600) / 60)
}

// --------------------------------------------------------------- results

@Composable
private fun Finished(state: DirectUi, a: DirectActions) {
    val r = state.result
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        SuccessMark()
        Spacer(Modifier.height(16.dp))
        Text(stringResource(if (state.role == DirectIntent.SEND) R.string.finished_send else R.string.finished_receive), style = MaterialTheme.typography.headlineMedium)
        state.peer?.let {
            Text(stringResource(if (state.role == DirectIntent.SEND) R.string.to_peer else R.string.from_peer, it.name), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (r != null) {
            Spacer(Modifier.height(20.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Stat(r.saved.toString(), stringResource(R.string.stat_saved), SF.Success)
                Stat(r.skipped.toString(), stringResource(R.string.stat_skipped), SF.Accent2)
                Stat(r.failed.toString(), stringResource(R.string.stat_failed), if (r.failed > 0) SF.Danger else MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (state.role == DirectIntent.RECEIVE && state.inbox.isNotEmpty()) {
            Spacer(Modifier.height(18.dp))
            InboxCard(state, a)
        }
        Spacer(Modifier.height(22.dp))
        PrimaryButton(stringResource(R.string.done_btn), onClick = a.leave)
    }
}

@Composable
private fun Failed(state: DirectUi, a: DirectActions) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 20.dp)) {
        Box(Modifier.size(96.dp).clip(CircleShape).background(SF.Danger.copy(alpha = 0.14f)), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.ErrorOutline, null, tint = SF.Danger, modifier = Modifier.size(48.dp))
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(errorText(state.error)), style = MaterialTheme.typography.titleMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(24.dp))
        PrimaryButton(stringResource(R.string.try_again)) {
            when (state.role) {
                DirectIntent.RECEIVE -> a.receive()
                DirectIntent.SEND -> a.send()
                else -> a.leave()
            }
        }
        Spacer(Modifier.height(10.dp))
        SecondaryButton(stringResource(R.string.back), onClick = a.leave)
    }
}

private fun errorText(code: String?) = when (code) {
    "unreachable" -> R.string.err_unreachable
    "bad_qr" -> R.string.err_bad_qr
    "old_qr" -> R.string.err_old_qr
    "lost" -> R.string.err_lost
    "cancelled" -> R.string.err_cancelled
    "peer_cancelled" -> R.string.err_peer_cancelled
    "both_send" -> R.string.err_both_send
    "both_receive" -> R.string.err_both_receive
    "busy" -> R.string.err_busy
    "insufficient_storage" -> R.string.err_insufficient_storage
    "listen_failed" -> R.string.err_listen_failed
    else -> R.string.err_generic
}

// ------------------------------------------------------------- apps inbox

@Composable
fun InboxCard(state: DirectUi, a: DirectActions) {
    SfCard {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                IconBadge(Icons.Rounded.InstallMobile, categoryColor(DirectCategory.APPS))
                Spacer(Modifier.width(14.dp))
                Column(Modifier.weight(1f)) {
                    Text(stringResource(R.string.install_title), style = MaterialTheme.typography.titleMedium)
                    Text(stringResource(R.string.install_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            if (!state.canInstall) {
                Text(stringResource(R.string.allow_install_body), style = MaterialTheme.typography.bodyMedium)
                PrimaryButton(stringResource(R.string.open_settings), onClick = a.allowInstalls)
            } else {
                for (row in state.inbox) InboxRow(row, a)
                if (state.inbox.count { it.state == "ready" || it.state == "failed" } > 1) {
                    PrimaryButton(stringResource(R.string.install_all), icon = Icons.Rounded.InstallMobile, onClick = a.installAll)
                }
            }
        }
    }
}

@Composable
private fun InboxRow(row: InstallRow, a: DirectActions) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        AppIcon(row.app.pkg, a.inboxIcon)
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(row.app.label, style = MaterialTheme.typography.titleMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val status = when (row.state) {
                "installing" -> stringResource(R.string.installing)
                "installed" -> stringResource(R.string.installed)
                "failed" -> stringResource(R.string.install_failed)
                else -> stringResource(R.string.ready_to_install)
            }
            Text(status, style = MaterialTheme.typography.labelMedium, color = if (row.state == "failed") SF.Danger else MaterialTheme.colorScheme.onSurfaceVariant)
        }
        when (row.state) {
            "installing" -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            "installed" -> CheckDot(true)
            else -> Row {
                TextButton(onClick = { a.removeApp(row.app.pkg) }) { Text(stringResource(R.string.remove), color = MaterialTheme.colorScheme.onSurfaceVariant) }
                Box(
                    Modifier.clip(RoundedCornerShape(14.dp)).background(AccentGradient).bouncyClick { a.install(row.app.pkg) }.padding(horizontal = 14.dp, vertical = 9.dp),
                ) { Text(stringResource(R.string.install), color = Color.White, fontWeight = FontWeight.SemiBold) }
            }
        }
    }
}

/** Full-screen frame shared by the home and phone-to-phone screens. */
@Composable
fun ScreenFrame(content: @Composable () -> Unit) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 20.dp, vertical = 14.dp),
        ) { content() }
    }
}
