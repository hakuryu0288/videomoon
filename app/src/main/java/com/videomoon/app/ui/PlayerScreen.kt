package com.videomoon.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Close
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Replay
import androidx.compose.material.icons.rounded.SaveAlt
import androidx.compose.material.icons.rounded.ViewColumn
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videomoon.app.data.Take
import com.videomoon.app.data.TakeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlin.math.abs

@Composable
fun PlayerScreen(vm: MainViewModel, takeId: String, onClose: () -> Unit, onRetake: (Take) -> Unit) {
    val takes by vm.takes.collectAsState()
    val take = takes.firstOrNull { it.id == takeId } ?: run { LaunchedEffect(Unit) { onClose() }; return }
    var compareWith by remember(takeId) { mutableStateOf<Take?>(null) }
    var pickCompare by remember { mutableStateOf(false) }
    var confirmDelete by remember { mutableStateOf(false) }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        Column(Modifier.fillMaxSize()) {
            Row(Modifier.statusBarsPadding().padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                Box(
                    Modifier.size(40.dp).clip(CircleShape).background(VM.S1).clickable { if (compareWith != null) compareWith = null else onClose() },
                    contentAlignment = Alignment.Center,
                ) { Icon(Icons.Rounded.Close, "閉じる", tint = VM.Tx) }
                Spacer(Modifier.width(10.dp))
                Column(Modifier.weight(1f)) {
                    Text(if (compareWith != null) "比較" else take.title, color = VM.Tx, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(
                        compareWith?.let { "A：${take.title} ／ B：${it.title}" } ?: if (take.kind == TakeKind.VIDEO)
                            "%.1f秒 ・ %dfps ・ %s".format(take.durationMs / 1000f, take.fps, vm.trackOf(take)?.name ?: "音声なし")
                        else "写真",
                        color = VM.Mu, fontSize = 12.sp, maxLines = 1, overflow = TextOverflow.Ellipsis,
                    )
                }
            }
            Box(Modifier.weight(1f).fillMaxWidth()) {
                val other = compareWith
                when {
                    take.kind == TakeKind.PHOTO -> PhotoView(vm, take)
                    other != null -> CompareView(vm, take, other)
                    else -> PlayerSurface(rememberTakePlayer(vm, take), Modifier.fillMaxSize())
                }
            }
            if (compareWith == null) {
                Row(
                    Modifier.fillMaxWidth().background(Color.Black).navigationBarsPadding().padding(vertical = 10.dp),
                    horizontalArrangement = Arrangement.SpaceAround,
                ) {
                    Action(Icons.Rounded.SaveAlt, "書き出す") { vm.export(listOf(take)) }
                    if (take.kind == TakeKind.VIDEO) {
                        Action(Icons.Rounded.Replay, "撮り直す") { onRetake(take) }
                        Action(Icons.Rounded.ViewColumn, "比較") { pickCompare = true }
                    }
                    Action(Icons.Rounded.Delete, "削除") { confirmDelete = true }
                }
            }
        }

        Sheet(pickCompare, { pickCompare = false }) {
            Text("並べて比べるテイク", color = VM.Tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("同じクリップのテイク同士なら、音楽に合わせて動きのズレを見比べられます。", color = VM.Mu, fontSize = 12.5.sp)
            Spacer(Modifier.size(10.dp))
            val others = takes.filter { it.kind == TakeKind.VIDEO && it.id != take.id }
            if (others.isEmpty()) Text("比較できる他の動画がありません", color = VM.Mu)
            others.sortedByDescending { it.clipId == take.clipId }.forEach { o ->
                SheetOption(o.title, "%.1f秒 ・ %dfps".format(o.durationMs / 1000f, o.fps), selected = o.clipId != null && o.clipId == take.clipId) {
                    compareWith = o; pickCompare = false
                }
            }
        }
        Sheet(confirmDelete, { confirmDelete = false }) {
            Text("このテイクを削除しますか？", color = VM.Tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("アプリ内から消えます。書き出し済みのファイルはギャラリーに残ります。", color = VM.Mu, fontSize = 13.sp)
            Spacer(Modifier.size(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoonButton("やめる", Modifier.weight(1f), primary = false) { confirmDelete = false }
                MoonButton("削除する", Modifier.weight(1f), primary = false, color = VM.Rec) {
                    confirmDelete = false; vm.deleteTakes(listOf(take.id)); onClose()
                }
            }
        }
    }
}

@Composable
private fun Action(icon: ImageVector, label: String, onClick: () -> Unit) {
    Column(
        Modifier.clickable(onClick = onClick).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Icon(icon, null, tint = VM.Tx)
        Text(label, color = Color(0xFFC9C8D6), fontSize = 11.5.sp)
    }
}

@Composable
private fun PhotoView(vm: MainViewModel, take: Take) {
    var bmp by remember(take.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(take.id) {
        bmp = withContext(Dispatchers.IO) {
            val f = vm.store.takeFile(take)
            val o = BitmapFactory.Options().apply { inSampleSize = 2 }
            BitmapFactory.decodeFile(f.absolutePath, o)?.asImageBitmap()
        }
    }
    bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
}

/** 2つのテイクを左右に並べて同時再生（音はどちらか一方） */
@Composable
private fun CompareView(vm: MainViewModel, a: Take, b: Take) {
    var audioB by remember { mutableStateOf(false) }
    val pa = rememberTakePlayer(vm, a, autoPlay = false, muted = audioB)
    val pb = rememberTakePlayer(vm, b, autoPlay = false, muted = !audioB)
    var playing by remember { mutableStateOf(false) }
    var pos by remember { mutableFloatStateOf(0f) }
    val len = minOf(a.durationMs, b.durationMs).coerceAtLeast(1)

    LaunchedEffect(playing) {
        if (playing) { pb.seekTo(pa.currentPosition); pa.play(); pb.play() } else { pa.pause(); pb.pause() }
        while (playing) {
            delay(200)
            if (abs(pa.currentPosition - pb.currentPosition) > 60) pb.seekTo(pa.currentPosition)
            pos = pa.currentPosition.toFloat() / len
        }
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Box(Modifier.weight(1f)) {
                PlayerSurface(pa, Modifier.fillMaxSize(), controls = false)
                Text("A", color = VM.Tx, fontSize = 12.sp, modifier = Modifier.padding(8.dp).background(Color(0xB305070F)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
            Box(Modifier.weight(1f)) {
                PlayerSurface(pb, Modifier.fillMaxSize(), controls = false)
                Text("B", color = VM.Tx, fontSize = 12.sp, modifier = Modifier.padding(8.dp).background(Color(0xB305070F)).padding(horizontal = 8.dp, vertical = 2.dp))
            }
        }
        Row(
            Modifier.fillMaxWidth().navigationBarsPadding().padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(Modifier.size(40.dp).clip(CircleShape).background(VM.S1).clickable { playing = !playing }, contentAlignment = Alignment.Center) {
                Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, null, tint = VM.Tx)
            }
            Slider(
                pos, { v -> pos = v; val ms = (v * len).toLong(); pa.seekTo(ms); pb.seekTo(ms) },
                Modifier.weight(1f).padding(horizontal = 10.dp),
                colors = SliderDefaults.colors(thumbColor = VM.Moon, activeTrackColor = VM.Moon, inactiveTrackColor = VM.S3),
            )
            Chip("音: ${if (audioB) "B" else "A"}") { audioB = !audioB }
        }
    }
}
