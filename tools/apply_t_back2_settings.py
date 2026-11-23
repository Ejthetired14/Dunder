#!/usr/bin/env python3
"""Applies T-BACK2 (Settings -> PixelScaffold) to the repo. Run from the repo root.
Idempotent: refuses to run twice, and aborts without writing if any anchor is missing."""
import pathlib, sys

p = pathlib.Path("app/src/main/java/com/agoria/app/ui/settings/SettingsScreen.kt")
s = p.read_text(encoding="utf-8")
if "PixelScaffold(" in s:
    sys.exit("SettingsScreen.kt already uses PixelScaffold; nothing to do.")

OLD_BAR = '''    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Settings") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back")
                    }
                },
            )
        },
        snackbarHost = { SnackbarHost(snackbarHostState) }
    ) { padding ->'''
NEW_BAR = '''    PixelScaffold(
        title = "Settings",
        onBack = onBack,
        snackbarHost = { SnackbarHost(snackbarHostState) },
    ) { padding ->'''

edits = [
    (OLD_BAR, NEW_BAR),
    ("import androidx.compose.material.icons.filled.ArrowBack\n", ""),
    ("import androidx.compose.material3.IconButton\n", ""),
    ("import androidx.compose.material3.Scaffold\n", ""),
    ("import androidx.compose.material3.TopAppBar\n", ""),
    ("import com.agoria.app.ui.SettingsScreenState\n",
     "import com.agoria.app.ui.SettingsScreenState\nimport com.agoria.app.ui.components.PixelScaffold\n"),
]
for old, _ in edits:
    if s.count(old) != 1:
        sys.exit("Anchor not found exactly once, nothing written:\n" + old[:120])
for old, new in edits:
    s = s.replace(old, new)
p.write_text(s, encoding="utf-8")
print("SettingsScreen.kt converted to PixelScaffold.")
