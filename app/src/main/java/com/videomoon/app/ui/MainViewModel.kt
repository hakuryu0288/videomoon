package com.videomoon.app.ui

import android.app.Application
import android.net.Uri
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.videomoon.app.camera.RecordController
import com.videomoon.app.data.AppSettings
import com.videomoon.app.data.Clip
import com.videomoon.app.data.LibraryStore
import com.videomoon.app.data.Take
import com.videomoon.app.data.TakeKind
import com.videomoon.app.data.Track
import com.videomoon.app.data.newId
import com.videomoon.app.media.Exporter
import com.videomoon.app.media.MediaTools
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainViewModel(app: Application) : AndroidViewModel(app) {
    val store = LibraryStore(app)

    private val _tracks = MutableStateFlow<List<Track>>(emptyList())
    val tracks = _tracks.asStateFlow()
    private val _takes = MutableStateFlow<List<Take>>(emptyList())
    val takes = _takes.asStateFlow()
    private val _settings = MutableStateFlow(store.loadSettings())
    val settings = _settings.asStateFlow()
    private val _active = MutableStateFlow(store.loadActiveClip())
    val activeClip = _active.asStateFlow()

    /** 画面上部に出す短いお知らせ */
    val messages = MutableSharedFlow<String>(extraBufferCapacity = 8)
    /** 処理中表示（null=非表示）と進捗 */
    val busy = MutableStateFlow<String?>(null)
    val progress = MutableStateFlow<Float?>(null)
    /** 取り込み完了後に開くトリム画面 */
    val openTrim = MutableSharedFlow<String>(extraBufferCapacity = 1)

    private val peakCache = HashMap<String, FloatArray>()

    init {
        val (t, k) = store.load()
        _tracks.value = t.sortedBy { it.createdAt }
        _takes.value = k.sortedByDescending { it.createdAt }
    }

    private fun persist() = viewModelScope.launch(Dispatchers.IO) { store.save(_tracks.value, _takes.value) }
    private fun say(s: String) { messages.tryEmit(s) }

    fun updateSettings(f: (AppSettings) -> AppSettings) {
        _settings.value = f(_settings.value); store.saveSettings(_settings.value)
    }

    // ---------- 音源 ----------
    fun importMedia(uri: Uri, openAfter: Boolean = true) = viewModelScope.launch {
        busy.value = "音源を読み込み中…"
        try {
            val track = withContext(Dispatchers.IO) {
                val id = newId()
                val (name, file) = MediaTools.copyIntoApp(getApplication(), uri, store.tracksDir, id)
                if (!MediaTools.hasAudioTrack(file)) { file.delete(); error("音声が入っていないファイルです") }
                val dur = MediaTools.durationMs(file)
                val peaks = MediaTools.decodePeaks(file)
                store.savePeaks(id, peaks); peakCache[id] = peaks
                Track(id, name, file.name, dur, System.currentTimeMillis())
            }
            _tracks.value = _tracks.value + track
            persist()
            if (openAfter) openTrim.tryEmit(track.id)
        } catch (e: Exception) {
            say(e.message ?: "読み込めませんでした")
        } finally {
            busy.value = null
        }
    }

    fun deleteTrack(id: String) {
        val t = _tracks.value.firstOrNull { it.id == id } ?: return
        store.trackFile(t).delete(); store.deletePeaks(id); peakCache.remove(id)
        _tracks.value = _tracks.value.filterNot { it.id == id }
        if (_active.value?.first == id) setActiveClip(null)
        persist()
    }

    fun renameTrack(id: String, name: String) {
        _tracks.value = _tracks.value.map { if (it.id == id) it.copy(name = name) else it }; persist()
    }

    suspend fun peaks(track: Track): FloatArray = peakCache[track.id] ?: withContext(Dispatchers.IO) {
        store.loadPeaks(track.id) ?: MediaTools.decodePeaks(store.trackFile(track)).also { store.savePeaks(track.id, it) }
    }.also { peakCache[track.id] = it }

    fun trackFile(t: Track): File = store.trackFile(t)

    /** 同じidなら上書き、なければ追加 */
    fun saveClip(trackId: String, clip: Clip): Clip {
        val c = if (clip.id.isEmpty()) clip.copy(id = newId()) else clip
        _tracks.value = _tracks.value.map { t ->
            if (t.id != trackId) t
            else if (t.clips.any { it.id == c.id }) t.copy(clips = t.clips.map { if (it.id == c.id) c else it })
            else t.copy(clips = t.clips + c)
        }
        persist(); return c
    }

    fun deleteClip(trackId: String, clipId: String) {
        _tracks.value = _tracks.value.map { t -> if (t.id == trackId) t.copy(clips = t.clips.filterNot { it.id == clipId }) else t }
        if (_active.value?.second == clipId) setActiveClip(null)
        persist()
    }

    fun setActiveClip(v: Pair<String, String>?) { _active.value = v; store.saveActiveClip(v) }

    fun resolveActive(): Pair<Track, Clip>? {
        val (tid, cid) = _active.value ?: return null
        val t = _tracks.value.firstOrNull { it.id == tid } ?: return null
        val c = t.clips.firstOrNull { it.id == cid } ?: return null
        return t to c
    }

    // ---------- テイク ----------
    fun newVideoFile(): File = File(store.takesDir, "${newId()}.mp4")
    fun newPhotoFile(): File = File(store.takesDir, "${newId()}.jpg")

    suspend fun addVideoTake(res: RecordController.Result, active: Pair<Track, Clip>?): Take {
        val s = _settings.value
        val n = _takes.value.count { it.kind == TakeKind.VIDEO && active != null && it.clipId == active.second.id } + 1
        val id = res.file.nameWithoutExtension
        val thumb = withContext(Dispatchers.IO) {
            val f = File(store.thumbsDir, "$id.jpg"); if (MediaTools.makeVideoThumb(res.file, f)) f.name else null
        }
        val take = Take(
            id = id, kind = TakeKind.VIDEO, fileName = res.file.name, thumbName = thumb,
            createdAt = System.currentTimeMillis(),
            title = active?.let { "${it.second.name} ・ テイク $n" } ?: "動画",
            durationMs = res.durationMs, fps = s.fps, quality = s.quality,
            trackId = active?.first?.id, clipId = active?.second?.id,
            audioStartMs = res.audioStartMs, audioLimitMs = active?.second?.endMs ?: 0,
            fadeInMs = active?.second?.fadeInMs ?: 0, fadeOutMs = active?.second?.fadeOutMs ?: 0,
        )
        _takes.value = listOf(take) + _takes.value
        persist(); return take
    }

    suspend fun addPhotoTake(file: File): Take {
        val id = file.nameWithoutExtension
        val thumb = withContext(Dispatchers.IO) {
            val f = File(store.thumbsDir, "$id.jpg"); if (MediaTools.makePhotoThumb(file, f)) f.name else null
        }
        val take = Take(id, TakeKind.PHOTO, file.name, thumb, System.currentTimeMillis(), "写真")
        _takes.value = listOf(take) + _takes.value
        persist(); return take
    }

    fun deleteTakes(ids: Collection<String>) {
        val del = _takes.value.filter { it.id in ids }
        del.forEach { store.takeFile(it).delete(); store.thumbFile(it)?.delete() }
        _takes.value = _takes.value.filterNot { it.id in ids }
        persist()
    }

    fun trackOf(take: Take): Track? = _tracks.value.firstOrNull { it.id == take.trackId }

    fun export(list: List<Take>) = viewModelScope.launch {
        var ok = 0
        for ((i, t) in list.withIndex()) {
            busy.value = if (list.size > 1) "書き出し中 (${i + 1}/${list.size})" else "書き出し中…"
            progress.value = 0f
            try {
                if (t.kind == TakeKind.PHOTO) Exporter.exportPhoto(getApplication(), t, store.takeFile(t))
                else Exporter.exportVideo(
                    getApplication(), t, store.takeFile(t), trackOf(t)?.let { store.trackFile(it) },
                    _settings.value.syncOffsetMs, _settings.value.hevc,
                ) { progress.value = it }
                ok++
            } catch (e: Exception) {
                say("書き出しに失敗しました：${e.message}")
            }
        }
        busy.value = null; progress.value = null
        if (ok > 0) say(if (list.size > 1) "${ok}件をギャラリー（videomoonフォルダ）に保存しました" else "ギャラリー（videomoonフォルダ）に保存しました")
    }
}
