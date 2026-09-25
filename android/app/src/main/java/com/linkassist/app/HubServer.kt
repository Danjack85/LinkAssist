package com.linkassist.app

import org.json.JSONArray
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.IOException
import java.io.InputStream
import java.io.OutputStream
import java.net.DatagramPacket
import java.net.DatagramSocket
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.NetworkInterface
import java.net.ServerSocket
import java.net.Socket
import java.security.MessageDigest
import java.util.Base64
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * 手机互连(直连中心):与电脑端同协议的轻量服务,跑在本机前台服务里。
 * 其他手机把它当"一台电脑"添加即可互发聊天/互转验证码/互传文件,无需电脑参与。
 * - WS   /device?token=配对码   (hello/chat/sms/notif/peers/file_offer,含 ping/pong)
 * - POST /api/transfers/offer + /{id}/upload;GET /{id}/download(文件中转)
 * - GET  /api/history?token=&limit=(最近 200 条中转记录,供对方补齐)
 * - POST /api/pair 一次性扫码兑换长期配对码；UDP 37777 只回复地址/端口，不含密钥。
 */
class HubServer(
    private val token: String,
    private val serverName: String,
    private val selfDeviceId: String,
    private val baseDir: File,
    private val onPeers: (Int) -> Unit,
    private val onRelay: (type: String, fromName: String, fromDeviceId: String, body: String, code: String?, ts: Long, fileName: String?) -> Unit,
    private val onFileRelay: (name: String, size: Long, file: File) -> Unit,
) {
    private class Peer(val sock: Socket, val out: OutputStream, var id: String, var name: String)

    private var server: ServerSocket? = null
    private val peers = ConcurrentHashMap<String, Peer>()
    private val history = ArrayList<JSONObject>()
    private val histLock = Any()
    private val seq = AtomicInteger(0)
    private val connSeq = AtomicInteger(0)

    // 文件中转
    private val transferMeta = ConcurrentHashMap<String, JSONObject>()
    private val transferTokens = OneTimeTransferTokens()
    private val pairingSessions = PairingSessions()
    private val activeUploads = ConcurrentHashMap.newKeySet<String>()
    private val cancelledTransfers = ConcurrentHashMap.newKeySet<String>()
    private val fileDir = File(baseDir, "files").apply { mkdirs() }

    fun pairingPayload(): String? = if (running) pairingSessions.payload(lanIp(), HUB_PORT, serverName) else null

    @Volatile
    private var running = false

    @Volatile
    var lastError = ""
        private set

    private var udpSock: DatagramSocket? = null

    fun lanIp(): String {
        try {
            val nis = NetworkInterface.getNetworkInterfaces()
            while (nis.hasMoreElements()) {
                val ni = nis.nextElement()
                if (!ni.isUp || ni.isLoopback) continue
                for (ia in ni.interfaceAddresses) {
                    val a = ia.address ?: continue
                    if (!a.isLoopbackAddress && a.hostAddress?.contains(':') == false) return a.hostAddress ?: ""
                }
            }
        } catch (_: Exception) {
        }
        return "0.0.0.0"
    }

    fun start(): Boolean {
        // App 快速重启时端口可能残留,开 SO_REUSEADDR 并短暂重试
        repeat(4) { attempt ->
            try {
                val ss = ServerSocket()
                ss.reuseAddress = true
                ss.bind(InetSocketAddress(HUB_PORT))
                server = ss
                running = true
                Thread({ acceptLoop(ss) }, "hub-accept").start()
                startDiscovery()
                return true
            } catch (e: Exception) {
                lastError = e.message ?: "端口被占用"
                try {
                    Thread.sleep(400L * (attempt + 1))
                } catch (_: InterruptedException) {
                }
            }
        }
        return false
    }

    fun stop() {
        running = false
        pairingSessions.clear()
        transferTokens.clear()
        cancelledTransfers.addAll(activeUploads)
        activeUploads.clear()
        cancelledTransfers.clear()
        try {
            udpSock?.close()
        } catch (_: Exception) {
        }
        try {
            server?.close()
        } catch (_: Exception) {
        }
        for (p in peers.values) try {
            p.sock.close()
        } catch (_: Exception) {
        }
        peers.clear()
        onPeers(0)
    }

    private fun acceptLoop(ss: ServerSocket) {
        while (running) {
            val sock = try {
                ss.accept()
            } catch (_: Exception) {
                break
            }
            Thread({ handle(sock) }, "hub-conn").start()
        }
    }

    private fun handle(sock: Socket) {
        var connKey: String? = null
        try {
            sock.soTimeout = 15000
            val ins = sock.getInputStream()
            val head = readHead(ins) ?: return
            val reqLine = head.lineSequence().firstOrNull() ?: return
            val parts = reqLine.split(" ")
            if (parts.size < 2) return
            val target = parts[1]
            val path = target.substringBefore("?")
            val query = target.substringAfter("?", "")
            if (target.length > 8192) { httpRaw(sock, 414, errJson("request target too long")); return }
            val qToken = queryValue(query, "token")
            if (path == "/api/pair") {
                if (parts[0] != "POST") { httpRaw(sock, 405, errJson("POST required")); return }
                handlePair(sock, ins, head)
                return
            }
            // 临时令牌只能用于其对应的传输端点，不能拿来访问历史、WS 或创建其他传输。
            val upload = Regex("^/api/transfers/([A-Za-z0-9_-]{1,80})/upload$").matchEntire(path)
            val download = Regex("^/api/transfers/([A-Za-z0-9_-]{1,80})/download$").matchEntire(path)
            if (upload != null) {
                if (parts[0] != "POST") { httpRaw(sock, 405, errJson("POST required")); return }
                val out = handleUpload(upload.groupValues[1], query, head, ins)
                httpRaw(sock, if (JSONObject(out).has("error")) 400 else 200, out)
                return
            }
            if (download != null) {
                if (parts[0] != "GET") { httpRaw(sock, 405, errJson("GET required")); return }
                httpFile(sock, download.groupValues[1], query)
                return
            }
            if (qToken.isEmpty() || !MessageDigest.isEqual(qToken.toByteArray(), token.toByteArray())) {
                httpRaw(sock, 401, errJson("unauthorized"))
                return
            }
            if (path == "/api/transfers/offer" && parts[0] == "POST") {
                val out = handleOffer(readBody(ins, head, 16384))
                httpRaw(sock, if (JSONObject(out).has("error")) 400 else 200, out)
                return
            }
            Regex("^/api/transfers/([A-Za-z0-9_-]{1,80})/cancel$").matchEntire(path)?.let { m ->
                if (parts[0] != "POST") { httpRaw(sock, 405, errJson("POST required")); return }
                val id = m.groupValues[1]
                val item = transferMeta[id]
                if (item == null) { httpRaw(sock, 404, errJson("transfer not found")); return }
                cancelledTransfers.add(id)
                transferTokens.revoke(id)
                item.put("status", "cancelled")
                sendOthers(null, JSONObject().put("type", "file_error").put("transfer", JSONObject(item.toString())))
                httpRaw(sock, 200, "{\"ok\":true}")
                return
            }
            if (path == "/api/history" && parts[0] == "GET") {
                val limit = (Regex("limit=(\\d+)").find(query)?.groupValues?.get(1)?.toIntOrNull() ?: 200)
                    .coerceIn(1, 200)
                val arr = JSONArray()
                synchronized(histLock) {
                    val recipient = queryValue(query, "deviceId")
                    history.takeLast(limit).filter {
                        val targetId = it.optString("targetDeviceId")
                        targetId.isBlank() || (recipient.isNotBlank() && (targetId == recipient || it.optString("fromDeviceId") == recipient))
                    }.forEach { arr.put(it) }
                }
                httpRaw(sock, 200, JSONObject().put("messages", arr).toString())
                return
            }
            if (path != "/device") {
                httpRaw(sock, 404, "{\"error\":\"not found\"}")
                return
            }
            // ---- WebSocket 升级 ----
            val key = Regex("(?i)sec-websocket-key:\\s*(\\S+)").find(head)?.groupValues?.get(1) ?: return
            val accept = Base64.getEncoder().encodeToString(
                MessageDigest.getInstance("SHA-1")
                    .digest((key + WS_GUID).toByteArray(Charsets.US_ASCII)),
            )
            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\n" +
                    "Sec-WebSocket-Accept: $accept\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            out.flush()
            sock.soTimeout = 0
            connKey = "c${connSeq.incrementAndGet()}"
            val peer = Peer(sock, out, "pending-$connKey", "设备")
            peers[connKey] = peer
            readFrames(connKey, peer, ins)
        } catch (_: Exception) {
        } finally {
            if (connKey != null) dropPeer(connKey) else try {
                sock.close()
            } catch (_: Exception) {
            }
        }
    }

    private fun readHead(ins: InputStream): String? {
        val sb = StringBuilder()
        val buf = ByteArray(1)
        while (sb.length < 16384) {
            val n = ins.read(buf)
            if (n < 0) return null
            sb.append(buf[0].toInt().toChar())
            if (sb.endsWith("\r\n\r\n")) return sb.toString()
        }
        return null
    }

    private fun queryValue(query: String, name: String): String {
        val values = query.split('&').filter { it.substringBefore('=') == name }
        if (values.size != 1) return ""
        return try { java.net.URLDecoder.decode(values.single().substringAfter('=', ""), "UTF-8") } catch (_: Exception) { "" }
    }

    private fun contentLength(head: String): Long {
        if (head.lineSequence().any { it.startsWith("Transfer-Encoding:", true) }) throw IOException("chunked bodies not supported")
        val lengths = head.lineSequence().filter { it.startsWith("Content-Length:", true) }.toList()
        if (lengths.size != 1) throw IOException("Content-Length required")
        return lengths.single().substringAfter(':').trim().toLongOrNull()?.takeIf { it >= 0 }
            ?: throw IOException("invalid Content-Length")
    }

    private fun readBody(ins: InputStream, head: String, max: Int): ByteArray {
        val len = contentLength(head)
        if (len > max) throw IOException("body too large")
        val buf = ByteArray(len.toInt())
        readFull(ins, buf)
        return buf
    }

    private fun handlePair(sock: Socket, ins: InputStream, head: String) {
        try {
            val o = JSONObject(String(readBody(ins, head, 4096), Charsets.UTF_8))
            if (o.length() != 2 || o.opt("key") !is String || o.opt("deviceName") !is String) {
                httpRaw(sock, 400, errJson("expected key and deviceName")); return
            }
            val key = o.getString("key")
            val name = o.getString("deviceName")
            if (!PairingSessions.validKey(key) || name.isBlank() || name.length > 80 || name.any { it.isISOControl() }) {
                httpRaw(sock, 400, errJson("invalid pairing fields")); return
            }
            if (!pairingSessions.consume(key, name)) {
                httpRaw(sock, 401, errJson("pairing key expired, consumed or invalid")); return
            }
            httpRaw(sock, 200, JSONObject().put("token", token).put("name", serverName.take(80)).put("port", HUB_PORT).toString())
        } catch (_: Exception) {
            httpRaw(sock, 400, errJson("invalid or oversized pairing request"))
        }
    }

    // ------------------------------------------------------------ 文件中转
    private fun handleOffer(body: ByteArray): String {
        return try {
            val o = JSONObject(String(body, Charsets.UTF_8))
            val fromDeviceId = o.optString("fromDeviceId")
            val target = o.optString("targetDeviceId")
            if (fromDeviceId.isBlank() || fromDeviceId.length > 80 || target.length > 80 ||
                fromDeviceId.any { it.isISOControl() } || target.any { it.isISOControl() }) return errJson("invalid device id")
            val name = TransferIntegrity.safeName(o.optString("name"))
            val rawSize = o.opt("size")
            if (rawSize !is Int && rawSize !is Long) return errJson("file size invalid")
            val size = (rawSize as Number).toLong()
            if (size < 0 || size > MAX_FILE_SIZE) return errJson("file size invalid")
            val hash = o.optString("sha256")
            if (hash.isNotBlank() && !TransferIntegrity.validSha256(hash)) return errJson("invalid sha256")
            if (transferMeta.size >= 1000) return errJson("too many transfers; restart hub to clear metadata")
            val id = java.util.UUID.randomUUID().toString().replace("-", "")
            val item = JSONObject().apply {
                put("id", id); put("name", name); put("size", size)
                put("mime", o.optString("mime").take(120).ifBlank { "application/octet-stream" })
                put("direction", "phone_to_phone"); put("status", "offered"); put("progress", 0)
                put("fromDeviceId", fromDeviceId)
                put("targetDeviceId", target)
                put("sha256", hash)
                put("created", System.currentTimeMillis())
            }
            transferMeta[id] = item
            val upToken = newToken(id, "upload")
            JSONObject().put("transfer", item).put("uploadToken", upToken).toString()
        } catch (e: Exception) {
            errJson("invalid transfer metadata")
        }
    }

    private fun handleUpload(id: String, query: String, head: String, ins: InputStream): String {
        val item = transferMeta[id] ?: return errJson("transfer not found")
        if (!transferTokens.consume(queryValue(query, "token"), id, "upload")) return errJson("invalid upload token")
        if (item.optString("status") != "offered" || !activeUploads.add(id)) return errJson("transfer already started")
        val part = File(fileDir, "$id.part")
        val fin = File(fileDir, "$id.bin")
        try {
            val size = item.getLong("size")
            if (contentLength(head) != size) throw IOException("upload Content-Length mismatch")
            item.put("status", "uploading")
            // Socket 是 keep-alive 流，使用固定 Content-Length 子流，不等待请求方关闭连接。
            var left = size
            val bounded = object : InputStream() {
                override fun read(): Int {
                    if (left == 0L) return -1
                    val value = ins.read()
                    if (value >= 0) left--
                    return value
                }
                override fun read(b: ByteArray, off: Int, len: Int): Int {
                    if (left == 0L) return -1
                    val n = ins.read(b, off, minOf(len.toLong(), left).toInt())
                    if (n > 0) left -= n
                    return n
                }
            }
            val hash = TransferIntegrity.writeVerified(bounded, part, size, item.optString("sha256").ifBlank { null },
                cancelled = { !running || id in cancelledTransfers })
            if (id in cancelledTransfers) throw IOException("transfer cancelled")
            if (!part.renameTo(fin)) throw IOException("could not commit uploaded file")
            item.put("sha256", hash).put("status", "complete").put("progress", 100)
            val target = item.optString("targetDeviceId")
            val fromId = item.optString("fromDeviceId")
            // 每个接收连接单独发放且只用一次；上传响应也拿独立 token，绝不复用广播 URL。
            for ((key, peer) in peers) {
                if (peer.id.startsWith("pending-") || peer.id == fromId || peer.id == selfDeviceId) continue
                if (target.isNotBlank() && target != peer.id) continue
                trySend(key, JSONObject().put("type", "file_offer").put("transfer", withDownload(item)))
            }
            if (fromId != selfDeviceId && (target.isBlank() || target == selfDeviceId)) {
                onFileRelay(item.optString("name"), size, fin)
            }
            return JSONObject().put("transfer", withDownload(item)).toString()
        } catch (e: Exception) {
            fin.delete()
            transferTokens.revoke(id)
            item.put("status", if (id in cancelledTransfers) "cancelled" else "failed")
                .put("error", e.message ?: "upload failed")
            return errJson(e.message ?: "upload failed")
        } finally {
            part.delete()
            activeUploads.remove(id)
        }
    }

    private fun withDownload(item: JSONObject): JSONObject = JSONObject(item.toString()).apply {
        val id = item.getString("id")
        put("downloadUrl", "/api/transfers/$id/download?token=${newToken(id, "download")}")
    }

    private fun httpFile(sock: Socket, id: String, query: String) {
        try {
            val item = transferMeta[id]
            val dlToken = queryValue(query, "token")
            if (item == null || item.optString("status") != "complete" || id in cancelledTransfers ||
                !transferTokens.consume(dlToken, id, "download")) {
                httpRaw(sock, 401, errJson("invalid download token"))
                return
            }
            val f = File(fileDir, "$id.bin")
            if (!f.exists()) {
                httpRaw(sock, 404, errJson("file missing"))
                return
            }
            val out = sock.getOutputStream()
            out.write(
                ("HTTP/1.1 200 OK\r\nContent-Type: application/octet-stream\r\n" +
                    "Content-Length: ${f.length()}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII),
            )
            FileInputStream(f).use { ins -> ins.copyTo(out) }
            out.flush()
            sock.close()
        } catch (_: Exception) {
        }
    }

    private fun newToken(id: String, purpose: String): String = transferTokens.issue(id, purpose)

    private fun errJson(msg: String): String = JSONObject().put("error", msg).toString()

    private fun readFrames(connKey: String, peer: Peer, ins: InputStream) {
        try {
            while (running) {
                val b0 = ins.read()
                if (b0 < 0) break
                val b1 = ins.read()
                if (b1 < 0) break
                val opcode = b0 and 0x0F
                val masked = (b1 and 0x80) != 0
                var len = (b1 and 0x7F).toLong()
                if (len == 126L) {
                    len = ((ins.read() and 0xFF).toLong() shl 8) or (ins.read() and 0xFF).toLong()
                } else if (len == 127L) {
                    val ext = ByteArray(8)
                    readFull(ins, ext)
                    len = 0
                    for (b in ext) len = (len shl 8) or (b.toLong() and 0xFF)
                }
                if (!masked || b0 and 0x80 == 0 || b0 and 0x70 != 0 || len < 0 || len > 128 * 1024 ||
                    (opcode >= 8 && len > 125)) break
                val mask = if (masked) ByteArray(4).also { readFull(ins, it) } else null
                val payload = ByteArray(len.toInt())
                if (len > 0) readFull(ins, payload)
                if (mask != null) {
                    for (i in payload.indices) {
                        payload[i] = (payload[i].toInt() xor mask[i % 4].toInt()).toByte()
                    }
                }
                when (opcode) {
                    1 -> onText(connKey, peer, String(payload, Charsets.UTF_8))
                    8 -> break                       // close
                    9 -> sendRaw(peer.out, frame(10, payload)) // ping -> pong
                    else -> {
                    }
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun onText(connKey: String, peer: Peer, text: String) {
        try {
            val o = JSONObject(text)
            if (o.optString("type") != "hello" && peer.id.startsWith("pending-")) return
            when (o.optString("type")) {
                "hello" -> {
                    val newId = o.optString("deviceId").ifBlank { "dev-$connKey" }
                    if (newId.length > 80 || newId.any { it.isISOControl() } ||
                        (newId == selfDeviceId && !peer.sock.inetAddress.isLoopbackAddress)) return
                    peer.id = newId
                    peer.name = o.optString("device").ifBlank { "设备" }.take(80)
                    // 同一台设备重复连接(网络切换重连):踢掉旧连接,只保留最新
                    for ((k, other) in peers) {
                        if (k != connKey && other.id == newId) {
                            try {
                                other.sock.close()
                            } catch (_: Exception) {
                            }
                            peers.remove(k)
                        }
                    }
                    broadcastPeers()
                }
                "chat" -> {
                    val text2 = o.optString("text").ifBlank { o.optString("body") }
                    if (text2.isBlank() || text2.length > 32768) return
                    val ts = o.optLong("ts", 0).takeIf { it > 0 } ?: System.currentTimeMillis()
                    val fileName2 = o.optString("fileName").ifBlank { null }
                    val target = o.optString("targetDeviceId")
                    if (target.length > 80) return
                    store("chat", peer.name, peer.id, text2, null, ts, target, fileName2)
                    // 中心手机消费条件:广播消息,或定向给中心自己;自己发的不回显
                    val forSelf = (target.isBlank() || target == selfDeviceId) && peer.id != selfDeviceId
                    if (forSelf) {
                        onRelay("chat", peer.name, peer.id, text2, null, ts, fileName2)
                    }
                    val payload = JSONObject().apply {
                        put("type", "chat"); put("text", text2); put("ts", ts)
                        put("from", peer.name); put("fromDeviceId", peer.id)
                        put("targetDeviceId", target)
                        fileName2?.let { put("fileName", it.take(120)) }
                    }
                    if (target.isNotBlank()) sendTo(target, payload, excludeDeviceId = peer.id)
                    else sendOthers(connKey, payload)
                }
                "sms", "notif" -> {
                    val body = o.optString("body")
                    val target = o.optString("targetDeviceId")
                    if (body.isBlank() || body.length > 32768 || target.length > 80) return
                    val ts = o.optLong("ts", 0).takeIf { it > 0 } ?: System.currentTimeMillis()
                    val code = o.optString("code").take(80).ifBlank { null }
                    store(o.optString("type"), o.optString("from").take(120), peer.id, body, code, ts, target)
                    if (peer.id != selfDeviceId && (target.isBlank() || target == selfDeviceId)) {
                        onRelay(o.optString("type"), peer.name, peer.id, body, code, ts, null)
                    }
                    val payload = JSONObject().apply {
                        put("type", o.optString("type")); put("from", o.optString("from").take(120))
                        put("body", body); put("code", code ?: "")
                        put("title", o.optString("title").take(200))
                        put("targetDeviceId", target)
                        put("ts", ts); put("fromDeviceId", peer.id); put("fromDevice", peer.name)
                    }
                    if (target.isNotBlank()) sendTo(target, payload, excludeDeviceId = peer.id)
                    else sendOthers(connKey, payload)
                }
            }
        } catch (_: Exception) {
        }
    }

    private fun store(type: String, from: String, fromDeviceId: String, body: String, code: String?, ts: Long,
        target: String = "", fileName: String? = null) {
        synchronized(histLock) {
            history.add(
                JSONObject().apply {
                    put("id", seq.incrementAndGet()); put("type", type); put("direction", "in")
                    put("from", from); put("fromDeviceId", fromDeviceId)
                    put("body", body); put("code", code ?: ""); put("ts", ts)
                    if (type == "chat") put("text", body)
                    put("targetDeviceId", target)
                    fileName?.let { put("fileName", it.take(120)) }
                },
            )
            while (history.size > 200) history.removeAt(0)
        }
    }

    private fun sendOthers(excludeConn: String?, payload: JSONObject) {
        val fromDeviceId = payload.optJSONObject("transfer")?.optString("fromDeviceId")
            ?: payload.optString("fromDeviceId")
        for ((k, p) in peers) {
            if (k != excludeConn && p.id != fromDeviceId && p.id != selfDeviceId && !p.id.startsWith("pending-")) trySend(k, payload)
        }
    }

    private fun sendTo(deviceId: String, payload: JSONObject, excludeDeviceId: String = "") {
        for ((k, p) in peers) {
            if (p.id == deviceId && p.id != excludeDeviceId && p.id != selfDeviceId) trySend(k, payload)
        }
    }

    private fun trySend(key: String, payload: JSONObject) {
        val p = peers[key] ?: return
        try {
            sendRaw(p.out, frame(1, payload.toString().toByteArray(Charsets.UTF_8)))
        } catch (_: Exception) {
            dropPeer(key)
        }
    }

    private fun broadcastPeers() {
        val external = peers.values.filter { it.id != selfDeviceId && !it.id.startsWith("pending-") }.distinctBy { it.id }
        for ((key, recipient) in peers) {
            val arr = JSONArray()
            // 中心的内部 loopback 不作为在线设备计数，其他手机仍可定向发送给中心身份。
            if (recipient.id != selfDeviceId) arr.put(JSONObject().put("id", selfDeviceId).put("name", serverName))
            for (p in external) if (p.id != recipient.id) arr.put(JSONObject().put("id", p.id).put("name", p.name))
            trySend(key, JSONObject().put("type", "peers").put("devices", arr))
        }
        onPeers(external.size)
    }

    private fun dropPeer(key: String) {
        val p = peers.remove(key) ?: return
        try {
            p.sock.close()
        } catch (_: Exception) {
        }
        if (running) broadcastPeers()
    }

    private fun httpRaw(sock: Socket, code: Int, body: String) {
        try {
            val bytes = body.toByteArray(Charsets.UTF_8)
            sock.getOutputStream().write(
                ("HTTP/1.1 $code X\r\nContent-Type: application/json; charset=utf-8\r\n" +
                    "Content-Length: ${bytes.size}\r\nConnection: close\r\n\r\n").toByteArray(Charsets.US_ASCII) + bytes,
            )
            sock.close()
        } catch (_: Exception) {
        }
    }

    private fun startDiscovery() {
        Thread({
            try {
                val s = DatagramSocket(null)
                s.reuseAddress = true
                s.bind(InetSocketAddress(HUB_PORT_DISCOVER))
                udpSock = s
                val fallbackIp = lanIp()
                val buf = ByteArray(1024)
                while (running) {
                    val p = DatagramPacket(buf, buf.size)
                    try {
                        s.receive(p)
                    } catch (_: Exception) {
                        break
                    }
                    val msg = String(buf, 0, p.length, Charsets.UTF_8)
                    if (msg.trim().startsWith("LINKASSIST_DISCOVER")) {
                        // 关键:按"对方可达的那个本机接口"回复地址,否则多网卡设备会回一个不可达的虚拟接口 IP
                        val replyIp = localAddrFor(p.address).ifBlank { fallbackIp }
                        val reply = JSONObject()
                            .put("app", "linkassist")
                            .put("name", "$serverName(手机直连)")
                            .put("host", replyIp)
                            .put("port", HUB_PORT)
                            .put("pairingRequired", true)
                            .put("kind", "phone")
                            .toString().toByteArray(Charsets.UTF_8)
                        s.send(DatagramPacket(reply, reply.size, p.address, p.port))
                    }
                }
            } catch (_: Exception) {
            }
        }, "hub-discover").start()
    }

    /** 计算本机与 target 通信时所用的接口地址(标准 UDP connect 技巧) */
    private fun localAddrFor(target: InetAddress): String = try {
        java.net.DatagramSocket().use { s ->
            s.connect(InetSocketAddress(target, HUB_PORT_DISCOVER))
            s.localAddress.hostAddress ?: ""
        }
    } catch (_: Exception) {
        ""
    }

    companion object {
        const val HUB_PORT = 8765
        const val HUB_PORT_DISCOVER = 37777
        private const val WS_GUID = "258EAFA5-E914-47DA-95CA-C5AB0DC85B11"
        private const val MAX_FILE_SIZE = 512L * 1024 * 1024

        private fun readFull(ins: InputStream, buf: ByteArray) {
            var off = 0
            while (off < buf.size) {
                val n = ins.read(buf, off, buf.size - off)
                if (n < 0) throw IOException("eof")
                off += n
            }
        }

        private fun frame(opcode: Int, payload: ByteArray): ByteArray {
            val out = ByteArrayOutputStream()
            out.write(0x80 or opcode)
            val len = payload.size
            if (len < 126) {
                out.write(len)
            } else if (len < 65536) {
                out.write(126)
                out.write((len shr 8) and 0xFF)
                out.write(len and 0xFF)
            } else {
                out.write(127)
                val l = len.toLong()
                for (s in 56 downTo 0 step 8) out.write(((l shr s) and 0xFF).toInt())
            }
            out.write(payload)
            return out.toByteArray()
        }

        private fun sendRaw(out: OutputStream, data: ByteArray) {
            synchronized(out) {
                out.write(data)
                out.flush()
            }
        }
    }
}
