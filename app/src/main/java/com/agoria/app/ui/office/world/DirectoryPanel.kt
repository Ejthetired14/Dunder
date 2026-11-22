package com.agoria.app.ui.office.world

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
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

/** Every page of the app, and the room in the office where it lives. */
private val PAGES = listOf(
    Triple("HOME", "home", "Notice board"),
    Triple("DIARY", "diary", "Library"),
    Triple("PROJECT", "project", "War room, workshop"),
    Triple("APPROVALS", "approvals", "Your desk"),
    Triple("CONNECT", "connectors", "Server room, mail"),
    Triple("BROWSERS", "browsers", "Web room"),
    Triple("SETTINGS", "settings", "Finance"),
)

/** The office directory (the notice board). One tap to any page. */
@Composable
fun DirectoryPanel(
    onGo: (String) -> Unit,
    onListView: () -> Unit,
    onResetView: () -> Unit,
    soundOn: Boolean,
    onSound: (Boolean) -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    PixelPanel(modifier = modifier.fillMaxWidth()) {
        Column(
            modifier = Modifier
                .heightIn(max = 520.dp)
                .verticalScroll(rememberScrollState())
                .padding(12.dp),
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("OFFICE DIRECTORY", style = PixelLabel)
                    Text("Every page lives in a room. Tap one to go.", style = PixelBody.copy(fontSize = 12.sp))
                }
                PixelIconButton(Icons.Default.Close, "Close", onClose)
            }
            PAGES.forEach { (label, route, where) ->
                Spacer(Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    PixelButton(text = label, onClick = { onGo(route) }, modifier = Modifier.width(124.dp))
                    Spacer(Modifier.width(10.dp))
                    Text(where, style = PixelBody.copy(fontSize = 12.sp), modifier = Modifier.weight(1f))
                }
            }
            Spacer(Modifier.height(14.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                PixelButton(text = "List view", onClick = onListView, fill = Pal.cream, modifier = Modifier.weight(1f))
                PixelButton(text = "Reset view", onClick = onResetView, fill = Pal.cream, modifier = Modifier.weight(1f))
            }
            Spacer(Modifier.height(8.dp))
            PixelButton(
                text = if (soundOn) "Sound: on" else "Sound: off",
                onClick = { onSound(!soundOn) },
                fill = if (soundOn) Pal.amber else Pal.cream,
                modifier = Modifier.fillMaxWidth(),
            )
        }
    }
}
