package com.agoria.app.ui

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
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.EditNote
import androidx.compose.material.icons.filled.FactCheck
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Button
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.agoria.app.ui.components.OfficeThumbnail

@Composable
fun OnboardingScreen(viewModel: AgoriaViewModel) {
    var name by remember { mutableStateOf("Boss") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(24.dp),
    ) {
        OfficeThumbnail(
            modifier = Modifier
                .fillMaxWidth()
                .height(210.dp)
                .clip(RoundedCornerShape(28.dp)),
        )
        Spacer(Modifier.height(28.dp))
        Text("Agoria", style = MaterialTheme.typography.displaySmall)
        Spacer(Modifier.height(6.dp))
        Text(
            text = "A small office for autonomous AI agents. They think on their own, keep a diary, and ask you before they build or touch the outside world.",
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Spacer(Modifier.height(24.dp))

        OutlinedTextField(
            value = name,
            onValueChange = { name = it },
            label = { Text("What should the agents call you?") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(20.dp))
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

        Spacer(Modifier.height(28.dp))
        Button(
            onClick = { viewModel.finishSetup(name) },
            modifier = Modifier.fillMaxWidth(),
        ) {
            Text("Enter the office")
        }
        Spacer(Modifier.height(12.dp))
        Text(
            text = "Works with the free providers in the awesome-free-llm-apis list. Keyless providers are enabled by default; add your own keys in Settings for more reliability.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun FeatureRow(icon: ImageVector, title: String, body: String) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 10.dp),
        verticalAlignment = Alignment.Top,
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier
                .padding(top = 2.dp)
                .size(22.dp),
        )
        Spacer(Modifier.size(12.dp))
        Column(Modifier.weight(1f)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Text(
                text = body,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}