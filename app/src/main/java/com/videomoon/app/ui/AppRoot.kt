package com.videomoon.app.ui

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GridView
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.Tune
import androidx.compose.material.icons.rounded.Videocam
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

enum class Tab(val label: String, val icon: ImageVector) {
    Camera("撮影", Icons.Rounded.Videocam),
    Tracks("音源", Icons.Rounded.GraphicEq),
    Library("ライブラリ", Icons.Rounded.GridView),
    Settings("設定", Icons.Rounded.Tune),
}

@Composable
fun AppRoot(vm: MainViewModel) {
    var tab by rememberSaveable { mutableStateOf(Tab.Camera) }
    var trimId by rememberSaveable { mutableStateOf<String?>(null) }
    var playerId by rememberSaveable { mutableStateOf<String?>(null) }
    var recording by remember { mutableStateOf(false) }
    val snackbar = remember { SnackbarHostState() }
    val busy by vm.busy.collectAsState()
    val progress by vm.progress.collectAsState()

    LaunchedEffect(Unit) { vm.messages.collect { snackbar.showSnackbar(it) } }
    LaunchedEffect(Unit) { vm.openTrim.collect { trimId = it; tab = Tab.Tracks } }

    BackHandler(enabled = trimId != null || playerId != null || tab != Tab.Camera) {
        when {
            playerId != null -> playerId = null
            trimId != null -> trimId = null
            else -> tab = Tab.Camera
        }
    }

    Box(Modifier.fillMaxSize().background(VM.Night)) {
        Column(Modifier.fillMaxSize()) {
            Box(Modifier.weight(1f)) {
                val tid = trimId
                when {
                    tid != null -> TrimScreen(vm, tid, onBack = { trimId = null }, onShoot = { trimId = null; tab = Tab.Camera })
                    tab == Tab.Camera -> CameraScreen(
                        vm,
                        onOpenLibrary = { tab = Tab.Library },
                        onOpenTracks = { tab = Tab.Tracks },
                        onRecordingChange = { recording = it },
                    )
                    tab == Tab.Tracks -> TracksScreen(vm, onOpen = { trimId = it })
                    tab == Tab.Library -> LibraryScreen(vm, onOpen = { playerId = it })
                    tab == Tab.Settings -> SettingsScreen(vm)
                }
            }
            if (!recording && trimId == null) BottomBar(tab) { tab = it }
        }

        playerId?.let { id ->
            PlayerScreen(
                vm, id,
                onClose = { playerId = null },
                onRetake = { take ->
                    if (take.trackId != null && take.clipId != null) vm.setActiveClip(take.trackId to take.clipId)
                    playerId = null; tab = Tab.Camera
                },
            )
        }

        busy?.let { text ->
            Box(
                Modifier.fillMaxSize().background(Color(0xB305070F)).clickable(enabled = true) {},
                contentAlignment = Alignment.Center,
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    val p = progress
                    if (p == null) CircularProgressIndicator(color = VM.Moon)
                    else LinearProgressIndicator(progress = { p }, color = VM.Moon, trackColor = VM.S3, modifier = Modifier.width(200.dp))
                    Spacer(Modifier.height(12.dp))
                    Text(text + (p?.let { "  ${(it * 100).toInt()}%" } ?: ""), color = VM.Mu)
                }
            }
        }

        SnackbarHost(snackbar, Modifier.align(Alignment.TopCenter).statusBarsPadding().padding(top = 56.dp))
    }
}

@Composable
private fun BottomBar(tab: Tab, onSelect: (Tab) -> Unit) {
    Row(
        Modifier
            .fillMaxWidth()
            .background(VM.Night)
            .navigationBarsPadding()
            .height(62.dp),
        horizontalArrangement = Arrangement.SpaceEvenly,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Tab.entries.forEach { t ->
            val on = t == tab
            Column(
                Modifier.weight(1f).clip(RoundedCornerShape(12.dp)).clickable { onSelect(t) }.padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(t.icon, t.label, tint = if (on) VM.Moon else VM.Dim, modifier = Modifier.size(24.dp))
                Text(t.label, color = if (on) VM.Moon else VM.Dim, fontSize = 11.sp)
            }
        }
    }
}
