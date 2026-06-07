package dev.pockethost.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Dashboard
import androidx.compose.material.icons.filled.Dns
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Stop
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.outlined.Article
import androidx.compose.material.icons.outlined.Lan
import androidx.compose.material.icons.outlined.OpenInNew
import androidx.compose.material.icons.outlined.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.pockethost.model.LogLine
import dev.pockethost.model.ServiceSpec
import dev.pockethost.model.ServiceState
import dev.pockethost.model.ServiceStatus
import dev.pockethost.supervisor.AppPaths
import dev.pockethost.supervisor.Diagnostics
import dev.pockethost.supervisor.LogBus
import dev.pockethost.supervisor.NativeBinaryLocator
import dev.pockethost.supervisor.NextcloudInstaller
import dev.pockethost.supervisor.PhpRuntimeInstaller
import dev.pockethost.supervisor.ProcessSupervisor
import dev.pockethost.supervisor.ServerCommands
import dev.pockethost.supervisor.ServicePreferences
import dev.pockethost.supervisor.ServiceRegistry
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

private enum class Tab(val title: String, val icon: ImageVector) {
    Dashboard("Dashboard", Icons.Filled.Dashboard),
    Services("Services", Icons.Filled.Dns),
    Network("Network", Icons.Outlined.Lan),
    Tunnel("Tunnel", Icons.Outlined.OpenInNew),
    Matrix("Matrix", Icons.Filled.Dns),
    Nextcloud("Nextcloud", Icons.Filled.Storage),
    Storage("Storage", Icons.Filled.Storage),
    Logs("Logs", Icons.Outlined.Article),
    Settings("Settings", Icons.Filled.Settings)
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

    PocketHostTheme {
        Scaffold(
            bottomBar = {
                NavigationBar {
                    Tab.entries.forEach { tab ->
                        NavigationBarItem(
                            selected = selected == tab,
                            onClick = { selected = tab },
                            icon = { Icon(tab.icon, contentDescription = tab.title) },
                            label = { Text(tab.title, fontSize = 11.sp) }
                        )
                    }
                }
            }
        ) { padding ->
            Box(
                modifier = Modifier
                    .padding(padding)
                    .fillMaxSize()
                    .background(MaterialTheme.colorScheme.background)
            ) {
                when (selected) {
                    Tab.Dashboard -> DashboardScreen(context, statuses) { selected = Tab.Services }
                    Tab.Services -> ServicesScreen(context, statuses)
                    Tab.Network -> NetworkScreen(context, statuses)
                    Tab.Tunnel -> TunnelScreen(context, statuses, logs)
                    Tab.Matrix -> MatrixScreen(context, statuses)
                    Tab.Nextcloud -> NextcloudScreen(context, statuses)
                    Tab.Storage -> StorageScreen(context)
                    Tab.Logs -> LogsScreen(logs)
                    Tab.Settings -> SettingsScreen(context)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Screens
// ---------------------------------------------------------------------------

@Composable
private fun DashboardScreen(
    context: Context,
    statuses: Map<String, ServiceStatus>,
    onManage: () -> Unit
) {
    val palette = statusPalette()
    val running = statuses.values.count { it.state == ServiceState.Running }
    val attention = statuses.values.count {
        it.state == ServiceState.Failed || it.state == ServiceState.MissingBinary || it.state == ServiceState.Degraded
    }
    val exposed = ServicePreferences.exposeOnLan(context)

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header("PocketHost", "Android mini-server control plane") }

        if (exposed) item { ExposureBanner(palette) }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(20.dp)) {
                        StatBox(running.toString(), "running", palette.ok)
                        StatBox(attention.toString(), "attention", if (attention > 0) palette.error else palette.idle)
                        StatBox(ServiceRegistry.specs.size.toString(), "services", MaterialTheme.colorScheme.onSurface)
                    }
                    Text(
                        "${formatBytes(context.filesDir.usableSpace)} free on app storage",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { ServerCommands.startAll(context) }) {
                            Icon(Icons.Filled.PlayArrow, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Start all")
                        }
                        OutlinedButton(onClick = { ServerCommands.stopAll(context) }) {
                            Icon(Icons.Filled.Stop, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Stop all")
                        }
                    }
                }
            }
        }

        item { WebPanelCard(context, statuses["host"]?.state == ServiceState.Running) }

        item {
            Row(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Default services", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                TextButton(onClick = onManage) { Text("Manage") }
            }
        }
        items(ServiceRegistry.specs.filter { it.startByDefault }) { spec ->
            CompactServiceRow(spec, statuses[spec.id], palette)
        }
    }
}

@Composable
private fun ServicesScreen(context: Context, statuses: Map<String, ServiceStatus>) {
    val palette = statusPalette()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header("Services", "Start, stop, restart, and inspect daemons") }
        items(ServiceRegistry.specs) { spec ->
            ServiceCard(context, spec, statuses[spec.id], palette)
        }
    }
}

@Composable
private fun NetworkScreen(context: Context, statuses: Map<String, ServiceStatus>) {
    val palette = statusPalette()
    val bindHost = ServicePreferences.bindHost(context)
    val exposed = ServicePreferences.exposeOnLan(context)
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header("Network", "Local bindings and tunnel routes") }
        if (exposed) item { ExposureBanner(palette) }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Service endpoints", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        BindChip(exposed, palette)
                    }
                    ServiceRegistry.specs.filter { it.defaultPort != null }.forEach { spec ->
                        val state = statuses[spec.id]?.state ?: ServiceState.Stopped
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            StatusDot(state, palette)
                            Spacer(Modifier.width(8.dp))
                            Column(Modifier.weight(1f)) {
                                Text(spec.displayName, fontWeight = FontWeight.SemiBold)
                                Mono("http://$bindHost:${spec.defaultPort}")
                            }
                            Text(state.label, style = MaterialTheme.typography.bodySmall, color = palette.colorFor(state))
                        }
                    }
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Cloudflare Tunnel routes", style = MaterialTheme.typography.titleMedium)
                    Text("web.example.com → http://127.0.0.1:8080")
                    Text("files.example.com → http://127.0.0.1:8090")
                    Text("matrix.example.com → http://127.0.0.1:6167")
                    Divider(Modifier.padding(vertical = 4.dp))
                    val binary = NativeBinaryLocator.fileFor(context, ServiceRegistry.byId("cloudflared")!!)
                    Mono("Binary: ${if (binary.exists()) binary.absolutePath else "missing libcloudflared.so"}")
                    Mono("Config: ${AppPaths.cloudflaredConfig(context).absolutePath}")
                }
            }
        }
    }
}

@Composable
private fun TunnelScreen(context: Context, statuses: Map<String, ServiceStatus>, logs: List<LogLine>) {
    val palette = statusPalette()
    val cloudflared = ServiceRegistry.byId("cloudflared")!!
    val binary = NativeBinaryLocator.fileFor(context, cloudflared)
    val state = statuses["cloudflared"]?.state ?: ServiceState.Stopped
    val existing = remember { ServicePreferences.cloudflareTunnelSettings(context) }
    var tunnel by remember { mutableStateOf(existing.tunnel) }
    var credentialsFile by remember { mutableStateOf(existing.credentialsFile) }
    var hostHostname by remember { mutableStateOf(existing.hostHostname) }
    var webHostname by remember { mutableStateOf(existing.webHostname) }
    var filesHostname by remember { mutableStateOf(existing.filesHostname) }
    var proxyHostname by remember { mutableStateOf(existing.proxyHostname) }
    var matrixHostname by remember { mutableStateOf(existing.matrixHostname) }
    var nextcloudHostname by remember { mutableStateOf(existing.nextcloudHostname) }
    var quickTunnel by remember { mutableStateOf(existing.quickTunnel) }
    var quickUrl by remember { mutableStateOf(existing.quickUrl.ifBlank { "http://127.0.0.1:8080" }) }
    var message by remember { mutableStateOf("") }
    val latestQuickTunnelUrl = latestQuickTunnelUrl(logs)
    val credentialPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        if (uri != null) {
            runCatching {
                context.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                val imported = importCloudflareCredential(context, uri)
                credentialsFile = imported.absolutePath
                quickTunnel = false
                message = "Imported ${imported.absolutePath}"
            }.onFailure {
                message = "Import failed: ${it.message ?: it.javaClass.simpleName}"
            }
        }
    }

    fun currentSettings() = ServicePreferences.CloudflareTunnelSettings(
        tunnel = tunnel,
        credentialsFile = credentialsFile,
        hostHostname = hostHostname,
        webHostname = webHostname,
        filesHostname = filesHostname,
        proxyHostname = proxyHostname,
        matrixHostname = matrixHostname,
        nextcloudHostname = nextcloudHostname,
        quickTunnel = quickTunnel,
        quickUrl = quickUrl
    )

    fun saveConfig(): Boolean {
        ServicePreferences.setCloudflareTunnelSettings(context, currentSettings())
        if (quickTunnel) {
            message = "Quick Tunnel saved. Start cloudflared, then copy the trycloudflare.com URL from Logs."
            return true
        }
        return runCatching {
            val file = ServicePreferences.writeCloudflaredConfig(context)
            message = "Wrote ${file.absolutePath}"
            true
        }.getOrElse {
            message = "Failed: ${it.message ?: it.javaClass.simpleName}"
            false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header("Cloudflare Tunnel", "Choose exactly what becomes public") }

        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = palette.warn.copy(alpha = 0.12f))
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Warning, null, tint = palette.warn, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Quick Tunnel needs no Cloudflare account and prints a temporary trycloudflare.com URL in Logs. Named Tunnel needs an account and stable hostnames.",
                        color = palette.warn,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Tunnel daemon", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusChip(state, palette)
                    }
                    Mono("Binary: ${if (binary.exists()) binary.absolutePath else "missing libcloudflared.so"}")
                    Mono("Mode: ${if (quickTunnel) "Quick Tunnel, no account" else "Named Tunnel, Cloudflare account"}")
                    if (quickTunnel) {
                        Mono("Command target: $quickUrl")
                        if (latestQuickTunnelUrl != null) {
                            Mono("Public URL: $latestQuickTunnelUrl")
                            OutlinedButton(onClick = { copyText(context, "Cloudflare URL", latestQuickTunnelUrl) }) {
                                Text("Copy public URL")
                            }
                        }
                    } else {
                        Mono("Config: ${AppPaths.cloudflaredConfig(context).absolutePath}")
                    }
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (saveConfig()) ServerCommands.start(context, "cloudflared") }) {
                            Text("Save and start")
                        }
                        OutlinedButton(onClick = { if (saveConfig()) ServerCommands.restart(context, "cloudflared") }) {
                            Text("Save and restart")
                        }
                        OutlinedButton(onClick = { ServerCommands.stop(context, "cloudflared") }) {
                            Text("Stop")
                        }
                    }
                    if (message.isNotBlank()) Mono(message)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleRow(
                        title = "Use Quick Tunnel",
                        subtitle = "No account. Temporary random public URL. Best for fast demos.",
                        checked = quickTunnel
                    ) { quickTunnel = it }
                    Text("Quick Tunnel target", style = MaterialTheme.typography.titleMedium)
                    RoutePicker("Web server", "http://127.0.0.1:8080", quickUrl) { quickUrl = it }
                    RoutePicker("Files API", "http://127.0.0.1:8090", quickUrl) { quickUrl = it }
                    RoutePicker("Host panel", "http://127.0.0.1:8099", quickUrl) { quickUrl = it }
                    RoutePicker("Proxy", "http://127.0.0.1:8088", quickUrl) { quickUrl = it }
                    RoutePicker("Matrix", "http://127.0.0.1:6167", quickUrl) { quickUrl = it }
                    RoutePicker("Nextcloud", "http://127.0.0.1:8092", quickUrl) { quickUrl = it }
                    Text(
                        "Start the target service first, then start Cloudflare Tunnel. The public URL appears in the cloudflared log output.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Named Tunnel credentials", style = MaterialTheme.typography.titleMedium)
                    OutlinedTextField(
                        value = tunnel,
                        onValueChange = { tunnel = it },
                        label = { Text("Tunnel name or UUID") },
                        singleLine = true,
                        enabled = !quickTunnel,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = credentialsFile,
                        onValueChange = { credentialsFile = it },
                        label = { Text("Credentials file path on device") },
                        singleLine = true,
                        enabled = !quickTunnel,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedButton(
                        onClick = { credentialPicker.launch(arrayOf("application/json", "text/*", "*/*")) },
                        enabled = !quickTunnel,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        Text("Import credentials JSON")
                    }
                    Text("Public routes", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Empty field means private. Fill all fields if you intentionally want the whole PocketHost surface public through Cloudflare.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    RouteField("Host panel hostname", hostHostname, "http://127.0.0.1:8099", !quickTunnel) { hostHostname = it }
                    RouteField("Web hostname", webHostname, "http://127.0.0.1:8080", !quickTunnel) { webHostname = it }
                    RouteField("Files hostname", filesHostname, "http://127.0.0.1:8090", !quickTunnel) { filesHostname = it }
                    RouteField("Proxy hostname", proxyHostname, "http://127.0.0.1:8088", !quickTunnel) { proxyHostname = it }
                    RouteField("Matrix hostname", matrixHostname, "http://127.0.0.1:6167", !quickTunnel) { matrixHostname = it }
                    RouteField("Nextcloud hostname", nextcloudHostname, "http://127.0.0.1:8092", !quickTunnel) { nextcloudHostname = it }
                    Button(onClick = { saveConfig() }, modifier = Modifier.fillMaxWidth()) {
                        Text(if (quickTunnel) "Save Quick Tunnel target" else "Save cloudflared.yml")
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text("Matrix status", style = MaterialTheme.typography.titleMedium)
                    Text(
                        "The app can supervise Matrix on port 6167, but the bundled matrixd is still a placeholder. A proper Matrix server requires selecting and bundling a real Android-compatible homeserver binary.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Mono("Expected route target: http://127.0.0.1:6167")
                    Mono("Data directory: ${AppPaths.matrixRoot(context).absolutePath}")
                }
            }
        }
    }
}

@Composable
private fun MatrixScreen(context: Context, statuses: Map<String, ServiceStatus>) {
    val palette = statusPalette()
    val status = statuses["matrix"]
    val state = status?.state ?: ServiceState.Stopped
    val existing = remember { ServicePreferences.matrixSettings(context) }
    var serverName by remember { mutableStateOf(existing.serverName) }
    var registrationEnabled by remember { mutableStateOf(existing.registrationEnabled) }
    var registrationToken by remember { mutableStateOf(existing.registrationToken) }
    var federationEnabled by remember { mutableStateOf(existing.federationEnabled) }
    var message by remember { mutableStateOf("") }
    val binary = NativeBinaryLocator.fileFor(context, ServiceRegistry.byId("matrix")!!)

    fun saveMatrixConfig(): Boolean {
        ServicePreferences.setMatrixSettings(
            context,
            ServicePreferences.MatrixSettings(
                serverName = serverName,
                registrationEnabled = registrationEnabled,
                registrationToken = registrationToken,
                federationEnabled = federationEnabled,
                configured = true
            )
        )
        return runCatching {
            val file = ServicePreferences.writeDendriteConfig(context)
            message = "Wrote ${file.absolutePath}"
            true
        }.getOrElse {
            message = "Failed: ${it.message ?: it.javaClass.simpleName}"
            false
        }
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header("Matrix", "Dendrite homeserver setup") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Runtime", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusChip(state, palette)
                    }
                    Mono("Binary: ${if (binary.exists()) binary.absolutePath else "missing libmatrixd.so"}")
                    Mono("Config: ${AppPaths.matrixConfig(context).absolutePath}")
                    Mono("Data: ${AppPaths.matrixRoot(context).absolutePath}")
                    if (!status?.lastMessage.isNullOrBlank()) Text("Last: ${status?.lastMessage}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (saveMatrixConfig()) ServerCommands.start(context, "matrix") }) { Text("Save and start") }
                        OutlinedButton(onClick = { if (saveMatrixConfig()) ServerCommands.restart(context, "matrix") }) { Text("Save and restart") }
                        OutlinedButton(onClick = { ServerCommands.stop(context, "matrix") }) { Text("Stop") }
                    }
                    if (message.isNotBlank()) Mono(message)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(
                        value = serverName,
                        onValueChange = { serverName = it },
                        label = { Text("Matrix server name, for example matrix.example.com") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ToggleRow("Enable registration", "Requires a registration token. Keep disabled after creating users.", registrationEnabled) {
                        registrationEnabled = it
                    }
                    OutlinedTextField(
                        value = registrationToken,
                        onValueChange = { registrationToken = it },
                        label = { Text("Registration token") },
                        singleLine = true,
                        enabled = registrationEnabled,
                        modifier = Modifier.fillMaxWidth()
                    )
                    ToggleRow("Enable federation", "Only enable after public Matrix routing and backups are verified.", federationEnabled, accent = if (federationEnabled) palette.error else null) {
                        federationEnabled = it
                    }
                    if (federationEnabled) {
                        Text(
                            "Federation exposes this homeserver to other Matrix servers. Confirm DNS, Cloudflare routing, backups, and abuse controls first.",
                            color = palette.error,
                            style = MaterialTheme.typography.bodySmall,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                    Button(onClick = { saveMatrixConfig() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Save dendrite.yaml")
                    }
                }
            }
        }
    }
}

@Composable
private fun NextcloudScreen(context: Context, statuses: Map<String, ServiceStatus>) {
    val palette = statusPalette()
    val status = statuses["nextcloud"]
    val state = status?.state ?: ServiceState.Stopped
    val existing = remember { ServicePreferences.nextcloudSettings(context) }
    var enabled by remember { mutableStateOf(existing.enabled) }
    var adminUser by remember { mutableStateOf(existing.adminUser) }
    var adminPassword by remember { mutableStateOf(existing.adminPassword) }
    var trustedDomain by remember { mutableStateOf(existing.trustedDomain) }
    var phpMemoryLimit by remember { mutableStateOf(existing.phpMemoryLimit) }
    var message by remember { mutableStateOf("") }
    val binary = NativeBinaryLocator.fileFor(context, ServiceRegistry.byId("nextcloud")!!)
    val php = File(context.applicationInfo.nativeLibraryDir, "libphp.so")

    fun saveNextcloud(): Boolean {
        ServicePreferences.setNextcloudSettings(
            context,
            ServicePreferences.NextcloudSettings(
                enabled = enabled,
                installedVersion = existing.installedVersion,
                adminUser = adminUser,
                adminPassword = adminPassword,
                trustedDomain = trustedDomain,
                phpMemoryLimit = phpMemoryLimit
            )
        )
        message = "Saved Nextcloud settings"
        return true
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header("Nextcloud", "Experimental PHP + SQLite module") }
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(containerColor = palette.warn.copy(alpha = 0.12f))
            ) {
                Row(Modifier.padding(16.dp), verticalAlignment = Alignment.Top) {
                    Icon(Icons.Outlined.Warning, null, tint = palette.warn, modifier = Modifier.size(18.dp))
                    Spacer(Modifier.width(8.dp))
                    Text(
                        "Experimental and minimal/testing only. SQLite is not a production Nextcloud database. This service is off by default.",
                        color = palette.warn,
                        style = MaterialTheme.typography.bodySmall,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text("Runtime", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                        StatusChip(state, palette)
                    }
                    Mono("Wrapper: ${if (binary.exists()) binary.absolutePath else "missing libnextcloudd.so"}")
                    Mono("PHP executable: ${if (AppPaths.phpExecutable(context).exists()) AppPaths.phpExecutable(context).absolutePath else "missing libphp.so"}")
                    Mono("PHP runtime: ${AppPaths.phpRuntimeDir(context).absolutePath}")
                    Mono("PHP extensions: ${AppPaths.phpExtensionsDir(context).absolutePath}")
                    Mono("PHP preflight: ${PhpRuntimeInstaller.preflight(context) ?: "ok"}")
                    Mono("Server files: ${AppPaths.nextcloudAppDir(context).absolutePath}")
                    Mono("Data: ${AppPaths.nextcloudDataDir(context).absolutePath}")
                    OutlinedButton(onClick = {
                        message = runCatching { "Installed ${PhpRuntimeInstaller.installFromAssets(context, phpMemoryLimit).absolutePath}" }
                            .getOrElse { "PHP install failed: ${it.message ?: it.javaClass.simpleName}" }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Install packaged PHP runtime")
                    }
                    OutlinedButton(onClick = {
                        message = runCatching { PhpRuntimeInstaller.runModuleSelfCheck(context, phpMemoryLimit) }
                            .getOrElse { "PHP self-check failed: ${it.message ?: it.javaClass.simpleName}" }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Run PHP module self-check")
                    }
                    OutlinedButton(onClick = {
                        message = runCatching { "Installed ${NextcloudInstaller.installFromAssets(context).absolutePath}" }
                            .getOrElse { "Install failed: ${it.message ?: it.javaClass.simpleName}" }
                    }, modifier = Modifier.fillMaxWidth()) {
                        Text("Install packaged Nextcloud payload")
                    }
                    if (!status?.lastMessage.isNullOrBlank()) Text("Last: ${status?.lastMessage}", style = MaterialTheme.typography.bodySmall)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(onClick = { if (saveNextcloud()) ServerCommands.start(context, "nextcloud") }, enabled = enabled) { Text("Save and start") }
                        OutlinedButton(onClick = { if (saveNextcloud()) ServerCommands.restart(context, "nextcloud") }, enabled = enabled) { Text("Save and restart") }
                        OutlinedButton(onClick = { ServerCommands.stop(context, "nextcloud") }) { Text("Stop") }
                    }
                    if (message.isNotBlank()) Mono(message)
                }
            }
        }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    ToggleRow("Enable experimental Nextcloud", "Allows manual start. It is never started by default.", enabled) {
                        enabled = it
                    }
                    OutlinedTextField(
                        value = adminUser,
                        onValueChange = { adminUser = it },
                        label = { Text("Admin username") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = adminPassword,
                        onValueChange = { adminPassword = it },
                        label = { Text("Initial admin password") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = trustedDomain,
                        onValueChange = { trustedDomain = it },
                        label = { Text("Trusted domain") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    OutlinedTextField(
                        value = phpMemoryLimit,
                        onValueChange = { phpMemoryLimit = it },
                        label = { Text("PHP memory limit") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth()
                    )
                    Button(onClick = { saveNextcloud() }, modifier = Modifier.fillMaxWidth()) {
                        Text("Save Nextcloud settings")
                    }
                }
            }
        }
    }
}

@Composable
private fun StorageScreen(context: Context) {
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header("Storage", "App data, service data, logs, and public files") }
        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text("Directories", style = MaterialTheme.typography.titleMedium)
                    DirRow("Files", context.filesDir)
                    DirRow("Config", AppPaths.configDir(context))
                    DirRow("Data", AppPaths.dataDir(context))
                    DirRow("Logs", AppPaths.logsDir(context))
                    DirRow("Web root", AppPaths.webRoot(context))
                    DirRow("File root", AppPaths.filesRoot(context))
                    DirRow("Matrix", AppPaths.matrixRoot(context))
                    DirRow("Nextcloud", AppPaths.nextcloudRoot(context))
                }
            }
        }
    }
}

@Composable
private fun LogsScreen(logs: List<LogLine>) {
    val palette = statusPalette()
    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        item { Header("Logs", "Recent daemon and supervisor messages") }
        if (logs.isEmpty()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Text(
                        "No logs yet. Start a service to generate logs.",
                        Modifier.padding(16.dp),
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        } else {
            items(logs) { line -> LogRow(line, palette) }
        }
    }
}

@Composable
private fun SettingsScreen(context: Context) {
    val palette = statusPalette()
    var autostart by remember { mutableStateOf(ServicePreferences.autostartEnabled(context)) }
    var exposeLan by remember { mutableStateOf(ServicePreferences.exposeOnLan(context)) }
    var adminToken by remember { mutableStateOf(ServicePreferences.adminToken(context)) }
    var diagnosticsPath by remember { mutableStateOf("") }

    LazyColumn(
        modifier = Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item { Header("Settings", "Boot behavior, exposure, tokens, and diagnostics") }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp)) {
                    ToggleRow(
                        title = "Start default services on boot",
                        subtitle = "Uses BOOT_COMPLETED and starts the foreground supervisor.",
                        checked = autostart
                    ) {
                        autostart = it
                        ServicePreferences.setAutostartEnabled(context, it)
                    }
                }
            }
        }

        // Network exposure — explicit, warned, off by default.
        item {
            Card(
                Modifier.fillMaxWidth(),
                colors = CardDefaults.cardColors(
                    containerColor = if (exposeLan)
                        palette.error.copy(alpha = 0.10f) else MaterialTheme.colorScheme.surface
                )
            ) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    ToggleRow(
                        title = "Expose services on the local network",
                        subtitle = "Bind daemons to 0.0.0.0 instead of 127.0.0.1. Off keeps everything loopback-only.",
                        checked = exposeLan,
                        accent = if (exposeLan) palette.error else null
                    ) {
                        exposeLan = it
                        ServicePreferences.setExposeOnLan(context, it)
                    }
                    if (exposeLan) {
                        Row(verticalAlignment = Alignment.Top) {
                            Icon(Icons.Outlined.Warning, null, tint = palette.error, modifier = Modifier.size(18.dp))
                            Spacer(Modifier.width(8.dp))
                            Text(
                                "Services will be reachable by any device on the network. Make sure the admin token is set and you trust this network. Public internet access should still go through Cloudflare Tunnel, not a raw open port.",
                                style = MaterialTheme.typography.bodySmall,
                                color = palette.error
                            )
                        }
                        Button(onClick = { ServerCommands.restartAll(context) }, modifier = Modifier.fillMaxWidth()) {
                            Icon(Icons.Filled.Refresh, null, Modifier.size(18.dp))
                            Spacer(Modifier.width(6.dp)); Text("Restart running services to apply")
                        }
                    } else {
                        Text(
                            "Bind host: 127.0.0.1 (loopback). Restart services after changing this toggle.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Admin token", fontWeight = FontWeight.SemiBold)
                    Text(
                        "Required for protected daemon APIs and to unlock the web control panel.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Text(
                        "Full token stays visible here:",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                    Mono(adminToken)
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        OutlinedButton(onClick = {
                            adminToken = ServicePreferences.rotateAdminToken(context)
                        }) { Text("Rotate") }
                        OutlinedButton(onClick = { copyText(context, "PocketHost admin token", adminToken) }) { Text("Copy") }
                        TextButton(onClick = { AppPaths.ensure(context) }) { Text("Recreate dirs") }
                    }
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Diagnostics", fontWeight = FontWeight.SemiBold)
                    OutlinedButton(onClick = {
                        diagnosticsPath = runCatching { Diagnostics.createBundle(context).absolutePath }
                            .getOrElse { "Failed: ${it.message}" }
                    }) { Text("Create bundle") }
                    if (diagnosticsPath.isNotBlank()) Mono(diagnosticsPath)
                }
            }
        }

        item {
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text("Native library directory", style = MaterialTheme.typography.titleMedium)
                    Mono(context.applicationInfo.nativeLibraryDir)
                    Text(
                        "Daemons must be packaged here as lib<name>.so files.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------
// Reusable pieces
// ---------------------------------------------------------------------------

@Composable
private fun Header(title: String, subtitle: String) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(title, style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text(subtitle, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatBox(value: String, label: String, color: Color) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, fontSize = 30.sp, fontWeight = FontWeight.Bold, color = color)
        Text(label.uppercase(), fontSize = 10.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun StatusDot(state: ServiceState, palette: StatusPalette) {
    Box(Modifier.size(10.dp).background(palette.colorFor(state), CircleShape))
}

@Composable
private fun StatusChip(state: ServiceState, palette: StatusPalette) {
    val color = palette.colorFor(state)
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Box(Modifier.size(8.dp).background(color, CircleShape))
        Spacer(Modifier.width(6.dp))
        Text(state.label, color = color, fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun BindChip(exposed: Boolean, palette: StatusPalette) {
    val color = if (exposed) palette.error else palette.ok
    val label = if (exposed) "0.0.0.0 · network" else "127.0.0.1 · loopback"
    Row(
        verticalAlignment = Alignment.CenterVertically,
        modifier = Modifier
            .background(color.copy(alpha = 0.16f), RoundedCornerShape(999.dp))
            .padding(horizontal = 10.dp, vertical = 4.dp)
    ) {
        Text(label, color = color, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
    }
}

@Composable
private fun ExposureBanner(palette: StatusPalette) {
    Card(
        Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(containerColor = palette.error.copy(alpha = 0.12f))
    ) {
        Row(Modifier.padding(14.dp), verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Outlined.Warning, null, tint = palette.error, modifier = Modifier.size(20.dp))
            Spacer(Modifier.width(10.dp))
            Text(
                "Services are bound to 0.0.0.0 and reachable from the network.",
                color = palette.error,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

@Composable
private fun WebPanelCard(context: Context, hostRunning: Boolean) {
    val palette = statusPalette()
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(20.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(Modifier.width(8.dp))
                Text("Web control panel", style = MaterialTheme.typography.titleMedium, modifier = Modifier.weight(1f))
                StatusChip(if (hostRunning) ServiceState.Running else ServiceState.Stopped, palette)
            }
            Text(
                "Served by the Host API on http://${ServicePreferences.bindHost(context)}:8099. Unlock with the admin token.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            OutlinedButton(enabled = hostRunning, onClick = { openUrl(context, "http://127.0.0.1:8099/") }) {
                Icon(Icons.Outlined.OpenInNew, null, Modifier.size(18.dp))
                Spacer(Modifier.width(6.dp)); Text("Open in browser")
            }
        }
    }
}

@Composable
private fun ToggleRow(
    title: String,
    subtitle: String,
    checked: Boolean,
    accent: Color? = null,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Column(Modifier.weight(1f)) {
            Text(title, fontWeight = FontWeight.SemiBold, color = accent ?: MaterialTheme.colorScheme.onSurface)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}

@Composable
private fun RoutePicker(label: String, url: String, selected: String, onSelect: (String) -> Unit) {
    OutlinedButton(
        onClick = { onSelect(url) },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text("${if (selected == url) "Selected: " else ""}$label -> $url")
    }
}

@Composable
private fun RouteField(label: String, value: String, target: String, enabled: Boolean, onValueChange: (String) -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        OutlinedTextField(
            value = value,
            onValueChange = onValueChange,
            label = { Text(label) },
            singleLine = true,
            enabled = enabled,
            modifier = Modifier.fillMaxWidth()
        )
        Mono("Routes to $target")
    }
}
@Composable
private fun CompactServiceRow(spec: ServiceSpec, status: ServiceStatus?, palette: StatusPalette) {
    val state = status?.state ?: ServiceState.Stopped
    Card(Modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(14.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            StatusDot(state, palette)
            Spacer(Modifier.width(12.dp))
            Column(Modifier.weight(1f)) {
                Text(spec.displayName, fontWeight = FontWeight.SemiBold)
                Text(
                    buildString {
                        append(state.label)
                        spec.defaultPort?.let { append(" · :$it") }
                        uptimeText(status)?.let { up -> append(" · up $up") }
                    },
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            StatusChip(state, palette)
        }
    }
}

@Composable
private fun ServiceCard(context: Context, spec: ServiceSpec, status: ServiceStatus?, palette: StatusPalette) {
    val state = status?.state ?: ServiceState.Stopped
    val binary = NativeBinaryLocator.fileFor(context, spec)
    val bindHost = ServicePreferences.bindHost(context)

    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text(spec.displayName, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                    Text(spec.description, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                StatusChip(state, palette)
            }
            Mono("${binary.name} · ${if (binary.exists()) "available" else "missing"}")
            spec.defaultPort?.let { Mono("http://$bindHost:$it") }
            uptimeText(status)?.let {
                Text("Uptime: $it", style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (!status?.lastMessage.isNullOrBlank()) {
                Text(
                    "Last: ${status?.lastMessage}",
                    style = MaterialTheme.typography.bodySmall,
                    color = if (state == ServiceState.Failed || state == ServiceState.MissingBinary) palette.error
                    else MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            val active = state == ServiceState.Running || state == ServiceState.Degraded || state == ServiceState.Starting
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(onClick = { ServerCommands.start(context, spec.id) }, enabled = !active) { Text("Start") }
                OutlinedButton(onClick = { ServerCommands.stop(context, spec.id) }, enabled = active) { Text("Stop") }
                OutlinedButton(onClick = { ServerCommands.restart(context, spec.id) }, enabled = active) { Text("Restart") }
            }
        }
    }
}

@Composable
private fun DirRow(label: String, file: File) {
    Column {
        Text(label, fontWeight = FontWeight.SemiBold)
        Mono(file.absolutePath)
        Text(
            "${formatBytes(directorySize(file))} used · ${formatBytes(file.usableSpace)} free",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
    }
}

@Composable
private fun LogRow(line: LogLine, palette: StatusPalette) {
    val levelColor = when (line.level.uppercase()) {
        "ERROR" -> palette.error
        "WARN" -> palette.warn
        else -> palette.idle
    }
    Card(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(Modifier.size(8.dp).background(levelColor, CircleShape))
                Spacer(Modifier.width(8.dp))
                Text(
                    "${formatTime(line.timestampEpochMs)} · ${line.serviceId} · ${line.level}",
                    fontWeight = FontWeight.SemiBold,
                    style = MaterialTheme.typography.bodySmall
                )
            }
            Text(line.message, style = MaterialTheme.typography.bodySmall)
        }
    }
}

@Composable
private fun Mono(text: String) {
    Text(
        text,
        style = MaterialTheme.typography.bodySmall,
        fontFamily = FontFamily.Monospace,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )
}

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

private fun openUrl(context: Context, url: String) {
    runCatching {
        context.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url)).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }
}

private fun copyText(context: Context, label: String, text: String) {
    val clipboard = context.getSystemService(ClipboardManager::class.java)
    clipboard.setPrimaryClip(ClipData.newPlainText(label, text))
}

private fun importCloudflareCredential(context: Context, uri: Uri): File {
    AppPaths.ensure(context)
    val out = File(AppPaths.cloudflaredDir(context), "credentials.json")
    context.contentResolver.openInputStream(uri).use { input ->
        require(input != null) { "could not open selected file" }
        out.outputStream().use { output -> input.copyTo(output) }
    }
    val text = out.readText().take(64 * 1024)
    val missing = listOf("AccountTag", "TunnelID", "TunnelSecret").filter { !text.contains("\"$it\"") }
    require(missing.isEmpty()) { "credentials JSON missing: ${missing.joinToString(", ")}" }
    return out
}

private fun latestQuickTunnelUrl(logs: List<LogLine>): String? {
    val pattern = Regex("""https://[A-Za-z0-9.-]+\.trycloudflare\.com""")
    return logs.firstOrNull { it.serviceId == "cloudflared" && pattern.containsMatchIn(it.message) }
        ?.let { pattern.find(it.message)?.value }
}

private fun uptimeText(status: ServiceStatus?): String? {
    if (status == null) return null
    if (status.state != ServiceState.Running && status.state != ServiceState.Degraded) return null
    val started = status.startedAtEpochMs ?: return null
    val seconds = ((System.currentTimeMillis() - started) / 1000).coerceAtLeast(0)
    val d = seconds / 86400
    val h = (seconds % 86400) / 3600
    val m = (seconds % 3600) / 60
    val s = seconds % 60
    return when {
        d > 0 -> "${d}d ${h}h"
        h > 0 -> "${h}h ${m}m"
        m > 0 -> "${m}m ${s}s"
        else -> "${s}s"
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
