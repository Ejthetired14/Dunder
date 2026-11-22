package com.agoria.app.ui.office.world

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agoria.app.util.Time

/** The office's recent talk, newest first. Tap a line to jump to whoever said it. */
@Composable
fun ChatterPanel(
    lines: List<ChatLine>,
    onPick: (String) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PixelPanel(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .heightIn(max = 440.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("OFFICE CHATTER", style = PixelLabel)
                    Text(
                        text = if (lines.isEmpty()) "Nobody has said anything to each other yet." else "What agents say to each other.",
                        style = PixelBody.copy(fontSize = 12.sp),
                    )
                }
                PixelIconButton(Icons.Default.Close, "Close", onClose)
            }
            lines.take(14).forEach { line ->
                Spacer(Modifier.height(8.dp))
                Column(
                    Modifier
                        .fillMaxWidth()
                        .background(Color(0x14000000))
                        .border(2.dp, Pal.ink)
                        .clickable { onPick(line.speakerId) }
                        .padding(10.dp),
                ) {
                    Text(
                        text = "${line.speakerName.uppercase()} → ${line.listenerName.uppercase()}  ·  ${Time.relativeTime(line.at)}",
                        style = PixelLabel.copy(fontSize = 11.sp),
                    )
                    Text(
                        text = line.text,
                        style = PixelBody.copy(fontSize = 13.sp, lineHeight = 17.sp),
                        modifier = Modifier.padding(top = 2.dp),
                    )
                }
            }
        }
    }
}
