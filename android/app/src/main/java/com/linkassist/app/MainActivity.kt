@file:OptIn(ExperimentalFoundationApi::class)

package com.linkassist.app

import android.Manifest
import android.app.Activity
import android.content.ContentUris
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.Settings
import android.webkit.MimeTypeMap
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.Image
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ChatBubbleOutline
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.Description
import androidx.compose.material.icons.outlined.Devices
import androidx.compose.material.icons.outlined.ExpandLess
import androidx.compose.material.icons.outlined.ExpandMore
import androidx.compose.material.icons.outlined.FolderOpen
import androidx.compose.material.icons.outlined.Image
import androidx.compose.material.icons.outlined.Link
import androidx.compose.material.icons.outlined.NotificationsNone
import androidx.compose.material.icons.outlined.QrCodeScanner
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.outlined.Settings
import androidx.compose.material.icons.outlined.Smartphone
import androidx.compose.material.icons.outlined.Sms
import androidx.compose.material.icons.outlined.Videocam
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.journeyapps.barcodescanner.ScanContract
import com.journeyapps.barcodescanner.ScanOptions
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import java.net.Proxy
import java.util.concurrent.TimeUnit

// ---------- 青绿 / 石墨主题 ----------
private val Bg = Color(0xFF101716)
private val CardBg = Color(0xFF192321)
private val CardBg2 = Color(0xFF23332F)
private val CodeBg = Color(0xFF111C19)
private val Muted = Color(0xFFA8BBB5)
private val Faint = Color(0xFF8BA39A)
private val Line = Color(0xFF30443D)
private val Accent = Color(0xFF56D6BC)
private val Accent2 = Color(0xFF89D7B2)
private val BubbleOutBrush = Brush.horizontalGradient(listOf(Color(0xFF18604F), Color(0xFF1E7064)))
private val Green = Color(0xFF76DEAE)
private val Orange = Color(0xFFE9C17A)
private val Red = Color(0xFFFF9A95)

class MainActivity : ComponentActivity() {
    private var settingsRequest by mutableStateOf(0)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        try {
            ContextCompat.startForegroundService(this, Intent(this, LinkService::class.java))
        } catch (_: Exception) {
        }
        if (intent.getBooleanExtra("open_settings", false)) settingsRequest++
        // Runtime permissions are requested only after an explicit action in Scan or Settings.
        UpdateWorker.schedule(this, checkAtStartup = savedInstanceState == null)
        setContent { App(settingsRequest) }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.getBooleanExtra("open_settings", false)) settingsRequest++
    }
}

// ============================================================ 主框架:底部导航
@Composable
fun App(settingsRequest: Int = 0) {
    MaterialTheme(
        colorScheme = darkColorScheme(
            background = Bg, surface = CardBg, primary = Accent, onPrimary = CodeBg,
            secondary = Accent2, onSecondary = CodeBg, primaryContainer = CardBg2,
            onPrimaryContainer = Accent, surfaceVariant = CardBg2, onSurfaceVariant = Muted,
            outline = Line, error = Red, onSurface = Color(0xFFECF4F0),
            onBackground = Color(0xFFECF4F0),
        ),
    ) {
        val ctx = LocalContext.current
        var tab by rememberSaveable { mutableStateOf(if (Prefs.isConfigured(ctx)) 0 else 2) }
        LaunchedEffect(settingsRequest) { if (settingsRequest > 0) tab = 3 }

        val msgs = remember { mutableStateListOf<Msg>().apply { addAll(LinkService.messages) } }
        var connected by remember { mutableStateOf(LinkService.connected) }
        var status by remember { mutableStateOf(LinkService.statusText) }
        var input by remember { mutableStateOf("") }
        var showProfileEdit by remember { mutableStateOf<PcProfile?>(null) }
        var showProfileAdd by remember { mutableStateOf(false) }

        val transfers = remember { mutableStateListOf<Transfer>().apply { addAll(LinkService.transfers) } }
        val peers = remember { mutableStateListOf<Pair<String, String>>().apply { addAll(LinkService.peers) } }
        val profiles = remember {
            mutableStateListOf<ProfileState>().apply {
                addAll(LinkService.profileStates.ifEmpty {
                    Prefs.profiles(ctx).map { ProfileState(it.id, it.name, it.host, it.port, false, "等待连接") }
                })
            }
        }
        var hubOn by remember { mutableStateOf(LinkService.hubRunning) }
        var hubPeers by remember { mutableStateOf(LinkService.hubPeerCount) }
        var fileTick by remember { mutableStateOf(0) }

        val picker = rememberLauncherForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (uris.isNotEmpty()) {
                if (!LinkService.connected && LinkService.hubPeerCount == 0) {
                    toast(ctx, "请先连接设备，再选择文件发送")
                } else {
                    uris.distinct().forEach { uri ->
                        try {
                            ctx.contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
                        } catch (_: Exception) {
                        }
                        LinkService.sendFile(ctx, uri)
                    }
                    toast(ctx, "已加入 ${uris.distinct().size} 个文件")
                }
            }
        }
        val mediaPicker = rememberLauncherForActivityResult(ActivityResultContracts.PickVisualMedia()) { uri ->
            uri?.let { u ->
                val name = ctx.contentResolver.query(u, null, null, null, null)?.use { c ->
                    val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
                    if (c.moveToFirst() && i >= 0) c.getString(i) else null
                } ?: "photo_${System.currentTimeMillis()}.jpg"
                val mime = ctx.contentResolver.getType(u) ?: ""
                val isVideo = mime.startsWith("video/") ||
                    name.substringAfterLast('.', "").lowercase() in setOf("mp4", "mov", "avi", "mkv", "webm")
                LinkService.sendMedia(ctx, u, name, isVideo)
                Toast.makeText(ctx, "正在发送${if (isVideo) "视频" else "图片"}…", Toast.LENGTH_SHORT).show()
            }
        }

        val receivedList = remember { mutableStateListOf<ReceivedFile>() }
        var receivedMap by remember { mutableStateOf(mapOf<String, ReceivedFile>()) }
        var zoomFile by remember { mutableStateOf<ReceivedFile?>(null) }
        val refreshReceived = {
            Thread {
                val list = listReceived(ctx)
                Handler(Looper.getMainLooper()).post {
                    receivedList.clear(); receivedList.addAll(list)
                    receivedMap = list.associateBy { it.name }
                }
            }.start()
            Unit
        }
        LaunchedEffect(fileTick) { refreshReceived() }
        DisposableEffect(Unit) {
            LinkService.onEvent = { m ->
                Handler(Looper.getMainLooper()).post {
                    msgs.add(m)
                    while (msgs.size > 500) msgs.removeAt(0)
                }
            }
            LinkService.onStatus = { c, s ->
                Handler(Looper.getMainLooper()).post {
                    connected = c
                    status = s
                }
            }
            LinkService.onTransfer = { t ->
                Handler(Looper.getMainLooper()).post {
                    val i = transfers.indexOfFirst { it.id == t.id }
                    if (i >= 0) transfers[i] = t else transfers.add(t)
                    if (t.status == "complete") fileTick++
                }
            }
            LinkService.onPeers = {
                Handler(Looper.getMainLooper()).post {
                    peers.clear(); peers.addAll(LinkService.peers)
                }
            }
            LinkService.onProfiles = {
                Handler(Looper.getMainLooper()).post {
                    profiles.clear(); profiles.addAll(LinkService.profileStates)
                }
            }
            LinkService.onHub = { running, n ->
                Handler(Looper.getMainLooper()).post {
                    hubOn = running
                    hubPeers = n
                }
            }
            LinkService.onCleared = {
                Handler(Looper.getMainLooper()).post { msgs.clear() }
            }
            LinkService.onMediaSaved = {
                Handler(Looper.getMainLooper()).post { fileTick++ }
            }
            onDispose {
                LinkService.onEvent = null
                LinkService.onStatus = null
                LinkService.onTransfer = null
                LinkService.onPeers = null
                LinkService.onProfiles = null
                LinkService.onHub = null
                LinkService.onCleared = null
                LinkService.onMediaSaved = null
            }
        }

        Scaffold(
            containerColor = Bg,
            bottomBar = {
                NavigationBar(containerColor = CardBg, contentColor = Muted, tonalElevation = 0.dp) {
                    val items = listOf("消息", "文件", "设备", "设置")
                    val icons = listOf(Icons.Outlined.ChatBubbleOutline, Icons.Outlined.FolderOpen,
                        Icons.Outlined.Devices, Icons.Outlined.Settings)
                    items.forEachIndexed { i, label ->
                        NavigationBarItem(
                            selected = tab == i,
                            onClick = { tab = i },
                            label = { Text(label, fontSize = 12.sp) },
                            icon = { Icon(icons[i], contentDescription = label, modifier = Modifier.size(23.dp)) },
                            colors = NavigationBarItemDefaults.colors(
                                selectedTextColor = Accent,
                                selectedIconColor = Accent,
                                indicatorColor = Accent.copy(alpha = 0.14f),
                            ),
                        )
                    }
                }
            },
        ) { pad ->
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(Bg)
                    .padding(pad),
            ) {
                // ---------- 顶栏 ----------
                Row(
                    Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Box(
                        Modifier.size(40.dp)
                            .background(Brush.linearGradient(listOf(Accent, Accent2)), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center,
                    ) { Icon(Icons.Outlined.Link, contentDescription = null, tint = CodeBg, modifier = Modifier.size(24.dp)) }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text("互传助手", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                        Spacer(Modifier.height(2.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                Modifier.size(7.dp)
                                    .background(if (connected || hubPeers > 0) Green else Faint, CircleShape),
                            )
                            Spacer(Modifier.width(6.dp))
                            Text(
                                when {
                                    hubPeers > 0 -> "手机互连 · $hubPeers 台在线"
                                    profiles.isEmpty() && !connected -> "尚未配对 · 扫码即可开始"
                                    else -> status
                                },
                                fontSize = 12.sp, color = Muted, maxLines = 1, overflow = TextOverflow.Ellipsis,
                            )
                        }
                    }
                }
                HorizontalDivider(color = Line)

                when (tab) {
                    0 -> MessagesPage(
                        msgs = msgs, peers = peers, input = input,
                        online = connected || hubPeers > 0, configured = profiles.isNotEmpty(),
                        onConnect = { tab = 2 },
                        onInput = { input = it }, onSend = {
                            val t = input.trim()
                            if (t.isNotEmpty()) {
                                LinkService.sendChat(t)
                                input = ""
                            }
                        },
                        fileMap = receivedMap,
                        onPickMedia = {
                            mediaPicker.launch(androidx.activity.result.PickVisualMediaRequest(
                                ActivityResultContracts.PickVisualMedia.ImageAndVideo))
                        },
                        refresh = refreshReceived,
                    )
                    1 -> FilesPage(
                        transfers = transfers,
                        fileTick = fileTick,
                        online = connected || hubPeers > 0,
                        onPick = { picker.launch(arrayOf("*/*")) },
                        onConnect = { tab = 2 },
                    )
                    2 -> DevicesPage(
                        profiles = profiles,
                        hubOn = hubOn,
                        hubPeers = hubPeers,
                        onAdd = { showProfileAdd = true },
                        onEdit = { showProfileEdit = it },
                        onDelete = { p ->
                            val rest = Prefs.profiles(ctx).filter { it.id != p.id }
                            Prefs.saveProfiles(ctx, rest)
                            LinkService.instance?.syncProfiles()
                            toast(ctx, "已删除 ${p.name}")
                        },
                    )
                    3 -> SettingsPage()
                }
            }
        }

        if (showProfileEdit != null) ProfileDialog(initial = showProfileEdit, onClose = { showProfileEdit = null })
        if (showProfileAdd) ProfileDialog(initial = null, onClose = { showProfileAdd = false })
    }
}

// ============================================================ 页面 0:消息
@Composable
fun MessagesPage(
    msgs: List<Msg>,
    peers: List<Pair<String, String>>,
    input: String,
    online: Boolean,
    configured: Boolean,
    onConnect: () -> Unit,
    fileMap: Map<String, ReceivedFile>,
    onPickMedia: () -> Unit,
    refresh: () -> Unit,
    onInput: (String) -> Unit,
    onSend: () -> Unit,
) {
    val listState = rememberLazyListState()
    LaunchedEffect(msgs.size) {
        if (msgs.isNotEmpty()) listState.animateScrollToItem(msgs.size - 1)
    }
    var zoom by remember { mutableStateOf<ReceivedFile?>(null) }
    var selectedPeer by remember { mutableStateOf(LinkService.selectedPeerId) }
    LaunchedEffect(peers.toList()) {
        if (selectedPeer != null && peers.none { it.first == selectedPeer }) {
            selectedPeer = null
            LinkService.selectedPeerId = null
        }
    }
    Column(Modifier.fillMaxSize().imePadding()) {
        if (!online) {
            Row(Modifier.fillMaxWidth().background(CardBg2).padding(horizontal = 16.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                Text(if (configured) "设备离线，重连后可继续互传" else "先配对设备，即可互传消息与文件",
                    fontSize = 12.sp, color = Muted, modifier = Modifier.weight(1f))
                TextButton(onClick = onConnect) { Text(if (configured) "查看设备" else "去连接") }
            }
        }
        if (peers.isNotEmpty()) {
            Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text("发送给", color = Muted, fontSize = 12.sp)
                Spacer(Modifier.width(8.dp))
                Row(Modifier.weight(1f).horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    FilterChip(selected = selectedPeer == null,
                        onClick = { selectedPeer = null; LinkService.selectedPeerId = null }, label = { Text("全部设备") })
                    peers.forEach { (id, name) ->
                        FilterChip(selected = selectedPeer == id,
                            onClick = { selectedPeer = id; LinkService.selectedPeerId = id },
                            label = { Text(name, maxLines = 1) })
                    }
                }
            }
            HorizontalDivider(color = Line)
        }

        LazyColumn(
            state = listState,
            modifier = Modifier.weight(1f).fillMaxWidth(),
            contentPadding = PaddingValues(vertical = 12.dp, horizontal = 12.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            if (msgs.isEmpty()) item {
                EmptyHint()
                if (!configured) Button(onClick = onConnect, modifier = Modifier.fillMaxWidth()) { Text("前往设备页扫码连接") }
            }
            items(msgs, key = { it.id }) { m ->
                Box(Modifier.animateItem()) { MsgItem(m, fileMap) { f -> zoom = f } }
            }
        }

        HorizontalDivider(color = Line)

        Row(Modifier.fillMaxWidth().padding(12.dp), verticalAlignment = Alignment.Bottom) {
            IconButton(onClick = onPickMedia, enabled = online) {
                Icon(Icons.Outlined.Image, contentDescription = "发送图片或视频", tint = if (online) Accent else Faint)
            }
            OutlinedTextField(
                value = input,
                onValueChange = onInput,
                modifier = Modifier.weight(1f),
                placeholder = { Text("输入消息…", color = Faint, fontSize = 14.sp) },
                maxLines = 3,
                shape = RoundedCornerShape(14.dp),
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = onSend,
                enabled = online && input.isNotBlank(),
                shape = RoundedCornerShape(14.dp),
                contentPadding = PaddingValues(horizontal = 20.dp, vertical = 14.dp),
            ) { Text("发送") }
        }

        // 图片全屏查看
        zoom?.let { f ->
            androidx.compose.ui.window.Dialog(
                onDismissRequest = { zoom = null },
                properties = androidx.compose.ui.window.DialogProperties(usePlatformDefaultWidth = false),
            ) {
                val ctx2 = LocalContext.current
                val big = remember(f.name) {
                    decodeScaled(ctx2, f.uri, 1600)
                        ?: decodeFileScaled(java.io.File(
                            android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS),
                            "LinkAssist/${f.name}"), 1600)
                }
                Box(
                    Modifier.fillMaxSize().background(Color.Black)
                        .clickable { zoom = null },
                    contentAlignment = Alignment.Center,
                ) {
                    if (big != null) {
                        androidx.compose.foundation.Image(
                            bitmap = big.asImageBitmap(),
                            contentDescription = f.name,
                            modifier = Modifier.fillMaxWidth(),
                        )
                    } else {
                        Text("无法显示 ${f.name}", color = Color.White)
                    }
                }
            }
        }
    }
}

// ============================================================ 页面 1:文件
data class ReceivedFile(val uri: Uri, val name: String, val size: Long, val added: Long, val mime: String)

@Composable
fun FilesPage(transfers: List<Transfer>, fileTick: Int, online: Boolean, onPick: () -> Unit, onConnect: () -> Unit) {
    val ctx = LocalContext.current
    var query by rememberSaveable { mutableStateOf("") }
    var selected by remember { mutableStateOf(setOf<String>()) }
    var received by remember { mutableStateOf<List<ReceivedFile>>(emptyList()) }
    var viewer by remember { mutableStateOf<ReceivedFile?>(null) }
    var viewerText by remember { mutableStateOf("") }
    var viewerLoading by remember { mutableStateOf(false) }

    LaunchedEffect(fileTick) {
        received = withContext(Dispatchers.IO) { listReceived(ctx) }
        selected = selected.intersect(received.map { it.uri.toString() }.toSet())
    }

    fun open(f: ReceivedFile) {
        val ext = f.name.substringAfterLast('.', "").lowercase(Locale.getDefault())
        val textLike = ext in setOf("txt", "md", "log", "json", "csv", "xml", "html", "ini", "cfg") || f.mime.startsWith("text/")
        val hasSystemHandler = f.mime.isNotBlank() && ext !in setOf("txt", "md", "log")
        if (!textLike && hasSystemHandler && openReceived(ctx, f)) return
        // 应用内查看(文本/未知类型兜底)
        viewer = f
        viewerLoading = true
        viewerText = ""
        Thread {
            val sb = StringBuilder()
            try {
                ctx.contentResolver.openInputStream(f.uri)?.use { ins ->
                    val buf = ByteArray(8192)
                    var total = 0
                    var n: Int
                    while (ins.read(buf).also { n = it } > 0 && total < 128 * 1024) {
                        sb.append(String(buf, 0, n, Charsets.UTF_8))
                        total += n
                    }
                    if (total >= 128 * 1024) sb.append("\n…(仅显示前 128 KB)")
                } ?: sb.append("(无法读取文件)")
            } catch (e: Exception) {
                sb.append("(读取失败:${e.message})")
            }
            Handler(Looper.getMainLooper()).post {
                viewerText = sb.toString()
                viewerLoading = false
            }
        }.start()
    }

    val filtered = received.filter { it.name.contains(query.trim(), ignoreCase = true) }
    fun toggle(file: ReceivedFile) {
        val id = file.uri.toString()
        selected = if (id in selected) selected - id else selected + id
    }
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
        item {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("文件", fontSize = 24.sp, fontWeight = FontWeight.Bold)
                    Text("多选发送 · 原文件传输", fontSize = 12.sp, color = Muted)
                }
                Button(onClick = if (online) onPick else onConnect, shape = RoundedCornerShape(12.dp)) {
                    Text(if (online) "选择多个文件" else "先连接设备")
                }
            }
        }
        item {
            OutlinedTextField(value = query, onValueChange = { query = it }, singleLine = true,
                placeholder = { Text("搜索收到的文件") }, leadingIcon = { Icon(Icons.Outlined.Search, contentDescription = null) },
                trailingIcon = { if (query.isNotEmpty()) IconButton(onClick = { query = "" }) { Icon(Icons.Outlined.Close, contentDescription = "清空搜索") } },
                modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(14.dp))
        }
        item {
            Text("收到的文件 · ${received.size}", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            Text("Download/LinkAssist/ · 长按进入多选", fontSize = 11.sp, color = Muted)
        }
        if (selected.isNotEmpty()) item {
            Surface(color = CardBg2, shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("已选 ${selected.size} 个文件", color = Accent, fontWeight = FontWeight.SemiBold)
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        TextButton(onClick = { selected = filtered.map { it.uri.toString() }.toSet() }) { Text("选择搜索结果") }
                        TextButton(onClick = { selected = emptySet() }) { Text("取消") }
                        Spacer(Modifier.weight(1f))
                        Button(onClick = {
                            val files = received.filter { it.uri.toString() in selected }
                            files.forEach { LinkService.sendFile(ctx, it.uri) }
                            toast(ctx, "已加入 ${files.size} 个文件")
                            selected = emptySet()
                        }, enabled = online) { Text("发送") }
                    }
                }
            }
        }
        if (filtered.isEmpty()) item {
            Column(Modifier.fillMaxWidth().padding(vertical = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.FolderOpen, contentDescription = null, tint = Faint, modifier = Modifier.size(40.dp))
                Spacer(Modifier.height(10.dp))
                Text(if (query.isBlank()) "等待你的第一份文件" else "没有匹配的文件", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text(if (query.isBlank()) "其他设备发来的文件会出现在这里，点击即可打开。" else "试试文件名的一部分，或清空搜索。",
                    color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
        }
        items(filtered, key = { it.uri.toString() }) { file ->
            val checked = file.uri.toString() in selected
            Card(colors = CardDefaults.cardColors(containerColor = if (checked) CardBg2 else CardBg),
                shape = RoundedCornerShape(14.dp),
                modifier = Modifier.fillMaxWidth().combinedClickable(
                    onClick = { if (selected.isNotEmpty()) toggle(file) else open(file) }, onLongClick = { toggle(file) },
                )) {
                Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                    if (selected.isNotEmpty()) Checkbox(checked = checked, onCheckedChange = { toggle(file) })
                    else Box(Modifier.size(42.dp).background(Accent.copy(alpha = .1f), RoundedCornerShape(12.dp)),
                        contentAlignment = Alignment.Center) {
                        Text(fileIcon(file.name), color = Accent, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                    Spacer(Modifier.width(10.dp))
                    Column(Modifier.weight(1f)) {
                        Text(file.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold, maxLines = 2, overflow = TextOverflow.Ellipsis)
                        Spacer(Modifier.height(3.dp))
                        Text("${fmtSize(file.size)} · ${fmtTime(file.added)}", fontSize = 11.sp, color = Muted)
                    }
                }
            }
        }
        item {
            HorizontalDivider(color = Line, modifier = Modifier.padding(vertical = 4.dp))
            Text("最近传输", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
        }
        if (transfers.isEmpty()) item { Text("发送或接收文件后，在这里查看进度。", color = Muted, fontSize = 12.sp) }
        items(transfers.takeLast(20).reversed(), key = { it.id }) { transfer ->
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(12.dp)) {
                Column(Modifier.fillMaxWidth().padding(12.dp), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    Text(transfer.name, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("${transferLabel(transfer.status)} · ${transfer.progress.coerceIn(0, 100)}%",
                        color = if (transfer.status == "failed") Red else Muted, fontSize = 11.sp)
                    LinearProgressIndicator(progress = { transfer.progress.coerceIn(0, 100) / 100f },
                        modifier = Modifier.fillMaxWidth().height(4.dp),
                        color = if (transfer.status == "failed") Red else Accent, trackColor = Line)
                    if (transfer.status == "failed") Text(transfer.error.ifBlank { "传输失败，请确认连接后重新发送" },
                        color = Red, fontSize = 11.sp, maxLines = 2, overflow = TextOverflow.Ellipsis)
                }
            }
        }
    }

    // 应用内文件查看器
    viewer?.let { f ->
        AlertDialog(
            onDismissRequest = { viewer = null },
            title = { Text(f.name, maxLines = 2, overflow = TextOverflow.Ellipsis) },
            text = {
                if (viewerLoading) {
                    Text("读取中…", color = Muted, fontSize = 13.sp)
                } else {
                    LazyColumn {
                        item {
                            Text(
                                viewerText.ifBlank { "(空文件)" },
                                fontFamily = FontFamily.Monospace,
                                fontSize = 12.sp, lineHeight = 17.sp,
                            )
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { viewer = null }) { Text("关闭") } },
            dismissButton = {
                TextButton(onClick = {
                    openReceived(ctx, f)
                }) { Text("用其他应用打开") }
            },
        )
    }
}

private fun fileIcon(name: String): String {
    val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
    return ext.takeIf { it.isNotEmpty() && it.length <= 4 }?.uppercase(Locale.ROOT) ?: "FILE"
}

private fun fmtSize(n: Long): String {
    if (n < 1024) return "$n B"
    if (n < 1024 * 1024) return String.format(Locale.getDefault(), "%.1f KB", n / 1024f)
    return String.format(Locale.getDefault(), "%.1f MB", n / 1024f / 1024f)
}

private fun listReceived(ctx: Context): List<ReceivedFile> {
    val out = mutableListOf<ReceivedFile>()
    if (Build.VERSION.SDK_INT >= 29) {
        try {
            val proj = arrayOf(
                MediaStore.MediaColumns._ID,
                MediaStore.MediaColumns.DISPLAY_NAME,
                MediaStore.MediaColumns.SIZE,
                MediaStore.MediaColumns.DATE_ADDED,
                MediaStore.MediaColumns.MIME_TYPE,
            )
            ctx.contentResolver.query(
                MediaStore.Downloads.EXTERNAL_CONTENT_URI,
                proj,
                "${MediaStore.MediaColumns.RELATIVE_PATH}=?",
                arrayOf("Download/LinkAssist/"),
                "${MediaStore.MediaColumns.DATE_ADDED} DESC",
            )?.use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    out += ReceivedFile(
                        uri = ContentUris.withAppendedId(MediaStore.Downloads.EXTERNAL_CONTENT_URI, id),
                        name = c.getString(1) ?: "未命名",
                        size = c.getLong(2),
                        added = c.getLong(3) * 1000L,
                        mime = c.getString(4) ?: "",
                    )
                }
            }
        } catch (_: Exception) {
        }
    } else {
        try {
            val dir = java.io.File(ctx.getExternalFilesDir(null), "Download")
            dir.listFiles()?.sortedByDescending { it.lastModified() }?.forEach {
                out += ReceivedFile(Uri.fromFile(it), it.name, it.length(), it.lastModified(), "")
            }
        } catch (_: Exception) {
        }
    }
    return out
}

/** 用系统应用打开文件;返回是否成功 */
fun openReceived(ctx: Context, f: ReceivedFile): Boolean {
    val ext = f.name.substringAfterLast('.', "").lowercase(Locale.getDefault())
    val mime = f.mime.ifBlank {
        MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext) ?: "*/*"
    }
    return try {
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(f.uri, mime)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
        true
    } catch (_: Exception) {
        try {
            ctx.startActivity(
                Intent(Intent.ACTION_VIEW).apply {
                    setDataAndType(f.uri, "*/*")
                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
                },
            )
            true
        } catch (_: Exception) {
            false
        }
    }
}

private fun transferLabel(s: String): String = when (s) {
    "offered" -> "等待上传"; "uploading" -> "上传中"; "downloading" -> "下载中"
    "complete" -> "已完成"; "failed" -> "失败"; "cancelled" -> "已取消"
    else -> s
}

// ============================================================ 扫码与确认配对
private val pairingClient by lazy {
    OkHttpClient.Builder()
        .proxy(Proxy.NO_PROXY)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(false) // A one-time secret must not be automatically replayed.
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .build()
}

private suspend fun requestPairing(ctx: Context, payload: Pairing.Payload): PcProfile {
    val responseProfile = withContext(Dispatchers.IO) {
        val body = JSONObject().put("key", payload.key).put("deviceName", Build.MODEL).toString()
        val request = Request.Builder()
            .url("http://${payload.host}:${payload.port}/api/pair")
            .post(body.toRequestBody("application/json; charset=utf-8".toMediaType()))
            .build()
        try {
            pairingClient.newCall(request).execute().use { response ->
                if (response.code != 200) throw IllegalArgumentException(when (response.code) {
                    400, 401, 403, 409, 410 -> "连接码已失效或已使用，请在对方设备刷新后重新扫码"
                    404 -> "对方版本不支持扫码配对，请更新或使用手动连接"
                    429 -> "配对请求过于频繁，请稍后再试"
                    in 300..399 -> "配对服务返回了重定向，已阻止连接"
                    else -> "配对失败（HTTP ${response.code}），请重新生成连接码"
                })
                val source = response.body?.source() ?: throw IllegalArgumentException("配对服务返回空响应")
                source.request(16_385)
                require(source.buffer.size <= 16_384) { "配对响应过大，已拒绝" }
                val json = try { JSONObject(source.readUtf8()) } catch (_: Exception) {
                    throw IllegalArgumentException("配对服务响应格式不正确")
                }
                val token = json.opt("token") as? String ?: ""
                val name = json.opt("name") as? String ?: ""
                val portNumber = json.opt("port") as? Number
                val port = portNumber?.toInt() ?: 0
                require(Pairing.isSessionToken(token) && Pairing.isSafeName(name) &&
                    port in 1..65535 && portNumber?.toDouble() == port.toDouble()) {
                    "配对服务响应无效，未保存连接"
                }
                PcProfile("", name.trim(), payload.host, port, token)
            }
        } catch (_: java.io.IOException) {
            // Exception messages from HTTP clients may contain URLs. Do not surface or log them.
            throw IllegalArgumentException("无法连接对方，请确认同一局域网、服务已开启；重试前请刷新二维码")
        }
    }
    val list = Prefs.profiles(ctx).toMutableList()
    val existing = list.firstOrNull { it.host == responseProfile.host && it.port == responseProfile.port }
        ?: list.firstOrNull { it.host == payload.host && it.port == payload.port }
    val profile = responseProfile.copy(id = existing?.id ?: Prefs.newId())
    list.removeAll { it.id == profile.id || (it.host == profile.host && it.port == profile.port) }
    list.add(profile)
    Prefs.saveProfiles(ctx, list)
    LinkService.instance?.syncProfiles()
    return profile
}

@Composable
private fun PairingCard(onManual: () -> Unit) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    val scope = rememberCoroutineScope()
    var pending by remember { mutableStateOf<Pairing.Payload?>(null) }
    var message by remember { mutableStateOf<String?>(null) }
    var pairing by remember { mutableStateOf(false) }
    var readingPhoto by remember { mutableStateOf(false) }
    var showPaste by remember { mutableStateOf(false) }
    var pasted by remember { mutableStateOf("") }
    var alternatives by rememberSaveable { mutableStateOf(false) }
    var permissionDenied by remember { mutableStateOf(false) }

    fun accept(raw: String) {
        try {
            pending = Pairing.parse(raw)
            message = null
            pasted = ""
            showPaste = false
        } catch (error: IllegalArgumentException) {
            message = error.message ?: "这不是有效的 LinkAssist 连接码"
        }
    }
    val scanner = rememberLauncherForActivityResult(ScanContract()) { result ->
        val contents = result.contents
        if (contents == null) {
            message = "已取消扫码；也可从相册识别或粘贴连接码"
        } else {
            accept(contents)
        }
    }
    fun startScanner() {
        try {
            scanner.launch(ScanOptions().apply {
                setDesiredBarcodeFormats(ScanOptions.QR_CODE)
                setCaptureActivity(QrScannerActivity::class.java)
                setPrompt("扫描 LinkAssist 连接码 · 识别后仍需确认设备")
                setBeepEnabled(false)
                setOrientationLocked(false)
                setBarcodeImageEnabled(false)
                setTimeout(60_000)
            })
        } catch (_: Exception) {
            message = "无法打开相机，请使用相册或粘贴连接码"
        }
    }
    val cameraPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        permissionDenied = !granted
        if (granted) startScanner() else {
            alternatives = true
            message = "相机权限未授予。其他功能不受影响，可从相册识别或粘贴连接码。"
        }
    }
    val photoPicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) scope.launch {
            readingPhoto = true
            try {
                val raw = withContext(Dispatchers.IO) { readPairingQr(ctx, uri) }
                if (raw == null) message = "图片中未识别到二维码，请选择清晰的原图" else accept(raw)
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                message = "无法读取这张图片，请换一张或粘贴连接码"
            } finally {
                readingPhoto = false
            }
        }
    }

    Card(colors = CardDefaults.cardColors(containerColor = CardBg2), shape = RoundedCornerShape(22.dp)) {
        Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Icon(Icons.Outlined.QrCodeScanner, contentDescription = null, tint = Accent, modifier = Modifier.size(34.dp))
            Text("连接，从扫码开始", fontSize = 22.sp, fontWeight = FontWeight.Bold)
            Text("在电脑端打开连接二维码，或让另一台手机开启“手机互连”。两台设备需在同一局域网。",
                color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
            Button(
                onClick = {
                    message = null
                    if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) ==
                        android.content.pm.PackageManager.PERMISSION_GRANTED) startScanner()
                    else cameraPermission.launch(Manifest.permission.CAMERA)
                },
                modifier = Modifier.fillMaxWidth().heightIn(min = 52.dp),
                enabled = !readingPhoto && !pairing,
                shape = RoundedCornerShape(14.dp),
            ) {
                Icon(Icons.Outlined.QrCodeScanner, contentDescription = null)
                Spacer(Modifier.width(10.dp))
                Text("扫码连接", fontSize = 16.sp, fontWeight = FontWeight.SemiBold)
            }
            TextButton(onClick = { alternatives = !alternatives }, modifier = Modifier.fillMaxWidth()) {
                Text("其他连接方式")
                Icon(if (alternatives) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
            }
            if (alternatives) {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { photoPicker.launch("image/*") }, enabled = !readingPhoto,
                        modifier = Modifier.weight(1f)) { Text(if (readingPhoto) "识别中…" else "从相册识别") }
                    OutlinedButton(onClick = { pasted = ""; showPaste = true }, modifier = Modifier.weight(1f)) {
                        Text("粘贴连接码")
                    }
                }
                TextButton(onClick = onManual, modifier = Modifier.fillMaxWidth()) { Text("手动输入地址与备用配对码") }
            }
            if (readingPhoto) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
            message?.let { Text(it, color = Orange, fontSize = 12.sp, lineHeight = 19.sp) }
            if (permissionDenied) TextButton(onClick = {
                openSettingsSafely(ctx, Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
            }) { Text("前往系统设置管理相机权限") }
            Text("仅识别 LinkAssist 局域网连接码，确认设备后才发起配对。二维码一次有效，最长 5 分钟。",
                fontSize = 11.sp, color = Faint, lineHeight = 18.sp)
        }
    }
    if (showPaste) AlertDialog(
        onDismissRequest = { showPaste = false; pasted = "" },
        title = { Text("粘贴连接码") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text("只接受 linkassist://pair 连接码；不会打开普通网址。", color = Muted, fontSize = 13.sp)
                OutlinedTextField(value = pasted, onValueChange = { pasted = it.take(Pairing.MAX_LENGTH + 1) },
                    label = { Text("连接码") }, modifier = Modifier.fillMaxWidth(), minLines = 3, maxLines = 5)
                TextButton(onClick = {
                    val value = clipboard.getText()?.text.orEmpty()
                    if (value.length > Pairing.MAX_LENGTH) message = "连接码超过 2048 字符" else pasted = value
                }) { Text("从剪贴板粘贴") }
                message?.let { Text(it, fontSize = 12.sp, color = Orange) }
            }
        },
        confirmButton = { TextButton(onClick = { accept(pasted.trim()) }, enabled = pasted.isNotBlank()) { Text("识别") } },
        dismissButton = { TextButton(onClick = { showPaste = false; pasted = "" }) { Text("取消") } },
    )
    pending?.let { target ->
        AlertDialog(
            onDismissRequest = { if (!pairing) pending = null },
            icon = { Icon(if (target.kind == "phone") Icons.Outlined.Smartphone else Icons.Outlined.Devices, contentDescription = null) },
            title = { Text("确认连接此设备？") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Text(target.name, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                    Text(if (target.kind == "phone") "手机互连" else "电脑", color = Muted)
                    Surface(color = CodeBg, shape = RoundedCornerShape(10.dp)) {
                        Text(target.address, modifier = Modifier.fillMaxWidth().padding(12.dp), fontFamily = FontFamily.Monospace)
                    }
                    Text("请核对设备名称与局域网地址。仅在信任的网络中连接；确认后将保存凭据并同步消息与文件。短信和通知转发需在设置中单独开启。",
                        color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
                    if (pairing) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
            },
            confirmButton = {
                TextButton(enabled = !pairing, onClick = {
                    pairing = true
                    scope.launch {
                        try {
                            val profile = requestPairing(ctx, target)
                            toast(ctx, "已配对 ${profile.name}，正在连接")
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (error: IllegalArgumentException) {
                            message = error.message ?: "配对失败，请重新扫码"
                        } catch (_: Exception) {
                            message = "配对未完成，请确认网络并重新扫码"
                        } finally {
                            pairing = false
                            pending = null // Never retry an already submitted one-time secret.
                        }
                    }
                }) { Text(if (pairing) "配对中…" else "确认并连接") }
            },
            dismissButton = { TextButton(enabled = !pairing, onClick = { pending = null }) { Text("取消") } },
        )
    }
}

// ============================================================ 页面 2:设备
@Composable
fun DevicesPage(
    profiles: List<ProfileState>,
    hubOn: Boolean,
    hubPeers: Int,
    onAdd: () -> Unit,
    onEdit: (PcProfile) -> Unit,
    onDelete: (PcProfile) -> Unit,
) {
    val ctx = LocalContext.current
    var deleting by remember { mutableStateOf<PcProfile?>(null) }
    Column(
        Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
    ) {
        PairingCard(onManual = onAdd)
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("已配对设备", fontSize = 17.sp, fontWeight = FontWeight.SemiBold, modifier = Modifier.weight(1f))
            Text("${profiles.count { it.connected }} / ${profiles.size} 在线", fontSize = 12.sp, color = Muted)
        }
        if (profiles.isEmpty()) {
            Text("还没有配对设备。扫码后先核对名称与地址，再确认连接；不会自动连接陌生设备。",
                color = Muted, fontSize = 13.sp, lineHeight = 21.sp)
        }
        profiles.forEach { p ->
            Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(16.dp)) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(Icons.Outlined.Devices, contentDescription = null, tint = if (p.connected) Accent else Muted)
                        Spacer(Modifier.width(12.dp))
                        Column(Modifier.weight(1f)) {
                            Text(p.name, fontSize = 16.sp, fontWeight = FontWeight.SemiBold,
                                maxLines = 2, overflow = TextOverflow.Ellipsis)
                            Text("${p.host}:${p.port}", fontSize = 12.sp, color = Muted, fontFamily = FontFamily.Monospace)
                        }
                        Surface(color = if (p.connected) Accent.copy(alpha = .12f) else CardBg2,
                            shape = RoundedCornerShape(20.dp)) {
                            Text(if (p.connected) "已连接" else "未连接", color = if (p.connected) Green else Muted,
                                fontSize = 11.sp, modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp))
                        }
                    }
                    Text(p.status, fontSize = 12.sp, color = Muted, maxLines = 2, overflow = TextOverflow.Ellipsis)
                    Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                        if (!p.connected) TextButton(onClick = { LinkService.reconnect(); toast(ctx, "正在重新连接") }) {
                            Text("重连")
                        }
                        TextButton(onClick = { onEdit(PcProfile(p.id, p.name, p.host, p.port, "")) }) { Text("编辑") }
                        TextButton(onClick = { deleting = PcProfile(p.id, p.name, p.host, p.port, "") }) { Text("移除", color = Red) }
                    }
                }
            }
        }
        HorizontalDivider(color = Line)
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("手机互连", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                        Text(if (hubOn) "已开启 · $hubPeers 台设备在线" else "无需电脑，同一 Wi-Fi / 热点内直接互传",
                            fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
                    }
                    Switch(checked = hubOn, onCheckedChange = { LinkService.setHub(ctx, it) })
                }
                if (hubOn) HubPairingQr(hubPeers) else {
                    Text(LinkService.hubStatus.takeIf { it.startsWith("开启失败") }
                        ?: "开启后在此展示本机二维码。另一台手机扫码并确认即可，双方只需单侧添加。",
                        color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                }
            }
        }
        Spacer(Modifier.height(4.dp))
    }
    deleting?.let { p ->
        AlertDialog(onDismissRequest = { deleting = null }, title = { Text("移除 ${p.name}？") },
            text = { Text("将断开连接并删除本机保存的配对凭据，不会删除已接收文件。") },
            confirmButton = { TextButton(onClick = { onDelete(p); deleting = null }) { Text("移除", color = Red) } },
            dismissButton = { TextButton(onClick = { deleting = null }) { Text("取消") } })
    }
}

@Composable
private fun HubPairingQr(hubPeers: Int) {
    val ctx = LocalContext.current
    val clipboard = LocalClipboardManager.current
    var payload by remember { mutableStateOf<String?>(null) }
    var bitmap by remember { mutableStateOf<android.graphics.Bitmap?>(null) }
    var address by remember { mutableStateOf("") }
    var error by remember { mutableStateOf<String?>(null) }
    var refresh by remember { mutableStateOf(0) }
    var manual by rememberSaveable { mutableStateOf(false) }
    LaunchedEffect(hubPeers, refresh) {
        while (isActive) {
            try {
                val next = withContext(Dispatchers.IO) { LinkService.hubPairingPayload() }
                if (next == null) {
                    payload = null; bitmap = null; address = ""
                    error = "暂未获得本机局域网地址，请连接 Wi-Fi 或开启热点后刷新"
                } else if (next != payload) {
                    val target = Pairing.parse(next)
                    val image = withContext(Dispatchers.Default) { pairingQrBitmap(next) }
                    address = target.address
                    bitmap = image
                    payload = next
                    error = null
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                payload = null; bitmap = null; address = ""
                error = "二维码暂不可用，请稍后刷新"
            }
            // Provider returns the cached session and rotates only after consumption / expiry.
            delay(5_000)
        }
    }
    bitmap?.let { image ->
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            Image(bitmap = image.asImageBitmap(), contentDescription = "本机配对二维码，包含一次性凭据，请勿公开分享",
                modifier = Modifier.widthIn(max = 280.dp).fillMaxWidth().aspectRatio(1f))
        }
        Text(address, color = Accent, fontFamily = FontFamily.Monospace, fontSize = 14.sp)
        Text("一次性二维码 · 5 分钟内有效，使用或到期后自动换新。仅向可信设备展示。",
            color = Muted, fontSize = 12.sp, lineHeight = 19.sp)
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = {
                payload?.let { clipboard.setText(AnnotatedString(it)); toast(ctx, "已复制连接码，请勿公开分享") }
            }) { Text("复制连接码") }
            TextButton(onClick = { refresh++ }) { Text("刷新状态") }
        }
    }
    error?.let {
        Text(it, color = Orange, fontSize = 12.sp)
        TextButton(onClick = { refresh++ }) { Text("重新获取二维码") }
    }
    if (bitmap == null && error == null) LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
    TextButton(onClick = { manual = !manual }) {
        Text("手动连接备用")
        Icon(if (manual) Icons.Outlined.ExpandLess else Icons.Outlined.ExpandMore, contentDescription = null)
    }
    if (manual) {
        Surface(color = CodeBg, shape = RoundedCornerShape(12.dp)) {
            Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(address.ifBlank { "等待局域网地址" }, fontSize = 12.sp, fontFamily = FontFamily.Monospace)
                Text("备用配对码 ${Prefs.hubToken(ctx)}", color = Accent, fontFamily = FontFamily.Monospace)
                Text("仅供手动输入，勿发送到群聊或公开位置。", color = Muted, fontSize = 11.sp)
                TextButton(onClick = {
                    clipboard.setText(AnnotatedString(Prefs.hubToken(ctx))); toast(ctx, "已复制备用配对码")
                }) { Text("复制备用码") }
            }
        }
    }
}

// ============================================================ 页面 3:设置
@Composable
fun SettingsPage() {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val lifecycleOwner = LocalLifecycleOwner.current
    val main = remember { Handler(Looper.getMainLooper()) }
    var checking by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<Updater.Remote?>(null) }
    var updateMsg by remember { mutableStateOf("手动检查或开启自动检查，只提醒，不会自动下载。") }
    var progress by remember { mutableStateOf(-1) }
    var downloading by remember { mutableStateOf(false) }
    var readyFile by remember { mutableStateOf<java.io.File?>(null) }
    var repository by rememberSaveable { mutableStateOf(Prefs.updateRepository(ctx)) }
    var repoError by remember { mutableStateOf<String?>(null) }
    var autoCheck by remember { mutableStateOf(Prefs.autoCheckUpdates(ctx)) }
    var smsEnabled by remember { mutableStateOf(Prefs.smsForwardingEnabled(ctx)) }
    var notificationEnabled by remember { mutableStateOf(Prefs.notificationForwardingEnabled(ctx)) }
    var notificationAccess by remember { mutableStateOf(hasNotificationAccess(ctx)) }
    var notificationPermission by remember { mutableStateOf(NotificationManagerCompat.from(ctx).areNotificationsEnabled()) }
    var awaitingNotificationAccess by rememberSaveable { mutableStateOf(false) }
    var showNotificationDisclosure by remember { mutableStateOf(false) }
    var clearConfirmation by remember { mutableStateOf(false) }

    val smsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        smsEnabled = allowed
        Prefs.setSmsForwardingEnabled(ctx, allowed)
        toast(ctx, if (allowed) "已开启短信验证码转发" else "短信权限未授予，转发保持关闭")
    }
    val notificationsPermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        notificationPermission = allowed && NotificationManagerCompat.from(ctx).areNotificationsEnabled()
        toast(ctx, if (allowed) "已允许通知提醒" else "通知未开启，仍可手动检查更新")
    }
    val storagePermission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { allowed ->
        toast(ctx, if (allowed) "已允许保存到公共目录" else "未授予存储权限")
    }
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                notificationAccess = hasNotificationAccess(ctx)
                notificationPermission = NotificationManagerCompat.from(ctx).areNotificationsEnabled()
                if (awaitingNotificationAccess) {
                    awaitingNotificationAccess = false
                    notificationEnabled = notificationAccess
                    Prefs.setNotificationForwardingEnabled(ctx, notificationAccess)
                }
                if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) !=
                    android.content.pm.PackageManager.PERMISSION_GRANTED) {
                    smsEnabled = false
                    Prefs.setSmsForwardingEnabled(ctx, false)
                }
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun saveRepository(): Boolean = try {
        Prefs.setUpdateRepository(ctx, repository.trim())
        repository = Prefs.updateRepository(ctx)
        repoError = null
        true
    } catch (_: IllegalArgumentException) {
        repoError = "请填写 GitHub 仓库 owner/repo，例如 Danjack85/LinkAssist；不要填写 token。"
        false
    }
    fun doCheck() {
        if (!saveRepository()) return
        checking = true
        found = null
        readyFile = null
        progress = -1
        scope.launch {
            try {
                val (remote, reached, error) = withContext(Dispatchers.IO) { Updater.checkAll(ctx) }
                found = remote
                updateMsg = when {
                    !reached -> "检查失败：${error.ifBlank { "无法访问更新源" }}"
                    remote != null -> "发现 v${remote.versionName} · ${fmtSize(remote.size)}"
                    error.isNotBlank() -> "可访问的更新源暂无新版。$error"
                    else -> "已是最新版本"
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                updateMsg = "检查未完成，请确认仓库可匿名访问或已连接局域网电脑。"
            } finally {
                checking = false
            }
        }
    }
    fun doDownload() {
        val remote = found ?: return
        progress = 0
        downloading = true
        scope.launch {
            try {
                val (file, error) = withContext(Dispatchers.IO) {
                    Updater.download(ctx, remote) { p -> main.post { progress = p.coerceIn(0, 100) } }
                }
                readyFile = file
                updateMsg = if (file != null) "下载并校验完成。请点击安装，由系统确认。" else "下载失败：${error.orEmpty()}"
                progress = if (file != null) 100 else -1
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (_: Exception) {
                progress = -1
                updateMsg = "下载或校验失败，未安装更新。"
            } finally {
                downloading = false
            }
        }
    }
    fun doInstall() {
        val file = readyFile ?: return
        try {
            if (!Updater.canInstall(ctx)) {
                toast(ctx, "请允许本应用安装更新，返回后再次点击安装")
                Updater.requestInstallPermission(ctx)
            } else {
                Updater.install(ctx, file)
            }
        } catch (_: Exception) {
            updateMsg = "安装被阻止：安装包校验未通过或系统无法打开。请重新检查并下载。"
            readyFile = null
            progress = -1
        }
    }

    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text("设置", fontSize = 24.sp, fontWeight = FontWeight.Bold, modifier = Modifier.weight(1f))
            Text("v${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", color = Muted, fontSize = 12.sp)
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("应用更新", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text(updateMsg, fontSize = 13.sp, color = if (found != null) Green else Muted, lineHeight = 21.sp)
                if (checking || downloading) {
                    if (downloading) LinearProgressIndicator(progress = { progress / 100f }, modifier = Modifier.fillMaxWidth())
                    else LinearProgressIndicator(modifier = Modifier.fillMaxWidth())
                }
                found?.let { remote ->
                    Surface(color = CodeBg, shape = RoundedCornerShape(12.dp)) {
                        Column(Modifier.padding(12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            Text("来源：${if (remote.source == "pc") "已配对电脑 · ${remote.host}:${remote.port}" else "GitHub · $repository"}",
                                color = Accent, fontSize = 12.sp)
                            if (remote.notes.isNotBlank()) Text(remote.notes.take(4000), fontSize = 12.sp, color = Muted, lineHeight = 19.sp)
                            Text(if (remote.sha256.isNotBlank()) "SHA-256 完整性校验已提供" else "此更新源未提供 SHA-256，安装前仍需通过包验证",
                                color = if (remote.sha256.isNotBlank()) Muted else Orange, fontSize = 11.sp)
                            if (remote.releaseUrl.isNotBlank()) TextButton(onClick = {
                                val uri = Uri.parse(remote.releaseUrl)
                                if (uri.scheme == "https" && uri.host == "github.com" && uri.userInfo == null) {
                                    openSettingsSafely(ctx, Intent(Intent.ACTION_VIEW, uri))
                                } else toast(ctx, "发布地址无效，已阻止打开")
                            }) { Text("查看 GitHub 发布说明") }
                        }
                    }
                }
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(onClick = { doCheck() }, enabled = !checking && !downloading, modifier = Modifier.weight(1f)) {
                        Text(if (checking) "检查中…" else "检查更新")
                    }
                    if (found != null && readyFile == null) OutlinedButton(onClick = { doDownload() },
                        enabled = !checking && !downloading, modifier = Modifier.weight(1f)) {
                        Text(if (downloading) "下载 $progress%" else "下载更新")
                    }
                    if (readyFile != null) OutlinedButton(onClick = { doInstall() }, modifier = Modifier.weight(1f)) { Text("安装更新") }
                }
                HorizontalDivider(color = Line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("自动检查更新", fontSize = 14.sp)
                        Text("启动时及约每 12 小时检查，仅通知新版", color = Muted, fontSize = 11.sp)
                    }
                    Switch(checked = autoCheck, onCheckedChange = {
                        autoCheck = it
                        Prefs.setAutoCheckUpdates(ctx, it)
                        UpdateWorker.schedule(ctx, checkAtStartup = it)
                    })
                }
                if (!notificationPermission) Text("系统通知尚未开启；自动检查不会弹出提醒，可在下方自愿授权。", color = Orange, fontSize = 12.sp)
                OutlinedTextField(value = repository, onValueChange = { repository = it; repoError = null },
                    label = { Text("GitHub 仓库 owner/repo") }, singleLine = true, modifier = Modifier.fillMaxWidth(),
                    enabled = !checking && !downloading, isError = repoError != null)
                repoError?.let { Text(it, color = Red, fontSize = 12.sp) }
                TextButton(onClick = {
                    if (saveRepository()) { found = null; readyFile = null; toast(ctx, "更新仓库已保存") }
                }, enabled = !checking && !downloading) { Text("保存仓库") }
                Text("默认 Danjack85/LinkAssist。私有仓库无法匿名检查或下载；本应用绝不内置 GitHub token。可使用已配对电脑提供的局域网更新源。后台检查受系统省电策略影响，不会自动下载或安装。",
                    color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text("权限与转发 · 按需开启", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("扫码、消息和文件传输不需要短信或通知使用权。以下转发功能默认关闭；请仅与可信设备配对。",
                    color = Muted, fontSize = 12.sp, lineHeight = 20.sp)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("短信验证码转发", fontSize = 14.sp)
                        Text("开启后申请接收短信权限，并转发验证码", fontSize = 11.sp, color = Muted)
                    }
                    Switch(checked = smsEnabled, onCheckedChange = { enabled ->
                        if (!enabled) {
                            smsEnabled = false; Prefs.setSmsForwardingEnabled(ctx, false)
                        } else if (ContextCompat.checkSelfPermission(ctx, Manifest.permission.RECEIVE_SMS) ==
                            android.content.pm.PackageManager.PERMISSION_GRANTED) {
                            smsEnabled = true; Prefs.setSmsForwardingEnabled(ctx, true)
                        } else smsPermission.launch(Manifest.permission.RECEIVE_SMS)
                    })
                }
                HorizontalDivider(color = Line)
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text("邮件 / 应用验证码转发", fontSize = 14.sp)
                        Text(if (notificationAccess) "已授予通知使用权，开关控制转发" else "需手动授予通知使用权", fontSize = 11.sp, color = Muted)
                    }
                    Switch(checked = notificationEnabled, onCheckedChange = { enabled ->
                        if (!enabled) {
                            awaitingNotificationAccess = false
                            notificationEnabled = false; Prefs.setNotificationForwardingEnabled(ctx, false)
                        } else if (hasNotificationAccess(ctx)) {
                            notificationEnabled = true; Prefs.setNotificationForwardingEnabled(ctx, true)
                        } else showNotificationDisclosure = true
                    })
                }
                OutlinedButton(onClick = {
                    if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) !=
                        android.content.pm.PackageManager.PERMISSION_GRANTED) notificationsPermission.launch(Manifest.permission.POST_NOTIFICATIONS)
                    else openSettingsSafely(ctx, if (Build.VERSION.SDK_INT >= 26)
                        Intent(Settings.ACTION_APP_NOTIFICATION_SETTINGS).putExtra(Settings.EXTRA_APP_PACKAGE, ctx.packageName)
                        else Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:${ctx.packageName}")))
                }, modifier = Modifier.fillMaxWidth()) { Text(if (notificationPermission) "管理消息与更新通知" else "允许消息与更新通知") }
                TextButton(onClick = { openSettingsSafely(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) {
                    Text("管理系统通知使用权")
                }
                if (Build.VERSION.SDK_INT <= 28) OutlinedButton(onClick = { storagePermission.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE) },
                    modifier = Modifier.fillMaxWidth()) { Text("允许保存媒体到公共目录") }
            }
        }
        Card(colors = CardDefaults.cardColors(containerColor = CardBg), shape = RoundedCornerShape(20.dp)) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text("本机数据", fontSize = 17.sp, fontWeight = FontWeight.SemiBold)
                Text("接收文件保存在 Download/LinkAssist/。断线后自动重连；如后台连接受限，请在系统电池设置中允许后台运行。",
                    fontSize = 12.sp, color = Muted, lineHeight = 20.sp)
                OutlinedButton(onClick = { clearConfirmation = true }, modifier = Modifier.fillMaxWidth()) { Text("清空本机消息记录", color = Red) }
            }
        }
    }
    if (showNotificationDisclosure) AlertDialog(onDismissRequest = { showNotificationDisclosure = false },
        title = { Text("开启通知验证码转发？") },
        text = { Text("系统通知使用权允许 LinkAssist 读取通知内容，用于提取并转发验证码到已配对设备。请仅在信任这些设备时授权；可随时关闭转发或撤销权限。") },
        confirmButton = { TextButton(onClick = {
            showNotificationDisclosure = false
            awaitingNotificationAccess = true
            openSettingsSafely(ctx, Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS))
        }) { Text("前往授权") } },
        dismissButton = { TextButton(onClick = { showNotificationDisclosure = false }) { Text("暂不开启") } })
    if (clearConfirmation) AlertDialog(onDismissRequest = { clearConfirmation = false }, title = { Text("清空本机消息？") },
        text = { Text("此操作不可撤销，不影响其他设备上的记录和已接收文件。") },
        confirmButton = { TextButton(onClick = { LinkService.clearLocal(); clearConfirmation = false; toast(ctx, "已清空本机消息记录") }) { Text("清空", color = Red) } },
        dismissButton = { TextButton(onClick = { clearConfirmation = false }) { Text("取消") } })
}

private fun hasNotificationAccess(ctx: Context): Boolean =
    NotificationManagerCompat.getEnabledListenerPackages(ctx).contains(ctx.packageName)

private fun openSettingsSafely(ctx: Context, intent: Intent) {
    try { ctx.startActivity(intent) } catch (_: Exception) { toast(ctx, "系统无法打开此页面，请在系统设置中手动操作") }
}

// ============================================================ 添加/编辑电脑
@Composable
fun ProfileDialog(initial: PcProfile?, onClose: () -> Unit) {
    val ctx = LocalContext.current
    var name by remember { mutableStateOf(initial?.name ?: "") }
    var host by remember { mutableStateOf(initial?.host ?: "") }
    var port by remember { mutableStateOf(initial?.port?.toString() ?: "8765") }
    var token by remember { mutableStateOf("") }
    var validation by remember { mutableStateOf<String?>(null) }
    var searching by remember { mutableStateOf(false) }
    var found by remember { mutableStateOf<List<Disc>?>(null) }

    fun fill(d: Disc) {
        host = d.host
        port = d.port.toString()
        d.token?.let { token = it }
        if (name.isBlank()) name = d.name.removeSuffix("(手机直连)")
    }

    AlertDialog(
        onDismissRequest = onClose,
        title = { Text(if (initial == null) "添加电脑" else "编辑电脑") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                OutlinedTextField(
                    value = name, onValueChange = { name = it },
                    label = { Text("名称(如:家里/公司)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = host, onValueChange = { host = it },
                    label = { Text("电脑地址(可粘贴完整网址)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = port, onValueChange = { port = it },
                    label = { Text("端口(默认 8765)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedTextField(
                    value = token, onValueChange = { token = it },
                    label = { Text(initial?.let { "新配对码(留空=沿用)" } ?: "配对码(电脑端🔑)") },
                    singleLine = true, modifier = Modifier.fillMaxWidth(),
                )
                OutlinedButton(
                    onClick = {
                        searching = true
                        discoverPC { list ->
                            searching = false
                            when {
                                list.isEmpty() -> toast(ctx, "未发现,请确认同一 Wi-Fi 或手动填写")
                                list.size == 1 -> {
                                    fill(list[0])
                                    toast(ctx, "已发现并自动填好: ${list[0].name}")
                                }
                                else -> found = list
                            }
                        }
                    },
                    enabled = !searching,
                    modifier = Modifier.fillMaxWidth(),
                ) { Text(if (searching) "搜索中…" else "🔍 搜索局域网内的电脑/手机") }
                Text(
                    "提示:若两台手机要互连,请只添加\"对方手机(手机直连)\";双方互加会造成回环,已自动防回声,但建议单侧添加。",
                    fontSize = 10.5.sp, color = Faint, lineHeight = 15.sp,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = {
                val (cleanHost, portInAddr) = Prefs.cleanHost(host)
                if (cleanHost.isBlank()) {
                    toast(ctx, "请填写电脑地址")
                    return@TextButton
                }
                val looksLan = cleanHost.startsWith("192.168.") || cleanHost.startsWith("10.") ||
                    Regex("^172\\.(1[6-9]|2\\d|3[01])\\.").containsMatchIn(cleanHost)
                if (!looksLan) {
                    toast(ctx, "提醒:$cleanHost 不是常见局域网地址,若连不上请重新搜索")
                }
                val finalPort = portInAddr ?: (port.trim().toIntOrNull() ?: 8765)
                val finalToken = token.trim().uppercase(Locale.getDefault())
                    .ifBlank { Prefs.profiles(ctx).firstOrNull { it.id == initial?.id }?.token ?: "" }
                val list = Prefs.profiles(ctx).toMutableList()
                val p = PcProfile(
                    id = initial?.id ?: Prefs.newId(),
                    name = name.trim().ifBlank { "电脑" },
                    host = cleanHost, port = finalPort, token = finalToken,
                )
                list.removeAll { it.id == p.id }
                list.add(p)
                Prefs.saveProfiles(ctx, list)
                LinkService.instance?.syncProfiles()
                toast(ctx, "已保存 ${p.name}")
                onClose()
            }) { Text("保存") }
        },
        dismissButton = { TextButton(onClick = onClose) { Text("取消") } },
    )

    // 搜索到多台设备时的选择列表
    found?.let { list ->
        AlertDialog(
            onDismissRequest = { found = null },
            title = { Text("发现 ${list.size} 台设备,请选择") },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    list.forEach { d ->
                        Card(
                            colors = CardDefaults.cardColors(containerColor = CardBg2),
                            shape = RoundedCornerShape(10.dp),
                            modifier = Modifier.fillMaxWidth().clickable {
                                fill(d)
                                found = null
                                toast(ctx, "已选择: ${d.name}")
                            },
                        ) {
                            Row(Modifier.padding(12.dp), verticalAlignment = Alignment.CenterVertically) {
                                Text(if (d.kind == "phone") "📱" else "💻", fontSize = 18.sp)
                                Spacer(Modifier.width(10.dp))
                                Column {
                                    Text(d.name, fontSize = 14.sp, fontWeight = FontWeight.SemiBold)
                                    Text(
                                        "${d.host}:${d.port} · ${if (d.kind == "phone") "手机直连" else "电脑"}",
                                        fontSize = 11.sp, color = Muted,
                                    )
                                }
                            }
                        }
                    }
                }
            },
            confirmButton = { TextButton(onClick = { found = null }) { Text("取消") } },
        )
    }
}

// ============================================================ 消息渲染
@Composable
fun MsgItem(m: Msg, fileMap: Map<String, ReceivedFile>, onZoom: (ReceivedFile) -> Unit = {}) {
    if (m.type == "chat") {
        ChatBubble(m, fileMap, onZoom)
    } else {
        EventCard(m)
    }
}

@Composable
fun ChatBubble(m: Msg, fileMap: Map<String, ReceivedFile>, onZoom: (ReceivedFile) -> Unit = {}) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val out = m.direction == "out"
    Column(
        Modifier.fillMaxWidth(),
        horizontalAlignment = if (out) Alignment.End else Alignment.Start,
    ) {
        Text(
            "${if (out) "我 ➜ 电脑" else (m.pc.ifBlank { "电脑" })} · ${fmtTime(m.ts)}",
            fontSize = 10.sp, color = Faint,
            modifier = Modifier.padding(horizontal = 6.dp, vertical = 1.dp),
        )
        Spacer(Modifier.height(2.dp))
        Surface(
            shape = RoundedCornerShape(
                topStart = 16.dp, topEnd = 16.dp,
                bottomStart = if (out) 16.dp else 5.dp,
                bottomEnd = if (out) 5.dp else 16.dp,
            ),
            color = Color.Transparent,
            modifier = Modifier.widthIn(max = 320.dp)
                .combinedClickable(
                    onClick = {
                        clipboard.setText(AnnotatedString(m.body))
                        toast(ctx, "已复制")
                    },
                    onLongClick = {
                        clipboard.setText(AnnotatedString(m.body))
                        toast(ctx, "已复制")
                    },
                ),
        ) {
            Column(
                Modifier.then(
                    if (out) Modifier.background(BubbleOutBrush)
                    else Modifier.background(CardBg)
                ).padding(6.dp),
            ) {
                val fname = m.fileName
                if (fname != null) {
                    MediaContent(fname, fileMap, textColor = if (out) Color.White else Color(0xFFE6EAF4),
                        onZoom = onZoom)
                    if (m.body.isNotBlank() && m.body != "[图片] $fname" && m.body != "[视频] $fname") {
                        Spacer(Modifier.height(6.dp))
                        Text(m.body, fontSize = 14.sp, lineHeight = 20.sp,
                            color = if (out) Color.White else Color(0xFFE6EAF4))
                    }
                } else {
                    Text(m.body, fontSize = 14.sp, lineHeight = 20.sp,
                        color = if (out) Color.White else Color(0xFFE6EAF4))
                }
            }
        }
    }
}

/** 聊天内嵌媒体:照片直接显示,视频点击播放(本地 Download/LinkAssist 已由传输自动落盘) */
@Composable
fun MediaContent(fname: String, fileMap: Map<String, ReceivedFile>, textColor: Color,
                 onZoom: (ReceivedFile) -> Unit = {}) {
    val ctx = LocalContext.current
    val f = fileMap[fname]
    val isVideo = isVideoName(fname)
    var showMenu by remember { mutableStateOf(false) }

    fun saveToGallery() {
        Thread {
            try {
                val where = LinkService.instance?.saveToGallery(ctx, fname) ?: "保存失败"
                Handler(Looper.getMainLooper()).post { toast(ctx, "已保存到相册:$where") }
            } catch (e: Exception) {
                Handler(Looper.getMainLooper()).post { toast(ctx, "保存失败:${e.message}") }
            }
        }.start()
    }

    Box {
        if (isVideo) {
            // 视频内联播放(ExoPlayer,带控制条)
            if (f != null) {
                val exo = remember(f.uri) {
                    androidx.media3.exoplayer.ExoPlayer.Builder(ctx).build().apply {
                        setMediaItem(androidx.media3.common.MediaItem.fromUri(f.uri))
                        prepare()
                    }
                }
                DisposableEffect(f.uri) { onDispose { exo.release() } }
                androidx.compose.ui.viewinterop.AndroidView(
                    factory = { vctx ->
                        androidx.media3.ui.PlayerView(vctx).apply {
                            player = exo
                            useController = true
                        }
                    },
                    update = { it.player = exo },
                    modifier = Modifier.fillMaxWidth().height(230.dp),
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🎬", fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("$fname(传输中…)", fontSize = 13.sp, color = textColor)
                }
            }
        } else {
            // 照片:优先 fileMap(MediaStore uri),否则直接读公共 Download 路径
            val bmp = remember(fname, fileMap.size) {
                f?.let { decodeScaled(ctx, it.uri, 640) }
                    ?: decodeFileScaled(
                        java.io.File(
                            android.os.Environment.getExternalStoragePublicDirectory(
                                android.os.Environment.DIRECTORY_DOWNLOADS),
                            "LinkAssist/$fname"),
                        640)
            }
            if (bmp != null) {
                androidx.compose.foundation.Image(
                    bitmap = bmp.asImageBitmap(),
                    contentDescription = fname,
                    modifier = Modifier.fillMaxWidth().clickable {
                        (f ?: ReceivedFile(
                            uri = Uri.fromFile(java.io.File(
                                android.os.Environment.getExternalStoragePublicDirectory(
                                    android.os.Environment.DIRECTORY_DOWNLOADS), "LinkAssist/$fname")),
                            name = fname, size = 0, added = 0, mime = "image/*",
                        )).let { onZoom(it) }
                    },
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("🖼", fontSize = 16.sp)
                    Spacer(Modifier.width(6.dp))
                    Text("$fname(传输中…)", fontSize = 13.sp, color = textColor)
                }
            }
        }
        // 右上角 ⋮ 菜单
        Box(
            Modifier.align(Alignment.TopEnd).clickable { showMenu = true }
                .padding(4.dp).background(Color(0x66000000), CircleShape).padding(horizontal = 5.dp, vertical = 1.dp),
        ) { Text("⋮", color = Color.White, fontSize = 15.sp) }
        androidx.compose.material3.DropdownMenu(expanded = showMenu, onDismissRequest = { showMenu = false }) {
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(if (isVideo) "保存视频到相册" else "保存图片到相册", fontSize = 13.sp) },
                onClick = { showMenu = false; saveToGallery() })
            androidx.compose.material3.DropdownMenuItem(
                text = { Text(if (isVideo) "播放" else "全屏查看", fontSize = 13.sp) },
                onClick = {
                    showMenu = false
                    if (isVideo) f?.let { openReceived(ctx, it) } else f?.let { onZoom(it) }
                })
        }
    }
}

private fun decodeFileScaled(f: java.io.File, target: Int): android.graphics.Bitmap? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    android.graphics.BitmapFactory.decodeFile(f.absolutePath, bounds)
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target || bounds.outHeight / (sample * 2) >= target) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    android.graphics.BitmapFactory.decodeFile(f.absolutePath, opts)
} catch (_: Exception) {
    null
}

private fun isVideoName(name: String): Boolean =
    name.substringAfterLast('.', "").lowercase(Locale.getDefault()) in
        setOf("mp4", "mov", "avi", "mkv", "webm", "3gp")

private fun decodeScaled(ctx: Context, uri: Uri, target: Int): android.graphics.Bitmap? = try {
    val bounds = android.graphics.BitmapFactory.Options().apply { inJustDecodeBounds = true }
    ctx.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, bounds)
    }
    var sample = 1
    while (bounds.outWidth / (sample * 2) >= target || bounds.outHeight / (sample * 2) >= target) sample *= 2
    val opts = android.graphics.BitmapFactory.Options().apply { inSampleSize = sample }
    ctx.contentResolver.openInputStream(uri)?.use {
        android.graphics.BitmapFactory.decodeStream(it, null, opts)
    }
} catch (_: Exception) {
    null
}

@Composable
fun EventCard(m: Msg) {
    val clipboard = LocalClipboardManager.current
    val ctx = LocalContext.current
    val isSms = m.type == "sms"
    val tint = if (isSms) Green else Orange
    Card(
        colors = CardDefaults.cardColors(containerColor = CardBg),
        shape = RoundedCornerShape(14.dp),
        modifier = Modifier.fillMaxWidth().combinedClickable(
            onClick = {},
            onLongClick = {
                clipboard.setText(AnnotatedString(m.body))
                toast(ctx, "已复制全文")
            },
        ),
    ) {
        Row(Modifier.padding(12.dp)) {
            Box(
                Modifier.size(38.dp).background(tint.copy(alpha = 0.14f), RoundedCornerShape(12.dp)),
                contentAlignment = Alignment.Center,
            ) { Text(if (isSms) "✉️" else "🔔", fontSize = 17.sp) }
            Spacer(Modifier.width(11.dp))
            Column(Modifier.weight(1f)) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text(
                        if (isSms) "短信验证码" else "APP通知",
                        color = tint, fontSize = 11.sp, fontWeight = FontWeight.SemiBold,
                        modifier = Modifier
                            .background(tint.copy(alpha = 0.12f), RoundedCornerShape(6.dp))
                            .padding(horizontal = 7.dp, vertical = 2.dp),
                    )
                    Spacer(Modifier.width(7.dp))
                    Text(
                        srcLine(m),
                        color = Muted, fontSize = 11.5.sp,
                        maxLines = 1, overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f),
                    )
                    Text(fmtTime(m.ts), color = Faint, fontSize = 10.5.sp)
                }
                Spacer(Modifier.height(5.dp))
                Text(m.body.ifBlank { m.title }, fontSize = 14.sp, lineHeight = 20.sp)
                if (m.pc.isNotBlank()) {
                    Spacer(Modifier.height(2.dp))
                    Text("同步到 ${m.pc}", color = Faint, fontSize = 10.sp)
                }
                val code = m.code
                if (!code.isNullOrBlank()) {
                    Spacer(Modifier.height(8.dp))
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .background(
                                Brush.horizontalGradient(listOf(Accent.copy(alpha = .14f), Accent2.copy(alpha = .14f))),
                                RoundedCornerShape(10.dp),
                            )
                            .clickable {
                                clipboard.setText(AnnotatedString(code))
                                toast(ctx, "已复制验证码 $code")
                            }
                            .padding(horizontal = 14.dp, vertical = 9.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Text(
                            code,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 22.sp, fontWeight = FontWeight.Bold,
                            style = androidx.compose.ui.text.TextStyle(
                                brush = Brush.horizontalGradient(listOf(Color(0xFF6EA8FF), Color(0xFFA78BFF))),
                            ),
                        )
                        Spacer(Modifier.weight(1f))
                        Text("点击复制", fontSize = 11.sp, color = Muted)
                    }
                }
            }
        }
    }
}

@Composable
fun EmptyHint() {
    Column(
        Modifier.fillMaxWidth().padding(vertical = 60.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text("📨", fontSize = 38.sp)
        Spacer(Modifier.height(10.dp))
        Text("暂无消息", color = Muted, fontSize = 14.sp)
        Text("手机收到验证码会自动转发到电脑", color = Faint, fontSize = 12.sp)
    }
}

// ---------- 工具 ----------
private fun srcLine(m: Msg): String = when {
    m.type == "sms" -> "来自 ${m.from}" + if (m.pc.isNotBlank()) " → ${m.pc}" else ""
    m.type == "notif" -> (listOf(m.from, m.title).filter { it.isNotBlank() }.joinToString(" · "))
    else -> m.from
}

private val timeFmt = SimpleDateFormat("HH:mm:ss", Locale.getDefault())
private val dateFmt = SimpleDateFormat("MM-dd HH:mm", Locale.getDefault())

private fun fmtTime(ts: Long): String {
    val d = Date(ts)
    return if (android.text.format.DateUtils.isToday(ts)) timeFmt.format(d) else dateFmt.format(d)
}

private fun toast(ctx: Context, msg: String) {
    Toast.makeText(ctx, msg, Toast.LENGTH_SHORT).show()
}

/** 局域网里发现的一台电脑/直连手机 */
data class Disc(
    val name: String,
    val host: String,
    val port: Int,
    val token: String?,
    val kind: String,
)

/** UDP 广播搜索:收集 3 秒内所有应答(电脑 + 手机直连中心可能同时存在),交由用户选择 */
private fun discoverPC(onResults: (List<Disc>) -> Unit) {
    Thread {
        val found = LinkedHashMap<String, Disc>()
        val listener = Thread {
            try {
                DatagramSocket().use { s ->
                    s.soTimeout = 3000
                    val buf = ByteArray(2048)
                    val p = DatagramPacket(buf, buf.size)
                    while (true) {
                        try {
                            s.receive(p)
                        } catch (_: java.net.SocketTimeoutException) {
                            break
                        }
                        try {
                            val o = JSONObject(String(buf, 0, p.length, Charsets.UTF_8))
                            if (o.optString("app") == "linkassist") {
                                val h = o.getString("host")
                                val pt = o.getInt("port")
                                val key = "$h:$pt"
                                if (!found.containsKey(key)) {
                                    found[key] = Disc(
                                        name = o.optString("name").ifBlank { h },
                                        host = h,
                                        port = pt,
                                        token = o.optString("token").ifBlank { null },
                                        kind = o.optString("kind").ifBlank { "pc" },
                                    )
                                }
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
        }
        listener.start()
        try {
            val targets = LinkedHashSet<InetAddress>()
            targets.add(InetAddress.getByName("255.255.255.255"))
            val nis = java.net.NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val bc = ia.broadcast ?: continue
                    if (!bc.isLoopbackAddress) targets.add(bc)
                }
            }
            for (t in targets) {
                try {
                    DatagramSocket().use { s ->
                        s.broadcast = true
                        val data = "LINKASSIST_DISCOVER".toByteArray(Charsets.UTF_8)
                        s.send(DatagramPacket(data, data.size, t, 37777))
                    }
                } catch (_: Exception) {
                }
            }
        } catch (_: Exception) {
        }
        listener.join(3500)
        Handler(Looper.getMainLooper()).post { onResults(found.values.toList()) }
    }.start()
}
