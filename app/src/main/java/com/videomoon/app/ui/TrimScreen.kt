package com.videomoon.app.ui

import android.net.Uri
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.rounded.ArrowBack
import androidx.compose.material.icons.rounded.Delete
import androidx.compose.material.icons.rounded.Pause
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material.icons.rounded.Repeat
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.media3.common.MediaItem
import androidx.media3.common.PlaybackParameters
import androidx.media3.exoplayer.ExoPlayer
import com.videomoon.app.data.Clip
import kotlinx.coroutines.delay
import kotlin.math.abs

@Composable
fun TrimScreen(vm: MainViewModel, trackId: String, onBack: () -> Unit, onShoot: () -> Unit) {
    val context = LocalContext.current
    val tracks by vm.tracks.collectAsState()
    val activeKey by vm.activeClip.collectAsState()
    val track = tracks.firstOrNull { it.id == trackId } ?: run {
        LaunchedEffect(Unit) { onBack() }
        return
    }
    val dur = track.durationMs.coerceAtLeast(1000)

    var clip by remember(trackId) {
        val fromActive = activeKey?.takeIf { it.first == trackId }?.let { k -> track.clips.firstOrNull { it.id == k.second } }
        mutableStateOf(
            fromActive ?: track.clips.firstOrNull()
            ?: Clip("", "", (dur / 4).coerceAtMost(dur - 5000).coerceAtLeast(0), (dur / 4 + 30_000).coerceAtMost(dur))
        )
    }
    var peaks by remember(trackId) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(trackId) { peaks = vm.peaks(track) }
    var v0 by remember { mutableLongStateOf(0L) }
    var v1 by remember { mutableLongStateOf(dur) }
    var playhead by remember { mutableLongStateOf(-1L) }
    var playing by remember { mutableStateOf(false) }
    var loop by remember { mutableStateOf(true) }
    var speed by remember { mutableFloatStateOf(1f) }
    var nameSheet by remember { mutableStateOf(false) }

    val player = remember(trackId) {
        ExoPlayer.Builder(context).build().apply {
            setMediaItem(MediaItem.fromUri(Uri.fromFile(vm.trackFile(track)))); prepare()
        }
    }
    DisposableEffect(trackId) { onDispose { player.release() } }
    LaunchedEffect(speed) { player.playbackParameters = PlaybackParameters(speed) }
    LaunchedEffect(playing) {
        if (!playing) { player.pause(); return@LaunchedEffect }
        val from = clip.startMs - clip.prerollMs.coerceAtMost(clip.startMs)
        if (playhead !in from until clip.endMs) player.seekTo(from) else player.seekTo(playhead)
        player.play()
        while (true) {
            delay(30)
            playhead = player.currentPosition
            if (playhead >= clip.endMs) {
                if (loop) player.seekTo(clip.startMs) else { playing = false; playhead = -1 }
            }
        }
    }

    fun update(c: Clip) { clip = c.copy(prerollMs = c.prerollMs.coerceAtMost(c.startMs)) }

    Box(Modifier.fillMaxSize().background(VM.Night)) {
        Column(
            Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState())
                .padding(horizontal = 18.dp).padding(top = 8.dp, bottom = 120.dp)
        ) {
            Row(Modifier.clickable { onBack() }.padding(vertical = 4.dp), verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.AutoMirrored.Rounded.ArrowBack, null, tint = VM.Moon, modifier = Modifier.size(20.dp))
                Text("音源", color = VM.Moon)
            }
            Text(track.name, color = VM.Tx, fontSize = 19.sp, fontWeight = FontWeight.Bold, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 6.dp))
            Text("長さ ${fmtTime(track.durationMs, false)}", color = VM.Mu, fontSize = 12.sp)

            // ---- 波形 ----
            Box(
                Modifier.padding(top = 14.dp, bottom = 6.dp).fillMaxWidth().height(150.dp).clip(RoundedCornerShape(12.dp)).background(VM.S1)
                    .pointerInput(v0, v1) {
                        var target = 0 // 1=開始 2=終了 3=範囲ごと
                        var grab = 0L
                        var s0 = 0L
                        var e0 = 0L
                        fun tAt(x: Float) = v0 + ((x / size.width) * (v1 - v0)).toLong()
                        detectDragGestures(
                            onDragStart = { o ->
                                val t = tAt(o.x)
                                val msPerPx = (v1 - v0).toFloat() / size.width
                                val ds = abs(t - clip.startMs) / msPerPx
                                val de = abs(t - clip.endMs) / msPerPx
                                target = when {
                                    minOf(ds, de) < 70 -> if (ds < de) 1 else 2
                                    t in clip.startMs..clip.endMs -> 3
                                    else -> 0
                                }
                                grab = t; s0 = clip.startMs; e0 = clip.endMs
                            },
                            onDrag = { change, _ ->
                                val t = tAt(change.position.x).coerceIn(0, dur)
                                when (target) {
                                    1 -> update(clip.copy(startMs = t.coerceAtMost(clip.endMs - 500)))
                                    2 -> update(clip.copy(endMs = t.coerceAtLeast(clip.startMs + 500)))
                                    3 -> {
                                        val len = e0 - s0
                                        val ns = (s0 + (t - grab)).coerceIn(0, dur - len)
                                        update(clip.copy(startMs = ns, endMs = ns + len))
                                    }
                                }
                            },
                        )
                    }
                    .pointerInput(v0, v1) {
                        detectTapGestures { o ->
                            playhead = v0 + ((o.x / size.width) * (v1 - v0)).toLong()
                            if (playing) player.seekTo(playhead)
                        }
                    }
            ) {
                Canvas(Modifier.fillMaxSize()) {
                    val p = peaks ?: return@Canvas
                    val span = (v1 - v0).toFloat()
                    fun x(t: Long) = (t - v0) / span * size.width
                    val pre = clip.startMs - clip.prerollMs
                    drawRect(VM.Moon.copy(alpha = .08f), Offset(x(clip.startMs), 0f), Size(x(clip.endMs) - x(clip.startMs), size.height))
                    if (clip.prerollMs > 0) drawRect(VM.Warn.copy(alpha = .07f), Offset(x(pre), 0f), Size(x(clip.startMs) - x(pre), size.height))
                    val cols = (size.width / 3f).toInt()
                    for (i in 0 until cols) {
                        val t0 = v0 + (span * i / cols).toLong()
                        val t1 = v0 + (span * (i + 1) / cols).toLong()
                        val a = (t0.toFloat() / dur * p.size).toInt()
                        val b = (t1.toFloat() / dur * p.size).toInt().coerceAtLeast(a + 1).coerceAtMost(p.size)
                        var m = 0f
                        for (k in a until b) m = maxOf(m, p[k])
                        val h = (m * (size.height - 36f)).coerceAtLeast(1.5f)
                        val inSel = t0 in clip.startMs..clip.endMs
                        var col = if (inSel) VM.Moon else Color(0xFF3B4473)
                        if (inSel && clip.fadeInMs > 0 && t0 < clip.startMs + clip.fadeInMs) col = VM.Moon.copy(alpha = .35f + .65f * (t0 - clip.startMs) / clip.fadeInMs)
                        if (inSel && clip.fadeOutMs > 0 && t0 > clip.endMs - clip.fadeOutMs) col = VM.Moon.copy(alpha = .35f + .65f * (clip.endMs - t0) / clip.fadeOutMs)
                        if (!inSel && clip.prerollMs > 0 && t0 in pre until clip.startMs) col = Color(0xFF7A6A45)
                        drawRect(col, Offset(i * 3f, (size.height - h) / 2), Size(2f, h))
                    }
                    listOf(clip.startMs to false, clip.endMs to true).forEach { (t, right) ->
                        val hx = x(t)
                        drawRect(VM.Moon, Offset(hx - 1f, 0f), Size(2f, size.height))
                        drawRoundRect(VM.Moon, Offset(if (right) hx - 30f else hx, size.height - 54f), Size(30f, 46f), CornerRadius(8f))
                    }
                    if (playhead >= 0) drawRect(VM.Rec, Offset(x(playhead) - 1f, 0f), Size(2f, size.height))
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                Text(fmtTime(v0), color = VM.Dim, fontSize = 12.sp, style = NumStyle)
                Spacer(Modifier.weight(1f))
                val zoomed = v1 - v0 < dur - 10
                Text(if (zoomed) "全体を表示" else "選択範囲を拡大", color = VM.Moon, fontSize = 12.sp, modifier = Modifier.clickable {
                    if (zoomed) { v0 = 0; v1 = dur } else {
                        val pad = maxOf(1000L, clip.lengthMs / 8)
                        v0 = (clip.startMs - clip.prerollMs - pad).coerceAtLeast(0); v1 = (clip.endMs + pad).coerceAtMost(dur)
                    }
                }.padding(4.dp))
                Spacer(Modifier.weight(1f))
                Text(fmtTime(v1), color = VM.Dim, fontSize = 12.sp, style = NumStyle)
            }

            // ---- 微調整 ----
            Spacer(Modifier.height(10.dp))
            NudgeRow("開始", clip.startMs) { d -> update(clip.copy(startMs = (clip.startMs + d).coerceIn(0, clip.endMs - 500))) }
            Spacer(Modifier.height(8.dp))
            NudgeRow("終了", clip.endMs) { d -> update(clip.copy(endMs = (clip.endMs + d).coerceIn(clip.startMs + 500, dur))) }
            Row(
                Modifier.padding(vertical = 14.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(VM.S1).padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("クリップの長さ", color = VM.Mu, modifier = Modifier.weight(1f))
                Text("%.1f 秒".format(clip.lengthMs / 1000f), color = VM.Moon, fontSize = 20.sp, style = NumStyle)
            }

            // ---- 再生 ----
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Box(
                    Modifier.size(52.dp).clip(CircleShape).background(VM.Moon).clickable { playing = !playing },
                    contentAlignment = Alignment.Center,
                ) { Icon(if (playing) Icons.Rounded.Pause else Icons.Rounded.PlayArrow, "区間を再生", tint = VM.Ink) }
                SmallToggle("ループ", loop, Icons.Rounded.Repeat) { loop = !loop }
                SmallToggle("ここで開始", false) {
                    if (playhead >= 0) update(clip.copy(startMs = playhead.coerceAtMost(clip.endMs - 500)))
                    else vm.messages.tryEmit("再生中か、波形をタップした位置で使えます")
                }
                SmallToggle("ここで終了", false) {
                    if (playhead >= 0) update(clip.copy(endMs = playhead.coerceAtLeast(clip.startMs + 500)))
                    else vm.messages.tryEmit("再生中か、波形をタップした位置で使えます")
                }
            }
            Spacer(Modifier.height(10.dp))
            SettingRow("練習用の再生速度", "確認・練習のときだけ。撮影は常に等速です") {
                Seg(listOf(.5f to "0.5", .75f to "0.75", 1f to "1.0"), speed, { speed = it })
            }
            SettingRow("フェードイン") { Seg(listOf(0L to "なし", 500L to "0.5s", 1000L to "1s"), clip.fadeInMs, { update(clip.copy(fadeInMs = it)) }) }
            SettingRow("フェードアウト") { Seg(listOf(0L to "なし", 500L to "0.5s", 1000L to "1s", 2000L to "2s"), clip.fadeOutMs, { update(clip.copy(fadeOutMs = it)) }) }
            SettingRow("プリロール", "開始位置の少し前から音楽を流し、開始位置から録画します") {
                Seg(listOf(0L to "なし", 2000L to "2s", 4000L to "4s", 8000L to "8s"), clip.prerollMs, { update(clip.copy(prerollMs = it)) })
            }

            // ---- 保存したクリップ ----
            Text("保存したクリップ", color = VM.Mu, fontSize = 14.sp, modifier = Modifier.padding(top = 22.dp, bottom = 8.dp))
            if (track.clips.isEmpty()) Text("まだありません。範囲を決めて「クリップを保存」を押してください。", color = VM.Mu, fontSize = 12.sp)
            track.clips.forEach { c ->
                Row(
                    Modifier.padding(bottom = 6.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp)).background(VM.S1)
                        .then(if (c.id == clip.id) Modifier.border(1.5.dp, VM.Moon.copy(alpha = .5f), RoundedCornerShape(12.dp)) else Modifier)
                        .clickable { clip = c; v0 = 0; v1 = dur }.padding(horizontal = 12.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(c.name, color = VM.Tx, fontWeight = FontWeight.Medium)
                        Text("${fmtTime(c.startMs)} – ${fmtTime(c.endMs)}（%.1f秒）".format(c.lengthMs / 1000f), color = VM.Mu, fontSize = 13.sp, style = NumStyle.copy(fontWeight = FontWeight.Normal))
                    }
                    Icon(Icons.Rounded.Delete, "削除", tint = VM.Mu, modifier = Modifier.clickable {
                        vm.deleteClip(track.id, c.id); if (clip.id == c.id) clip = clip.copy(id = "", name = "")
                    }.padding(6.dp))
                }
            }
        }

        Row(
            Modifier.align(Alignment.BottomCenter).fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, VM.Night, VM.Night)))
                .navigationBarsPadding().padding(horizontal = 18.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            MoonButton("クリップを保存", primary = false) { nameSheet = true }
            MoonButton("● この範囲で撮影", Modifier.weight(1f)) {
                playing = false
                val saved = vm.saveClip(track.id, if (clip.id.isEmpty()) clip.copy(name = "クリップ ${track.clips.size + 1}") else clip)
                clip = saved
                vm.setActiveClip(track.id to saved.id)
                onShoot()
            }
        }

        var name by remember(nameSheet) { mutableStateOf(clip.name) }
        Sheet(nameSheet, { nameSheet = false }) {
            val exists = clip.id.isNotEmpty() && track.clips.any { it.id == clip.id }
            Text(if (exists) "クリップを更新" else "クリップを保存", color = VM.Tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("${fmtTime(clip.startMs)} – ${fmtTime(clip.endMs)}", color = VM.Mu, fontSize = 12.sp)
            Spacer(Modifier.height(8.dp))
            OutlinedTextField(
                name, { name = it }, Modifier.fillMaxWidth(), singleLine = true,
                placeholder = { Text("名前（例：サビ、Aメロ）") },
                colors = OutlinedTextFieldDefaults.colors(focusedBorderColor = VM.Moon, unfocusedBorderColor = VM.Line, cursorColor = VM.Moon),
            )
            Spacer(Modifier.height(10.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (exists) MoonButton("別のクリップとして保存", Modifier.weight(1f), primary = false) {
                    clip = vm.saveClip(track.id, clip.copy(id = "", name = name.ifBlank { "クリップ ${track.clips.size + 1}" }))
                    nameSheet = false; vm.messages.tryEmit("クリップを保存しました")
                }
                MoonButton("保存", Modifier.weight(1f)) {
                    clip = vm.saveClip(track.id, clip.copy(name = name.ifBlank { clip.name.ifBlank { "クリップ ${track.clips.size + 1}" } }))
                    nameSheet = false; vm.messages.tryEmit("クリップを保存しました")
                }
            }
        }
    }
}

@Composable
private fun NudgeRow(label: String, value: Long, onNudge: (Long) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(label, color = VM.Mu, fontSize = 12.5.sp, modifier = Modifier.width(36.dp))
        NudgeBtn("−1s") { onNudge(-1000) }
        NudgeBtn("−.1") { onNudge(-100) }
        Text(fmtTime(value), color = VM.Tx, fontSize = 19.sp, style = NumStyle, modifier = Modifier.weight(1f), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
        NudgeBtn("+.1") { onNudge(100) }
        NudgeBtn("+1s") { onNudge(1000) }
    }
}

@Composable
private fun NudgeBtn(text: String, onClick: () -> Unit) {
    Box(
        Modifier.height(34.dp).width(46.dp).clip(RoundedCornerShape(9.dp)).background(VM.S2).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = VM.Tx, fontSize = 13.sp, style = NumStyle.copy(fontWeight = FontWeight.Normal)) }
}

@Composable
private fun SmallToggle(text: String, on: Boolean, icon: androidx.compose.ui.graphics.vector.ImageVector? = null, onClick: () -> Unit) {
    Row(
        Modifier.height(38.dp).clip(RoundedCornerShape(10.dp)).background(VM.S1)
            .border(1.dp, if (on) VM.Moon else VM.Line, RoundedCornerShape(10.dp)).clickable(onClick = onClick)
            .padding(horizontal = 10.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (icon != null) { Icon(icon, null, tint = if (on) VM.Moon else VM.Tx, modifier = Modifier.size(16.dp)); Spacer(Modifier.width(4.dp)) }
        Text(text, color = if (on) VM.Moon else VM.Tx, fontSize = 12.5.sp, maxLines = 1)
    }
}
