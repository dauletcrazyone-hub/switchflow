package app.switchflow.companion.ui

import android.text.format.Formatter
import android.view.HapticFeedbackConstants
import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.LocalIndication
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Apps
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.composed
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathMeasure
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.switchflow.companion.R
import app.switchflow.companion.direct.DirectCategory
import app.switchflow.companion.net.Category

/**
 * Press feedback used by every tappable thing: a springy shrink while the finger is down,
 * a short haptic tick, and the system ripple.
 */
fun Modifier.bouncyClick(enabled: Boolean = true, pressedScale: Float = 0.95f, onClick: () -> Unit): Modifier = composed {
    val interaction = remember { MutableInteractionSource() }
    val pressed by interaction.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (pressed && enabled) pressedScale else 1f,
        animationSpec = spring(dampingRatio = 0.45f, stiffness = Spring.StiffnessMediumLow),
        label = "press",
    )
    val view = LocalView.current
    this
        .graphicsLayer { scaleX = scale; scaleY = scale }
        .clickable(interaction, LocalIndication.current, enabled = enabled) {
            view.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            onClick()
        }
}

val AccentGradient get() = Brush.linearGradient(listOf(SF.Accent, SF.Accent2))

/** The main call to action: gradient pill with a press animation. */
@Composable
fun PrimaryButton(text: String, modifier: Modifier = Modifier, enabled: Boolean = true, icon: ImageVector? = null, onClick: () -> Unit) {
    val alpha by animateFloatAsState(if (enabled) 1f else 0.4f, label = "enabled")
    Box(
        modifier
            .fillMaxWidth()
            .height(58.dp)
            .graphicsLayer { this.alpha = alpha }
            .clip(RoundedCornerShape(20.dp))
            .background(AccentGradient)
            .bouncyClick(enabled, 0.97f, onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = Color.White, modifier = Modifier.size(22.dp))
                Spacer(Modifier.width(10.dp))
            }
            Text(text, color = Color.White, fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
        }
    }
}

@Composable
fun SecondaryButton(text: String, modifier: Modifier = Modifier, color: Color = MaterialTheme.colorScheme.onSurface, icon: ImageVector? = null, onClick: () -> Unit) {
    Box(
        modifier
            .fillMaxWidth()
            .height(52.dp)
            .clip(RoundedCornerShape(18.dp))
            .background(MaterialTheme.colorScheme.surface)
            .border(1.dp, MaterialTheme.colorScheme.onSurface.copy(alpha = 0.08f), RoundedCornerShape(18.dp))
            .bouncyClick(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            if (icon != null) {
                Icon(icon, null, tint = color, modifier = Modifier.size(20.dp))
                Spacer(Modifier.width(8.dp))
            }
            Text(text, color = color, fontSize = 16.sp, fontWeight = FontWeight.Medium)
        }
    }
}

/** A rounded surface; tappable when [onClick] is set. */
@Composable
fun SfCard(modifier: Modifier = Modifier, tint: Color? = null, onClick: (() -> Unit)? = null, content: @Composable () -> Unit) {
    val base = modifier
        .fillMaxWidth()
        .clip(RoundedCornerShape(24.dp))
    Surface(
        modifier = if (onClick != null) base.bouncyClick(pressedScale = 0.97f, onClick = onClick) else base,
        color = tint ?: MaterialTheme.colorScheme.surface,
    ) { Box(Modifier.padding(18.dp)) { content() } }
}

/** Icon in a soft coloured circle. */
@Composable
fun IconBadge(icon: ImageVector, color: Color, size: Dp = 44.dp) {
    Box(Modifier.size(size).clip(CircleShape).background(color.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
        Icon(icon, null, tint = color, modifier = Modifier.size(size * 0.5f))
    }
}

/** Animated round check box. */
@Composable
fun CheckDot(checked: Boolean, enabled: Boolean = true) {
    val bg by animateColorAsState(if (checked) SF.Accent else Color.Transparent, label = "check")
    val scale by animateFloatAsState(if (checked) 1f else 0.4f, spring(dampingRatio = 0.4f, stiffness = Spring.StiffnessMedium), label = "tick")
    Box(
        Modifier
            .size(26.dp)
            .graphicsLayer { alpha = if (enabled) 1f else 0.35f }
            .clip(CircleShape)
            .background(bg)
            .border(2.dp, if (checked) SF.Accent else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f), CircleShape),
        contentAlignment = Alignment.Center,
    ) {
        if (checked) Icon(Icons.Rounded.Check, null, tint = Color.White, modifier = Modifier.size(18.dp).graphicsLayer { scaleX = scale; scaleY = scale })
    }
}

fun categoryIcon(c: String): ImageVector = when (c) {
    Category.PHOTOS -> Icons.Rounded.Photo
    Category.VIDEOS -> Icons.Rounded.Videocam
    Category.CONTACTS -> Icons.Rounded.Contacts
    Category.CALENDARS -> Icons.Rounded.CalendarMonth
    Category.MUSIC -> Icons.Rounded.MusicNote
    DirectCategory.APPS -> Icons.Rounded.Apps
    else -> Icons.Rounded.Description
}

fun categoryColor(c: String): Color = when (c) {
    Category.PHOTOS -> Color(0xFF4F8DFF)
    Category.VIDEOS -> Color(0xFFB66DFF)
    Category.CONTACTS -> Color(0xFF35C98A)
    Category.CALENDARS -> Color(0xFFFF7A59)
    Category.MUSIC -> Color(0xFFFF5C93)
    DirectCategory.APPS -> Color(0xFF2EC5D3)
    else -> Color(0xFFF2B450)
}

@Composable
fun categoryName(c: String) = stringResource(
    when (c) {
        Category.PHOTOS -> R.string.cat_photos
        Category.VIDEOS -> R.string.cat_videos
        Category.CONTACTS -> R.string.cat_contacts
        Category.DOCUMENTS -> R.string.cat_documents
        Category.MUSIC -> R.string.cat_music
        DirectCategory.APPS -> R.string.cat_apps
        else -> R.string.cat_calendars
    },
)

@Composable
fun bytes(n: Long): String = Formatter.formatShortFileSize(LocalContext.current, n.coerceAtLeast(0))

/** Ring progress with a soft gradient and an animated sweep. */
@Composable
fun ProgressRing(progress: Float, modifier: Modifier = Modifier, stroke: Dp = 14.dp, content: @Composable () -> Unit) {
    val sweep by animateFloatAsState(progress.coerceIn(0f, 1f), tween(600), label = "ring")
    val spin = rememberInfiniteTransition(label = "spin")
    val angle by spin.animateFloat(0f, 360f, infiniteRepeatable(tween(2400, easing = LinearEasing)), label = "a")
    val track = MaterialTheme.colorScheme.surfaceVariant
    Box(modifier, contentAlignment = Alignment.Center) {
        Canvas(Modifier.matchParentSize()) {
            val w = stroke.toPx()
            val d = size.minDimension - w
            val topLeft = Offset((size.width - d) / 2, (size.height - d) / 2)
            drawArc(track, 0f, 360f, false, topLeft, Size(d, d), style = Stroke(w))
            drawArc(
                Brush.sweepGradient(listOf(SF.Accent, SF.Accent2, SF.Accent)), -90f, 360f * sweep, false, topLeft, Size(d, d),
                style = Stroke(w, cap = StrokeCap.Round),
            )
        }
        // A small comet running along the track shows the transfer is alive even when progress is slow.
        Canvas(Modifier.matchParentSize().rotate(angle)) {
            val w = stroke.toPx()
            val r = (size.minDimension - w) / 2
            drawCircle(Color.White.copy(alpha = 0.55f), w * 0.22f, Offset(size.width / 2, size.height / 2 - r))
        }
        content()
    }
}

/** Circle and tick drawn in, for the finish screen. */
@Composable
fun SuccessMark(size: Dp = 120.dp, color: Color = SF.Success) {
    val circle = remember { Animatable(0f) }
    val tick = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        circle.animateTo(1f, tween(520))
        tick.animateTo(1f, tween(380))
    }
    Canvas(Modifier.size(size)) {
        val w = this.size.width
        drawCircle(color.copy(alpha = 0.14f), radius = w / 2 * circle.value)
        drawArc(color, -90f, 360f * circle.value, false, Offset(w * 0.06f, w * 0.06f), Size(w * 0.88f, w * 0.88f), style = Stroke(w * 0.05f, cap = StrokeCap.Round))
        val path = Path().apply {
            moveTo(w * 0.30f, w * 0.52f)
            lineTo(w * 0.45f, w * 0.66f)
            lineTo(w * 0.71f, w * 0.38f)
        }
        val m = PathMeasure().apply { setPath(path, false) }
        val part = Path()
        m.getSegment(0f, m.length * tick.value, part, true)
        drawPath(part, color, style = Stroke(w * 0.07f, cap = StrokeCap.Round))
    }
}

/** Gentle up-and-down float for illustrations. */
@Composable
fun floatOffset(): Float {
    val t = rememberInfiniteTransition(label = "float")
    val y by t.animateFloat(-6f, 6f, infiniteRepeatable(tween(1800), RepeatMode.Reverse), label = "y")
    return y
}

@Composable
fun SectionTitle(text: String) {
    Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(start = 4.dp, bottom = 8.dp, top = 6.dp))
}

@Composable
fun Pill(text: String, color: Color, icon: ImageVector? = null) {
    Row(
        Modifier.clip(CircleShape).background(color.copy(alpha = 0.15f)).padding(horizontal = 12.dp, vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp),
    ) {
        if (icon != null) Icon(icon, null, tint = color, modifier = Modifier.size(16.dp))
        Text(text, color = color, style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
fun RowScope.Stat(value: String, label: String, color: Color) {
    Column(
        Modifier.weight(1f).clip(RoundedCornerShape(18.dp)).background(color.copy(alpha = 0.12f)).padding(vertical = 14.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(value, color = color, fontSize = 22.sp, fontWeight = FontWeight.SemiBold)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
