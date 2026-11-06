package app.switchflow.companion.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.AllInclusive
import androidx.compose.material.icons.rounded.Check
import androidx.compose.material.icons.rounded.Description
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material.icons.rounded.WorkspacePremium
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.switchflow.companion.R
import app.switchflow.companion.billing.FreeTier

/** The Pro offer: shown when the free version would send more than it may. */
@Composable
fun Paywall(price: String?, busy: Boolean, onBuy: () -> Unit, onRestore: () -> Unit, onClose: () -> Unit) {
    BackHandler(onBack = onClose)
    Column(Modifier.fillMaxWidth().padding(top = 8.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val y = floatOffset()
        Box(
            Modifier
                .size(96.dp)
                .graphicsLayer { translationY = y }
                .clip(RoundedCornerShape(30.dp))
                .background(Brush.linearGradient(listOf(Color(0xFFFFC857), Color(0xFFFF8A5B)))),
            contentAlignment = Alignment.Center,
        ) { Icon(Icons.Rounded.WorkspacePremium, null, tint = Color.White, modifier = Modifier.size(52.dp)) }
        Spacer(Modifier.height(18.dp))
        Text("SwitchFlow Pro", fontSize = 30.sp, fontWeight = FontWeight.SemiBold)
        Spacer(Modifier.height(6.dp))
        Text(
            stringResource(R.string.pro_subtitle),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        SfCard {
            Column(verticalArrangement = Arrangement.spacedBy(14.dp)) {
                Perk(Icons.Rounded.AllInclusive, stringResource(R.string.pro_perk_unlimited))
                Perk(Icons.Rounded.Videocam, stringResource(R.string.pro_perk_video))
                Perk(Icons.Rounded.MusicNote, stringResource(R.string.pro_perk_music))
                Perk(Icons.Rounded.Description, stringResource(R.string.pro_perk_docs))
                Perk(Icons.Rounded.Check, stringResource(R.string.pro_perk_once))
            }
        }
        Spacer(Modifier.height(10.dp))
        Text(
            stringResource(R.string.pro_free_is, FreeTier.MEDIA_LIMIT),
            style = MaterialTheme.typography.labelMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.padding(horizontal = 8.dp),
        )
        Spacer(Modifier.height(20.dp))
        if (busy) {
            CircularProgressIndicator(Modifier.size(36.dp), strokeWidth = 3.dp)
        } else {
            PrimaryButton(
                if (price != null) stringResource(R.string.pro_buy_price, price) else stringResource(R.string.pro_buy),
                enabled = price != null,
                onClick = onBuy,
            )
        }
        Row(Modifier.padding(top = 6.dp)) {
            TextButton(onClick = onRestore) { Text(stringResource(R.string.pro_restore)) }
            TextButton(onClick = onClose) { Text(stringResource(R.string.later), color = MaterialTheme.colorScheme.onSurfaceVariant) }
        }
    }
}

@Composable
private fun Perk(icon: ImageVector, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Box(Modifier.size(34.dp).clip(CircleShape).background(SF.Accent.copy(alpha = 0.16f)), contentAlignment = Alignment.Center) {
            Icon(icon, null, tint = SF.Accent, modifier = Modifier.size(18.dp))
        }
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Small "PRO" mark on what the free version cannot send. */
@Composable
fun ProMark() {
    Box(
        Modifier
            .clip(CircleShape)
            .background(Brush.linearGradient(listOf(Color(0xFFFFC857), Color(0xFFFF8A5B))))
            .padding(horizontal = 8.dp, vertical = 3.dp),
    ) { Text("PRO", color = Color.White, fontSize = 11.sp, fontWeight = FontWeight.Bold) }
}
