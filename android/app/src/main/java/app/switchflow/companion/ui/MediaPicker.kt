package app.switchflow.companion.ui

import android.graphics.Bitmap
import androidx.activity.compose.BackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.Spring
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.ArrowBack
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.produceState
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import app.switchflow.companion.R
import app.switchflow.companion.net.Album
import app.switchflow.companion.net.Category
import app.switchflow.companion.net.SourceItem
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Pick exactly which photos or videos to send: a grid of previews with album filters.
 * [initial] is the current choice (null = everything).
 */
@Composable
fun MediaPickerScreen(
    category: String,
    items: List<SourceItem>,
    albums: List<Album>,
    initial: Set<String>?,
    thumbnail: (String, Int) -> Bitmap?,
    onDone: (Set<String>) -> Unit,
    onBack: () -> Unit,
) {
    var chosen by remember(category) { mutableStateOf(initial ?: items.map { it.id }.toSet()) }
    var album by rememberSaveable(category) { mutableStateOf<String?>(null) }
    val shown = remember(items, album) { if (album == null) items else items.filter { album in it.albumIds } }
    val chosenBytes = remember(items, chosen) { items.filter { it.id in chosen }.sumOf { it.size } }
    BackHandler(onBack = onBack)
    Surface(color = MaterialTheme.colorScheme.background, modifier = Modifier.fillMaxSize()) {
        Column(Modifier.safeDrawingPadding()) {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(42.dp).clip(CircleShape).background(MaterialTheme.colorScheme.surface).bouncyClick(onClick = onBack),
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.ArrowBack, stringResource(R.string.back)) }
                Spacer(Modifier.width(12.dp))
                Column(Modifier.weight(1f)) {
                    Text(categoryName(category), style = MaterialTheme.typography.titleLarge)
                    Text(
                        stringResource(R.string.picked_of, chosen.size, items.size) + " · " + bytes(chosenBytes),
                        style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                val allShown = shown.isNotEmpty() && shown.all { it.id in chosen }
                TextButton(onClick = {
                    val ids = shown.map { it.id }.toSet()
                    chosen = if (allShown) chosen - ids else chosen + ids
                }) { Text(stringResource(if (allShown) R.string.clear_all else R.string.select_all)) }
            }
            if (albums.isNotEmpty()) {
                LazyRow(contentPadding = PaddingValues(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    item { Chip(stringResource(R.string.all_albums), album == null) { album = null } }
                    items(albums, key = { it.id }) { a -> Chip("${a.name} · ${a.count}", album == a.id) { album = a.id } }
                }
            }
            LazyVerticalGrid(
                columns = GridCells.Adaptive(104.dp),
                modifier = Modifier.weight(1f).fillMaxWidth(),
                contentPadding = PaddingValues(12.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(shown, key = { it.id }) { item ->
                    Tile(item, item.id in chosen, thumbnail) { chosen = if (item.id in chosen) chosen - item.id else chosen + item.id }
                }
            }
            Box(Modifier.padding(16.dp)) {
                PrimaryButton(stringResource(R.string.picked_done, chosen.size)) { onDone(chosen) }
            }
        }
    }
}

@Composable
private fun Chip(text: String, on: Boolean, onClick: () -> Unit) {
    Box(
        Modifier
            .clip(CircleShape)
            .background(if (on) SF.Accent else MaterialTheme.colorScheme.surface)
            .bouncyClick(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 8.dp),
    ) {
        Text(text, color = if (on) Color.White else MaterialTheme.colorScheme.onSurface, style = MaterialTheme.typography.labelMedium, maxLines = 1, overflow = TextOverflow.Ellipsis)
    }
}

@Composable
private fun Tile(item: SourceItem, selected: Boolean, thumbnail: (String, Int) -> Bitmap?, onClick: () -> Unit) {
    val px = with(LocalDensity.current) { 120.dp.roundToPx() }
    val bmp by produceState<Bitmap?>(null, item.id) { value = withContext(Dispatchers.IO) { thumbnail(item.id, px) } }
    val inset by animateFloatAsState(if (selected) 0.88f else 1f, spring(dampingRatio = 0.5f, stiffness = Spring.StiffnessMedium), label = "tile")
    Box(Modifier.aspectRatio(1f).bouncyClick(pressedScale = 0.92f, onClick = onClick)) {
        Box(
            Modifier
                .fillMaxSize()
                .graphicsLayer { scaleX = inset; scaleY = inset }
                .clip(RoundedCornerShape(10.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
                .then(if (selected) Modifier.border(3.dp, SF.Accent, RoundedCornerShape(10.dp)) else Modifier),
        ) {
            bmp?.let { Image(it.asImageBitmap(), null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
            if (item.category == Category.VIDEOS) {
                Icon(Icons.Rounded.PlayArrow, null, tint = Color.White, modifier = Modifier.align(Alignment.BottomStart).padding(4.dp).size(20.dp))
            }
        }
        Box(Modifier.align(Alignment.TopEnd).padding(6.dp)) {
            AnimatedVisibility(selected, enter = scaleIn() + fadeIn(), exit = scaleOut() + fadeOut()) { CheckDot(true) }
        }
    }
}
