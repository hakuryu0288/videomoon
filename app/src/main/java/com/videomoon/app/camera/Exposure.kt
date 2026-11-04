package com.videomoon.app.camera

import android.hardware.camera2.CaptureRequest
import kotlin.math.abs
import kotlin.math.roundToInt

/** プロ設定（Blackmagic Camera風のマニュアル操作） */
data class ProState(
    val ae: Boolean = true,              // ISO・シャッターをオートにするか
    val iso: Int = 800,
    val shutterDen: Int = 120,           // 1/120 秒
    val awbMode: Int = CaptureRequest.CONTROL_AWB_MODE_AUTO,
    val evIndex: Int = 0,                // オート時の露出補正（端末のステップ単位）
    val af: Boolean = true,
    val focus: Float = 0.7f,             // 0=最短 … 1=無限遠
    val torch: Boolean = false,
    val zoom: Float = 1f,
)

object Exposure {
    val ISO_STOPS = listOf(50, 64, 80, 100, 125, 160, 200, 250, 320, 400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400)
    val SHUTTER_STOPS = listOf(24, 25, 30, 40, 48, 50, 60, 100, 120, 125, 180, 200, 240, 250, 320, 400, 500, 640, 800, 1000, 1600, 2000, 4000)

    data class Wb(val mode: Int, val label: String, val kelvin: String)
    val WB = listOf(
        Wb(CaptureRequest.CONTROL_AWB_MODE_AUTO, "オート", "AWB"),
        Wb(CaptureRequest.CONTROL_AWB_MODE_INCANDESCENT, "電球", "2850K"),
        Wb(CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT, "蛍光灯", "4000K"),
        Wb(CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT, "晴天", "5500K"),
        Wb(CaptureRequest.CONTROL_AWB_MODE_CLOUDY_DAYLIGHT, "くもり", "6500K"),
        Wb(CaptureRequest.CONTROL_AWB_MODE_SHADE, "日陰", "7500K"),
    )

    fun wbLabel(mode: Int) = WB.firstOrNull { it.mode == mode }?.kelvin ?: "AWB"

    /** フレーム時間より長いシャッターは選べない */
    fun shutterStops(fps: Int, minNs: Long?, maxNs: Long?): List<Int> = SHUTTER_STOPS.filter { d ->
        val ns = 1_000_000_000L / d
        d >= fps && (minNs == null || ns >= minNs) && (maxNs == null || ns <= maxNs)
    }

    /** 照明の明滅周期と合うシャッター（1/100, 1/50 … or 1/120, 1/60 …）か */
    fun flickerSafe(den: Int, hz: Int): Boolean {
        val r = (2.0 * hz) / den
        return abs(r - r.roundToInt()) < 1e-6 && r.roundToInt() >= 1
    }

    /** フリッカーが出ず、なるべく180°（=1/(fps×2)）に近いシャッター */
    fun safeShutter(fps: Int, hz: Int): Int {
        val cands = (1..4).map { 2 * hz / it }.filter { it >= fps && (2 * hz) % it == 0 }
        val target = fps * 2
        return cands.minByOrNull { abs(it - target) } ?: fps
    }

    fun shutterAngle(fps: Int, den: Int) = (360.0 * fps / den).roundToInt()

    data class Preset(val name: String, val apply: (ProState, Int, Int) -> ProState)
    val PRESETS = listOf(
        Preset("オート") { p, _, _ -> p.copy(ae = true, af = true, awbMode = CaptureRequest.CONTROL_AWB_MODE_AUTO, evIndex = 0) },
        Preset("暗いステージ") { p, fps, hz -> p.copy(ae = false, iso = 1600, shutterDen = safeShutter(fps, hz), awbMode = CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT) },
        Preset("ペンライト") { p, fps, hz -> p.copy(ae = false, iso = 800, shutterDen = safeShutter(fps, hz), awbMode = CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT) },
        Preset("屋内照明") { p, fps, hz -> p.copy(ae = false, iso = 640, shutterDen = safeShutter(fps, hz), awbMode = CaptureRequest.CONTROL_AWB_MODE_FLUORESCENT) },
        Preset("屋外") { p, fps, _ -> p.copy(ae = false, iso = 100, shutterDen = shutterStops(fps, null, null).minByOrNull { abs(it - 1000) } ?: 1000, awbMode = CaptureRequest.CONTROL_AWB_MODE_DAYLIGHT) },
    )
}
