package com.videomoon.app.ui

import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Add
import androidx.compose.material.icons.rounded.ChevronRight
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videomoon.app.data.Track

@Composable
fun TracksScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val tracks by vm.tracks.collectAsState()
    var deleting by remember { mutableStateOf<Track?>(null) }
    val pickAudio = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importMedia(uri) }
    val pickVideo = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri -> if (uri != null) vm.importMedia(uri) }

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            item {
                Text("音源", color = VM.Tx, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 12.dp))
                Row(
                    Modifier.fillMaxWidth().height(56.dp).clip(RoundedCornerShape(14.dp))
                        .border(1.5.dp, VM.Line, RoundedCornerShape(14.dp))
                        .clickable { pickAudio.launch(arrayOf("audio/*")) },
                    horizontalArrangement = Arrangement.Center, verticalAlignment = Alignment.CenterVertically,
                ) {
                    Icon(Icons.Rounded.Add, null, tint = VM.Moon)
                    Spacer(Modifier.width(6.dp))
                    Text("端末のファイルから追加", color = VM.Moon, fontWeight = FontWeight.Medium)
                }
                Spacer(Modifier.height(8.dp))
                Box(
                    Modifier.fillMaxWidth().height(42.dp).clip(RoundedCornerShape(11.dp)).background(VM.S1)
                        .border(1.dp, VM.Line, RoundedCornerShape(11.dp)).clickable { pickVideo.launch(arrayOf("video/*")) },
                    contentAlignment = Alignment.Center,
                ) { Text("動画から音声を取り出す", color = VM.Tx, fontSize = 13.sp) }
                Spacer(Modifier.height(12.dp))
                if (tracks.isEmpty()) {
                    Text(
                        "まだ音源がありません。\n端末に保存されている曲（MP3・M4A・WAV・FLACなど）を追加してください。ほかのアプリの「共有」から videomoon を選んでも追加できます。",
                        color = VM.Mu, fontSize = 13.sp, modifier = Modifier.padding(vertical = 30.dp),
                    )
                }
            }
            items(tracks, key = { it.id }) { t ->
                TrackRow(vm, t, onClick = { onOpen(t.id) }, onDelete = { deleting = t })
            }
            item {
                Text(
                    "追加した音源をタップすると、使いたい範囲を切り取れます。切り取った範囲は「クリップ」として何個でも保存でき、撮影画面からすぐ選べます。",
                    color = VM.Mu, fontSize = 12.sp,
                    modifier = Modifier.padding(top = 10.dp, bottom = 24.dp).clip(RoundedCornerShape(12.dp)).background(VM.S1).padding(12.dp),
                )
            }
        }
        Sheet(deleting != null, { deleting = null }) {
            val t = deleting
            Text("音源を削除しますか？", color = VM.Tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("「${t?.name}」とそのクリップがアプリから消えます。撮影済みの動画は残りますが、音楽なしになります。", color = VM.Mu, fontSize = 13.sp)
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                MoonButton("やめる", Modifier.weight(1f), primary = false) { deleting = null }
                MoonButton("削除する", Modifier.weight(1f), primary = false, color = VM.Rec) { t?.let { vm.deleteTrack(it.id) }; deleting = null }
            }
        }
    }
}

@Composable
private fun TrackRow(vm: MainViewModel, t: Track, onClick: () -> Unit, onDelete: () -> Unit) {
    var peaks by remember(t.id) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(t.id) { peaks = vm.peaks(t) }
    Row(
        Modifier.fillMaxWidth().clip(RoundedCornerShape(14.dp)).background(VM.S1).clickable(onClick = onClick).padding(12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Canvas(Modifier.width(64.dp).height(40.dp)) {
            val p = peaks ?: return@Canvas
            val cols = (size.width / 3f).toInt()
            for (x in 0 until cols) {
                val a = x * p.size / cols
                val b = ((x + 1) * p.size / cols).coerceAtLeast(a + 1).coerceAtMost(p.size)
                var m = 0f
                for (i in a until b) m = maxOf(m, p[i])
                val h = (m * size.height * .9f).coerceAtLeast(1.5f)
                drawRect(VM.Dim, Offset(x * 3f, (size.height - h) / 2), Size(2f, h))
            }
        }
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(t.name, color = VM.Tx, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text("${fmtTime(t.durationMs, false)} ・ クリップ ${t.clips.size}", color = VM.Mu, fontSize = 12.sp)
        }
        Icon(Icons.Rounded.Delete, "削除", tint = VM.Dim, modifier = Modifier.size(36.dp).clip(RoundedCornerShape(18.dp)).clickable(onClick = onDelete).padding(8.dp))
        Icon(Icons.Rounded.ChevronRight, null, tint = VM.Dim)
    }
}
