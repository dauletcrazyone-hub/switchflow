package app.switchflow.companion.ui

import androidx.compose.animation.AnimatedContent
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.togetherWith
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.QrCodeScanner
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.switchflow.companion.R
import app.switchflow.companion.core.Message
import app.switchflow.companion.core.PermissionAsk
import app.switchflow.companion.core.Phase
import app.switchflow.companion.core.UiState
import app.switchflow.companion.net.Category

@Composable
fun CompanionScreen(
    state: UiState,
    onScan: () -> Unit,
    onAllow: (PermissionAsk) -> Unit,
    onPickDocuments: () -> Unit,
    onSkipDocuments: () -> Unit,
    onDisconnect: () -> Unit,
    onDismissMessage: () -> Unit,
    onBack: (() -> Unit)? = null,
) {
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(
            Modifier
                .safeDrawingPadding()
                .verticalScroll(rememberScrollState())
                .padding(horizontal = 22.dp, vertical = 16.dp),
        ) {
            if (onBack != null) {
                androidx.activity.compose.BackHandler(onBack = onBack)
                Box(
                    Modifier.padding(bottom = 14.dp).size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).bouncyClick(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { androidx.compose.material3.Icon(Icons.Rounded.ArrowBack, stringResource(R.string.back)) }
            }
            AppHeader()
            Spacer(Modifier.height(28.dp))
            AnimatedContent(
                targetState = state.phase,
                transitionSpec = { (fadeIn(tween(320)) + slideInVertically(tween(320)) { it / 12 }) togetherWith fadeOut(tween(180)) },
                label = "phase",
            ) { phase ->
                when (phase) {
                    Phase.Idle -> Welcome(state.message, onScan, onDismissMessage)
                    Phase.Connecting -> Waiting(stringResource(R.string.connecting, state.desktopName ?: "PC"), null)
                    Phase.AwaitingApproval -> Waiting(stringResource(R.string.waiting_approval), state.verificationCode)
                    Phase.Connected, Phase.Reconnecting -> Connected(state, onAllow, onPickDocuments, onSkipDocuments, onDisconnect)
                }
            }
        }
    }
}

@Composable
fun AppHeader() {
    Row(verticalAlignment = Alignment.CenterVertically) {
        LogoMark(36.dp)
        Spacer(Modifier.width(12.dp))
        Column {
            Text("SwitchFlow", style = MaterialTheme.typography.titleLarge)
            Text(stringResource(R.string.slogan), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun LogoMark(size: androidx.compose.ui.unit.Dp) {
    Canvas(Modifier.size(size).clip(RoundedCornerShape(size * 0.23f)).background(Brush.linearGradient(listOf(Color(0xFF1B2538), Color(0xFF070A11))))) {
        val w = this.size.width
        val path = androidx.compose.ui.graphics.Path().apply {
            moveTo(w * 0.31f, w * 0.46f)
            cubicTo(w * 0.41f, w * 0.74f, w * 0.59f, w * 0.24f, w * 0.69f, w * 0.55f)
        }
        drawPath(path, Brush.linearGradient(listOf(SF.Accent, SF.Accent2)), style = Stroke(width = w * 0.075f, cap = StrokeCap.Round))
        drawCircle(SF.Accent, w * 0.05f, Offset(w * 0.31f, w * 0.46f))
        drawCircle(SF.Accent2, w * 0.05f, Offset(w * 0.69f, w * 0.55f))
    }
}

@Composable
private fun Card(modifier: Modifier = Modifier, tint: Color? = null, content: @Composable () -> Unit) {
    Surface(
        modifier = modifier.fillMaxWidth(),
        shape = RoundedCornerShape(22.dp),
        color = tint ?: MaterialTheme.colorScheme.surface,
        tonalElevation = 0.dp,
    ) { Box(Modifier.padding(20.dp)) { content() } }
}

@Composable
private fun PulsingPhone() {
    val t = rememberInfiniteTransition(label = "pulse")
    val scale by t.animateFloat(0.85f, 1.35f, infiniteRepeatable(tween(2200, easing = FastOutSlowInEasing), RepeatMode.Restart), label = "s")
    val alpha by t.animateFloat(0.5f, 0f, infiniteRepeatable(tween(2200), RepeatMode.Restart), label = "a")
    Box(Modifier.size(160.dp), contentAlignment = Alignment.Center) {
        Canvas(Modifier.size(120.dp)) {
            drawCircle(SF.Accent.copy(alpha = alpha), radius = size.minDimension / 2 * scale, style = Stroke(2.dp.toPx()))
        }
        Canvas(Modifier.size(width = 52.dp, height = 104.dp)) {
            drawRoundRect(
                color = SF.Text2,
                cornerRadius = androidx.compose.ui.geometry.CornerRadius(12.dp.toPx()),
                style = Stroke(2.5.dp.toPx(), pathEffect = androidx.compose.ui.graphics.PathEffect.dashPathEffect(floatArrayOf(14f, 12f))),
            )
        }
    }
}

@Composable
private fun Welcome(message: Message?, onScan: () -> Unit, onDismiss: () -> Unit) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth()) {
        PulsingPhone()
        Spacer(Modifier.height(12.dp))
        Text(stringResource(R.string.connect), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.connect_hint),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(28.dp))
        PrimaryButton(stringResource(R.string.connect), icon = Icons.Rounded.QrCodeScanner) { onDismiss(); onScan() }
        AnimatedVisibility(message != null) {
            val text = when (message) {
                Message.BadQr -> R.string.bad_qr
                Message.Unreachable -> R.string.unreachable
                Message.Denied -> R.string.denied
                Message.Expired -> R.string.expired
                Message.Forgotten -> R.string.forgotten
                null -> R.string.app_name
            }
            Card(Modifier.padding(top = 18.dp), tint = SF.Danger.copy(alpha = 0.14f)) {
                Text(stringResource(text), style = MaterialTheme.typography.bodyMedium)
            }
        }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.secure), style = MaterialTheme.typography.labelMedium, color = SF.Success)
    }
}

@Composable
private fun Waiting(title: String, code: String?) {
    Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.fillMaxWidth().padding(top = 24.dp)) {
        if (code != null) {
            Text(stringResource(R.string.compare_code), style = MaterialTheme.typography.bodyLarge, textAlign = TextAlign.Center, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Spacer(Modifier.height(18.dp))
            Text(
                code.chunked(3).joinToString(" "),
                fontSize = 44.sp,
                fontWeight = FontWeight.SemiBold,
                letterSpacing = 6.sp,
                color = MaterialTheme.colorScheme.onBackground,
            )
            Spacer(Modifier.height(24.dp))
        }
        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary, strokeWidth = 3.dp, modifier = Modifier.size(28.dp))
        Spacer(Modifier.height(14.dp))
        Text(title, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
    }
}

@Composable
private fun categoryLabel(c: String) = stringResource(
    when (c) {
        Category.PHOTOS -> R.string.cat_photos
        Category.VIDEOS -> R.string.cat_videos
        Category.CONTACTS -> R.string.cat_contacts
        Category.DOCUMENTS -> R.string.cat_documents
        Category.MUSIC -> R.string.cat_music
        else -> R.string.cat_calendars
    },
)

@Composable
private fun Connected(
    state: UiState,
    onAllow: (PermissionAsk) -> Unit,
    onPickDocuments: () -> Unit,
    onSkipDocuments: () -> Unit,
    onDisconnect: () -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
        Card {
            Column {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(Modifier.size(10.dp).clip(CircleShape).background(if (state.phase == Phase.Connected) SF.Success else SF.Warning))
                    Spacer(Modifier.width(10.dp))
                    Text(
                        if (state.phase == Phase.Connected) stringResource(R.string.connected_to, state.desktopName ?: "PC") else stringResource(R.string.reconnecting),
                        style = MaterialTheme.typography.titleMedium,
                    )
                }
                Spacer(Modifier.height(6.dp))
                Text(
                    stringResource(
                        when (state.role) {
                            "source" -> R.string.role_source
                            "target" -> R.string.role_target
                            else -> R.string.role_none
                        },
                    ),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Spacer(Modifier.height(4.dp))
                Text(stringResource(R.string.secure) + if (state.viaCable) " · USB" else " · Wi-Fi", style = MaterialTheme.typography.labelMedium, color = SF.Success)
            }
        }

        state.permissionAsk?.let { ask ->
            Card(tint = SF.Accent.copy(alpha = 0.16f)) {
                Column {
                    Text(stringResource(R.string.access_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    val cats = ask.categories.map { categoryLabel(it) }.joinToString(", ")
                    Text(stringResource(if (ask.write) R.string.access_body_write else R.string.access_body, cats), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    PrimaryButton(stringResource(R.string.allow)) { onAllow(ask) }
                }
            }
        }

        if (state.documentsAsk != null) {
            Card(tint = SF.Accent.copy(alpha = 0.16f)) {
                Column {
                    Text(stringResource(R.string.pick_docs_title), style = MaterialTheme.typography.titleMedium)
                    Spacer(Modifier.height(6.dp))
                    Text(stringResource(R.string.pick_docs_body), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(14.dp))
                    Row {
                        PrimaryButton(stringResource(R.string.pick_docs), Modifier.weight(1f), onClick = onPickDocuments)
                        Spacer(Modifier.width(8.dp))
                        TextButton(onClick = onSkipDocuments) { Text(stringResource(R.string.skip)) }
                    }
                }
            }
        }

        state.transfer?.let { t ->
            Card {
                Column {
                    Text(
                        when (t.finished) {
                            "done" -> stringResource(R.string.done)
                            "cancelled" -> stringResource(R.string.cancelled)
                            else -> if (t.paused) stringResource(R.string.paused) else stringResource(if (t.sending) R.string.sending else R.string.receiving)
                        },
                        style = MaterialTheme.typography.titleMedium,
                    )
                    Spacer(Modifier.height(10.dp))
                    if (t.itemsTotal > 0) {
                        LinearProgressIndicator(
                            progress = { (t.itemsDone.toFloat() / t.itemsTotal).coerceIn(0f, 1f) },
                            modifier = Modifier.fillMaxWidth().height(6.dp).clip(CircleShape),
                            color = MaterialTheme.colorScheme.primary,
                            trackColor = MaterialTheme.colorScheme.surfaceVariant,
                            strokeCap = StrokeCap.Round,
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(stringResource(R.string.progress_count, t.itemsDone, t.itemsTotal), style = MaterialTheme.typography.bodyMedium)
                    } else if (t.finished == null) {
                        LinearProgressIndicator(Modifier.fillMaxWidth().clip(CircleShape))
                        Spacer(Modifier.height(8.dp))
                        Text("${t.itemsDone}", style = MaterialTheme.typography.bodyMedium)
                    }
                    t.currentName?.let {
                        Spacer(Modifier.height(4.dp))
                        Text(it, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    }
                    if (t.finished == null) {
                        Spacer(Modifier.height(10.dp))
                        Text(stringResource(R.string.keep_open), style = MaterialTheme.typography.labelMedium, color = SF.Warning)
                    }
                }
            }
        }

        Text(
            stringResource(R.string.originals_safe),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(horizontal = 4.dp),
        )
        SecondaryButton(stringResource(R.string.disconnect), color = SF.Danger, onClick = onDisconnect)
    }
}
