package com.agoria.app.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.agoria.app.ui.components.OfficeThumbnail
import com.agoria.app.ui.office.world.Pal
import com.agoria.app.ui.office.world.PixelBody
import com.agoria.app.ui.office.world.PixelButton
import com.agoria.app.ui.office.world.PixelLabel
import com.agoria.app.ui.office.world.PixelPanel
import com.agoria.app.ui.office.world.PixelTextField

/**
 * First-run screen. Not a PixelScaffold page: there is no office behind it to go back to.
 * Pixel look (task T-SCREENS): flat navy ground, square ink-bordered thumbnail, PixelPanel feature
 * rows, PixelTextField, PixelButton. No rounded corners, no Material buttons or text fields.
 */
@Composable
fun OnboardingScreen(viewModel: AgoriaViewModel) {
    var name by remember { mutableStateOf("Boss") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(Pal.navy)
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        OfficeThumbnail(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .border(2.dp, Pal.ink),
        )
        Spacer(Modifier.height(28.dp))
        Text("DUNDER", style = PixelLabel.copy(color = Pal.amber, fontSize = 30.sp))
        Spacer(Modifier.height(6.dp))
        Text(
            text = "A small office for autonomous AI agents. They think on their own, keep a diary, and ask you before they build or touch the outside world.",
            style = PixelBody.copy(color = Pal.cream, fontSize = 15.sp, lineHeight = 21.sp),
        )
        Spacer(Modifier.height(24.dp))

        PixelTextField(
            value = name,
            onValueChange = { name = it },
            label = "What should the agents call you?",
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FeatureRow(
                icon = Icons.Default.AutoAwesome,
                title = "Autonomy",
                body = "Agents run on free model APIs. They choose what to think about, what to learn, and what to propose.",
            )
            FeatureRow(
                icon = Icons.Default.EditNote,
                title = "A diary of thoughts",
                body = "Every agent keeps a private diary. You can read it, search it, and watch the office change over time.",
            )
            FeatureRow(
                icon = Icons.Default.FactCheck,
                title = "Approval gates",
                body = "Building a project, sending email, posting to a chat, or making an external HTTP request requires your approval.",
            )
            FeatureRow(
                icon = Icons.Default.Hub,
                title = "Connectors",
                body = "Agents can ask for Gmail, GitHub, Slack, Notion, or a custom HTTP connector. You configure the credentials.",
            )
        }

        Spacer(Modifier.height(28.dp))
        PixelButton(
            text = "Enter the office",
            onClick = { viewModel.finishSetup(name) },
            modifier = Modifier.fillMaxWidth(),
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Works with the free providers in the awesome-free-llm-apis list. Keyless providers are enabled by default; add your own keys in Settings for more reliability.",
            style = PixelBody.copy(color = Pal.tan, fontSize = 12.sp, lineHeight = 16.sp),
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    PixelPanel(modifier = Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.Top,
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = Pal.teal,
                modifier = Modifier
                    .padding(top = 2.dp)
                    .size(22.dp),
            )
            Spacer(Modifier.size(12.dp))
            Column(Modifier.weight(1f)) {
                Text(title.uppercase(), style = PixelLabel)
                Spacer(Modifier.height(2.dp))
                Text(text = body, style = PixelBody.copy(fontSize = 13.sp))
            }
        }
    }
}
