package com.videomoon.app.ui

import android.graphics.Color
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.common.Player
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import com.videomoon.app.data.Take
import com.videomoon.app.media.buildTakeSource

/** テイクを「映像＋その時流れていた音楽」で再生するプレーヤー */
@Composable
fun rememberTakePlayer(vm: MainViewModel, take: Take, autoPlay: Boolean = true, muted: Boolean = false): ExoPlayer {
    val context = LocalContext.current
    val settings by vm.settings.collectAsState()
    val player = remember(take.id) {
        ExoPlayer.Builder(context).build().apply { repeatMode = Player.REPEAT_MODE_ONE }
    }
    LaunchedEffect(take.id, settings.syncOffsetMs) {
        val track = vm.trackOf(take)
        player.setMediaSource(
            buildTakeSource(context, vm.store.takeFile(take), track?.let { vm.trackFile(it) }, take, settings.syncOffsetMs)
        )
        player.prepare()
        player.playWhenReady = autoPlay
    }
    LaunchedEffect(muted) { player.volume = if (muted) 0f else 1f }
    DisposableEffect(take.id) { onDispose { player.release() } }
    return player
}

@Composable
fun PlayerSurface(player: Player, modifier: Modifier = Modifier, controls: Boolean = true) {
    AndroidView(
        factory = { ctx ->
            PlayerView(ctx).apply {
                this.player = player
                useController = controls
                setShutterBackgroundColor(Color.BLACK)
                setBackgroundColor(Color.BLACK)
            }
        },
        update = { it.player = player; it.useController = controls },
        modifier = modifier,
    )
}
