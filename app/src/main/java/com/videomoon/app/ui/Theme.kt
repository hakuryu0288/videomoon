package com.videomoon.app.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

object VM {
    val Night = Color(0xFF0B0B10)
    val S1 = Color(0xFF14141C)
    val S2 = Color(0xFF1C1C26)
    val S3 = Color(0xFF262634)
    val Line = Color(0xFF2A2A38)
    val Tx = Color(0xFFF2F0EB)
    val Mu = Color(0xFF8B8A96)
    val Dim = Color(0xFF5D5C6A)
    val Moon = Color(0xFFFF8A00)  // 乙打ちドットコムのアクセント（UO）
    val Ink = Color(0xFF1A0E00)
    val Rec = Color(0xFFFF5A4E)
    val Ok = Color(0xFF8FD3B6)
    val Warn = Color(0xFFFFC46B)
}

/** 数字は等幅（タイムコードや露出値が揺れないように） */
val NumStyle = TextStyle(fontFeatureSettings = "tnum", fontWeight = FontWeight.SemiBold)

@Composable
fun VideomoonTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            primary = VM.Moon, onPrimary = VM.Ink, background = VM.Night, onBackground = VM.Tx,
            surface = VM.S1, onSurface = VM.Tx, surfaceVariant = VM.S2, onSurfaceVariant = VM.Mu,
            error = VM.Rec, outline = VM.Line,
        ),
        content = content,
    )
}

fun fmtTime(ms: Long, dec: Boolean = true): String {
    val t = ms.coerceAtLeast(0)
    val m = t / 60000
    val s = (t % 60000) / 1000.0
    return if (dec) "%d:%04.1f".format(m, s) else "%d:%02d".format(m, (t % 60000) / 1000)
}

/** セグメント切り替え（例：24 / 30 / 60） */
@Composable
fun <T> Seg(options: List<Pair<T, String>>, selected: T, onSelect: (T) -> Unit, modifier: Modifier = Modifier) {
    Row(
        modifier
            .clip(RoundedCornerShape(10.dp))
            .background(VM.S1)
            .border(1.dp, VM.Line, RoundedCornerShape(10.dp))
            .padding(2.dp)
    ) {
        options.forEach { (v, label) ->
            val on = v == selected
            Box(
                Modifier
                    .clip(RoundedCornerShape(8.dp))
                    .background(if (on) VM.S3 else Color.Transparent)
                    .clickable { onSelect(v) }
                    .padding(horizontal = 11.dp, vertical = 6.dp),
                contentAlignment = Alignment.Center,
            ) {
                Text(label, color = if (on) VM.Moon else VM.Mu, fontSize = 13.sp, style = NumStyle.copy(fontWeight = if (on) FontWeight.SemiBold else FontWeight.Normal))
            }
        }
    }
}

@Composable
fun Toggle(on: Boolean, onChange: (Boolean) -> Unit) =
    Seg(listOf(false to "オフ", true to "オン"), on, onChange)

@Composable
fun SettingRow(title: String, desc: String? = null, control: @Composable () -> Unit) {
    Row(
        Modifier.fillMaxWidth().padding(vertical = 11.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        Column(Modifier.weight(1f)) {
            Text(title, color = VM.Tx, fontSize = 14.sp)
            if (desc != null) Text(desc, color = VM.Mu, fontSize = 11.5.sp, lineHeight = 15.sp)
        }
        control()
    }
    Box(Modifier.fillMaxWidth().height(1.dp).background(VM.Line.copy(alpha = .55f)))
}

@Composable
fun Chip(text: String, modifier: Modifier = Modifier, accent: Boolean = false, onClick: () -> Unit) {
    Box(
        modifier
            .height(30.dp)
            .clip(RoundedCornerShape(15.dp))
            .background(VM.S1.copy(alpha = .75f))
            .border(1.dp, if (accent) VM.Moon.copy(alpha = .4f) else VM.Line, RoundedCornerShape(15.dp))
            .clickable(onClick = onClick)
            .padding(horizontal = 11.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = if (accent) VM.Moon else VM.Tx, fontSize = 12.5.sp, style = NumStyle.copy(fontWeight = FontWeight.Medium)) }
}

@Composable
fun MoonButton(text: String, modifier: Modifier = Modifier, primary: Boolean = true, color: Color? = null, onClick: () -> Unit) {
    Box(
        modifier
            .height(50.dp)
            .clip(RoundedCornerShape(14.dp))
            .background(if (primary) VM.Moon else VM.S2)
            .then(if (primary) Modifier else Modifier.border(1.dp, VM.Line, RoundedCornerShape(14.dp)))
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp),
        contentAlignment = Alignment.Center,
    ) { Text(text, color = color ?: if (primary) VM.Ink else VM.Tx, fontWeight = FontWeight.Bold, fontSize = 15.sp) }
}

/** 画面下から出るシート（Material3の実験APIを使わない簡易版） */
@Composable
fun Sheet(visible: Boolean, onDismiss: () -> Unit, content: @Composable ColumnScope.() -> Unit) {
    AnimatedVisibility(visible, enter = fadeIn(), exit = fadeOut()) {
        Box(
            Modifier.fillMaxSize().background(Color(0x99030410))
                .clickable(remember { MutableInteractionSource() }, null, onClick = onDismiss)
        )
    }
    AnimatedVisibility(visible, enter = slideInVertically { it }, exit = slideOutVertically { it }) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.BottomCenter) {
            Column(
                Modifier
                    .fillMaxWidth()
                    .heightIn(max = 640.dp)
                    .clip(RoundedCornerShape(topStart = 22.dp, topEnd = 22.dp))
                    .background(VM.S1)
                    .clickable(remember { MutableInteractionSource() }, null) {}
                    .navigationBarsPadding()
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 18.dp, vertical = 10.dp)
            ) {
                Box(Modifier.align(Alignment.CenterHorizontally).width(38.dp).height(4.dp).clip(RoundedCornerShape(2.dp)).background(VM.Line))
                Spacer(Modifier.size(12.dp))
                content()
                Spacer(Modifier.size(8.dp))
            }
        }
    }
}

@Composable
fun SheetOption(title: String, sub: String? = null, selected: Boolean = false, onClick: () -> Unit) {
    Column(
        Modifier
            .fillMaxWidth()
            .padding(bottom = 6.dp)
            .clip(RoundedCornerShape(12.dp))
            .background(VM.S2)
            .then(if (selected) Modifier.border(1.5.dp, VM.Moon, RoundedCornerShape(12.dp)) else Modifier)
            .clickable(onClick = onClick)
            .padding(12.dp)
    ) {
        Text(title, color = VM.Tx, fontWeight = FontWeight.Medium)
        if (sub != null) Text(sub, color = VM.Mu, fontSize = 12.sp, style = NumStyle.copy(fontWeight = FontWeight.Normal))
    }
}
