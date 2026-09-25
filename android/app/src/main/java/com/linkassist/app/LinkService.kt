package com.linkassist.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import okhttp3.WebSocket
import okhttp3.WebSocketListener
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.TimeUnit
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody
import okio.BufferedSink

/** 每台电脑的一条连接 */
private class Conn(
    val id: String,
    var name: String,
    var host: String,
    var port: Int,
    var token: String,
    val localHub: Boolean = false,
) {
    var ws: WebSocket? = null
    var connecting = false
    var backoff = 1000L
    var connected = false
    var status = "未连接"
    var failCount = 0
    /** 该服务器最近一次名册里提供的设备 id(断开时回收,避免跨服务器名册互相污染) */
    val contributedPeers = CopyOnWriteArrayList<String>()
    val peerNames = ConcurrentHashMap<String, String>()
}

/** 暴露给 UI 的连接状态快照 */
class ProfileState(
    val id: String,
    val name: String,
    val host: String,
    val port: Int,
    val connected: Boolean,
    val status: String,
)

/**
 * 常驻前台服务:与多台电脑保持 WebSocket 连接
 * - 上行:短信验证码 / APP通知 / 聊天 / 文件
 * - 下行:电脑聊天 / 文件推送 / 设备名册
 * - 同步:连接后拉取 /api/history 补齐断线期间的消息;消息落盘,重启不丢
 * - 稳定:网络可用即秒级重连 + 30s 看门狗自愈 + 指数退避
 */
class LinkService : Service() {

    companion object {
        const val CH_SERVICE = "service"
        const val CH_MESSAGES = "messages"
        private const val MAX_MSGS = 500
        private const val HISTORY_FILE = "linkassist_messages.jsonl"
        private const val LOCAL_HUB_ID = "internal-local-hub"

        @Volatile
        var instance: LinkService? = null
            private set

        val messages = CopyOnWriteArrayList<Msg>()
        val transfers = CopyOnWriteArrayList<Transfer>()
        val peers = CopyOnWriteArrayList<Pair<String, String>>()
        val profileStates = CopyOnWriteArrayList<ProfileState>()

        @Volatile
        var selectedPeerId: String? = null

        @Volatile
        var connected = false
            private set

        @Volatile
        var statusText = "服务启动中…"
            private set

        var onEvent: ((Msg) -> Unit)? = null
        var onStatus: ((Boolean, String) -> Unit)? = null
        var onTransfer: ((Transfer) -> Unit)? = null
        var onPeers: (() -> Unit)? = null
        var onProfiles: (() -> Unit)? = null
        var onHub: ((Boolean, Int) -> Unit)? = null
        var onCleared: (() -> Unit)? = null
        /** 媒体文件已保存到 Download/LinkAssist(供气泡内联刷新) */
        var onMediaSaved: (() -> Unit)? = null

        @Volatile
        var hubRunning = false
            private set

        @Volatile
        var hubPeerCount = 0
            private set

        @Volatile
        var hubIp = ""
            private set

        @Volatile
        var hubStatus = ""
            private set

        private val seenKeys: MutableSet<String> = ConcurrentHashMap.newKeySet()
        private var myDeviceId: String? = null

        /** 供 SmsReceiver / 通知监听器调用 */
        fun sendEvent(context: android.content.Context, json: JSONObject) {
            if (json.optString("type") == "sms" && !Prefs.smsForwardingEnabled(context)) return
            if (json.optString("type") == "notif" && !Prefs.notificationForwardingEnabled(context)) return
            val svc = instance
            if (svc != null) {
                svc.enqueue(json)
                return
            }
            try {
                val i = Intent(context, LinkService::class.java).putExtra("event", json.toString())
                if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(i) else context.startService(i)
            } catch (_: Exception) {
            }
        }

        fun sendChat(text: String) {
            val svc = instance ?: return
            val ts = System.currentTimeMillis()
            val json = JSONObject().apply {
                put("type", "chat")
                put("text", text)
                put("ts", ts)
                selectedPeerId?.let { put("targetDeviceId", it) }
            }
            val m = Msg(Msg.newId(), "chat", "out", "我", "", text, null, ts)
            val m2 = m.copy(key = "local:${m.id}")
            svc.enqueue(json)
            addLocal(m2)
        }

        /**
         * 发送照片/视频:经文件通道广播给所有在线目标(电脑+互连中心),
         * 完成后自动发一条带文件名的聊天消息;发送方与接收方气泡内均直接显示。
         */
        fun sendMedia(context: android.content.Context, uri: Uri, name: String, isVideo: Boolean) {
            val svc = instance ?: return
            val target = selectedPeerId
            Thread {
                try {
                    val targets = svc.connectedTargets(target)
                    if (targets.isEmpty()) throw IOException("没有已连接的电脑或互连设备")
                    var ok = 0
                    var lastErr: IOException? = null
                    for (pc in targets) {
                        try {
                            svc.uploadTo(context, uri, pc, name, target)
                            ok++
                        } catch (e: IOException) {
                            if (e is java.io.InterruptedIOException && e.message == "已取消") throw e
                            lastErr = e
                        }
                    }
                    if (ok == 0) throw lastErr ?: IOException("所有目标均上传失败")
                    // 发送方自己也把媒体统一存一份到 Download/LinkAssist,气泡内联显示用
                    try {
                        context.contentResolver.openInputStream(uri)?.use { ins ->
                            svc.saveToDownloads(name, ins) { }
                        }
                        svc.handler.post { onMediaSaved?.invoke() }
                    } catch (_: Exception) {
                    }
                    val ts = System.currentTimeMillis()
                    val label = if (isVideo) "[视频] " else "[图片] "
                    val json = JSONObject().apply {
                        put("type", "chat")
                        put("text", label + name)
                        put("fileName", name)
                        put("ts", ts)
                        target?.let { put("targetDeviceId", it) }
                    }
                    svc.enqueue(json)
                    addLocal(
                        Msg(Msg.newId(), "chat", "out", "我", "", label + name, null, ts,
                            fileName = name),
                    )
                } catch (e: Exception) {
                    onStatus?.invoke(connected, "媒体发送失败: ${e.message}")
                }
            }.start()
        }

        /** 在手机本地界面留一条记录(如刚转发的验证码) */
        fun addLocal(m: Msg) {
            val m2 = if (m.key == null) m.copy(key = "local:${m.id}") else m
            seenKeys.add(m2.key!!)
            messages.add(m2)
            trimMessages()
            instance?.persist(m2)
            onEvent?.invoke(m2)
        }

        fun reconnect() {
            instance?.forceReconnect()
        }

        /** 清空本地消息记录(落盘文件一并删除;不影响电脑端记录) */
        fun clearLocal() {
            messages.clear()
            seenKeys.clear()
            val svc = instance
            if (svc != null) {
                try {
                    File(svc.filesDir, HISTORY_FILE).delete()
                } catch (_: Exception) {
                }
            }
            onCleared?.invoke()
        }

        /** 开/关手机互连中心(持久化;由 UI 调用) */
        fun setHub(context: android.content.Context, enabled: Boolean) {
            Prefs.setHubEnabled(context, enabled)
            instance?.applyHub()
        }

        /** 未启用中心/无 LAN IPv4 返回 null；未过期且未消费的扫码会话会复用。 */
        fun hubPairingPayload(): String? = instance?.hub?.pairingPayload()

        fun sendFile(context: android.content.Context, uri: Uri) {
            instance?.uploadFile(context, uri)
        }

        fun cancelTransfer(id: String) { instance?.cancelActiveTransfer(id) }

        /** 供应用级功能(如自动更新)发系统通知 */
        fun notifyPublic(title: String, text: String) {
            instance?.notifyMessage(title, text)
        }

        /** 运行时连接目标(实际端口,可能已跟随漂移):host, port, token */
        fun runtimeTargets(): List<Triple<String, Int, String>> {
            val svc = instance ?: return emptyList()
            return svc.conns.values.filterNot { it.localHub }.map { Triple(it.host, it.port, it.token) }
        }

        private fun trimMessages() {
            while (messages.size > MAX_MSGS) messages.removeAt(0)
        }
    }

    private val client = OkHttpClient.Builder()
        .pingInterval(10, TimeUnit.SECONDS)
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .retryOnConnectionFailure(true)
        .build()
    private val transferClient = client.newBuilder()
        .retryOnConnectionFailure(false)
        .callTimeout(10, TimeUnit.MINUTES)
        .build()
    private val transferCalls = ConcurrentHashMap<String, okhttp3.Call>()
    private val downloadPublishLock = Any()
    private val transferConnections = ConcurrentHashMap<String, Conn>()
    private val cancelledTransfers = ConcurrentHashMap.newKeySet<String>()
    private val receivedOffers = java.util.Collections.synchronizedSet(LinkedHashSet<String>())

    private val conns = ConcurrentHashMap<String, Conn>()
    @Volatile private var hub: HubServer? = null
    private var hubStarting = false
    private val handler = Handler(Looper.getMainLooper())
    private var shouldRun = false
    private val pending = ArrayDeque<JSONObject>()
    private var netCallback: ConnectivityManager.NetworkCallback? = null
    private val watchdog = object : Runnable {
        override fun run() {
            if (!shouldRun) return
            for (pc in conns.values) {
                if (!pc.connected && !pc.connecting) connectProfile(pc.id)
            }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        instance = this
        myDeviceId = deviceId()
        createChannels()
        loadPersisted()
        startForegroundNotify()
        registerNetworkCallback()
        handler.postDelayed(watchdog, 30_000)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        intent?.getStringExtra("event")?.let { raw ->
            try {
                enqueue(JSONObject(raw))
            } catch (_: Exception) {
            }
        }
        shouldRun = true
        syncProfiles()
        applyHub()
        return START_STICKY
    }

    /** 按 Prefs 开关启动/停止手机互连中心 */
    private fun applyHub() {
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { applyHub() }; return }
        val want = Prefs.hubEnabled(this)
        val h = hub
        if (want && h == null && !hubStarting) {
            hubStarting = true
            val server = HubServer(
                token = Prefs.hubToken(this),
                serverName = deviceName(),
                selfDeviceId = myDeviceId ?: deviceId(),
                baseDir = java.io.File(getExternalFilesDir(null), "hub_files"),
                onPeers = { n ->
                    handler.post {
                        hubPeerCount = n
                        onHub?.invoke(hubRunning, n)
                        refreshAggregatedStatus()
                    }
                },
                onRelay = { type, fromName, fromDeviceId, body, code, ts, fileName ->
                    // 经本机中心中转的消息,中心手机自己也消费一份(手机↔手机互通的关键)
                    handler.post {
                        val key = "rt:$fromDeviceId:$ts:${body.hashCode()}"
                        if (messages.none { it.key == key }) {
                            val isChat = type == "chat"
                            val m = Msg(
                                Msg.newId(), type, "in", fromName, "", body, code, ts,
                                pc = "手机互连", key = key, fileName = fileName,
                            )
                            messages.add(m)
                            trimMessages()
                            persist(m)
                            onEvent?.invoke(m)
                            notifyMessage(
                                if (isChat) "来自 $fromName" else "来自 $fromName 的验证码",
                                body,
                            )
                        }
                    }
                },
                onFileRelay = { name, _size, file ->
                    // 直连模式下收到文件:中心手机把文件转存到 Download/LinkAssist
                    handler.post {
                        Thread {
                            try {
                                val noProgress = { _p: Long -> }
                                java.io.FileInputStream(file).use { ins ->
                                    val where = saveToDownloads(name, ins, noProgress)
                                    notifyMessage("文件已接收(互连)", "$name\n已保存到 $where")
                                }
                            } catch (e: Exception) {
                                notifyMessage("文件接收失败(互连)", "$name: ${e.message}")
                            }
                        }.start()
                    }
                },
            )
            Thread({
                val ok = server.start()
                if (!shouldRun) { server.stop(); return@Thread }
                handler.post {
                    hubStarting = false
                    if (!shouldRun || !Prefs.hubEnabled(this)) { server.stop(); return@post }
                    hub = if (ok) server else null
                    hubRunning = ok
                    hubIp = if (ok) server.lanIp() else ""
                    hubStatus = if (ok) "已开启 · ${hubIp}:${HubServer.HUB_PORT}"
                    else "开启失败:${server.lastError.ifBlank { "8765 端口被占用" }}"
                    if (ok) {
                        // 不写入用户配置，不出现在连接数中。中心只需单侧被添加便可主动发送。
                        val local = Conn(LOCAL_HUB_ID, "手机互连", "127.0.0.1", HubServer.HUB_PORT, Prefs.hubToken(this), localHub = true)
                        conns[LOCAL_HUB_ID] = local
                        connectProfile(local.id)
                    }
                    onHub?.invoke(ok, hubPeerCount)
                    refreshAggregatedStatus()
                }
            }, "hub-start").start()
        } else if (!want && h != null) {
            conns.remove(LOCAL_HUB_ID)?.let { closeConn(it) }
            h.stop()
            hub = null
            hubRunning = false
            hubPeerCount = 0
            hubIp = ""
            hubStatus = ""
            onHub?.invoke(false, 0)
            refreshAggregatedStatus()
        }
    }

    override fun onDestroy() {
        shouldRun = false
        transferCalls.forEach { (id, call) -> cancelledTransfers.add(id); call.cancel() }
        handler.removeCallbacksAndMessages(null)
        netCallback?.let {
            try {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(it)
            } catch (_: Exception) {
            }
        }
        for (pc in conns.values) {
            try {
                pc.ws?.close(1000, "bye")
            } catch (_: Exception) {
            }
        }
        conns.clear()
        try {
            hub?.stop()
        } catch (_: Exception) {
        }
        hub = null
        hubRunning = false
        refreshAggregatedStatus()
        instance = null
        super.onDestroy()
    }

    // ------------------------------------------------------------ 配置同步
    /** 读取 Prefs 里的多电脑配置,增/改/删连接 */
    fun syncProfiles() {
        val list = Prefs.profiles(this)
        val ids = list.map { it.id }.toSet()
        // 移除已删除的
        for (pc in conns.values) {
            if (!pc.localHub && pc.id !in ids) {
                closeConn(pc)
                conns.remove(pc.id)
                profileStates.removeAll { it.id == pc.id }
            }
        }
        // 新增/更新
        for (p in list) {
            val exist = conns[p.id]
            if (exist == null) {
                val pc = Conn(p.id, p.name, p.host, p.port, p.token)
                conns[p.id] = pc
                profileStates.add(ProfileState(p.id, p.name, p.host, p.port, false, "未连接"))
                if (shouldRun) handler.post { connectProfile(p.id) }
            } else {
                val changed = exist.host != p.host || exist.port != p.port ||
                        exist.token != p.token || exist.name != p.name
                exist.name = p.name
                exist.host = p.host
                exist.port = p.port
                exist.token = p.token
                for (i in profileStates.indices) {
                    val s = profileStates[i]
                    if (s.id == p.id) profileStates[i] = ProfileState(p.id, p.name, p.host, p.port, s.connected, s.status)
                }
                if (changed && shouldRun) {
                    closeConn(exist)
                    handler.post { connectProfile(p.id) }
                }
            }
        }
        onProfiles?.invoke()
        refreshAggregatedStatus()
    }

    private fun closeConn(pc: Conn) {
        pc.connected = false
        pc.connecting = false
        val w = pc.ws
        pc.ws = null
        // 回收该服务器贡献的定向发送名册
        pc.contributedPeers.clear()
        rebuildPeers()
        try {
            w?.cancel()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ 连接
    private fun connectProfile(profileId: String) {
        if (!shouldRun) return
        val pc = conns[profileId] ?: return
        if (pc.connected || pc.connecting) return
        // 清掉可能悬挂的旧 socket,防止重复拨号
        val stale = pc.ws
        pc.ws = null
        if (stale != null) {
            try {
                stale.cancel()
            } catch (_: Exception) {
            }
        }
        if (pc.host.isBlank() || pc.token.isBlank()) {
            setProfileStatus(pc, false, "配置不完整")
            return
        }
        pc.connecting = true
        setProfileStatus(pc, false, "连接中 ${pc.host}:${pc.port} …")
        val req = try {
            Request.Builder().url(endpoint(pc, "device")).build()
        } catch (_: Exception) {
            pc.connecting = false
            setProfileStatus(pc, false, "连接地址无效")
            return
        }
        pc.ws = client.newWebSocket(req, object : WebSocketListener() {
            override fun onOpen(w: WebSocket, response: Response) {
                handler.post {
                    if (w !== pc.ws) return@post   // 过期回调(旧连接),忽略
                    pc.connecting = false
                    pc.backoff = 1000L
                    pc.failCount = 0
                    setProfileStatus(pc, true, "已连接")
                    w.send(JSONObject().apply {
                        put("type", "hello")
                        put("device", deviceName())
                        put("deviceId", myDeviceId ?: deviceId())
                    }.toString())
                    flushPending(pc)
                    if (!pc.localHub) pullHistory(pc)
                }
            }

            override fun onMessage(w: WebSocket, text: String) {
                // 丢弃旧连接的尾部事件，避免一次性下载在重连时重复触发。
                handler.post { if (w === pc.ws) handleIncoming(pc, text) }
            }

            override fun onFailure(w: WebSocket, t: Throwable, response: Response?) {
                handler.post {
                    if (w !== pc.ws) return@post   // 过期回调,忽略(新连接可能已在跑)
                    pc.connecting = false
                    val raw = t.message ?: "未知错误"
                    val msg = when {
                        response?.code == 401 -> "配对码错误(401)"
                        raw.contains("Unable to resolve host", true) -> "地址无法解析(检查 IP)"
                        raw.contains("timeout", true) -> "连接超时"
                        raw.contains("ECONNREFUSED", true) -> "对方未在线或端口不对"
                        else -> "连接失败:${raw.take(40)}"
                    }
                    scheduleReconnect(pc, msg)
                }
            }

            override fun onClosed(w: WebSocket, code: Int, reason: String) {
                handler.post {
                    if (w !== pc.ws) return@post   // 过期回调,忽略
                    pc.connecting = false
                    scheduleReconnect(pc, "已断开,重连中…")
                }
            }
        })
    }

    private fun scheduleReconnect(pc: Conn, reason: String) {
        // 能走到这里的回调都已通过 w === pc.ws 身份校验(旧连接回调被过滤),
        // 因此这里必须无条件清状态并重连,否则电脑断开后手机会卡在"已连接"假状态
        setProfileStatus(pc, false, reason)
        pc.ws = null
        if (!shouldRun) return
        pc.failCount++
        val delay = pc.backoff
        handler.postDelayed({ connectProfile(pc.id) }, delay)
        pc.backoff = (pc.backoff * 2).coerceAtMost(15_000)
        // 连续失败(端口可能因被占用而漂移):自动 UDP 发现,跟随电脑的实际端口
        if (pc.failCount >= 3 && !pc.localHub) tryAutoDiscoverPort(pc)
    }

    /** UDP 广播发现:找到同一 host 的新端口则自动跟随并重连(60 秒限频) */
    private var lastAutoDiscover = 0L

    private fun tryAutoDiscoverPort(pc: Conn) {
        val now = System.currentTimeMillis()
        if (now - lastAutoDiscover < 60_000) return
        lastAutoDiscover = now
        Thread({
            val found = mutableListOf<Pair<String, Int>>()
            try {
                DatagramSocket().use { s ->
                    s.broadcast = true
                    s.soTimeout = 2500
                    val data = "LINKASSIST_DISCOVER".toByteArray(Charsets.UTF_8)
                    s.send(DatagramPacket(data, data.size, InetAddress.getByName("255.255.255.255"), HubServer.HUB_PORT_DISCOVER))
                    val buf = ByteArray(2048)
                    val pkt = DatagramPacket(buf, buf.size)
                    while (true) {
                        try {
                            pkt.length = buf.size
                            s.receive(pkt)
                        } catch (_: java.net.SocketTimeoutException) {
                            break
                        }
                        try {
                            val o = JSONObject(String(buf, 0, pkt.length, Charsets.UTF_8))
                            val host = o.optString("host")
                            val port = o.optInt("port")
                            if (o.optString("app") == "linkassist" && host == pkt.address.hostAddress && port in 1..65535) {
                                found.add(host to port) // 只跟随端口，绝不采用发现响应里的 token。
                            }
                        } catch (_: Exception) {
                        }
                    }
                }
            } catch (_: Exception) {
            }
            handler.post {
                if (!shouldRun) return@post
                for ((h, pt) in found) {
                    if (conns[pc.id] === pc && h == pc.host && pt != pc.port && pt in 1..65535) {
                        closeConn(pc)
                        pc.port = pt
                        val saved = Prefs.profiles(this)
                        Prefs.saveProfiles(this, saved.map { if (it.id == pc.id) it.copy(port = pt) else it })
                        pc.backoff = 1000L
                        pc.failCount = 0
                        setProfileStatus(pc, false, "端口已更新为 $pt,重连中")
                        handler.post { connectProfile(pc.id) }
                        break
                    }
                }
            }
        }, "hub-port-follow").start()
    }

    fun forceReconnect() {
        handler.post {
            for (pc in conns.values) {
                pc.backoff = 1000L
                closeConn(pc)
                connectProfile(pc.id)
            }
        }
    }

    // ------------------------------------------------------------ 网络回调 / 看门狗
    private fun registerNetworkCallback() {
        try {
            val cm = getSystemService(ConnectivityManager::class.java) ?: return
            val cb = object : ConnectivityManager.NetworkCallback() {
                override fun onAvailable(network: Network) {
                    handler.post {
                        // 只补拨"完全空闲"的连接,不打断进行中的拨号(避免同配置双连)
                        for (pc in conns.values) {
                            if (!pc.connected && !pc.connecting && pc.ws == null) {
                                pc.backoff = 1000L
                                connectProfile(pc.id)
                            }
                        }
                    }
                }
            }
            cm.registerNetworkCallback(
                NetworkRequest.Builder().addCapability(android.net.NetworkCapabilities.NET_CAPABILITY_INTERNET).build(),
                cb,
            )
            netCallback = cb
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ 收发
    private fun endpoint(pc: Conn, path: String, token: String = pc.token): okhttp3.HttpUrl =
        okhttp3.HttpUrl.Builder().scheme("http").host(pc.host).port(pc.port)
            .addPathSegments(path.trimStart('/')).addQueryParameter("token", token).build()

    private fun eventAllowed(json: JSONObject): Boolean = when (json.optString("type")) {
        "sms" -> Prefs.smsForwardingEnabled(this)
        "notif" -> Prefs.notificationForwardingEnabled(this)
        else -> true
    }

    /** 定向消息只走拥有该 peer 的一条连接（本机中心优先），不向无关服务器泄露。 */
    private fun routedTargets(target: String?): List<Conn> {
        val online = conns.values.filter { it.connected && (!it.localHub || hubPeerCount > 0) }
            .sortedBy { if (it.localHub) 0 else 1 }
        if (target.isNullOrBlank()) return online
        return online.firstOrNull { it.contributedPeers.contains(target) }?.let { listOf(it) } ?: emptyList()
    }

    /** 发送失败或目标尚未出现在名册时入队；入站消息从不重新广播。 */
    fun enqueue(json: JSONObject) {
        if (!eventAllowed(json)) return
        val outgoing = JSONObject(json.toString())
        if (Looper.myLooper() != Looper.getMainLooper()) { handler.post { enqueue(outgoing) }; return }
        var sent = false
        for (pc in routedTargets(outgoing.optString("targetDeviceId"))) {
            val w = pc.ws ?: continue
            val ok = try { w.send(outgoing.toString()) } catch (_: Exception) { false }
            if (ok) sent = true
            else if (w === pc.ws) scheduleReconnect(pc, "连接失效,重连中…")
        }
        if (!sent) synchronized(pending) {
            pending.addLast(outgoing)
            while (pending.size > 50) pending.removeFirst()
        }
    }

    private fun flushPending(pc: Conn) {
        val w = pc.ws ?: return
        synchronized(pending) {
            repeat(pending.size) {
                val json = pending.removeFirst()
                if (eventAllowed(json)) {
                    val route = routedTargets(json.optString("targetDeviceId"))
                    if (pc !in route || !w.send(json.toString())) pending.addLast(json)
                }
            }
        }
    }

    private fun rebuildPeers() {
        val incoming = linkedMapOf<String, String>()
        for (pc in conns.values.filter { it.connected }) {
            for (id in pc.contributedPeers) if (id != myDeviceId) incoming.putIfAbsent(id, pc.peerNames[id] ?: "设备")
        }
        peers.clear()
        peers.addAll(incoming.map { it.key to it.value })
        onPeers?.invoke()
    }

    private fun handleIncoming(pc: Conn, text: String) {
        try {
            if (text.length > 256 * 1024) return
            val o = JSONObject(text)
            val target = o.optString("targetDeviceId")
            if (target.isNotBlank() && target != (myDeviceId ?: deviceId())) return
            when (o.optString("type")) {
                "peers" -> {
                    // 多服务器名册按来源合并:先回收该连接上次贡献的条目,再合入新名册(过滤自己)
                    val mine = myDeviceId ?: deviceId()
                    val incoming = mutableListOf<Pair<String, String>>()
                    o.optJSONArray("devices")?.let { devices ->
                        for (i in 0 until devices.length()) {
                            val peer = devices.getJSONObject(i)
                            val pid = peer.optString("id")
                            if (pid == mine || pid.isBlank()) continue
                            incoming.add(pid to peer.optString("name"))
                        }
                    }
                    pc.peerNames.clear()
                    pc.peerNames.putAll(incoming.toMap())
                    pc.contributedPeers.clear()
                    pc.contributedPeers.addAll(incoming.map { it.first })
                    rebuildPeers()
                    flushPending(pc)
                }
                "file_offer" -> {
                    val tr = o.optJSONObject("transfer") ?: o
                    val mine = myDeviceId ?: deviceId()
                    val recipient = tr.optString("targetDeviceId")
                    if (tr.optString("fromDeviceId") == mine || (recipient.isNotBlank() && recipient != mine)) return
                    val remoteId = tr.optString("id")
                    if (!Regex("[A-Za-z0-9_-]{1,80}").matches(remoteId)) return
                    val id = "${pc.id}:$remoteId"
                    val url = tr.optString("downloadUrl").ifBlank { return }
                    synchronized(receivedOffers) {
                        if (!receivedOffers.add(id)) return
                        while (receivedOffers.size > 500) receivedOffers.remove(receivedOffers.first())
                    }
                    val t = Transfer(id, TransferIntegrity.safeName(tr.optString("name")), tr.optLong("size", -1),
                        "downloading", 0, downloadUrl = url)
                    updateTransfer(t)
                    downloadIncoming(pc, t, tr.optString("sha256"))
                }
                "file_error", "file_cancelled" -> {
                    val tr = o.optJSONObject("transfer") ?: o
                    val id = "${pc.id}:${tr.optString("id")}"
                    val previous = transfers.firstOrNull { it.id == id } ?: return
                    val cancelled = tr.optString("status") == "cancelled" || o.optString("type") == "file_cancelled"
                    if (cancelled) cancelledTransfers.add(id)
                    transferCalls[id]?.cancel()
                    updateTransfer(previous.copy(status = if (cancelled) "cancelled" else "failed", error = tr.optString("error")))
                }
                "chat" -> {
                    // 回声防线:其他手机转回来的自己消息直接丢弃(互相添加成环时必现)
                    val fromDev = o.optString("fromDeviceId")
                    if (fromDev == (myDeviceId ?: deviceId())) return
                    val ts = o.optLong("ts", 0).takeIf { it > 0 } ?: System.currentTimeMillis()
                    val fname = o.optString("fileName").ifBlank { null }
                    val m = Msg(
                        Msg.newId(), "chat", "in", pc.name, "",
                        o.optString("text"), null, ts,
                        pc = pc.name, key = "rt:${fromDev}:${ts}:${o.optString("text").hashCode()}",
                        fileName = fname,
                    )
                    if (m.body.isBlank()) return
                    // 统一去重:同源同 ts 的消息无论经电脑转发还是互连中心到达,只显示一次
                    if (messages.any { it.key != null && it.key == m.key }) return
                    messages.add(m)
                    trimMessages()
                    persist(m)
                    onEvent?.invoke(m)
                    notifyMessage("${pc.name} 的消息", m.body)
                }
                "sms", "notif" -> {
                    // 手机互连:另一台手机转发来的验证码/通知
                    val fromDev = o.optString("fromDeviceId")
                    if (fromDev == (myDeviceId ?: deviceId())) return  // 回声防线
                    val body = o.optString("body")
                    if (body.isBlank()) return
                    val type = o.optString("type")
                    val ts = o.optLong("ts", 0).takeIf { it > 0 } ?: System.currentTimeMillis()
                    val code = o.optString("code").trim()
                        .takeIf { it.isNotBlank() && it != "null" }
                    val fromName = o.optString("fromDevice").ifBlank { o.optString("from", pc.name) }
                    val m = Msg(
                        Msg.newId(), type, "in", o.optString("from", fromName),
                        o.optString("title"), body, code, ts,
                        pc = pc.name, key = "rt:${fromDev}:${ts}:${body.hashCode()}",
                    )
                    if (messages.any { it.key != null && it.key == m.key }) return
                    messages.add(m)
                    trimMessages()
                    persist(m)
                    onEvent?.invoke(m)
                    notifyMessage(
                        if (type == "sms") "来自 ${m.from} 的验证码(${fromName})" else "来自 ${fromName} 的通知",
                        body,
                    )
                }
            }
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ 历史补齐
    private fun pullHistory(pc: Conn) {
        Thread {
            try {
                val url = endpoint(pc, "api/history").newBuilder().addQueryParameter("limit", "200")
                    .addQueryParameter("deviceId", myDeviceId ?: deviceId()).build()
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) return@use
                    val stream = resp.body?.byteStream() ?: return@use
                    val arr = JSONObject(String(TransferIntegrity.readLimited(stream, 1024 * 1024), Charsets.UTF_8)).optJSONArray("messages")
                        ?: return@use
                        val mine = myDeviceId ?: deviceId()
                        val fresh = mutableListOf<Msg>()
                        for (i in 0 until arr.length()) {
                            val o = arr.getJSONObject(i)
                            // 自己发的消息电脑里有存档,跳过避免重复
                            if (o.optString("fromDeviceId") == mine) continue
                            val type = o.optString("type")
                            val ts = o.optLong("ts", 0)
                            val targetId = o.optString("targetDeviceId")
                            if (targetId.isNotBlank() && targetId != mine) continue
                            val body = if (type == "chat") o.optString("text").ifBlank { o.optString("body") } else o.optString("body")
                            if (body.isBlank()) continue
                            // 内容级去重:实时通道已收过的同内容同时间消息不再补一份
                            if (messages.any { it.ts == ts && it.body == body }) continue
                            val key = "${pc.id}:${o.optInt("id")}"
                            val added = synchronized(seenKeys) { seenKeys.add(key) }
                            if (!added) continue
                        fresh.add(
                            Msg(
                                Msg.newId(), type, o.optString("direction", "in"),
                                o.optString("from", pc.name), o.optString("title"),
                                body,
                                o.optString("code").trim().takeIf { it.isNotBlank() && it != "null" },
                                o.optLong("ts", System.currentTimeMillis()),
                                pc = pc.name, key = key, fileName = o.optString("fileName").ifBlank { null },
                            )
                        )
                    }
                    handler.post {
                        var added = 0
                        for (m in fresh.sortedBy { it.ts }) {
                            if (messages.any { it.key != null && it.key == m.key }) continue
                            messages.add(m)
                            trimMessages()
                            persist(m)
                            onEvent?.invoke(m)
                            added++
                        }
                        if (added > 0) setProfileStatus(pc, true, "已连接 · 补齐 $added 条")
                    }
                }
            } catch (_: Exception) {
            }
        }.start()
    }

    // ------------------------------------------------------------ 落盘
    private fun persist(m: Msg) {
        try {
            File(filesDir, HISTORY_FILE).appendText(
                JSONObject().apply {
                    put("key", m.key ?: "local:${m.id}")
                    put("type", m.type); put("direction", m.direction)
                    put("from", m.from); put("title", m.title); put("body", m.body)
                    put("code", m.code ?: ""); put("ts", m.ts); put("pc", m.pc)
                    put("fileName", m.fileName ?: "")
                }.toString() + "\n"
            )
        } catch (_: Exception) {
        }
    }

    private fun loadPersisted() {
        try {
            val f = File(filesDir, HISTORY_FILE)
            if (!f.exists()) return
            val lines = f.readLines().takeLast(300)
            for (line in lines) {
                try {
                    val o = JSONObject(line)
                    val key = o.optString("key")
                    if (key.isNotBlank()) seenKeys.add(key)
                    messages.add(
                        Msg(
                            Msg.newId(), o.optString("type"), o.optString("direction", "in"),
                            o.optString("from"), o.optString("title"), o.optString("body"),
                            o.optString("code").ifBlank { null }, o.optLong("ts"),
                            pc = o.optString("pc"), key = key.ifBlank { null },
                            fileName = o.optString("fileName").ifBlank { null },
                        )
                    )
                } catch (_: Exception) {
                }
            }
            trimMessages()
        } catch (_: Exception) {
        }
    }

    // ------------------------------------------------------------ 文件传输
    private fun connectedTargets(target: String? = selectedPeerId): List<Conn> = routedTargets(target)

    private fun uploadFile(context: android.content.Context, uri: Uri) {
        val target = selectedPeerId
        Thread {
            try {
                val targets = connectedTargets(target)
                if (targets.isEmpty()) throw IOException("没有已连接的电脑或互连设备")
                var lastErr: IOException? = null
                var uploaded = false
                for (pc in targets) {
                    try {
                        uploadTo(context, uri, pc, null, target)
                        uploaded = true
                        if (!target.isNullOrBlank()) break
                    } catch (e: IOException) {
                        if (e is java.io.InterruptedIOException && e.message == "已取消") throw e
                        lastErr = e
                    }
                }
                if (!uploaded) throw lastErr ?: IOException("所有目标均上传失败")
            } catch (e: Exception) {
                onStatus?.invoke(connected, "文件发送失败: ${e.message}")
            }
        }.start()
    }

    private fun uploadTo(context: android.content.Context, uri: Uri, pc: Conn, mediaName: String?, target: String? = selectedPeerId) {
        val resolver = context.contentResolver
        val name = TransferIntegrity.safeName(mediaName ?: resolver.query(uri, null, null, null, null)?.use { c ->
            val i = c.getColumnIndex(android.provider.OpenableColumns.DISPLAY_NAME)
            if (c.moveToFirst() && i >= 0) c.getString(i) else null
        } ?: "文件")
        val size = resolver.openAssetFileDescriptor(uri, "r")?.use { it.length } ?: -1L
        if (size !in 0..TransferIntegrity.MAX_FILE_BYTES) throw IOException("文件大小不可用或超过 512 MiB")
        val meta = JSONObject().apply {
            put("name", name); put("size", size)
            put("mime", resolver.getType(uri) ?: "application/octet-stream")
            put("direction", "phone_to_pc")
            put("fromDeviceId", myDeviceId ?: deviceId())
            target?.let { put("targetDeviceId", it) }
        }
        val offerReq = Request.Builder().url(endpoint(pc, "api/transfers/offer"))
            .post(RequestBody.create("application/json".toMediaType(), meta.toString())).build()
        val offer = transferClient.newCall(offerReq).execute().use { resp ->
            val stream = resp.body?.byteStream() ?: throw IOException("创建传输失败 ${resp.code}")
            val o = JSONObject(String(TransferIntegrity.readLimited(stream, 1024 * 1024), Charsets.UTF_8))
            if (!resp.isSuccessful || !o.has("transfer")) throw IOException(o.optString("error", "创建传输失败 ${resp.code}"))
            o
        }
        val remoteId = offer.getJSONObject("transfer").getString("id")
        if (!Regex("[A-Za-z0-9_-]{1,80}").matches(remoteId)) throw IOException("传输标识无效")
        val id = "${pc.id}:$remoteId"
        val transfer = Transfer(id, name, size, "uploading", 0)
        transferConnections[id] = pc
        updateTransfer(transfer)
        var sentHash = ""
        try {
            val uploadToken = offer.getString("uploadToken")
            val body = object : RequestBody() {
                override fun contentType() = (resolver.getType(uri) ?: "application/octet-stream").toMediaType()
                override fun contentLength() = size
                override fun isOneShot() = true
                override fun writeTo(sink: BufferedSink) {
                    val digest = java.security.MessageDigest.getInstance("SHA-256")
                    resolver.openInputStream(uri)?.use { input ->
                        val buf = ByteArray(256 * 1024)
                        var sent = 0L
                        while (true) {
                            if (id in cancelledTransfers) throw java.io.InterruptedIOException("已取消")
                            val n = input.read(buf)
                            if (n < 0) break
                            if (n == 0) continue
                            sent += n
                            if (sent > size) throw IOException("源文件大小已改变")
                            digest.update(buf, 0, n)
                            sink.write(buf, 0, n)
                            updateTransfer(transfer.copy(progress = if (size > 0) (sent * 99 / size).toInt() else 0))
                        }
                        if (sent != size) throw IOException("源文件大小已改变")
                    } ?: throw IOException("无法读取文件")
                    sentHash = digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
                }
            }
            val req = Request.Builder().url(endpoint(pc, "api/transfers/$remoteId/upload", uploadToken)).post(body).build()
            val call = transferClient.newCall(req)
            transferCalls[id] = call
            if (id in cancelledTransfers) call.cancel()
            call.execute().use { response ->
                val stream = response.body?.byteStream() ?: throw IOException("上传空响应")
                val result = JSONObject(String(TransferIntegrity.readLimited(stream, 1024 * 1024), Charsets.UTF_8))
                if (!response.isSuccessful || result.has("error")) throw IOException(result.optString("error", "上传失败 ${response.code}"))
                val done = result.optJSONObject("transfer") ?: throw IOException("上传响应缺少传输信息")
                if (done.optString("status") != "complete" || done.optLong("size", -1) != size) throw IOException("服务器未完成文件接收")
                val receivedHash = done.optString("sha256")
                if (!TransferIntegrity.validSha256(receivedHash) || !receivedHash.equals(sentHash, true)) throw IOException("服务器文件 SHA-256 校验失败")
            }
            if (id in cancelledTransfers) throw java.io.InterruptedIOException("已取消")
            updateTransfer(transfer.copy(status = "complete", progress = 100))
        } catch (e: Exception) {
            val cancelled = id in cancelledTransfers
            updateTransfer(transfer.copy(status = if (cancelled) "cancelled" else "failed", error = e.message ?: "上传失败"))
            if (cancelled) throw java.io.InterruptedIOException("已取消")
            throw if (e is IOException) e else IOException(e.message ?: "上传失败", e)
        } finally {
            transferCalls.remove(id)
            transferConnections.remove(id)
            cancelledTransfers.remove(id)
        }
    }

    private fun updateTransfer(t: Transfer) {
        val next = if (t.id in cancelledTransfers && t.status !in setOf("failed", "cancelled")) t.copy(status = "cancelled", error = "已取消") else t
        synchronized(transfers) {
            transfers.removeAll { it.id == next.id }; transfers.add(next)
            if (transfers.size > 100) transfers.removeAt(0)
        }
        handler.post { onTransfer?.invoke(next) }
    }

    private fun downloadIncoming(pc: Conn, t: Transfer, sha256: String) {
        Thread {
            var part: File? = null
            transferConnections[t.id] = pc
            try {
                if (!TransferIntegrity.validSha256(sha256)) throw IOException("对方未提供有效 SHA-256，无法验证文件；请升级发送端")
                if (t.size !in 0..TransferIntegrity.MAX_FILE_BYTES) throw IOException("文件大小无效")
                val base = endpoint(pc, "")
                val raw = t.downloadUrl ?: throw IOException("缺少下载地址")
                val url = base.resolve(raw) ?: throw IOException("下载地址无效")
                if (url.scheme != base.scheme || url.host != base.host || url.port != base.port ||
                    url.username.isNotEmpty() || url.password.isNotEmpty() || url.fragment != null ||
                    url.encodedPath != "/api/transfers/${t.id.substringAfter(':')}/download") throw IOException("下载地址不属于已配对目标")
                val tmp = File.createTempFile("incoming-", ".part", cacheDir)
                part = tmp
                val call = transferClient.newCall(Request.Builder().url(url).build())
                transferCalls[t.id] = call
                if (t.id in cancelledTransfers) call.cancel()
                call.execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code}")
                    val body = resp.body ?: throw IOException("空文件响应")
                    if (body.contentLength() >= 0 && body.contentLength() != t.size) throw IOException("文件响应大小不一致")
                    body.byteStream().use { input ->
                        TransferIntegrity.writeVerified(input, tmp, t.size, sha256,
                            cancelled = { t.id in cancelledTransfers }, onProgress = { read ->
                                updateTransfer(t.copy(status = "downloading", progress = if (t.size > 0) (read * 99 / t.size).toInt() else 0))
                            })
                    }
                }
                val where = publishDownload(t.name, tmp) { t.id in cancelledTransfers }
                updateTransfer(t.copy(status = "complete", progress = 100))
                notifyMessage("文件已接收", "${t.name}\n已保存到 $where(文件管理器可见)")
                handler.post { onMediaSaved?.invoke() }
            } catch (e: Exception) {
                val cancelled = t.id in cancelledTransfers || e is java.io.InterruptedIOException && Thread.currentThread().isInterrupted
                updateTransfer(t.copy(status = if (cancelled) "cancelled" else "failed", error = if (cancelled) "已取消" else e.message ?: "下载失败"))
                if (!cancelled) notifyMessage("文件接收失败", "${t.name}: ${e.message}")
            } finally {
                part?.delete()
                transferCalls.remove(t.id)
                transferConnections.remove(t.id)
                cancelledTransfers.remove(t.id)
            }
        }.start()
    }

    private fun cancelActiveTransfer(id: String) {
        val transfer = transfers.firstOrNull { it.id == id } ?: return
        if (transfer.status !in setOf("uploading", "downloading", "offered")) return
        cancelledTransfers.add(id)
        transferCalls[id]?.cancel()
        updateTransfer(transfer.copy(status = "cancelled", error = "已取消"))
        val pc = transferConnections[id] ?: return
        // 接收方只取消自己的下载，不撤销其他接收方的独立一次性 URL。
        if (transfer.status == "uploading") Thread {
            try {
                val request = Request.Builder().url(endpoint(pc, "api/transfers/${id.substringAfter(':')}/cancel"))
                    .post(RequestBody.create(null, ByteArray(0))).build()
                client.newCall(request).execute().close()
            } catch (_: Exception) { }
        }.start()
    }

    /** 先完整暂存输入，再发布；MediaStore 回退时重新打开完整文件，不能复用已读一半的流。 */
    private fun saveToDownloads(name: String, input: java.io.InputStream, onProgress: (Long) -> Unit): String {
        val staged = File.createTempFile("received-", ".part", cacheDir)
        try {
            staged.outputStream().use { out ->
                val buf = ByteArray(256 * 1024)
                var total = 0L
                while (true) {
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n == 0) continue
                    total += n
                    if (total > TransferIntegrity.MAX_FILE_BYTES) throw IOException("文件超过 512 MiB")
                    out.write(buf, 0, n)
                    onProgress(total)
                }
            }
            return publishDownload(name, staged)
        } finally {
            staged.delete()
        }
    }

    private fun publishDownload(name: String, staged: File, cancelled: () -> Boolean = { false }): String {
        val safe = TransferIntegrity.safeName(name)
        fun copyTo(out: java.io.OutputStream) {
            staged.inputStream().use { input ->
                val buf = ByteArray(256 * 1024)
                while (true) {
                    if (cancelled()) throw java.io.InterruptedIOException("已取消")
                    val n = input.read(buf)
                    if (n < 0) break
                    if (n > 0) out.write(buf, 0, n)
                }
            }
            if (cancelled()) throw java.io.InterruptedIOException("已取消")
        }
        if (Build.VERSION.SDK_INT >= 29) {
            var pendingUri: Uri? = null
            try {
                val values = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.Downloads.DISPLAY_NAME, safe)
                    put(android.provider.MediaStore.Downloads.MIME_TYPE, guessMime(safe))
                    put(android.provider.MediaStore.Downloads.RELATIVE_PATH, "Download/LinkAssist")
                    put(android.provider.MediaStore.Downloads.IS_PENDING, 1)
                }
                val uri = contentResolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IOException("无法创建下载文件")
                pendingUri = uri
                contentResolver.openOutputStream(uri)?.use { copyTo(it) } ?: throw IOException("无法打开下载文件")
                val published = android.content.ContentValues().apply { put(android.provider.MediaStore.Downloads.IS_PENDING, 0) }
                if (contentResolver.update(uri, published, null, null) != 1) throw IOException("下载文件发布失败")
                return "Download/LinkAssist/$safe"
            } catch (e: Exception) {
                pendingUri?.let { runCatching { contentResolver.delete(it, null, null) } }
                if (cancelled()) throw e
            }
        }
        val dir = File(getExternalFilesDir(null) ?: filesDir, "Download")
        if (!dir.isDirectory && !dir.mkdirs()) throw IOException("无法创建下载目录")
        val part = File.createTempFile("received-", ".part", dir)
        try {
            java.io.FileOutputStream(part).use { out -> copyTo(out); out.fd.sync() }
            synchronized(downloadPublishLock) {
                var destination = File(dir, safe)
                var suffix = 1
                while (destination.exists()) {
                    val stem = safe.substringBeforeLast('.', safe)
                    val ext = safe.substringAfterLast('.', "")
                    destination = File(dir, "$stem-${suffix++}${if (ext.isBlank()) "" else ".$ext"}")
                }
                if (cancelled()) throw java.io.InterruptedIOException("已取消")
                if (!part.renameTo(destination)) throw IOException("下载文件落盘失败")
                return "应用目录 ${destination.absolutePath}"
            }
        } finally {
            part.delete()
        }
    }

    private fun guessMime(name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
        val map = mapOf(
            "apk" to "application/vnd.android.package-archive", "pdf" to "application/pdf",
            "zip" to "application/zip", "rar" to "application/x-rar-compressed",
            "jpg" to "image/jpeg", "jpeg" to "image/jpeg", "png" to "image/png", "gif" to "image/gif",
            "mp4" to "video/mp4", "mp3" to "audio/mpeg", "txt" to "text/plain", "doc" to "application/msword",
            "docx" to "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "xls" to "application/vnd.ms-excel",
            "xlsx" to "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
        )
        return map[ext] ?: "application/octet-stream"
    }

    /** 把 Download/LinkAssist 里的媒体文件保存到系统相册(Pictures/Movies 下的 LinkAssist) */
    fun saveToGallery(context: android.content.Context, name: String): String {
        val ext = name.substringAfterLast('.', "").lowercase(Locale.getDefault())
        val isVideoExt = ext in setOf("mp4", "mov", "avi", "mkv", "webm", "3gp")
        val isImage = !isVideoExt
        val src = java.io.File(
            android.os.Environment.getExternalStoragePublicDirectory(
                android.os.Environment.DIRECTORY_DOWNLOADS), "LinkAssist/$name")
        if (!src.exists()) throw java.io.IOException("源文件不存在")
        val mime = guessMime(name)
        return if (Build.VERSION.SDK_INT >= 29) {
            val collection = android.provider.MediaStore.Files.getContentUri(
                android.provider.MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val values = android.content.ContentValues().apply {
                put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, name)
                put(android.provider.MediaStore.MediaColumns.MIME_TYPE, mime)
                put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH,
                    if (isImage) "Pictures/LinkAssist" else "Movies/LinkAssist")
            }
            val resolver = context.contentResolver
            val uri = resolver.insert(collection, values) ?: throw java.io.IOException("保存失败")
            try {
                resolver.openOutputStream(uri)?.use { src.inputStream().copyTo(it) }
                    ?: throw java.io.IOException("写入失败")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw e
            }
            (if (isImage) "Pictures/LinkAssist/" else "Movies/LinkAssist/") + name
        } else {
            val dir = java.io.File(
                android.os.Environment.getExternalStoragePublicDirectory(
                    if (isImage) android.os.Environment.DIRECTORY_PICTURES
                    else android.os.Environment.DIRECTORY_MOVIES), "LinkAssist")
            dir.mkdirs()
            val dst = java.io.File(dir, name)
            src.copyTo(dst, overwrite = true)
            dst.absolutePath
        }
    }

    // ------------------------------------------------------------ 状态
    private fun setProfileStatus(pc: Conn, on: Boolean, text: String) {
        pc.connected = on
        pc.status = text
        if (!on) rebuildPeers()
        for (i in profileStates.indices) {
            val s = profileStates[i]
            if (s.id == pc.id) profileStates[i] = ProfileState(pc.id, pc.name, pc.host, pc.port, on, text)
        }
        onProfiles?.invoke()
        refreshAggregatedStatus()
    }

    private fun refreshAggregatedStatus() {
        val total = profileStates.size
        val on = profileStates.count { it.connected }
        val direct = hubRunning && hubPeerCount > 0 && conns[LOCAL_HUB_ID]?.connected == true
        connected = on > 0 || direct
        statusText = when {
            direct && total == 0 -> "手机互连 · $hubPeerCount 台在线"
            total == 0 -> if (hubRunning) "手机互连已开启，等待配对" else "未配置,请添加电脑"
            on == total -> if (total == 1) "已连接 · ${profileStates[0].name}" else "已连接全部 $on/$total 台"
            on == 0 -> "未连接(${total} 台待连接)"
            else -> "部分连接 $on/$total"
        }
        onStatus?.invoke(connected, statusText)
    }

    private fun startForegroundNotify() {
        val pi = PendingIntent.getActivity(
            this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val n = NotificationCompat.Builder(this, CH_SERVICE)
            .setContentTitle("互传助手运行中")
            .setContentText("保持与电脑的实时连接")
            .setSmallIcon(R.drawable.ic_stat)
            .setOngoing(true)
            .setContentIntent(pi)
            .build()
        if (Build.VERSION.SDK_INT >= 29) {
            startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
        } else {
            startForeground(1, n)
        }
    }

    private fun deviceName(): String {
        val manufacturer = (Build.MANUFACTURER ?: "").trim()
        val model = (Build.MODEL ?: "").trim()
        return listOf(manufacturer, model)
            .filter { it.isNotEmpty() && !it.equals("unknown", ignoreCase = true) }
            .joinToString(" ")
            .ifEmpty { "手机" }
    }

    private fun deviceId(): String =
        android.provider.Settings.Secure.getString(
            contentResolver, android.provider.Settings.Secure.ANDROID_ID,
        ) ?: "dev-${hashCode()}"

    private fun notifyMessage(title: String, text: String) {
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return
        val pi = PendingIntent.getActivity(
            this, 1, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
        )
        val largeIcon = android.graphics.BitmapFactory.decodeResource(resources, R.mipmap.ic_launcher)
        val n = NotificationCompat.Builder(this, CH_MESSAGES)
            .setSmallIcon(R.drawable.ic_stat)
            .setLargeIcon(largeIcon)
            .setContentTitle(title)
            .setContentText(text)
            .setStyle(NotificationCompat.BigTextStyle().bigText(text))
            .setAutoCancel(true)
            .setContentIntent(pi)
            .build()
        try {
            getSystemService(NotificationManager::class.java).notify(2001, n)
        } catch (_: SecurityException) {
        }
    }

    private fun createChannels() {
        if (Build.VERSION.SDK_INT >= 26) {
            val nm = getSystemService(NotificationManager::class.java)
            nm.createNotificationChannel(
                NotificationChannel(CH_SERVICE, "后台连接服务", NotificationManager.IMPORTANCE_LOW),
            )
            nm.createNotificationChannel(
                NotificationChannel(CH_MESSAGES, "收到的消息", NotificationManager.IMPORTANCE_HIGH),
            )
        }
    }
}
