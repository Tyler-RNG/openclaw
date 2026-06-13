package ai.openclaw.glasses.ui

import ai.openclaw.glasses.BuildConfig
import ai.openclaw.glasses.GlassesViewModel
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp

@Composable
fun GlassesScreen(viewModel: GlassesViewModel) {
    val telemetry by viewModel.telemetry.collectAsState()
    Surface(modifier = Modifier.fillMaxSize()) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .verticalScroll(rememberScrollState())
                .padding(16.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text("OpenClaw Glasses", style = MaterialTheme.typography.headlineSmall)
            Text(
                "build ${BuildConfig.BUILD_STAMP}",
                style = MaterialTheme.typography.bodySmall,
                modifier = Modifier.padding(top = 2.dp),
            )
            PhaseBanner(telemetry.phase, viewModel)
            AppLogCard(telemetry)
            FrameLogCard(telemetry)

            val phase = telemetry.phase
            when (phase) {
                is GlassesViewModel.Phase.Idle -> {
                    Button(onClick = { viewModel.startScan() }, modifier = Modifier.padding(top = 12.dp)) {
                        Text("Scan")
                    }
                }
                is GlassesViewModel.Phase.Scanning -> ScanningSection(phase, viewModel)
                is GlassesViewModel.Phase.Ready -> ConnectedSections(phase, telemetry, viewModel)
                is GlassesViewModel.Phase.BackoffWait -> {
                    OutlinedButton(
                        onClick = { viewModel.cancelReconnect() },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("Cancel reconnect") }
                }
                is GlassesViewModel.Phase.Error -> {
                    Row(modifier = Modifier.padding(top = 12.dp)) {
                        Button(onClick = { viewModel.reconnect() }) { Text("Retry") }
                        Spacer(Modifier.width(8.dp))
                        OutlinedButton(onClick = { viewModel.startScan() }) { Text("Rescan") }
                    }
                }
                else -> {
                    // Bonding / Connecting / Negotiating / Discovering / Installing
                    OutlinedButton(
                        onClick = { viewModel.disconnect() },
                        modifier = Modifier.padding(top = 12.dp),
                    ) { Text("Cancel") }
                }
            }
        }
    }
}

@Composable
private fun PhaseBanner(phase: GlassesViewModel.Phase, viewModel: GlassesViewModel) {
    val (text, level) = phaseLine(phase)
    val bg = when (level) {
        GlassesViewModel.LogLine.Level.ERROR -> MaterialTheme.colorScheme.errorContainer
        GlassesViewModel.LogLine.Level.WARN -> MaterialTheme.colorScheme.tertiaryContainer
        else -> MaterialTheme.colorScheme.surfaceVariant
    }
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 10.dp),
        colors = androidx.compose.material3.CardDefaults.cardColors(containerColor = bg),
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Text(text, style = MaterialTheme.typography.bodyMedium)
            if (phase is GlassesViewModel.Phase.BackoffWait) {
                Spacer(Modifier.height(4.dp))
                Text(
                    "Attempt ${phase.attempt + 1} of 5 in ${(phase.nextInMs / 1000).coerceAtLeast(1)}s — last error: ${phase.reason}",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
    }
}

private fun phaseLine(p: GlassesViewModel.Phase): Pair<String, GlassesViewModel.LogLine.Level> = when (p) {
    GlassesViewModel.Phase.Idle -> "Idle — tap Scan to find your Frame (off-dock)" to GlassesViewModel.LogLine.Level.INFO
    is GlassesViewModel.Phase.Scanning ->
        (if (p.devices.isEmpty()) "Scanning…" else "Select your Frame") to GlassesViewModel.LogLine.Level.INFO
    is GlassesViewModel.Phase.Bonding -> "Bonding ${p.device}" to GlassesViewModel.LogLine.Level.INFO
    is GlassesViewModel.Phase.Connecting -> "Connecting (attempt ${p.attempt}) ${p.device}" to GlassesViewModel.LogLine.Level.INFO
    is GlassesViewModel.Phase.Negotiating -> "Negotiating MTU…" to GlassesViewModel.LogLine.Level.INFO
    is GlassesViewModel.Phase.Discovering -> "Discovering services…" to GlassesViewModel.LogLine.Level.INFO
    is GlassesViewModel.Phase.Installing -> "Installing Lua app on Frame…" to GlassesViewModel.LogLine.Level.INFO
    is GlassesViewModel.Phase.Ready ->
        "Connected ${p.name ?: p.address}  mtu=${p.mtu}  maxPayload=${p.maxAppPayload}" to GlassesViewModel.LogLine.Level.INFO
    is GlassesViewModel.Phase.BackoffWait ->
        "Reconnecting to ${p.device}…" to GlassesViewModel.LogLine.Level.WARN
    is GlassesViewModel.Phase.Error -> "Error: ${p.message}" to GlassesViewModel.LogLine.Level.ERROR
}

@Composable
private fun AppLogCard(t: GlassesViewModel.Telemetry) {
    val clipboard = LocalClipboardManager.current
    SectionCard("App log (${t.appLog.size})") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
        ) {
            OutlinedButton(
                enabled = t.appLog.isNotEmpty(),
                onClick = {
                    val text = t.appLog.joinToString("\n") {
                        "${it.ts}  [${it.level}]  ${it.message}"
                    }
                    clipboard.setText(AnnotatedString(text))
                },
            ) { Text("Copy app log") }
        }
        if (t.appLog.isEmpty()) {
            Text("(no events yet)", style = MaterialTheme.typography.bodySmall)
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                    items(t.appLog.asReversed()) { line ->
                        val color = when (line.level) {
                            GlassesViewModel.LogLine.Level.ERROR -> Color(0xFFFF8888)
                            GlassesViewModel.LogLine.Level.WARN -> Color(0xFFFFCC66)
                            else -> Color.Unspecified
                        }
                        Text(
                            "${line.ts}  ${line.message}",
                            color = color,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun FrameLogCard(t: GlassesViewModel.Telemetry) {
    val clipboard = LocalClipboardManager.current
    SectionCard("Frame print output (${t.recentLua.size})") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            OutlinedButton(
                onClick = {
                    clipboard.setText(AnnotatedString(t.recentLua.joinToString("\n")))
                },
                enabled = t.recentLua.isNotEmpty(),
            ) { Text("Copy Frame log") }
        }
        if (t.recentLua.isEmpty()) {
            Text(
                "(no Frame output yet — on-device Lua print() lands here, including " +
                    "boot probe, cam errors, and Probe APIs results)",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(220.dp)) {
                LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                    items(t.recentLua.asReversed()) { line ->
                        Text(
                            line,
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ScanningSection(
    p: GlassesViewModel.Phase.Scanning,
    viewModel: GlassesViewModel,
) {
    if (p.devices.isEmpty()) {
        Text("Looking for Frame…", style = MaterialTheme.typography.bodyMedium)
    } else {
        LazyColumn(modifier = Modifier.fillMaxWidth().height(220.dp)) {
            items(p.devices, key = { it.device.address }) { d ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
                    onClick = { viewModel.connectTo(d.device) },
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Text(d.name ?: d.device.address, style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "${d.device.address} • ${d.rssi} dBm",
                            style = MaterialTheme.typography.bodySmall,
                        )
                    }
                }
            }
        }
    }
    Button(onClick = { viewModel.stopScan() }, modifier = Modifier.padding(top = 12.dp)) {
        Text("Stop")
    }
}

@Composable
private fun ConnectedSections(
    s: GlassesViewModel.Phase.Ready,
    t: GlassesViewModel.Telemetry,
    viewModel: GlassesViewModel,
) {
    StatusStrip(t)
    CameraCard(viewModel, t)
    TextCard(viewModel, t)
    SpritesCard(viewModel)
    MicCard(viewModel, t)
    LinkCard(s, t)
    ImuCard(t)
    LuaEvalCard(viewModel, t)
    PacketLogCard(t)
    ConnectionCard(viewModel)
}

@Composable
private fun StatusStrip(t: GlassesViewModel.Telemetry) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(12.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
        ) {
            StatusBadge(label = "BAT", value = t.batteryPct?.let { "$it%" } ?: "—")
            StatusBadge(label = "TAPS", value = "${t.tapCount}")
            StatusBadge(
                label = "HDG",
                value = t.lastHeading?.let { "${it.heading.toInt()}°" } ?: "—",
            )
        }
    }
}

@Composable
private fun StatusBadge(label: String, value: String) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(
            value,
            style = MaterialTheme.typography.titleMedium,
            fontFamily = FontFamily.Monospace,
        )
    }
}

@Composable
private fun SectionCard(title: String, content: @Composable () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(vertical = 6.dp),
        shape = RoundedCornerShape(12.dp),
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium)
            Spacer(Modifier.height(6.dp))
            content()
        }
    }
}

@Composable
private fun KvRow(k: String, v: String) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 1.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Text(k, style = MaterialTheme.typography.bodySmall)
        Text(v, style = MaterialTheme.typography.bodySmall, fontFamily = FontFamily.Monospace)
    }
}

@Composable
private fun LinkCard(s: GlassesViewModel.Phase.Ready, t: GlassesViewModel.Telemetry) {
    SectionCard("Link") {
        KvRow("Device", s.name ?: "(unnamed)")
        KvRow("MAC", s.address)
        KvRow("MTU", "${s.mtu}")
        KvRow("Max app payload", "${s.maxAppPayload} B")
        KvRow("Battery", t.batteryPct?.let { "$it%" } ?: "—")
        t.bootStatus?.let { b ->
            KvRow("Boot", "imu=${b.imuPresent} cam=${b.cameraPresent} mic=${b.micPresent}")
        }
    }
}

@Composable
private fun ImuCard(t: GlassesViewModel.Telemetry) {
    SectionCard("IMU") {
        KvRow("Taps", "${t.tapCount}")
        val h = t.lastHeading
        KvRow(
            "Heading (r/p/h)",
            if (h == null) "—" else "%.1f / %.1f / %.1f".format(h.roll, h.pitch, h.heading),
        )
    }
}

@Composable
private fun CameraCard(viewModel: GlassesViewModel, t: GlassesViewModel.Telemetry) {
    SectionCard("Camera") {
        // Preview FIRST so it's the dominant element.
        if (t.cameraImage != null) {
            Image(
                bitmap = t.cameraImage,
                contentDescription = "Frame camera capture",
                modifier = Modifier.fillMaxWidth().height(320.dp),
                contentScale = ContentScale.Fit,
            )
        } else {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(180.dp)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(8.dp),
                    ),
                contentAlignment = Alignment.Center,
            ) {
                Text(
                    "(no capture yet — tap Capture)",
                    style = MaterialTheme.typography.bodySmall,
                )
            }
        }
        Spacer(Modifier.height(8.dp))
        // Progress bar while streaming.
        if (t.cameraState is GlassesViewModel.CameraState.Streaming) {
            LinearProgressIndicator(
                modifier = Modifier.fillMaxWidth().height(4.dp),
            )
            Spacer(Modifier.height(6.dp))
        }
        Text("State: ${cameraStateText(t.cameraState)}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(onClick = { viewModel.captureImage() }) { Text("Capture") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = { viewModel.toggleAutoCapture() },
            ) { Text(if (t.autoCaptureEnabled) "Stop auto" else "Auto / 2.5s") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.testCamera() }) { Text("Probe") }
        }
        t.cameraImagePath?.let { path ->
            Spacer(Modifier.height(4.dp))
            Text(
                path,
                style = MaterialTheme.typography.bodySmall,
                fontFamily = FontFamily.Monospace,
            )
        }
    }
}

private fun cameraStateText(s: GlassesViewModel.CameraState): String = when (s) {
    GlassesViewModel.CameraState.Idle -> "idle"
    GlassesViewModel.CameraState.Requesting -> "requesting…"
    is GlassesViewModel.CameraState.Streaming -> "streaming (${s.bytes} B)"
    is GlassesViewModel.CameraState.Ready -> "ready (${s.bytes} B)"
    is GlassesViewModel.CameraState.Failed -> "failed: ${s.message}"
}

@Composable
private fun TextCard(viewModel: GlassesViewModel, t: GlassesViewModel.Telemetry) {
    var text by rememberSaveable { mutableStateOf("Hello from OpenClaw") }
    var color by rememberSaveable { mutableStateOf(GlassesViewModel.COLOR_WHITE) }
    var xField by rememberSaveable { mutableStateOf("50") }
    var yField by rememberSaveable { mutableStateOf("100") }
    var spacingField by rememberSaveable { mutableStateOf("4") }

    SectionCard("Display text") {
        val current = t.displayState
        val statusLine = when (current) {
            GlassesViewModel.DisplayState.Cleared -> "cleared"
            is GlassesViewModel.DisplayState.Showing ->
                "showing ${GlassesViewModel.paletteName(current.color)}: " +
                    "\"${current.text.take(40)}${if (current.text.length > 40) "…" else ""}\""
        }
        Text(statusLine, style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = text,
            onValueChange = { text = it },
            label = { Text("Text (newlines wrap to next line)") },
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodyMedium,
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedTextField(
                value = xField,
                onValueChange = { xField = it.filter(Char::isDigit).take(4) },
                label = { Text("X") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = yField,
                onValueChange = { yField = it.filter(Char::isDigit).take(4) },
                label = { Text("Y") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
            Spacer(Modifier.width(8.dp))
            OutlinedTextField(
                value = spacingField,
                onValueChange = { spacingField = it.filter(Char::isDigit).take(2) },
                label = { Text("Spacing") },
                singleLine = true,
                modifier = Modifier.weight(1f),
                textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
            )
        }
        Spacer(Modifier.height(8.dp))
        Text("Color: ${GlassesViewModel.paletteName(color)}", style = MaterialTheme.typography.bodySmall)
        Spacer(Modifier.height(4.dp))
        PalettePicker(selected = color, onSelect = { color = it })
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Button(
                onClick = {
                    viewModel.showText(
                        text = text,
                        colorIndex = color,
                        x = xField.toIntOrNull() ?: 50,
                        y = yField.toIntOrNull() ?: 100,
                        spacing = spacingField.toIntOrNull() ?: 4,
                    )
                },
            ) { Text("Show") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.clearDisplay() }) { Text("Clear") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                onClick = {
                    val now = java.text.SimpleDateFormat("HH:mm:ss", java.util.Locale.US)
                        .format(java.util.Date())
                    text = now
                    viewModel.showText(
                        now,
                        colorIndex = color,
                        x = xField.toIntOrNull() ?: 50,
                        y = yField.toIntOrNull() ?: 100,
                        spacing = spacingField.toIntOrNull() ?: 4,
                    )
                },
            ) { Text("Time") }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { viewModel.showPaletteDemo() }) { Text("Palette") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { viewModel.showColorTest() }) { Text("Color test") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { viewModel.showCornerDemo() }) { Text("Bounds") }
        }
        Spacer(Modifier.height(4.dp))
        Text(
            "Display is 640×400. Bounds maps the visible area. Color test renders " +
                "the same text in 9 variants — anything that looks distinct from the " +
                "WHITE baseline is a working color arg. Sprite + animation demos live " +
                "in the next card.",
            style = MaterialTheme.typography.bodySmall,
        )
    }
}

@Composable
private fun SpritesCard(viewModel: GlassesViewModel) {
    SectionCard("Sprites + animations") {
        Text(
            "frame.display.bitmap demos. Small sprites (≤ ~30×60 1bpp) ship in one " +
                "BLE write. Full-screen 640×400 uses chunked BEGIN/CHUNK/END (~2 s). " +
                "Animations loop until you Stop.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(10.dp))
        Text("Static", style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            OutlinedButton(onClick = { viewModel.showSmiley() }) { Text("Smiley") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { viewModel.showHeart() }) { Text("Heart") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { viewModel.showBatteryIconDemo() }) { Text("Battery") }
        }
        Spacer(Modifier.height(6.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { viewModel.showLetterboxDemo() }) { Text("Letterbox") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { viewModel.showCheckerboardFullScreen() }) {
                Text("Checker (640×400)")
            }
        }
        Spacer(Modifier.height(14.dp))
        Text("Animated (loops)", style = MaterialTheme.typography.labelMedium)
        Row(modifier = Modifier.fillMaxWidth().padding(top = 4.dp)) {
            OutlinedButton(onClick = { viewModel.playSpinnerAnimation() }) { Text("Spinner") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { viewModel.playBouncingBall() }) { Text("Ball") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { viewModel.playBatteryFillAnimation() }) { Text("Battery fill") }
        }
        Spacer(Modifier.height(10.dp))
        Button(onClick = { viewModel.stopAnimation() }) { Text("Stop animation") }
    }
}

@Composable
private fun PalettePicker(selected: Int, onSelect: (Int) -> Unit) {
    val swatches = paletteSwatches()
    Column(modifier = Modifier.fillMaxWidth()) {
        for (rowStart in listOf(0, 8)) {
            Row(modifier = Modifier.fillMaxWidth().padding(vertical = 2.dp)) {
                for (i in rowStart until rowStart + 8) {
                    val sw = swatches[i]
                    val isSel = i == selected
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .padding(horizontal = 2.dp)
                            .height(28.dp)
                            .background(sw, shape = RoundedCornerShape(6.dp))
                            .clickable { onSelect(i) },
                        contentAlignment = Alignment.Center,
                    ) {
                        if (isSel) {
                            Text(
                                "●",
                                color = if (sw.luminance() > 0.5f) Color.Black else Color.White,
                                style = MaterialTheme.typography.bodyMedium,
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun MicCard(viewModel: GlassesViewModel, t: GlassesViewModel.Telemetry) {
    var sampleRate by rememberSaveable { mutableStateOf(8000) }
    var bitDepth by rememberSaveable { mutableStateOf(8) }
    val clipboard = LocalClipboardManager.current

    SectionCard("Microphone") {
        Text(
            "Streams PCM from the Frame mic over BLE; saved as WAV when you stop.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(8.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Sample rate", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            ToggleChip("8 kHz", sampleRate == 8000) { sampleRate = 8000 }
            Spacer(Modifier.width(4.dp))
            ToggleChip("16 kHz", sampleRate == 16000) { sampleRate = 16000 }
        }
        Spacer(Modifier.height(4.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            Text("Bit depth", style = MaterialTheme.typography.bodySmall, modifier = Modifier.weight(1f))
            ToggleChip("8-bit", bitDepth == 8) { bitDepth = 8 }
            Spacer(Modifier.width(4.dp))
            ToggleChip("16-bit", bitDepth == 16) { bitDepth = 16 }
        }
        Spacer(Modifier.height(10.dp))
        when (val m = t.micState) {
            GlassesViewModel.MicState.Idle -> {
                Text("idle", style = MaterialTheme.typography.bodySmall)
            }
            is GlassesViewModel.MicState.Recording -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                Spacer(Modifier.height(6.dp))
                val secs = (m.durationMs / 1000.0)
                val expected = m.sampleRate * (m.bitDepth / 8) * secs
                Text(
                    "recording ${"%.1f".format(secs)}s • ${m.bytes} B" +
                        " (expected ~${expected.toInt()} B)",
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
            }
            GlassesViewModel.MicState.Stopping -> {
                LinearProgressIndicator(modifier = Modifier.fillMaxWidth().height(4.dp))
                Spacer(Modifier.height(6.dp))
                Text("stopping — draining Frame buffer…",
                    style = MaterialTheme.typography.bodySmall)
            }
            is GlassesViewModel.MicState.Saved -> {
                val seconds = (m.bytes.toDouble() / (m.sampleRate * (m.bitDepth / 8)))
                Text(
                    "saved ${"%.1f".format(seconds)}s • ${m.bytes} B • ${m.sampleRate}Hz ${m.bitDepth}-bit",
                    style = MaterialTheme.typography.bodySmall,
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    m.path,
                    style = MaterialTheme.typography.bodySmall,
                    fontFamily = FontFamily.Monospace,
                )
                Spacer(Modifier.height(6.dp))
                OutlinedButton(
                    onClick = { clipboard.setText(AnnotatedString(m.path)) },
                ) { Text("Copy path") }
            }
            is GlassesViewModel.MicState.Failed -> {
                Text("failed: ${m.message}", color = Color(0xFFFF8888),
                    style = MaterialTheme.typography.bodySmall)
            }
        }
        Spacer(Modifier.height(10.dp))
        Row(modifier = Modifier.fillMaxWidth()) {
            val isRecording = t.micState is GlassesViewModel.MicState.Recording
            val isStopping = t.micState is GlassesViewModel.MicState.Stopping
            Button(
                enabled = !isRecording && !isStopping,
                onClick = { viewModel.startMic(sampleRate, bitDepth) },
            ) { Text("Record") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(
                enabled = isRecording,
                onClick = { viewModel.stopMic() },
            ) { Text("Stop") }
        }
    }
}

@Composable
private fun ToggleChip(label: String, selected: Boolean, onClick: () -> Unit) {
    val bg = if (selected) MaterialTheme.colorScheme.primary else Color.Transparent
    val fg = if (selected) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface
    OutlinedButton(
        onClick = onClick,
        colors = androidx.compose.material3.ButtonDefaults.outlinedButtonColors(
            containerColor = bg,
            contentColor = fg,
        ),
    ) { Text(label, style = MaterialTheme.typography.labelSmall) }
}

private fun paletteSwatches(): List<Color> = listOf(
    Color(0xFF101010), // VOID
    Color(0xFFFFFFFF), // WHITE
    Color(0xFF888888), // GREY
    Color(0xFFFF3030), // RED
    Color(0xFFFF88AA), // PINK
    Color(0xFF4B2E20), // DARKBROWN
    Color(0xFF8B5A2B), // BROWN
    Color(0xFFFF8C00), // ORANGE
    Color(0xFFF0E020), // YELLOW
    Color(0xFF1F5F1F), // DARKGREEN
    Color(0xFF20C040), // GREEN
    Color(0xFF80FF80), // LIGHTGREEN
    Color(0xFF101040), // NIGHTBLUE
    Color(0xFF2060A0), // SEABLUE
    Color(0xFF40A0F0), // SKYBLUE
    Color(0xFFB0E0FF), // CLOUDBLUE
)

@Composable
private fun LuaEvalCard(viewModel: GlassesViewModel, t: GlassesViewModel.Telemetry) {
    var lua by rememberSaveable { mutableStateOf("print(frame.FIRMWARE_VERSION)") }
    SectionCard("Lua eval") {
        Text(
            "Raw Lua executed on the Frame REPL. Frame's print() output (including errors) " +
                "appears below. Write errors appear in the App log above.",
            style = MaterialTheme.typography.bodySmall,
        )
        Spacer(Modifier.height(6.dp))
        OutlinedTextField(
            value = lua,
            onValueChange = { lua = it },
            label = { Text("Lua statement") },
            singleLine = false,
            modifier = Modifier.fillMaxWidth(),
            textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
        )
        Row(modifier = Modifier.padding(top = 8.dp)) {
            Button(onClick = { viewModel.evalLua(lua) }) { Text("Eval") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { lua = "print(frame.battery_level())" }) { Text("battery") }
            Spacer(Modifier.width(6.dp))
            OutlinedButton(onClick = { lua = "print(type(frame.imu.direction))" }) { Text("imu?") }
        }
        // Frame print output now lives in its own top-level FrameLogCard so it's
        // always visible. Avoid duplicating it here.
    }
}

@Composable
private fun PacketLogCard(t: GlassesViewModel.Telemetry) {
    SectionCard("Recent inbound packets (${t.recentPackets.size})") {
        if (t.recentPackets.isEmpty()) {
            Text(
                "(none) — if this stays empty after Ready, the on-device Lua app " +
                    "isn't running. Hit Reinstall Lua in Connection.",
                style = MaterialTheme.typography.bodySmall,
            )
        } else {
            Box(modifier = Modifier.fillMaxWidth().height(180.dp)) {
                LazyColumn(reverseLayout = true, modifier = Modifier.fillMaxSize()) {
                    items(t.recentPackets.asReversed()) { p ->
                        Text(
                            "${p.channel.padEnd(5)} ${p.size.toString().padStart(4)}B  ${p.hex}",
                            style = MaterialTheme.typography.bodySmall,
                            fontFamily = FontFamily.Monospace,
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun ConnectionCard(viewModel: GlassesViewModel) {
    SectionCard("Connection") {
        Row(modifier = Modifier.fillMaxWidth()) {
            OutlinedButton(onClick = { viewModel.reconnect() }) { Text("Reconnect") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.reinstallLuaApp() }) { Text("Reinstall Lua") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.disconnect() }) {
                Text("Disconnect", color = Color.Red)
            }
        }
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedButton(onClick = { viewModel.probeApis() }) { Text("Probe Frame APIs") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.testSingleEval() }) { Text("Test BLE eval") }
            Spacer(Modifier.width(8.dp))
            OutlinedButton(onClick = { viewModel.pingFrame() }) { Text("Ping") }
        }
        val clipboard = LocalClipboardManager.current
        Row(modifier = Modifier.fillMaxWidth().padding(top = 8.dp)) {
            OutlinedButton(
                onClick = { clipboard.setText(AnnotatedString(viewModel.fullLogSnapshot())) },
            ) { Text("Copy all logs + state") }
        }
    }
}
