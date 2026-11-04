package com.videomoon.app.ui

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.GridItemSpan
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.videomoon.app.data.Take
import com.videomoon.app.data.TakeKind
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@Composable
fun LibraryScreen(vm: MainViewModel, onOpen: (String) -> Unit) {
    val takes by vm.takes.collectAsState()
    var filter by remember { mutableStateOf<TakeKind?>(null) }
    var selecting by remember { mutableStateOf(false) }
    var picked by remember { mutableStateOf(setOf<String>()) }
    val list = takes.filter { filter == null || it.kind == filter }

    LazyVerticalGrid(
        GridCells.Fixed(2),
        Modifier.fillMaxSize().statusBarsPadding().padding(horizontal = 18.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        item(span = { GridItemSpan(2) }) {
            Column {
                Row(Modifier.fillMaxWidth().padding(top = 14.dp, bottom = 12.dp), verticalAlignment = Alignment.CenterVertically) {
                    Text("ライブラリ", color = VM.Tx, fontSize = 26.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
                    Chip(if (selecting) "完了" else "選択") { selecting = !selecting; picked = emptySet() }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Seg(listOf(null to "すべて", TakeKind.VIDEO to "動画", TakeKind.PHOTO to "写真"), filter, { filter = it })
                    Spacer(Modifier.weight(1f))
                    if (list.isNotEmpty()) Text("${list.size} 件", color = VM.Mu, fontSize = 12.sp)
                }
                if (selecting) {
                    Row(Modifier.padding(top = 10.dp), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        MoonButton("まとめて書き出す（${picked.size}）", Modifier.weight(1f), primary = false) {
                            vm.export(takes.filter { it.id in picked }); selecting = false; picked = emptySet()
                        }
                        MoonButton("削除", primary = false, color = VM.Rec) {
                            vm.deleteTakes(picked); selecting = false; picked = emptySet()
                        }
                    }
                }
                if (list.isEmpty()) {
                    Text(
                        "撮影したテイクはここに並びます。\nアプリ内に好きなだけ保存しておけます。",
                        color = VM.Mu, fontSize = 13.sp, modifier = Modifier.fillMaxWidth().padding(vertical = 40.dp),
                    )
                }
                Spacer(Modifier.size(4.dp))
            }
        }
        items(list, key = { it.id }) { t ->
            TakeCell(vm, t, selecting, t.id in picked) {
                if (selecting) {
                    picked = if (t.id in picked) picked - t.id else picked + t.id
                } else {
                    onOpen(t.id)
                }
            }
        }
        item(span = { GridItemSpan(2) }) { Spacer(Modifier.size(24.dp)) }
    }
}

@Composable
private fun TakeCell(vm: MainViewModel, t: Take, selecting: Boolean, picked: Boolean, onClick: () -> Unit) {
    var bmp by remember(t.id) { mutableStateOf<ImageBitmap?>(null) }
    LaunchedEffect(t.id) {
        bmp = vm.store.thumbFile(t)?.takeIf { it.exists() }?.let { f ->
            withContext(Dispatchers.IO) { BitmapFactory.decodeFile(f.absolutePath)?.asImageBitmap() }
        }
    }
    Box(Modifier.fillMaxWidth().aspectRatio(9f / 14f).clip(RoundedCornerShape(12.dp)).background(VM.S1).clickable(onClick = onClick)) {
        bmp?.let { Image(it, null, Modifier.fillMaxSize(), contentScale = ContentScale.Crop) }
        Text(
            if (t.kind == TakeKind.PHOTO) "写真" else "${t.fps}p",
            color = if (t.kind == TakeKind.PHOTO) VM.Moon else VM.Tx, fontSize = 11.5.sp, style = NumStyle,
            modifier = Modifier.padding(7.dp).clip(RoundedCornerShape(6.dp)).background(Color(0xB305070F)).padding(horizontal = 6.dp, vertical = 1.dp),
        )
        if (selecting) {
            Box(
                Modifier.align(Alignment.TopEnd).padding(7.dp).size(22.dp).clip(CircleShape)
                    .background(if (picked) VM.Moon else Color.Transparent).border(2.dp, if (picked) VM.Moon else Color.White, CircleShape)
            )
        }
        Column(
            Modifier.align(Alignment.BottomStart).fillMaxWidth()
                .background(Brush.verticalGradient(listOf(Color.Transparent, Color(0xE605070F))))
                .padding(start = 9.dp, end = 9.dp, top = 22.dp, bottom = 8.dp)
        ) {
            Text(t.title, color = VM.Tx, fontSize = 12.5.sp, fontWeight = FontWeight.Medium, maxLines = 1, overflow = TextOverflow.Ellipsis)
            val d = SimpleDateFormat("M/d H:mm", Locale.JAPAN).format(Date(t.createdAt))
            Text(
                (if (t.kind == TakeKind.VIDEO) "%.1f秒 ・ ".format(t.durationMs / 1000f) else "") + d,
                color = VM.Mu, fontSize = 12.sp, style = NumStyle.copy(fontWeight = FontWeight.Normal),
            )
        }
    }
}
