package dev.pockethost.ui

import android.content.Context
import androidx.compose.foundation.layout.Arrangement
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.Divider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import dev.pockethost.model.LogLine
import dev.pockethost.model.ServiceSpec
import dev.pockethost.model.ServiceState
import dev.pockethost.model.ServiceStatus
import dev.pockethost.supervisor.AppPaths
import dev.pockethost.supervisor.Diagnostics
import dev.pockethost.supervisor.LogBus
import dev.pockethost.supervisor.NativeBinaryLocator
import dev.pockethost.supervisor.ProcessSupervisor
import dev.pockethost.supervisor.ServerCommands
import dev.pockethost.supervisor.ServicePreferences
import dev.pockethost.supervisor.ServiceRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val title: String, val short: String) {
    Dashboard("Dashboard", "Dash"),
    Services("Services", "Svc"),
    Network("Network", "Net"),
    Storage("Storage", "Disk"),
    Logs("Logs", "Logs"),
    Settings("Settings", "Set")
}

@Composable
fun PocketHostApp() {
    val context = LocalContext.current
    val statuses by ProcessSupervisor.statuses.collectAsState()
    val logs by LogBus.logs.collectAsState()
    var selected by remember { mutableStateOf(Tab.Dashboard) }

    LaunchedEffect(Unit) {
        AppPaths.ensure(context)
        LogBus.attach(context.applicationContext)
    }

    MaterialTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = { selected = tab },
                            icon = { Text(tab.short) },
                            label = { Text(tab.title) }
                        )
                    }
                }
            }
        ) { padding ->
            Column(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
            ) {
                when (selected) {
                    Tab.Dashboard -> DashboardScreen(context, statuses)
                    Tab.Services -> ServicesScreen(context, statuses)
                    Tab.Network -> NetworkScreen(context, statuses)
                    Tab.Storage -> StorageScreen(context)
                    Tab.Logs -> LogsScreen(logs)
                    Tab.Settings -> SettingsScreen(context)
                }
            }
        }
    }
}

@Composable
private fun DashboardScreen(context: Context, statuses: Map<String, ServiceStatus>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Header("PocketHost", "Android mini-server control plane")
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Runtime", style = MaterialTheme.typography.titleMedium)
                    val running = statuses.values.count { it.state == ServiceState.Running }
                    val failed = statuses.values.count {
                        it.state == ServiceState.Failed || it.state == ServiceState.MissingBinary || it.state == ServiceState.Degraded
                    }
                    Text("Services running: $running / ${ServiceRegistry.specs.size}")
                    Text("Attention needed: $failed")
                    Text("App storage: ${formatBytes(context.filesDir.usableSpace)} free")
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ServerCommands.startAll(context) }) { Text("Start all") }
                        OutlinedButton(onClick = { ServerCommands.stopAll(context) }) { Text("Stop all") }
                    }
                }
            }
        }
        item {
            Text("Default service status", style = MaterialTheme.typography.titleMedium)
        }
        items(ServiceRegistry.specs.filter { it.startByDefault }) { spec ->
            CompactServiceRow(spec, statuses[spec.id])
        }
    }
}

@Composable
private fun ServicesScreen(context: Context, statuses: Map<String, ServiceStatus>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header("Services", "Start, stop, restart, and inspect daemons") }
        items(ServiceRegistry.specs) { spec ->
            ServiceCard(context, spec, statuses[spec.id])
        }
    }
}

@Composable
private fun NetworkScreen(context: Context, statuses: Map<String, ServiceStatus>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header("Network", "Local bindings and tunnel routes") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Local service endpoints", style = MaterialTheme.typography.titleMedium)
                    ServiceRegistry.specs.filter { it.defaultPort != null }.forEach { spec ->
                        val state = statuses[spec.id]?.state?.label ?: "Stopped"
                        Text("${spec.displayName}: http://127.0.0.1:${spec.defaultPort} · $state")
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cloudflare Tunnel routes", style = MaterialTheme.typography.titleMedium)
                    Text("web.example.com -> http://127.0.0.1:8080")
                    Text("files.example.com -> http://127.0.0.1:8090")
                    Text("matrix.example.com -> http://127.0.0.1:6167")
                    val binary = NativeBinaryLocator.fileFor(context, ServiceRegistry.byId("cloudflared")!!)
                    Text("Binary: ${if (binary.exists()) binary.absolutePath else "missing libcloudflared.so"}")
                }
            }
        }
    }
}

@Composable
private fun StorageScreen(context: Context) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header("Storage", "App data, service data, logs, and public files") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Directories", style = MaterialTheme.typography.titleMedium)
                    DirRow("Files", context.filesDir)
                    DirRow("Config", AppPaths.configDir(context))
                    DirRow("Data", AppPaths.dataDir(context))
                    DirRow("Logs", AppPaths.logsDir(context))
                    DirRow("Web root", AppPaths.webRoot(context))
                    DirRow("File root", AppPaths.filesRoot(context))
                    DirRow("Matrix", AppPaths.matrixRoot(context))
                }
            }
        }
    }
}

@Composable
private fun LogsScreen(logs: List<LogLine>) {
    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Header("Logs", "Recent daemon and supervisor messages") }
        if (logs.isEmpty()) {
            item { Text("No logs yet. Start a service to generate logs.") }
        } else {
            items(logs) { line -> LogRow(line) }
        }
    }
}

@Composable
private fun SettingsScreen(context: Context) {
    var autostart by remember { mutableStateOf(ServicePreferences.autostartEnabled(context)) }
    var tokenPreview by remember { mutableStateOf(ServicePreferences.adminToken(context).take(12) + "...") }
    var diagnosticsPath by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header("Settings", "Boot behavior, tokens, and diagnostics") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Column(Modifier.weight(1f)) {
                            Text("Start default services on boot", fontWeight = FontWeight.SemiBold)
                            Text("Uses BOOT_COMPLETED and starts the foreground supervisor.")
                        }
                        Switch(
                            checked = autostart,
                            onCheckedChange = {
                                autostart = it
                                ServicePreferences.setAutostartEnabled(context, it)
                            }
                        )
                    }
                    Divider()
                    Text("Admin token", fontWeight = FontWeight.SemiBold)
                    Text(tokenPreview)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            tokenPreview = ServicePreferences.rotateAdminToken(context).take(12) + "..."
                        }) { Text("Rotate") }
                        TextButton(onClick = { AppPaths.ensure(context) }) { Text("Recreate dirs") }
                    }
                    Divider()
                    Text("Diagnostics", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = {
                        diagnosticsPath = runCatching { Diagnostics.createBundle(context).absolutePath }
                            .getOrElse { "Failed: ${it.message}" }
                    }) { Text("Create bundle") }
                    if (diagnosticsPath.isNotBlank()) {
                        Text(diagnosticsPath)
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Native library directory", style = MaterialTheme.typography.titleMedium)
                    Text(context.applicationInfo.nativeLibraryDir)
                    Text("Daemons must be packaged here as lib<name>.so files.")
                }
            }
        }
    }
}

@Composable
private fun Header(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium)
    }
}

@Composable
private fun CompactServiceRow(spec: ServiceSpec, status: ServiceStatus?) {
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(Modifier.weight(1f)) {
                Text(spec.displayName, fontWeight = FontWeight.SemiBold)
                Text("${status?.state?.label ?: "Stopped"}${spec.defaultPort?.let { " · :$it" } ?: ""}")
            }
            StatusText(status?.state ?: ServiceState.Stopped)
        }
    }
}

@Composable
private fun ServiceCard(context: Context, spec: ServiceSpec, status: ServiceStatus?) {
    val state = status?.state ?: ServiceState.Stopped
    val binary = NativeBinaryLocator.fileFor(context, spec)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.displayName, style = MaterialTheme.typography.titleMedium)
                    Text(spec.description)
                }
                StatusText(state)
            }
            Text("Binary: ${binary.name} · ${if (binary.exists()) "available" else "missing"}")
            spec.defaultPort?.let { Text("Local endpoint: http://127.0.0.1:$it") }
            if (!status?.lastMessage.isNullOrBlank()) {
                Text("Last: ${status?.lastMessage}")
            }
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ServerCommands.start(context, spec.id) }) { Text("Start") }
                OutlinedButton(onClick = { ServerCommands.stop(context, spec.id) }) { Text("Stop") }
                OutlinedButton(onClick = { ServerCommands.restart(context, spec.id) }) { Text("Restart") }
            }
        }
    }
}

@Composable
private fun StatusText(state: ServiceState) {
    Text(state.label, fontWeight = FontWeight.SemiBold)
}

@Composable
private fun DirRow(label: String, file: File) {
    Column {
        Text(label, fontWeight = FontWeight.SemiBold)
        Text(file.absolutePath)
        Text("${formatBytes(directorySize(file))} used · ${formatBytes(file.usableSpace)} free")
    }
}

@Composable
private fun LogRow(line: LogLine) {
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text("${formatTime(line.timestampEpochMs)} · ${line.serviceId} · ${line.level}", fontWeight = FontWeight.SemiBold)
            Text(line.message)
        }
    }
}

private fun formatTime(epochMs: Long): String =
    SimpleDateFormat("HH:mm:ss", Locale.US).format(Date(epochMs))

private fun formatBytes(bytes: Long): String {
    if (bytes < 1024) return "$bytes B"
    val units = arrayOf("KB", "MB", "GB", "TB")
    var value = bytes.toDouble() / 1024.0
    var idx = 0
    while (value >= 1024 && idx < units.lastIndex) {
        value /= 1024.0
        idx++
    }
    return "%.1f %s".format(Locale.US, value, units[idx])
}

private fun directorySize(file: File): Long {
    if (!file.exists()) return 0L
    if (file.isFile) return file.length()
    return file.listFiles()?.sumOf { directorySize(it) } ?: 0L
}
