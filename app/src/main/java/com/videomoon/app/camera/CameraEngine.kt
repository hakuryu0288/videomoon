package com.videomoon.app.camera

import android.annotation.SuppressLint
import android.content.Context
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.TotalCaptureResult
import android.os.SystemClock
import android.util.Range
import androidx.camera.camera2.interop.Camera2CameraControl
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.CaptureRequestOptions
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.AspectRatio
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.DynamicRange
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.FallbackStrategy
import androidx.camera.video.FileOutputOptions
import androidx.camera.video.Quality
import androidx.camera.video.QualitySelector
import androidx.camera.video.Recorder
import androidx.camera.video.Recording
import androidx.camera.video.VideoCapture
import androidx.camera.video.VideoRecordEvent
import androidx.camera.view.PreviewView
import androidx.core.content.ContextCompat
import androidx.lifecycle.LifecycleOwner
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * CameraX + Camera2 のラッパー。
 *  - 動画: Preview + VideoCapture(Recorder)。音声トラックは作らない（withAudioEnabledを呼ばない）
 *  - 写真: Preview + ImageCapture
 *  - フレームレート固定（60/30/24）、ISO・シャッター・WB・フォーカスのマニュアル制御
 */
@OptIn(ExperimentalCamera2Interop::class)
class CameraEngine(private val context: Context) {

    data class Caps(
        val ready: Boolean = false,
        val manualSensor: Boolean = false,
        val isoRange: Range<Int>? = null,
        val exposureRange: Range<Long>? = null,
        val minFocusDistance: Float = 0f,
        val evRange: Range<Int> = Range(0, 0),
        val evStep: Float = 0f,
        val actualFps: Int = 30,
        val hasFlash: Boolean = false,
        val minZoom: Float = 1f,
        val maxZoom: Float = 1f,
        val note: String? = null,
    )

    data class Live(val iso: Int = 0, val exposureNs: Long = 0, val measuredFps: Float = 0f)

    data class Config(
        val front: Boolean,
        val photo: Boolean,
        val fps: Int,
        val quality: String,
        val stabilization: Boolean,
        val mirrorSave: Boolean,
        val rotation: Int,
    )

    val caps = MutableStateFlow(Caps())
    val live = MutableStateFlow(Live())

    private var provider: ProcessCameraProvider? = null
    var camera: Camera? = null
        private set
    private var videoCapture: VideoCapture<Recorder>? = null
    private var imageCapture: ImageCapture? = null

    private var frames = 0
    private var windowStart = 0L
    private var lastPush = 0L

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val now = SystemClock.elapsedRealtime()
            frames++
            if (windowStart == 0L) windowStart = now
            if (now - windowStart >= 1000) {
                val f = frames * 1000f / (now - windowStart)
                frames = 0; windowStart = now
                live.value = live.value.copy(measuredFps = f)
            }
            if (now - lastPush > 200) {
                lastPush = now
                live.value = live.value.copy(
                    iso = result.get(CaptureResult.SENSOR_SENSITIVITY) ?: 0,
                    exposureNs = result.get(CaptureResult.SENSOR_EXPOSURE_TIME) ?: 0L,
                )
            }
        }
    }

    private suspend fun awaitProvider(): ProcessCameraProvider = provider ?: suspendCancellableCoroutine { cont ->
        val f = ProcessCameraProvider.getInstance(context)
        f.addListener({
            try { cont.resume(f.get()) } catch (e: Exception) { cont.resumeWithException(e) }
        }, ContextCompat.getMainExecutor(context))
    }.also { provider = it }

    private fun pickRange(ranges: List<Range<Int>>, fps: Int): Range<Int> {
        ranges.firstOrNull { it.lower == fps && it.upper == fps }?.let { return it }
        ranges.filter { it.upper == fps }.maxByOrNull { it.lower }?.let { return it }
        return ranges.filter { it.upper < fps }.maxWithOrNull(compareBy({ it.upper }, { it.lower })) ?: Range(30, 30)
    }

    suspend fun bind(owner: LifecycleOwner, previewView: PreviewView, cfg: Config) {
        val p = awaitProvider()
        p.unbindAll()
        val selector = if (cfg.front) CameraSelector.DEFAULT_FRONT_CAMERA else CameraSelector.DEFAULT_BACK_CAMERA
        val info = selector.filter(p.availableCameraInfos).firstOrNull()
        if (info == null) { caps.value = Caps(note = "カメラが見つかりません"); return }

        val ranges = info.supportedFrameRateRanges.toList()
        val range = pickRange(ranges, cfg.fps)
        var actualFps = range.upper
        var note: String? = if (range.upper < cfg.fps) "この端末のこのカメラは${cfg.fps}fpsに対応していないため、${range.upper}fpsで撮影します" else null

        val ratio = ResolutionSelector.Builder().setAspectRatioStrategy(
            if (cfg.photo) AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY else AspectRatioStrategy.RATIO_16_9_FALLBACK_AUTO_STRATEGY
        ).build()

        fun buildPreview(r: Range<Int>?): Preview {
            val b = Preview.Builder().setResolutionSelector(ratio)
            if (r != null) b.setTargetFrameRate(r)
            Camera2Interop.Extender(b).setSessionCaptureCallback(captureCallback)
            return b.build().also { it.setSurfaceProvider(previewView.surfaceProvider) }
        }

        fun buildVideo(r: Range<Int>, quality: Quality): VideoCapture<Recorder> {
            val bitrate = when (quality) { Quality.UHD -> 45_000_000; Quality.HD -> 10_000_000; else -> 20_000_000 } * (if (r.upper >= 50) 3 else 2) / 2
            val recorder = Recorder.Builder()
                .setQualitySelector(QualitySelector.from(quality, FallbackStrategy.lowerQualityOrHigherThan(quality)))
                .setAspectRatio(AspectRatio.RATIO_16_9)
                .setTargetVideoEncodingBitRate(bitrate)
                .build()
            val b = VideoCapture.Builder(recorder)
                .setTargetFrameRate(r)
                .setMirrorMode(if (cfg.mirrorSave) MirrorMode.MIRROR_MODE_ON_FRONT_ONLY else MirrorMode.MIRROR_MODE_OFF)
                .setTargetRotation(cfg.rotation)
            Camera2Interop.Extender(b).setCaptureRequestOption(
                CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE,
                if (cfg.stabilization) CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_ON else CaptureRequest.CONTROL_VIDEO_STABILIZATION_MODE_OFF,
            )
            return b.build()
        }

        val cam: Camera = if (cfg.photo) {
            val ic = ImageCapture.Builder()
                .setResolutionSelector(ratio)
                .setCaptureMode(ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
                .setTargetRotation(cfg.rotation)
                .build()
            imageCapture = ic; videoCapture = null
            p.bindToLifecycle(owner, selector, buildPreview(null), ic)
        } else {
            val supported = Recorder.getVideoCapabilities(info).getSupportedQualities(DynamicRange.SDR)
            val want = when (cfg.quality) { "720" -> Quality.HD; "4K" -> Quality.UHD; else -> Quality.FHD }
            if (want !in supported) note = (note?.plus("\n") ?: "") + "選んだ解像度に非対応のため、近い解像度で撮影します"
            try {
                val vc = buildVideo(range, want)
                videoCapture = vc; imageCapture = null
                p.bindToLifecycle(owner, selector, buildPreview(range), vc)
            } catch (e: Exception) {
                // 高解像度×高fpsの組み合わせに非対応 → 1080p/30fpsで再挑戦
                p.unbindAll()
                val r30 = pickRange(ranges, 30)
                note = "この端末では選んだ解像度とフレームレートを同時に使えないため、1080p・${r30.upper}fpsで撮影します"
                val vc = buildVideo(r30, Quality.FHD)
                videoCapture = vc
                actualFps = r30.upper
                p.bindToLifecycle(owner, selector, buildPreview(r30), vc)
            }
        }
        camera = cam

        val c2 = Camera2CameraInfo.from(cam.cameraInfo)
        val capArr = c2.getCameraCharacteristic(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: IntArray(0)
        val es = cam.cameraInfo.exposureState
        val zs = cam.cameraInfo.zoomState.value
        caps.value = Caps(
            ready = true,
            manualSensor = capArr.contains(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR),
            isoRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE),
            exposureRange = c2.getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE),
            minFocusDistance = c2.getCameraCharacteristic(CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE) ?: 0f,
            evRange = es.exposureCompensationRange,
            evStep = es.exposureCompensationStep.toFloat(),
            actualFps = if (cfg.photo) 30 else actualFps,
            hasFlash = cam.cameraInfo.hasFlashUnit(),
            minZoom = zs?.minZoomRatio ?: 1f,
            maxZoom = zs?.maxZoomRatio ?: 1f,
            note = note,
        )
    }

    fun unbind() { provider?.unbindAll(); camera = null }

    /** ISO・シャッター・WB・フォーカス・フリッカー対策をカメラに反映 */
    fun applyPro(p: ProState, fps: Int, flickerHz: Int) {
        val cam = camera ?: return
        val c = caps.value
        val b = CaptureRequestOptions.Builder()
            .setCaptureRequestOption(
                CaptureRequest.CONTROL_AE_ANTIBANDING_MODE,
                if (flickerHz == 50) CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_50HZ else CaptureRequest.CONTROL_AE_ANTIBANDING_MODE_60HZ,
            )
            .setCaptureRequestOption(CaptureRequest.CONTROL_AWB_MODE, p.awbMode)
        if (!p.ae && c.manualSensor) {
            val iso = c.isoRange?.let { p.iso.coerceIn(it.lower, it.upper) } ?: p.iso
            val expNs = (1_000_000_000L / p.shutterDen).let { ns -> c.exposureRange?.let { ns.coerceIn(it.lower, it.upper) } ?: ns }
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.SENSOR_SENSITIVITY, iso)
                .setCaptureRequestOption(CaptureRequest.SENSOR_EXPOSURE_TIME, expNs)
                .setCaptureRequestOption(CaptureRequest.SENSOR_FRAME_DURATION, 1_000_000_000L / fps)
        }
        if (!p.af && c.minFocusDistance > 0f) {
            b.setCaptureRequestOption(CaptureRequest.CONTROL_AF_MODE, CaptureRequest.CONTROL_AF_MODE_OFF)
                .setCaptureRequestOption(CaptureRequest.LENS_FOCUS_DISTANCE, c.minFocusDistance * (1f - p.focus))
        }
        Camera2CameraControl.from(cam.cameraControl).setCaptureRequestOptions(b.build())
        if (p.ae) cam.cameraControl.setExposureCompensationIndex(p.evIndex.coerceIn(c.evRange.lower, c.evRange.upper))
        if (c.hasFlash) cam.cameraControl.enableTorch(p.torch)
        cam.cameraControl.setZoomRatio(p.zoom.coerceIn(c.minZoom, c.maxZoom))
    }

    fun tapToFocus(previewView: PreviewView, x: Float, y: Float) {
        val cam = camera ?: return
        val pt = previewView.meteringPointFactory.createPoint(x, y)
        cam.cameraControl.startFocusAndMetering(FocusMeteringAction.Builder(pt).build())
    }

    fun setRotation(rotation: Int) {
        videoCapture?.targetRotation = rotation
        imageCapture?.targetRotation = rotation
    }

    /** 無音の動画を録画。音声は後から曲を重ねる */
    @SuppressLint("MissingPermission")
    fun startRecording(file: File, onEvent: (VideoRecordEvent) -> Unit): Recording? {
        val vc = videoCapture ?: return null
        return vc.output
            .prepareRecording(context, FileOutputOptions.Builder(file).build())
            .start(ContextCompat.getMainExecutor(context)) { onEvent(it) }
    }

    suspend fun takePhoto(file: File): File = suspendCancellableCoroutine { cont ->
        val ic = imageCapture ?: run { cont.resumeWithException(IllegalStateException("写真モードではありません")); return@suspendCancellableCoroutine }
        ic.takePicture(
            ImageCapture.OutputFileOptions.Builder(file).build(),
            ContextCompat.getMainExecutor(context),
            object : ImageCapture.OnImageSavedCallback {
                override fun onImageSaved(outputFileResults: ImageCapture.OutputFileResults) { cont.resume(file) }
                override fun onError(exception: ImageCaptureException) { cont.resumeWithException(exception) }
            },
        )
    }
}
