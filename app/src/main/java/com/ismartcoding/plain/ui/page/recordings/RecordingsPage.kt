package com.ismartcoding.plain.ui.page.recordings

import android.media.MediaPlayer
import android.text.format.Formatter
import android.widget.VideoView
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.DialogProperties
import androidx.navigation.NavHostController
import coil3.compose.AsyncImage
import coil3.request.ImageRequest
import coil3.video.videoFrameMillis
import com.ismartcoding.plain.R
import com.ismartcoding.plain.data.DRecording
import com.ismartcoding.plain.data.RecordingsMetaDb
import com.ismartcoding.plain.ui.base.PFilterChip
import com.ismartcoding.plain.ui.base.PIconButton
import com.ismartcoding.plain.ui.base.PScaffold
import com.ismartcoding.plain.ui.base.PScrollableTabRow
import com.ismartcoding.plain.ui.base.PTopAppBar
import com.ismartcoding.plain.ui.base.TextFieldDialog
import com.ismartcoding.plain.ui.helpers.DialogHelper
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

private data class RecTab(val value: String, val titleRes: Int)

private val TABS = listOf(
    RecTab("", R.string.recording_type_all),
    RecTab("video", R.string.recording_type_video),
    RecTab("photo", R.string.recording_type_photo),
    RecTab("audio", R.string.recording_type_audio),
    RecTab("screen", R.string.recording_type_screen),
    RecTab("screenshot", R.string.recording_type_screenshot),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun RecordingsPage(navController: NavHostController, initialType: String = "") {
    val scope = rememberCoroutineScope()

    var typeFilter by remember { mutableStateOf(initialType) }
    val items = remember { mutableStateListOf<DRecording>() }
    var viewer by remember { mutableStateOf<DRecording?>(null) }
    var renameTarget by remember { mutableStateOf<DRecording?>(null) }

    fun reload() {
        scope.launch(Dispatchers.IO) {
            val rows = RecordingsMetaDb.list(typeFilter.ifBlank { null }, 0, 500)
            withContext(Dispatchers.Main) {
                items.clear(); items.addAll(rows)
            }
        }
    }

    LaunchedEffect(typeFilter) { reload() }

    PScaffold(
        topBar = {
            PTopAppBar(
                navController = navController,
                title = stringResource(R.string.recordings),
            )
        },
    ) { paddingValues ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(top = paddingValues.calculateTopPadding()),
        ) {
            PScrollableTabRow(
                selectedTabIndex = TABS.indexOfFirst { it.value == typeFilter }.coerceAtLeast(0),
                modifier = Modifier.fillMaxWidth(),
            ) {
                TABS.forEachIndexed { i, t ->
                    PFilterChip(
                        modifier = Modifier.padding(start = if (i == 0) 0.dp else 8.dp),
                        selected = typeFilter == t.value,
                        onClick = { typeFilter = t.value },
                        label = { Text(stringResource(t.titleRes)) },
                    )
                }
            }

            if (items.isEmpty()) {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(
                        text = stringResource(R.string.no_recordings),
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            } else {
                LazyVerticalGrid(
                    columns = GridCells.Adaptive(minSize = 160.dp),
                    modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 8.dp),
                    horizontalArrangement = Arrangement.spacedBy(12.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp),
                ) {
                    items(items, key = { it.id }) { item ->
                        RecordingCard(item) { viewer = it }
                    }
                }
            }
        }
    }

    viewer?.let { it ->
        ViewerDialog(
            item = it,
            onClose = { viewer = null },
            onRename = { renameTarget = it },
            onDelete = { rec ->
                DialogHelper.confirmToAction(R.string.delete_recording_confirm) {
                    scope.launch(Dispatchers.IO) {
                        RecordingsMetaDb.delete(rec.id)
                        withContext(Dispatchers.Main) {
                            items.removeAll { x -> x.id == rec.id }
                            viewer = null
                        }
                    }
                }
            },
        )
    }

    renameTarget?.let { rec ->
        TextFieldDialog(
            title = stringResource(R.string.rename_recording),
            value = rec.name,
            onDismissRequest = { renameTarget = null },
            onConfirm = { newName ->
                scope.launch(Dispatchers.IO) {
                    val updated = RecordingsMetaDb.update(rec.id, name = newName)
                    withContext(Dispatchers.Main) {
                        if (updated != null) {
                            val idx = items.indexOfFirst { it.id == rec.id }
                            if (idx >= 0) items[idx] = updated
                            if (viewer?.id == rec.id) viewer = updated
                        }
                        renameTarget = null
                    }
                }
            },
        )
    }
}

@Composable
private fun RecordingCard(item: DRecording, onClick: (DRecording) -> Unit) {
    val context = LocalContext.current
    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(12.dp))
            .clickable { onClick(item) },
        color = MaterialTheme.colorScheme.surfaceContainer,
    ) {
        Column {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .aspectRatio(16f / 10f)
                    .background(Color.Black),
                contentAlignment = Alignment.Center,
            ) {
                when (item.type) {
                    "photo", "screenshot" -> AsyncImage(
                        model = ImageRequest.Builder(context).data(File(item.filePath)).build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    "video", "screen" -> AsyncImage(
                        model = ImageRequest.Builder(context)
                            .data(File(item.filePath))
                            .videoFrameMillis(1000L)
                            .build(),
                        contentDescription = null,
                        contentScale = ContentScale.Crop,
                        modifier = Modifier.fillMaxSize(),
                    )
                    else -> Icon(
                        painter = painterResource(R.drawable.music),
                        contentDescription = null,
                        tint = Color.White.copy(alpha = 0.7f),
                        modifier = Modifier.size(48.dp),
                    )
                }
                TypeBadge(
                    item.type,
                    modifier = Modifier
                        .align(Alignment.TopStart)
                        .padding(8.dp),
                )
                if (item.durationMs > 0) {
                    Surface(
                        modifier = Modifier
                            .align(Alignment.BottomEnd)
                            .padding(8.dp),
                        color = Color.Black.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(50),
                    ) {
                        Text(
                            text = formatDuration(item.durationMs),
                            color = Color.White,
                            fontSize = 11.sp,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
                        )
                    }
                }
            }
            Column(modifier = Modifier.padding(10.dp)) {
                Text(
                    text = item.name.ifBlank { File(item.filePath).name },
                    style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Medium),
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${Formatter.formatShortFileSize(context, item.sizeBytes)} • ${
                        android.text.format.DateFormat.getDateFormat(context).format(item.createdAt)
                    }",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
            }
        }
    }
}

@Composable
private fun TypeBadge(type: String, modifier: Modifier = Modifier) {
    val labelRes = when (type) {
        "video" -> R.string.recording_type_video
        "photo" -> R.string.recording_type_photo
        "audio" -> R.string.recording_type_audio
        "screen" -> R.string.recording_type_screen
        "screenshot" -> R.string.recording_type_screenshot
        else -> R.string.recording_type_all
    }
    Surface(
        modifier = modifier,
        color = MaterialTheme.colorScheme.primary.copy(alpha = 0.92f),
        shape = RoundedCornerShape(50),
    ) {
        Text(
            text = stringResource(labelRes),
            color = MaterialTheme.colorScheme.onPrimary,
            fontSize = 10.sp,
            fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp),
        )
    }
}

@Composable
private fun ViewerDialog(
    item: DRecording,
    onClose: () -> Unit,
    onRename: (DRecording) -> Unit,
    onDelete: (DRecording) -> Unit,
) {
    androidx.compose.ui.window.Dialog(
        onDismissRequest = onClose,
        properties = DialogProperties(usePlatformDefaultWidth = false),
    ) {
        Surface(
            modifier = Modifier
                .fillMaxWidth(0.95f)
                .fillMaxHeight(0.9f),
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surfaceContainerHigh,
        ) {
            Column(modifier = Modifier.fillMaxSize()) {
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                ) {
                    TypeBadge(item.type)
                    Spacer(Modifier.width(10.dp))
                    Text(
                        text = item.name.ifBlank { File(item.filePath).name },
                        style = MaterialTheme.typography.titleMedium,
                        modifier = Modifier.weight(1f),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                    )
                    PIconButton(
                        icon = R.drawable.pen,
                        contentDescription = stringResource(R.string.rename_recording),
                    ) { onRename(item) }
                    PIconButton(
                        icon = R.drawable.trash_2,
                        contentDescription = stringResource(R.string.delete_recording),
                    ) { onDelete(item) }
                    PIconButton(
                        icon = R.drawable.x,
                        contentDescription = stringResource(R.string.close),
                    ) { onClose() }
                }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f)
                        .background(Color.Black),
                    contentAlignment = Alignment.Center,
                ) {
                    when (item.type) {
                        "photo", "screenshot" -> AsyncImage(
                            model = File(item.filePath),
                            contentDescription = null,
                            contentScale = ContentScale.Fit,
                            modifier = Modifier.fillMaxSize(),
                        )
                        "video", "screen" -> AndroidView(
                            modifier = Modifier.fillMaxSize(),
                            factory = { ctx ->
                                VideoView(ctx).apply {
                                    setVideoPath(item.filePath)
                                    setOnPreparedListener { mp ->
                                        mp.isLooping = false
                                        start()
                                    }
                                }
                            },
                        )
                        else -> AudioPlayerInline(filePath = item.filePath)
                    }
                }
            }
        }
    }
}

@Composable
private fun AudioPlayerInline(filePath: String) {
    val context = LocalContext.current
    val player = remember { MediaPlayer() }
    var isPrepared by remember { mutableStateOf(false) }
    var isPlaying by remember { mutableStateOf(false) }

    LaunchedEffect(filePath) {
        runCatching {
            player.reset()
            player.setDataSource(filePath)
            player.setOnPreparedListener {
                isPrepared = true; player.start(); isPlaying = true
            }
            player.setOnCompletionListener { isPlaying = false }
            player.prepareAsync()
        }
    }
    DisposableEffect(Unit) {
        onDispose { runCatching { player.stop(); player.release() } }
    }

    Column(
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
        modifier = Modifier.fillMaxSize(),
    ) {
        Icon(
            painter = painterResource(R.drawable.music),
            contentDescription = null,
            tint = Color.White,
            modifier = Modifier.size(96.dp),
        )
        Spacer(Modifier.height(24.dp))
        IconButton(
            enabled = isPrepared,
            onClick = {
                if (isPlaying) { player.pause(); isPlaying = false }
                else { player.start(); isPlaying = true }
            },
        ) {
            Icon(
                painter = painterResource(if (isPlaying) R.drawable.pause else R.drawable.play_arrow),
                contentDescription = null,
                tint = Color.White,
                modifier = Modifier.size(56.dp),
            )
        }
    }
}

private fun formatDuration(ms: Long): String {
    val s = (ms / 1000).toInt()
    val m = s / 60
    val rs = (s % 60).toString().padStart(2, '0')
    return "$m:$rs"
}
