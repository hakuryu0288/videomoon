package com.videomoon.app.ui

import android.Manifest
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.media.AudioManager
import android.media.ToneGenerator
import android.os.SystemClock
import android.app.Activity
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.hardware.display.DisplayManager
import android.view.Surface
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.displayCutoutPadding
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.Cameraswitch
import androidx.compose.material.icons.rounded.FlashOff
import androidx.compose.material.icons.rounded.FlashOn
import androidx.compose.material.icons.rounded.MusicNote
import androidx.compose.material3.Icon
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.videomoon.app.camera.CameraEngine
import com.videomoon.app.camera.Exposure
import com.videomoon.app.camera.ProState
import com.videomoon.app.camera.RecordController
import com.videomoon.app.data.Take
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sqrt

private enum class ProParam { ISO, SHUTTER, WB, EV, FOCUS }

@Composable
fun CameraScreen(
    vm: MainViewModel,
    onOpenLibrary: () -> Unit,
    onOpenTracks: () -> Unit,
    onRecordingChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    var granted by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted = it }
    LaunchedEffect(Unit) { if (!granted) launcher.launch(Manifest.permission.CAMERA) }
    if (!granted) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("撮影にはカメラへのアクセスが必要です", color = VM.Tx, fontSize = 16.sp)
            Spacer(Modifier.height(6.dp))
            Text("マイクは使いません。動画に入る音は、撮影中に流した音楽だけです。", color = VM.Mu, fontSize = 13.sp)
            Spacer(Modifier.height(18.dp))
            MoonButton("カメラを許可する") { launcher.launch(Manifest.permission.CAMERA) }
        }
        return
    }
    CameraContent(vm, onOpenLibrary, onOpenTracks, onRecordingChange)
}

@Composable
private fun CameraContent(
    vm: MainViewModel,
    onOpenLibrary: () -> Unit,
    onOpenTracks: () -> Unit,
    onRecordingChange: (Boolean) -> Unit,
) {
    val context = LocalContext.current
    val owner = LocalLifecycleOwner.current
    val scope = rememberCoroutineScope()
    val settings by vm.settings.collectAsState()
    val tracks by vm.tracks.collectAsState()
    val activeKey by vm.activeClip.collectAsState()
    val takes by vm.takes.collectAsState()
    val active = remember(activeKey, tracks) { vm.resolveActive() }

    val engine = remember { CameraEngine(context) }
    val controller = remember { RecordController(context, engine) }
    val tone = remember { ToneGenerator(AudioManager.STREAM_MUSIC, 70) }
    DisposableEffect(Unit) { onDispose { controller.release(); engine.unbind(); tone.release() } }

    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FIT_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    var photoMode by rememberSaveable { mutableStateOf(false) }
    var pro by remember { mutableStateOf(ProState()) }
    var sel by remember { mutableStateOf<ProParam?>(null) }
    val caps by engine.caps.collectAsState()
    val live by engine.live.collectAsState()
    var phase by remember { mutableStateOf<RecordController.Phase>(RecordController.Phase.Idle) }
    var counting by remember { mutableIntStateOf(0) }
    var review by remember { mutableStateOf<Take?>(null) }
    var showClipSheet by remember { mutableStateOf(false) }
    var showQualitySheet by remember { mutableStateOf(false) }
    var stopLoop by remember { mutableStateOf(false) }
    val flash = remember { Animatable(0f) }

    val busyNow = counting > 0 || phase != RecordController.Phase.Idle
    LaunchedEffect(busyNow) { onRecordingChange(busyNow) }

    // ---- 画面の向き ----
    // 自動：端末を回すと画面も回り、録画も横長/縦長になる。録画中は向きを固定する
    val land = LocalConfiguration.current.orientation == Configuration.ORIENTATION_LANDSCAPE
    val activity = context as? Activity
    DisposableEffect(settings.orientationMode, busyNow) {
        activity?.requestedOrientation = when {
            busyNow -> ActivityInfo.SCREEN_ORIENTATION_LOCKED
            settings.orientationMode == "portrait" -> ActivityInfo.SCREEN_ORIENTATION_USER_PORTRAIT
            settings.orientationMode == "landscape" -> ActivityInfo.SCREEN_ORIENTATION_USER_LANDSCAPE
            else -> ActivityInfo.SCREEN_ORIENTATION_FULL_USER
        }
        onDispose { }
    }
    DisposableEffect(Unit) { onDispose { activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_FULL_USER } }

    // 画面の回転（左右どちらに倒したかも含む）を録画の向きに反映
    var rotation by remember { mutableIntStateOf(ContextCompat.getDisplayOrDefault(context).rotation) }
    DisposableEffect(Unit) {
        val dm = context.getSystemService(DisplayManager::class.java)
        val l = object : DisplayManager.DisplayListener {
            override fun onDisplayAdded(displayId: Int) {}
            override fun onDisplayRemoved(displayId: Int) {}
            override fun onDisplayChanged(displayId: Int) { rotation = ContextCompat.getDisplayOrDefault(context).rotation }
        }
        dm.registerDisplayListener(l, null)
        onDispose { dm.unregisterDisplayListener(l) }
    }

    // ---- カメラの起動（設定が変わったら組み直す） ----
    val cfg = CameraEngine.Config(
        front = settings.frontCamera, photo = photoMode, fps = settings.fps, quality = settings.quality,
        stabilization = settings.stabilization, mirrorSave = settings.mirrorSave, rotation = Surface.ROTATION_0,
    )
    LaunchedEffect(cfg) {
        try {
            engine.bind(owner, previewView, cfg)
            engine.setRotation(rotation)
        } catch (e: Exception) {
            vm.messages.tryEmit("カメラを起動できませんでした：${e.message}")
        }
    }
    LaunchedEffect(rotation) { if (!busyNow) engine.setRotation(rotation) }
    LaunchedEffect(caps.note) { caps.note?.let { vm.messages.tryEmit(it) } }
    LaunchedEffect(pro, caps, settings.flickerHz) { if (caps.ready) engine.applyPro(pro, caps.actualFps, settings.flickerHz) }

    // 曲を前もって読み込む
    LaunchedEffect(active) {
        val a = active
        if (a != null) controller.load(vm.trackFile(a.first), a.second) else controller.unload()
    }

    previewView.scaleX = if (settings.frontCamera && !settings.mirrorPreview) -1f else 1f

    // ---- ヒストグラム・ゼブラ・ピーキング ----
    var histo by remember { mutableStateOf<IntArray?>(null) }
    var overlay by remember { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(settings.histogram, settings.zebra, settings.peaking, settings.zebraLevel) {
        histo = null; overlay = null
        if (!settings.histogram && !settings.zebra && !settings.peaking) return@LaunchedEffect
        var ph = 0
        while (true) {
            delay(250)
            val bmp = previewView.bitmap ?: continue
            ph += 3
            val r = withContext(Dispatchers.Default) { Analysis.run(bmp, settings.zebra, settings.zebraLevel, settings.peaking, ph) }
            histo = if (settings.histogram) r.histo else null
            overlay = r.overlay?.asImageBitmap()
        }
    }

    // ---- 録画中の表示更新 ----
    var tick by remember { mutableLongStateOf(0L) }
    LaunchedEffect(phase) {
        while (phase != RecordController.Phase.Idle) { tick = SystemClock.elapsedRealtime(); delay(33) }
    }
    val recPhase = phase as? RecordController.Phase.Recording
    val elapsed = recPhase?.let { tick - it.startedAt } ?: 0L
    val clipProgress: Float? = active?.let { (_, c) ->
        if (phase == RecordController.Phase.Idle) null else {
            val pre = c.prerollMs.coerceAtMost(c.startMs)
            ((controller.clipPositionMs() - pre).toFloat() / c.lengthMs).coerceIn(0f, 1f)
        }
    }
    val inPreroll = phase == RecordController.Phase.PreRoll

    fun beep(high: Boolean) { tone.startTone(if (high) ToneGenerator.TONE_PROP_BEEP2 else ToneGenerator.TONE_PROP_BEEP, 120) }

    fun startTake() = scope.launch {
        stopLoop = false
        while (true) {
            if (settings.countdown > 0) {
                for (i in settings.countdown downTo 1) {
                    if (stopLoop) break
                    counting = i; beep(i == 1); delay(1000)
                }
                counting = 0
                if (stopLoop) break
            }
            val act = vm.resolveActive()
            val file = vm.newVideoFile()
            val res = controller.record(file, act?.second, settings.monitorVolume) { phase = it }
            phase = RecordController.Phase.Idle
            if (res.error != null) {
                if (res.error != "cancel") vm.messages.tryEmit(res.error)
                file.delete(); break
            }
            val take = vm.addVideoTake(res, act)
            if (settings.continuous && !stopLoop) {
                vm.messages.tryEmit("${take.title} を保存しました。続けて撮影します")
                delay(1200)
                if (stopLoop) break
            } else {
                if (settings.autoReview) review = take else vm.messages.tryEmit("ライブラリに保存しました")
                break
            }
        }
    }

    fun onShutter() {
        if (photoMode) {
            scope.launch {
                val f = vm.newPhotoFile()
                try {
                    beep(true)
                    launch { flash.snapTo(.85f); flash.animateTo(0f, tween(350)) }
                    engine.takePhoto(f); vm.addPhotoTake(f); vm.messages.tryEmit("写真を保存しました")
                } catch (e: Exception) { vm.messages.tryEmit("撮影できませんでした") }
            }
            return
        }
        if (busyNow) { stopLoop = true; controller.requestStop(); return }
        sel = null
        startTake()
    }

    Box(Modifier.fillMaxSize().background(Color.Black)) {
        AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
        overlay?.let {
            Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.FillBounds)
        }
        // 操作（タップでピント合わせ、ピンチでズーム）
        Box(
            Modifier
                .fillMaxSize()
                .pointerInput(Unit) {
                    detectTransformGestures { _, _, zoom, _ ->
                        pro = pro.copy(zoom = (pro.zoom * zoom).coerceIn(caps.minZoom, caps.maxZoom))
                    }
                }
                .pointerInput(Unit) {
                    detectTapGestures { off ->
                        if (sel != null) sel = null
                        else if (pro.af) engine.tapToFocus(previewView, off.x, off.y)
                    }
                }
        )
        if (settings.grid) GridOverlay(if (photoMode) (if (land) 4f / 3f else 3f / 4f) else if (land) 16f / 9f else 9f / 16f)

        if (counting > 0) {
            Text(
                "$counting", color = VM.Moon, fontSize = 150.sp, style = NumStyle.copy(fontWeight = FontWeight.Medium),
                modifier = Modifier.align(Alignment.Center),
            )
        }
        Box(Modifier.fillMaxSize().alpha(flash.value).background(Color.White))

        val dim = if (busyNow) .25f else 1f
        val recProgress = when {
            recPhase == null -> 0f
            clipProgress != null -> clipProgress
            else -> ((elapsed % 60_000) / 60_000f)
        }
        val orientLabel = when (settings.orientationMode) { "portrait" -> "縦"; "landscape" -> "横"; else -> "自動" }

        // ---- 上部 ----
        Column(
            Modifier
                .fillMaxWidth()
                .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color(0xC005070F), Color.Transparent)))
                .statusBarsPadding()
                .displayCutoutPadding()
                .padding(start = 12.dp, end = if (land) 124.dp else 12.dp, top = 6.dp, bottom = 6.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                Chip("${caps.actualFps.takeIf { caps.ready && !photoMode } ?: settings.fps}p", accent = true) {
                    if (!busyNow) vm.updateSettings { s -> s.copy(fps = listOf(60, 30, 24)[(listOf(60, 30, 24).indexOf(s.fps) + 1) % 3]) }
                }
                Chip("${if (settings.quality == "4K") "4K" else settings.quality + "p"} · $orientLabel") { if (!busyNow) showQualitySheet = true }
                Chip(if (settings.countdown > 0) "⏱ ${settings.countdown}s" else "⏱ オフ") {
                    if (!busyNow) vm.updateSettings { s -> s.copy(countdown = listOf(0, 3, 5, 10)[(listOf(0, 3, 5, 10).indexOf(s.countdown) + 1) % 4]) }
                }
                if (caps.hasFlash) {
                    Box(
                        Modifier.size(30.dp).clip(CircleShape).background(if (pro.torch) VM.Moon else VM.S1.copy(alpha = .75f))
                            .clickable { pro = pro.copy(torch = !pro.torch) },
                        contentAlignment = Alignment.Center,
                    ) { Icon(if (pro.torch) Icons.Rounded.FlashOn else Icons.Rounded.FlashOff, "ライト", tint = if (pro.torch) VM.Ink else VM.Tx, modifier = Modifier.size(16.dp)) }
                }
                if (land) Box(Modifier.weight(1f, fill = false)) { ClipChip(active, photoMode) { if (!busyNow) showClipSheet = true } }
                Spacer(Modifier.weight(1f))
                val fps = caps.actualFps.coerceAtLeast(1)
                val s = elapsed / 1000
                val f = ((elapsed % 1000) * fps / 1000)
                Box(Modifier.size(9.dp).clip(CircleShape).background(if (recPhase != null && (tick / 500) % 2 == 0L) VM.Rec else VM.Dim))
                Spacer(Modifier.width(5.dp))
                Text("%02d:%02d:%02d".format(s / 60, s % 60, f), color = if (recPhase != null) Color.White else VM.Tx, fontSize = 16.sp, style = NumStyle)
            }
            if (!land) {
                Spacer(Modifier.height(8.dp))
                ClipChip(active, photoMode) { if (!busyNow) showClipSheet = true }
            }
            if (busyNow && active != null) {
                Spacer(Modifier.height(8.dp))
                RecProgressBar(vm, active.first, active.second, clipProgress ?: 0f, inPreroll)
            }
            val flickerBad = !pro.ae && caps.manualSensor && !Exposure.flickerSafe(pro.shutterDen, settings.flickerHz)
            if (flickerBad && !busyNow) {
                Spacer(Modifier.height(8.dp))
                Chip("⚠ 1/${pro.shutterDen} は${settings.flickerHz}Hz照明でちらつきます（タップで修正）") {
                    pro = pro.copy(shutterDen = Exposure.safeShutter(caps.actualFps, settings.flickerHz))
                }
            }
        }

        // ---- ヒストグラムと実測fps ----
        Column(
            Modifier.align(Alignment.TopEnd).statusBarsPadding().displayCutoutPadding().padding(
                top = when { land && busyNow && active != null -> 92.dp; land -> 52.dp; busyNow && active != null -> 140.dp; else -> 96.dp },
                end = if (land) 128.dp else 10.dp,
            ),
            horizontalAlignment = Alignment.End,
        ) {
            histo?.let { Histogram(it) }
            Spacer(Modifier.height(6.dp))
            Text(
                "実測 ${live.measuredFps.roundToInt()} / ${caps.actualFps} fps", color = VM.Mu, fontSize = 12.sp,
                modifier = Modifier.clip(RoundedCornerShape(7.dp)).background(Color(0x8C05070F)).padding(horizontal = 7.dp, vertical = 1.dp),
            )
        }

        if (!land) {
            // ---- 縦：下部にまとめる ----
            Column(
                Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .background(androidx.compose.ui.graphics.Brush.verticalGradient(listOf(Color.Transparent, Color(0xD005070F))))
                    .padding(top = 30.dp, bottom = 8.dp)
            ) {
                sel?.let { p -> ProPanel(p, pro, caps, live, caps.actualFps, settings.flickerHz) { pro = it } }
                Box(Modifier.alpha(dim)) { ProStrip(pro, caps, live, sel) { if (!busyNow) sel = if (sel == it) null else it } }
                ModeSwitch(photoMode, vertical = false, enabled = !busyNow, modifier = Modifier.fillMaxWidth().padding(top = 12.dp, bottom = 6.dp).alpha(dim)) { photoMode = it }
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 30.dp),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    LastThumb(vm, takes.firstOrNull(), Modifier.alpha(dim)) { if (!busyNow) onOpenLibrary() }
                    MoonRecordButton(photoMode = photoMode, recording = busyNow, progress = recProgress, onClick = ::onShutter)
                    FlipButton(Modifier.alpha(dim)) { if (!busyNow) vm.updateSettings { it.copy(frontCamera = !it.frontCamera) } }
                }
            }
        } else {
            // ---- 横：右に撮影ボタン、下にプロ設定 ----
            Column(
                Modifier
                    .align(Alignment.CenterEnd)
                    .fillMaxHeight()
                    .background(androidx.compose.ui.graphics.Brush.horizontalGradient(listOf(Color.Transparent, Color(0xD005070F))))
                    .navigationBarsPadding()
                    .displayCutoutPadding()
                    .width(112.dp)
                    .padding(vertical = 8.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.spacedBy(14.dp, Alignment.CenterVertically),
            ) {
                ModeSwitch(photoMode, vertical = true, enabled = !busyNow, modifier = Modifier.alpha(dim)) { photoMode = it }
                FlipButton(Modifier.alpha(dim)) { if (!busyNow) vm.updateSettings { it.copy(frontCamera = !it.frontCamera) } }
                MoonRecordButton(photoMode = photoMode, recording = busyNow, progress = recProgress, onClick = ::onShutter)
                LastThumb(vm, takes.firstOrNull(), Modifier.alpha(dim)) { if (!busyNow) onOpenLibrary() }
            }
            Column(
                Modifier
                    .align(Alignment.BottomStart)
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .displayCutoutPadding()
                    .padding(end = 112.dp, bottom = 8.dp)
            ) {
                sel?.let { p -> Box(Modifier.widthIn(max = 560.dp)) { ProPanel(p, pro, caps, live, caps.actualFps, settings.flickerHz) { pro = it } } }
                Box(Modifier.alpha(dim)) { ProStrip(pro, caps, live, sel) { if (!busyNow) sel = if (sel == it) null else it } }
            }
        }

        // ---- シート ----
        Sheet(showClipSheet, { showClipSheet = false }) {
            Text("撮影中に流す音楽", color = VM.Tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            Text("選んだ範囲の音楽が撮影と同時に流れ、動画の音声になります。カメラのマイク音は入りません。", color = VM.Mu, fontSize = 12.5.sp)
            Spacer(Modifier.height(10.dp))
            SheetOption("音楽なし", "無音の動画として撮影", selected = activeKey == null) { vm.setActiveClip(null); showClipSheet = false }
            tracks.forEach { t ->
                t.clips.forEach { c ->
                    SheetOption(c.name, "${t.name} ・ ${fmtTime(c.startMs)}–${fmtTime(c.endMs)}", selected = activeKey?.second == c.id) {
                        vm.setActiveClip(t.id to c.id); showClipSheet = false
                    }
                }
            }
            SheetOption("音源を追加・範囲を切り取る", "端末の曲や動画の音声から") { showClipSheet = false; onOpenTracks() }
        }
        Sheet(showQualitySheet, { showQualitySheet = false }) {
            Text("解像度と向き", color = VM.Tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
            SettingRow("解像度") { Seg(listOf("720" to "720p", "1080" to "1080p", "4K" to "4K"), settings.quality, { q -> vm.updateSettings { it.copy(quality = q) } }) }
            SettingRow("向き", "自動：横に持てば横長、縦に持てば縦長で撮影") { Seg(listOf("auto" to "自動", "portrait" to "縦", "landscape" to "横"), settings.orientationMode, { v -> vm.updateSettings { it.copy(orientationMode = v) } }) }
            Text("4Kや60fpsは端末によって対応していないことがあります。そのときは近い設定で撮影し、画面上部でお知らせします。", color = VM.Mu, fontSize = 12.sp, modifier = Modifier.padding(top = 10.dp))
        }
        review?.let { t ->
            ReviewSheet(vm, t, onRetake = {
                vm.deleteTakes(listOf(t.id)); review = null
                vm.messages.tryEmit("削除しました。もう一度どうぞ")
            }, onKeep = { review = null }, onExport = { vm.export(listOf(t)); review = null })
        }
    }
}

@Composable
private fun ClipChip(active: Pair<com.videomoon.app.data.Track, com.videomoon.app.data.Clip>?, photoMode: Boolean, onClick: () -> Unit) {
    Row(
        Modifier
            .clip(RoundedCornerShape(17.dp))
            .background(VM.S1.copy(alpha = .75f))
            .border(1.dp, VM.Line, RoundedCornerShape(17.dp))
            .clickable(onClick = onClick)
            .padding(start = 6.dp, end = 12.dp, top = 5.dp, bottom = 5.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Box(Modifier.size(22.dp).clip(CircleShape).background(VM.Moon), contentAlignment = Alignment.Center) {
            Icon(Icons.Rounded.MusicNote, null, tint = VM.Ink, modifier = Modifier.size(14.dp))
        }
        Spacer(Modifier.width(8.dp))
        Text(
            if (active == null) (if (photoMode) "写真モード" else "音楽なしで撮影") else "${active.first.name} · ${active.second.name}",
            color = VM.Tx, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.weight(1f, fill = false),
        )
        Spacer(Modifier.width(8.dp))
        Text(
            if (active == null) "タップして選ぶ" else "${fmtTime(active.second.startMs)}–${fmtTime(active.second.endMs)}",
            color = VM.Mu, fontSize = 12.5.sp, maxLines = 1, style = NumStyle.copy(fontWeight = FontWeight.Normal),
        )
    }
}

@Composable
private fun ModeSwitch(photoMode: Boolean, vertical: Boolean, enabled: Boolean, modifier: Modifier = Modifier, onChange: (Boolean) -> Unit) {
    val items: @Composable () -> Unit = {
        listOf(true to "写真", false to "動画").forEach { (isPhoto, label) ->
            Text(
                label, color = if (photoMode == isPhoto) VM.Moon else VM.Mu,
                fontWeight = if (photoMode == isPhoto) FontWeight.Bold else FontWeight.Normal,
                modifier = Modifier.clickable(enabled = enabled) { onChange(isPhoto) }.padding(horizontal = 14.dp, vertical = 2.dp),
            )
        }
    }
    if (vertical) Column(modifier, horizontalAlignment = Alignment.CenterHorizontally) { items() }
    else Row(modifier, horizontalArrangement = Arrangement.Center) { items() }
}

@Composable
private fun FlipButton(modifier: Modifier, onClick: () -> Unit) {
    Box(
        modifier.size(46.dp).clip(CircleShape).background(VM.S1.copy(alpha = .8f)).border(1.dp, VM.Line, CircleShape).clickable(onClick = onClick),
        contentAlignment = Alignment.Center,
    ) { Icon(Icons.Rounded.Cameraswitch, "カメラ切替", tint = VM.Tx) }
}

@Composable
private fun ReviewSheet(vm: MainViewModel, take: Take, onRetake: () -> Unit, onKeep: () -> Unit, onExport: () -> Unit) {
    Sheet(true, onKeep) {
        Text(take.title, color = VM.Tx, fontSize = 18.sp, fontWeight = FontWeight.Bold)
        Text(
            "%.1f秒 ・ %dfps ・ %s".format(take.durationMs / 1000f, take.fps, vm.trackOf(take)?.name?.let { "音声：$it" } ?: "音声なし"),
            color = VM.Mu, fontSize = 12.5.sp,
        )
        Spacer(Modifier.height(10.dp))
        val player = rememberTakePlayer(vm, take)
        PlayerSurface(player, Modifier.fillMaxWidth().height(360.dp).clip(RoundedCornerShape(14.dp)))
        Spacer(Modifier.height(12.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            MoonButton("消して撮り直す", Modifier.weight(1f), primary = false, color = VM.Rec, onClick = onRetake)
            MoonButton("残して次へ", Modifier.weight(1f), onClick = onKeep)
        }
        Spacer(Modifier.height(8.dp))
        MoonButton("書き出して端末に保存", Modifier.fillMaxWidth(), primary = false, onClick = onExport)
    }
}

@Composable
private fun RecProgressBar(vm: MainViewModel, track: com.videomoon.app.data.Track, clip: com.videomoon.app.data.Clip, progress: Float, preroll: Boolean) {
    var peaks by remember(track.id) { mutableStateOf<FloatArray?>(null) }
    LaunchedEffect(track.id) { peaks = vm.peaks(track) }
    Box(Modifier.fillMaxWidth().height(34.dp).clip(RoundedCornerShape(8.dp)).background(VM.S1.copy(alpha = .6f))) {
        Canvas(Modifier.fillMaxSize()) {
            val p = peaks ?: return@Canvas
            val dur = track.durationMs.coerceAtLeast(1)
            val a = clip.startMs.toFloat() / dur
            val b = clip.endMs.toFloat() / dur
            val cols = (size.width / 3f).toInt()
            for (x in 0 until cols) {
                val i0 = ((a + (b - a) * x / cols) * p.size).toInt()
                val i1 = ((a + (b - a) * (x + 1) / cols) * p.size).toInt().coerceAtLeast(i0 + 1)
                var m = 0f
                for (i in i0 until i1.coerceAtMost(p.size)) m = maxOf(m, p[i])
                val h = (m * size.height * .85f).coerceAtLeast(1.5f)
                drawRect(
                    if (x.toFloat() / cols < progress) VM.Moon else Color(0xFF4A4A62),
                    Offset(x * 3f, (size.height - h) / 2), Size(2f, h),
                )
            }
            drawRect(VM.Rec, Offset(size.width * progress - 1f, 0f), Size(2f, size.height))
        }
        if (preroll) Text("プリロール中 — まだ録画されていません", color = VM.Warn, fontSize = 11.5.sp, modifier = Modifier.align(Alignment.CenterStart).padding(start = 8.dp))
    }
}

@Composable
private fun GridOverlay(frameAspect: Float) {
    Canvas(Modifier.fillMaxSize()) {
        var w = size.width
        var h = w / frameAspect
        if (h > size.height) { h = size.height; w = h * frameAspect }
        val l = (size.width - w) / 2
        val t = (size.height - h) / 2
        val c = Color.White.copy(alpha = .28f)
        for (i in 1..2) {
            drawLine(c, Offset(l + w * i / 3, t), Offset(l + w * i / 3, t + h), 1f)
            drawLine(c, Offset(l, t + h * i / 3), Offset(l + w, t + h * i / 3), 1f)
        }
    }
}

@Composable
private fun Histogram(h: IntArray) {
    Canvas(
        Modifier.width(112.dp).height(58.dp).clip(RoundedCornerShape(8.dp)).background(Color(0x8C05070F))
            .border(1.dp, VM.Line, RoundedCornerShape(8.dp)).padding(4.dp)
    ) {
        val mx = (h.maxOrNull() ?: 1).coerceAtLeast(1).toFloat()
        val bw = size.width / h.size
        val total = h.sum().coerceAtLeast(1)
        h.forEachIndexed { i, v ->
            val bh = sqrt(v / mx) * size.height
            drawRect(VM.Moon.copy(alpha = .75f), Offset(i * bw, size.height - bh), Size(bw - .6f, bh))
        }
        if (h.last().toFloat() / total > .02f) drawRect(VM.Rec, Offset(size.width - 3f, 0f), Size(3f, size.height))
    }
}

/** 録画ボタン：クリップの進み具合を月の満ち欠けで表示 */
@Composable
private fun MoonRecordButton(photoMode: Boolean, recording: Boolean, progress: Float, onClick: () -> Unit) {
    Canvas(
        Modifier.size(78.dp).clip(CircleShape).clickable(onClick = onClick)
    ) {
        val ring = when { photoMode -> Color.White; recording -> VM.Rec; else -> VM.Moon.copy(alpha = .85f) }
        drawCircle(ring, style = Stroke(3.dp.toPx()))
        val r = size.minDimension / 2 - 6.dp.toPx()
        val c = center
        drawCircle(if (photoMode) Color.White else VM.Rec, r, c)
        if (recording && !photoMode) {
            // 新月(0) → 満月(1)
            val k = cos(PI * (1 - progress.coerceIn(0f, 1f))).toFloat()
            val rx = abs(k) * r
            val path = Path().apply {
                moveTo(c.x, c.y - r)
                arcTo(Rect(c.x - r, c.y - r, c.x + r, c.y + r), -90f, -180f, false)
                arcTo(Rect(c.x - rx, c.y - r, c.x + rx, c.y + r), 90f, if (k > 0) 180f else -180f, false)
                close()
            }
            drawPath(path, Color(0xC70B0B10))
        }
    }
}

@Composable
private fun LastThumb(vm: MainViewModel, take: Take?, modifier: Modifier, onClick: () -> Unit) {
    var bmp by remember(take?.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(take?.id) {
        bmp = take?.let { t -> vm.store.thumbFile(t)?.takeIf(File::exists)?.let { f -> withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() } } }
    }
    Box(
        modifier.size(46.dp).clip(RoundedCornerShape(10.dp)).background(VM.S2)
            .border(2.dp, Color.White.copy(alpha = .2f), RoundedCornerShape(10.dp)).clickable(onClick = onClick)
    ) { bmp?.let { Image(it, "ライブラリ", Modifier.fillMaxSize(), contentScale = ContentScale.Crop) } }
}

// ---------------- プロ設定 ----------------

@Composable
private fun ProStrip(pro: ProState, caps: CameraEngine.Caps, live: CameraEngine.Live, sel: ProParam?, onSelect: (ProParam) -> Unit) {
    val shutterNow = if (pro.ae && live.exposureNs > 0) (1_000_000_000.0 / live.exposureNs).roundToInt() else pro.shutterDen
    val cells = listOf(
        Triple(ProParam.ISO, "ISO", if (pro.ae) (live.iso.takeIf { it > 0 }?.toString() ?: "AUTO") else pro.iso.toString()),
        Triple(ProParam.SHUTTER, "シャッター", "1/$shutterNow"),
        Triple(ProParam.WB, "WB", Exposure.wbLabel(pro.awbMode)),
        Triple(ProParam.EV, "露出補正", if (pro.ae) "%+.1f".format(pro.evIndex * caps.evStep).replace("+0.0", "±0") else "—"),
        Triple(ProParam.FOCUS, "フォーカス", if (pro.af) "AF" else "MF"),
    )
    Row(
        Modifier.padding(horizontal = 10.dp).fillMaxWidth().clip(RoundedCornerShape(12.dp))
            .background(Color(0xB30A0E1F)).border(1.dp, VM.Line, RoundedCornerShape(12.dp))
    ) {
        cells.forEach { (p, label, value) ->
            val auto = when (p) { ProParam.ISO, ProParam.SHUTTER -> pro.ae; ProParam.WB -> pro.awbMode == Exposure.WB[0].mode; ProParam.FOCUS -> pro.af; else -> null }
            Column(
                Modifier.weight(1f).background(if (sel == p) VM.Moon.copy(alpha = .12f) else Color.Transparent)
                    .clickable { onSelect(p) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(label, color = VM.Mu, fontSize = 10.5.sp)
                    if (auto != null) Text(if (auto) " A" else " M", color = if (auto) VM.Ok else VM.Moon, fontSize = 10.sp, style = NumStyle)
                }
                Text(value, color = if (sel == p) VM.Moon else if (p == ProParam.EV && !pro.ae) VM.Dim else VM.Tx, fontSize = 16.sp, style = NumStyle, maxLines = 1)
            }
        }
    }
}

@Composable
private fun ProPanel(
    p: ProParam, pro: ProState, caps: CameraEngine.Caps, live: CameraEngine.Live, fps: Int, flickerHz: Int,
    onChange: (ProState) -> Unit,
) {
    val isoStops = Exposure.ISO_STOPS.filter { caps.isoRange?.contains(it) ?: true }
    val shutStops = Exposure.shutterStops(fps, caps.exposureRange?.lower, caps.exposureRange?.upper)
    // オートから手動へ切り替えるときは、いまオートが選んでいる値から始める
    fun toManual(): ProState = if (!pro.ae) pro else pro.copy(
        ae = false,
        iso = isoStops.minByOrNull { abs(it - live.iso) } ?: pro.iso,
        shutterDen = if (live.exposureNs > 0) shutStops.minByOrNull { abs(it - 1_000_000_000.0 / live.exposureNs) } ?: pro.shutterDen else pro.shutterDen,
    )

    Column(
        Modifier.padding(horizontal = 10.dp).padding(bottom = 8.dp).fillMaxWidth().clip(RoundedCornerShape(14.dp))
            .background(VM.S1.copy(alpha = .94f)).border(1.dp, VM.Line, RoundedCornerShape(14.dp)).padding(12.dp)
    ) {
        val manualBlocked = (p == ProParam.ISO || p == ProParam.SHUTTER) && !caps.manualSensor
        val (name, value, sub) = when (p) {
            ProParam.ISO -> Triple("ISO感度", if (pro.ae) "${live.iso}" else "${pro.iso}", "明るさ↑ ノイズ↑")
            ProParam.SHUTTER -> {
                val d = if (pro.ae && live.exposureNs > 0) (1_000_000_000.0 / live.exposureNs).roundToInt() else pro.shutterDen
                Triple("シャッタースピード", "1/$d", "${Exposure.shutterAngle(fps, d)}°")
            }
            ProParam.WB -> Exposure.WB.first { it.mode == pro.awbMode }.let { Triple("ホワイトバランス", it.label, it.kelvin) }
            ProParam.EV -> Triple("露出補正（オート時）", "%+.1f".format(pro.evIndex * caps.evStep), "")
            ProParam.FOCUS -> Triple("フォーカス", if (pro.af) "AF" else if (pro.focus > .95f) "∞" else "${(pro.focus * 100).roundToInt()}%", "")
        }
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(name, color = VM.Mu, fontSize = 13.sp, modifier = Modifier.weight(1f))
            Text(value, color = VM.Moon, fontSize = 22.sp, style = NumStyle)
            if (sub.isNotEmpty()) Text("  $sub", color = VM.Mu, fontSize = 12.sp)
            Spacer(Modifier.weight(1f))
            val autoOn = when (p) { ProParam.ISO, ProParam.SHUTTER -> pro.ae; ProParam.WB -> pro.awbMode == Exposure.WB[0].mode; ProParam.FOCUS -> pro.af; ProParam.EV -> null }
            if (autoOn != null) {
                Box(
                    Modifier.clip(RoundedCornerShape(13.dp)).background(if (autoOn) VM.Moon else Color.Transparent)
                        .border(1.dp, if (autoOn) VM.Moon else VM.Line, RoundedCornerShape(13.dp))
                        .clickable {
                            onChange(
                                when (p) {
                                    ProParam.ISO, ProParam.SHUTTER -> if (pro.ae) toManual() else pro.copy(ae = true)
                                    ProParam.WB -> pro.copy(awbMode = Exposure.WB[0].mode)
                                    ProParam.FOCUS -> pro.copy(af = !pro.af)
                                    else -> pro
                                }
                            )
                        }.padding(horizontal = 10.dp, vertical = 4.dp)
                ) { Text(if (p == ProParam.FOCUS) "AF" else "オート", color = if (autoOn) VM.Ink else VM.Mu, fontSize = 12.sp) }
            }
        }
        val colors = SliderDefaults.colors(thumbColor = VM.Moon, activeTrackColor = VM.Moon, inactiveTrackColor = VM.S3)
        if (manualBlocked) {
            Text("この端末はISO・シャッターの手動設定に対応していません。露出補正で明るさを調整してください。", color = VM.Mu, fontSize = 12.sp, modifier = Modifier.padding(vertical = 8.dp))
        } else when (p) {
            ProParam.ISO -> StepSlider(isoStops.indexOf(if (pro.ae) isoStops.minByOrNull { abs(it - live.iso) } ?: pro.iso else pro.iso).coerceAtLeast(0), isoStops.size, colors) { i ->
                onChange(toManual().copy(iso = isoStops[i]))
            }
            ProParam.SHUTTER -> StepSlider(shutStops.indexOf(pro.shutterDen).coerceAtLeast(0), shutStops.size, colors) { i ->
                onChange(toManual().copy(shutterDen = shutStops[i]))
            }
            ProParam.WB -> StepSlider(Exposure.WB.indexOfFirst { it.mode == pro.awbMode }.coerceAtLeast(0), Exposure.WB.size, colors) { i ->
                onChange(pro.copy(awbMode = Exposure.WB[i].mode))
            }
            ProParam.EV -> {
                val r = caps.evRange
                if (r.upper > r.lower) StepSlider(pro.evIndex - r.lower, r.upper - r.lower + 1, colors, enabled = pro.ae) { i -> onChange(pro.copy(evIndex = r.lower + i)) }
                else Text("この端末は露出補正に対応していません", color = VM.Mu, fontSize = 12.sp)
            }
            ProParam.FOCUS -> {
                if (caps.minFocusDistance > 0f) Slider(pro.focus, { onChange(pro.copy(af = false, focus = it)) }, colors = colors)
                else Text("この端末はマニュアルフォーカスに対応していません", color = VM.Mu, fontSize = 12.sp)
            }
        }
        Row(Modifier.horizontalScroll(rememberScrollState()).padding(top = 4.dp), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Exposure.PRESETS.forEach { preset ->
                Chip(preset.name) { onChange(preset.apply(pro, fps, flickerHz)) }
            }
        }
    }
}

@Composable
private fun StepSlider(index: Int, count: Int, colors: androidx.compose.material3.SliderColors, enabled: Boolean = true, onIndex: (Int) -> Unit) {
    if (count <= 1) return
    Slider(
        value = index.toFloat(),
        onValueChange = { v -> val i = v.roundToInt().coerceIn(0, count - 1); if (i != index) onIndex(i) },
        valueRange = 0f..(count - 1).toFloat(),
        steps = (count - 2).coerceAtLeast(0),
        enabled = enabled,
        colors = colors,
    )
}
