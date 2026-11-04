package com.videomoon.app.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.material3.SliderDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.roundToInt

@Composable
fun SettingsScreen(vm: MainViewModel) {
    val s by vm.settings.collectAsState()
    fun set(f: (com.videomoon.app.data.AppSettings) -> com.videomoon.app.data.AppSettings) = vm.updateSettings(f)
    val sliderColors = SliderDefaults.colors(thumbColor = VM.Moon, activeTrackColor = VM.Moon, inactiveTrackColor = VM.S3)

    Column(
        Modifier.fillMaxSize().statusBarsPadding().verticalScroll(rememberScrollState()).padding(horizontal = 18.dp)
    ) {
        Text("設定", color = VM.Tx, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(top = 14.dp, bottom = 6.dp))

        Group("撮影")
        SettingRow("フレームレート", "60fpsでなめらかに。24は映画風") { Seg(listOf(24 to "24", 30 to "30", 60 to "60"), s.fps, { v -> set { it.copy(fps = v) } }) }
        SettingRow("解像度") { Seg(listOf("720" to "720p", "1080" to "1080p", "4K" to "4K"), s.quality, { v -> set { it.copy(quality = v) } }) }
        SettingRow("向き") { Seg(listOf(false to "縦", true to "横"), s.landscape, { v -> set { it.copy(landscape = v) } }) }
        SettingRow("手ブレ補正", "オンにすると画角が少し狭くなります") { Toggle(s.stabilization) { v -> set { it.copy(stabilization = v) } } }
        SettingRow("フリッカー低減", "照明のちらつきを抑えます。東日本50Hz／西日本60Hz") { Seg(listOf(50 to "50Hz", 60 to "60Hz"), s.flickerHz, { v -> set { it.copy(flickerHz = v) } }) }

        Group("撮影補助")
        SettingRow("カウントダウン") { Seg(listOf(0 to "なし", 3 to "3s", 5 to "5s", 10 to "10s"), s.countdown, { v -> set { it.copy(countdown = v) } }) }
        SettingRow("グリッド") { Toggle(s.grid) { v -> set { it.copy(grid = v) } } }
        SettingRow("ヒストグラム", "明るさの分布。右端が赤いと白飛び") { Toggle(s.histogram) { v -> set { it.copy(histogram = v) } } }
        SettingRow("ゼブラ", "白飛びしそうな部分に縞を表示") { Toggle(s.zebra) { v -> set { it.copy(zebra = v) } } }
        SettingRow("ゼブラの基準") { Seg(listOf(90 to "90%", 95 to "95%", 100 to "100%"), s.zebraLevel, { v -> set { it.copy(zebraLevel = v) } }) }
        SettingRow("フォーカスピーキング", "ピントが合った輪郭を緑で表示") { Toggle(s.peaking) { v -> set { it.copy(peaking = v) } } }
        SettingRow("前面カメラを鏡のように表示", "練習中の確認がしやすくなります") { Toggle(s.mirrorPreview) { v -> set { it.copy(mirrorPreview = v) } } }
        SettingRow("前面カメラの映像を反転して保存") { Toggle(s.mirrorSave) { v -> set { it.copy(mirrorSave = v) } } }

        Group("音楽")
        Text("撮影中のスピーカー音量", color = VM.Tx, fontSize = 14.sp, modifier = Modifier.padding(top = 10.dp))
        Text("動画に入る音量は変わりません", color = VM.Mu, fontSize = 11.5.sp)
        Slider(s.monitorVolume, { v -> set { it.copy(monitorVolume = v) } }, colors = sliderColors)
        Text(
            "音ズレ補正  ${if (s.syncOffsetMs > 0) "+" else ""}${s.syncOffsetMs} ms",
            color = VM.Tx, fontSize = 14.sp, modifier = Modifier.padding(top = 6.dp),
        )
        Text("動画と音楽のタイミングがずれる端末向け。＋で音を遅らせます。再生と書き出しの両方に反映されます", color = VM.Mu, fontSize = 11.5.sp)
        Slider(
            s.syncOffsetMs.toFloat(), { v -> set { it.copy(syncOffsetMs = (v / 10).roundToInt() * 10) } },
            valueRange = -300f..300f, colors = sliderColors,
        )

        Group("撮影のあと")
        SettingRow("撮影後すぐに確認", "撮り直すか残すかをその場で選べます") { Toggle(s.autoReview) { v -> set { it.copy(autoReview = v) } } }
        SettingRow("連続撮影", "止めるまで同じクリップで何テイクも続けて撮ります") { Toggle(s.continuous) { v -> set { it.copy(continuous = v) } } }
        SettingRow("書き出し形式", "HEVCはファイルが小さく、古い端末では再生できないことがあります") { Seg(listOf(false to "H.264", true to "HEVC"), s.hevc, { v -> set { it.copy(hevc = v) } }) }

        Text(
            "書き出した動画は「ムービー/videomoon」、写真は「ピクチャ/videomoon」に保存されます。",
            color = VM.Mu, fontSize = 12.sp, modifier = Modifier.padding(top = 18.dp),
        )
        Text("videomoon 0.1.0", color = VM.Dim, fontSize = 12.sp, modifier = Modifier.padding(vertical = 18.dp))
        Spacer(Modifier.height(20.dp))
    }
}

@Composable
private fun Group(title: String) {
    Text(title, color = VM.Mu, fontSize = 12.sp, modifier = Modifier.padding(top = 22.dp))
}
