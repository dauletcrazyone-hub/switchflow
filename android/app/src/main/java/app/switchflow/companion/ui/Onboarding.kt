package app.switchflow.companion.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.CalendarMonth
import androidx.compose.material.icons.rounded.CameraAlt
import androidx.compose.material.icons.rounded.Contacts
import androidx.compose.material.icons.rounded.Lock
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material.icons.rounded.Notifications
import androidx.compose.material.icons.rounded.Photo
import androidx.compose.material.icons.rounded.Shield
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import app.switchflow.companion.R
import app.switchflow.companion.net.Category

/**
 * First start: one screen, one tap, and Android asks for everything the app will ever need, so
 * transfers later (with the PC or another phone) do not stop for permission dialogs.
 */
@Composable
fun AllowAllScreen(onAllow: () -> Unit, onLater: () -> Unit) {
    Column(Modifier.fillMaxWidth().padding(top = 12.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val y = floatOffset()
        Column(Modifier.graphicsLayer { translationY = y }) { IconBadge(Icons.Rounded.Shield, SF.Accent, 88.dp) }
        Spacer(Modifier.height(18.dp))
        Text(stringResource(R.string.allow_all_title), style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(8.dp))
        Text(
            stringResource(R.string.allow_all_body),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(20.dp))
        SfCard {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Item(Icons.Rounded.Photo, categoryColor(Category.PHOTOS), stringResource(R.string.allow_all_media))
                Item(Icons.Rounded.MusicNote, categoryColor(Category.MUSIC), stringResource(R.string.cat_music))
                Item(Icons.Rounded.Contacts, categoryColor(Category.CONTACTS), stringResource(R.string.cat_contacts))
                Item(Icons.Rounded.CalendarMonth, categoryColor(Category.CALENDARS), stringResource(R.string.cat_calendars))
                Item(Icons.Rounded.CameraAlt, SF.Accent2, stringResource(R.string.allow_all_camera))
                Item(Icons.Rounded.Notifications, SF.Warning, stringResource(R.string.allow_all_notifications))
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.padding(horizontal = 6.dp)) {
            Icon(Icons.Rounded.Lock, null, tint = SF.Success, modifier = Modifier.padding(end = 6.dp))
            Text(stringResource(R.string.allow_all_privacy), style = MaterialTheme.typography.labelMedium, color = SF.Success)
        }
        Spacer(Modifier.height(20.dp))
        PrimaryButton(stringResource(R.string.allow_all), onClick = onAllow)
        TextButton(onClick = onLater, modifier = Modifier.padding(top = 6.dp)) {
            Text(stringResource(R.string.later), color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun Item(icon: ImageVector, color: Color, text: String) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        IconBadge(icon, color, 36.dp)
        Spacer(Modifier.width(12.dp))
        Text(text, style = MaterialTheme.typography.bodyLarge)
    }
}

/** Home: some access is still missing; one tap asks for all of it again. */
@Composable
fun AllowAllCard(onAllow: () -> Unit) {
    SfCard(Modifier.padding(bottom = 14.dp), tint = SF.Accent.copy(alpha = 0.14f), onClick = onAllow) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconBadge(Icons.Rounded.Shield, SF.Accent)
            Spacer(Modifier.width(14.dp))
            Column(Modifier.weight(1f)) {
                Text(stringResource(R.string.allow_all_card), style = MaterialTheme.typography.titleMedium)
                Text(stringResource(R.string.allow_all_card_hint), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}
