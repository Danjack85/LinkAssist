package com.linkassist.app

import android.content.Context
import android.content.Intent
import android.content.pm.PackageInfo
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Looper
import androidx.core.content.FileProvider
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.Response
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
import java.io.InterruptedIOException
import java.net.ConnectException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import java.util.concurrent.Callable
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * GitHub Release 优先、已配对电脑回退的更新器。检查与下载必须由后台线程调用。
 *
 * 体验约定(与"计划事件 PlanFocus"一致):
 * - 检查失败只回一句人话,不把逐台电脑的超时堆栈抛给用户;
 * - 已配对电脑的探测并行 + 短超时,离线电脑不会把检查拖成几十秒;
 * - 下载优先走 api.github.com 资产接口(github.com 主站被阻断的网络也可用),
 *   失败自动回退直链;中断保留下载进度,重试时断点续传。
 */
object Updater {

    data class Remote(
        val host: String,
        val port: Int,
        val token: String,
        val versionName: String,
        val versionCode: Long,
        val size: Long,
        val source: String = "pc",
        val notes: String = "",
        val releaseUrl: String = "",
        val downloadUrl: String = "",
        val fallbackUrl: String = "",
        val sha256: String = "",
    )

    // 禁用自动重定向，每一跳在发出请求前验证，避免 HTTPS 降级与凭据外泄。
    private val client = OkHttpClient.Builder()
        .connectTimeout(5, TimeUnit.SECONDS)
        .readTimeout(8, TimeUnit.SECONDS)
        .callTimeout(15, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()

    /** 局域网电脑探测专用:短超时,离线电脑直接跳过 */
    private val probeClient = OkHttpClient.Builder()
        .connectTimeout(1500, TimeUnit.MILLISECONDS)
        .readTimeout(2500, TimeUnit.MILLISECONDS)
        .callTimeout(3000, TimeUnit.MILLISECONDS)
        .build()

    private val downloadClient = client.newBuilder().callTimeout(10, TimeUnit.MINUTES).build()
    private val probePool = Executors.newFixedThreadPool(8)
    private val verifiedDownloads = ConcurrentHashMap<String, Remote>()

    private fun localVersionCode(ctx: Context): Long = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode
            else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
    } catch (_: Exception) {
        0L
    }

    fun currentVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    /** 返回 (比本机新的可信更新, 是否成功读取有效更新源, 失败时的一句人话)。不需要先配对电脑。 */
    fun checkAll(ctx: Context): Triple<Remote?, Boolean, String> {
        if (Looper.myLooper() == Looper.getMainLooper()) return Triple(null, false, "请在后台线程检查更新")
        val local = localVersionCode(ctx)
        try {
            val remote = githubLatest(Prefs.updateRepository(ctx))
            return Triple(remote.takeIf { it.versionCode > local }, true, "")
        } catch (e: Exception) {
            val githubError = describeGithubFailure(e)
            // GitHub 不可达时,并行、短超时地探测已配对电脑(局域网更新源)
            val targets = LinkedHashSet<Pair<String, Int>>()
            LinkService.runtimeTargets().forEach { targets.add(it.first to it.second) }
            Prefs.profiles(ctx).forEach { targets.add(it.host to it.port) }
            if (targets.isEmpty()) return Triple(null, false, "$githubError；未配置可回退的已配对电脑")
            val tokens = Prefs.profiles(ctx).associate { (it.host to it.port) to it.token }
            val futures = targets.take(20).map { target ->
                val (host, port) = target
                val token = tokens[target] ?: ""
                probePool.submit(Callable {
                    if (token.isBlank()) return@Callable null
                    runCatching { probePc(host, port, token) }.getOrNull()
                })
            }
            var best: Remote? = null
            var reached = false
            for (future in futures) {
                val remote = try {
                    future.get(4, TimeUnit.SECONDS)
                } catch (_: Exception) {
                    continue
                } ?: continue
                reached = true
                if (remote.versionCode > local && remote.versionCode > (best?.versionCode ?: 0L)) best = remote
            }
            return if (reached) Triple(best, true, "")
            else Triple(null, false, "$githubError；已配对电脑均不可达（共 ${targets.size} 台，需与电脑连同一 Wi-Fi）")
        }
    }

    /** 把底层网络异常翻译成一句用户看得懂的话 */
    private fun describeGithubFailure(e: Exception): String {
        val message = e.message.orEmpty()
        return when {
            e is UnknownHostException || e is SocketTimeoutException || e is ConnectException ||
                message.contains("timeout", true) || message.contains("Failed to connect", true) ||
                message.contains("Unable to resolve host", true) ||
                message.contains("Network is unreachable", true) ||
                message.contains("Connection reset", true) ||
                message.contains("unexpected end of stream", true) ->
                "当前网络无法连接 GitHub 更新服务器"
            message.startsWith("GitHub") || message.startsWith("仓库") ||
                message.startsWith("Release") || message.startsWith("更新") ->
                message
            else -> "GitHub 更新检查失败"
        }
    }

    /** 单台电脑的版本探测;短超时,失败静默 */
    private fun probePc(host: String, port: Int, token: String): Remote {
        val url = pcUrl(host, port, token, "version")
        probeClient.newCall(Request.Builder().url(url).build()).execute().use { resp ->
            if (!resp.isSuccessful) throw IOException(
                when (resp.code) {
                    404 -> "电脑尚未发布 APK"
                    401 -> "配对码已失效"
                    else -> "HTTP ${resp.code}"
                },
            )
            val o = jsonBody(resp)
            val name = o.getString("versionName")
            val code = strictLong(o, "versionCode")
            val size = strictLong(o, "size")
            val hash = o.optString("sha256")
            UpdatePolicy.apkMetadata(name, code, size, hash)
            return Remote(host, port, token, name, code, size, sha256 = hash,
                downloadUrl = pcUrl(host, port, token, "download").toString())
        }
    }

    private fun githubLatest(repo: String): Remote {
        val release = client.newCall(Request.Builder().url(UpdatePolicy.latestApi(repo))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "LinkAssist-Android").build()).execute().use { resp ->
            if (resp.code == 404) throw IOException("仓库尚未发布可用的 Release")
            if (resp.code == 403 || resp.code == 429) throw IOException("GitHub 请求过于频繁，请稍后重试")
            if (!resp.isSuccessful) throw IOException("GitHub HTTP ${resp.code}")
            jsonBody(resp)
        }
        require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "未找到正式 Release" }
        val tag = release.getString("tag_name")
        require(tag.length in 1..128) { "Release tag 无效" }
        val releaseUrl = UpdatePolicy.releaseUrl(release.getString("html_url"), repo, tag)
        val assets = release.getJSONArray("assets")
        val manifestAsset = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name") == "linkassist-update.json" }
        require(manifestAsset.size == 1) { "Release 未发布唯一的 linkassist-update.json" }
        val manifestItem = manifestAsset.single()
        require(strictLong(manifestItem, "size") in 1..UpdatePolicy.MAX_METADATA_BYTES.toLong()) { "更新清单大小无效" }
        val manifestUrl = assetFetchUrl(repo, manifestItem, tag, "linkassist-update.json")
        val manifest = githubAsset(client, manifestUrl, repo).use { resp ->
            if (!resp.isSuccessful) throw IOException(if (resp.code == 404) "仓库尚未发布可用的 Release" else "更新清单 HTTP ${resp.code}")
            jsonBody(resp)
        }
        require(strictLong(manifest, "schemaVersion") == 1L) { "不支持的更新清单版本" }
        val version = UpdatePolicy.versionName(manifest.getString("version"))
        val notes = if (manifest.has("notes")) manifest.getString("notes") else ""
        require(notes.length <= 65536) { "更新说明过长" }
        val android = manifest.getJSONObject("android")
        val name = android.getString("versionName")
        require(name == version) { "Android 版本与更新清单不一致" }
        val code = strictLong(android, "versionCode")
        val size = strictLong(android, "size")
        val hash = android.getString("sha256")
        UpdatePolicy.apkMetadata(name, code, size, hash)
        val file = android.getString("file")
        require(file.length in 5..200 && file.endsWith(".apk", true)) { "APK 文件名无效" }
        val directUrl = UpdatePolicy.assetUrl(android.getString("url"), repo, tag, file)
        val apkAssets = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name") == file }
        val primary = if (apkAssets.size == 1) assetFetchUrl(repo, apkAssets.single(), tag, file) else directUrl
        return Remote("", 443, "", name, code, size, "github", notes, releaseUrl,
            primary, if (primary != directUrl) directUrl else "", hash)
    }

    private fun strictLong(o: JSONObject, key: String): Long {
        val value = o.get(key)
        require(value is Int || value is Long) { "$key 必须为整数" }
        return (value as Number).toLong()
    }

    private fun jsonBody(resp: Response): JSONObject {
        val body = resp.body ?: throw IOException("更新接口空响应")
        if (body.contentLength() > UpdatePolicy.MAX_METADATA_BYTES) throw IOException("更新元数据超过 1 MiB")
        return JSONObject(String(TransferIntegrity.readLimited(body.byteStream(), UpdatePolicy.MAX_METADATA_BYTES), Charsets.UTF_8))
    }

    private fun pcUrl(host: String, port: Int, token: String, action: String): HttpUrl {
        require(host.isNotBlank() && port in 1..65535 && token.isNotBlank()) { "电脑配对配置无效" }
        return HttpUrl.Builder().scheme("http").host(host).port(port)
            .addPathSegments("api/app/$action").addQueryParameter("token", token).build()
    }

    /** 资产地址:优先 api.github.com 资产接口(受限网络可用),取不到 id 再退回 github.com 直链 */
    private fun assetFetchUrl(repo: String, asset: JSONObject, tag: String?, file: String?): String =
        try {
            UpdatePolicy.assetApiUrl(repo, strictLong(asset, "id"))
        } catch (_: Exception) {
            UpdatePolicy.assetUrl(asset.getString("browser_download_url"), repo, tag, file)
        }

    private fun githubAsset(
        http: OkHttpClient,
        initial: String,
        repo: String,
        extraHeader: ((Request.Builder) -> Unit)? = null,
    ): Response {
        var url = (if (initial.startsWith("https://api.github.com/repos/")) initial
        else UpdatePolicy.assetUrl(initial, repo)).toHttpUrl()
        repeat(6) { hop ->
            val builder = Request.Builder().url(url)
                .header("User-Agent", "LinkAssist-Android")
                .header("Accept", "application/octet-stream")
            extraHeader?.invoke(builder)
            val resp = http.newCall(builder.build()).execute()
            if (resp.code !in setOf(301, 302, 303, 307, 308)) return resp
            val next = resp.header("Location")?.let { url.resolve(it) }
            resp.close()
            if (hop == 5 || next == null) throw IOException("GitHub 资产重定向无效或次数过多")
            UpdatePolicy.assetRedirect(next.toString(), repo)
            url = next
        }
        throw IOException("GitHub 资产重定向失败")
    }

    /**
     * 流式下载、断点续传、大小/摘要/包信息验证全部通过后，才发布为 .apk。
     * 中断(网络掉线/用户取消)时保留下载进度,下次调用自动续传。
     */
    fun download(
        ctx: Context,
        remote: Remote,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean = { false },
    ): Pair<File?, String?> {
        if (Looper.myLooper() == Looper.getMainLooper()) return null to "请在后台线程下载更新"
        try {
            UpdatePolicy.apkMetadata(remote.versionName, remote.versionCode, remote.size, remote.sha256)
            require(remote.versionCode > localVersionCode(ctx)) { "更新版本不高于本机版本" }
            val dir = File(ctx.cacheDir, "apk").apply { if (!isDirectory && !mkdirs()) throw IOException("无法创建更新缓存") }
            val part = File(dir, "update-${remote.versionCode}.apk.part")
            val urls = listOf(remote.downloadUrl, remote.fallbackUrl)
                .filter { it.isNotBlank() }.distinct()
            require(urls.isNotEmpty()) { "更新下载地址无效" }
            if (part.isFile && part.length() >= remote.size) part.delete()

            var lastError = "下载失败"
            for (url in urls) {
                if (part.isFile && part.length() == remote.size) break
                try {
                    downloadOnce(ctx, remote, url, part, onProgress, cancelled)
                    lastError = ""
                } catch (cancelledError: InterruptedIOException) {
                    return null to "已暂停下载，进度已保留，可稍后继续"
                } catch (e: Exception) {
                    lastError = e.message?.take(160) ?: "下载失败"
                }
            }
            if (!part.isFile || part.length() != remote.size) {
                val kept = part.isFile && part.length() in 1 until remote.size
                return null to (lastError.ifBlank { "下载未完成" } + if (kept) "，已保留进度，重试可续传" else "")
            }
            val actual = TransferIntegrity.sha256(part)
            if (!actual.equals(remote.sha256, ignoreCase = true)) {
                part.delete()
                return null to "SHA-256 校验失败，已删除损坏文件，请重新下载"
            }
            validatePackage(ctx, part, remote)
            val out = File(dir, "update-${remote.versionCode}.apk")
            out.delete()
            if (!part.renameTo(out)) throw IOException("无法完成 APK 缓存落盘")
            verifiedDownloads[out.canonicalPath] = remote
            onProgress(100)
            return out to null
        } catch (e: Exception) {
            return null to (e.message?.take(200) ?: "下载异常")
        }
    }

    /** 单地址下载一趟;支持服务端 206 断点续传,忽略 Range 时从头覆盖。 */
    private fun downloadOnce(
        ctx: Context,
        remote: Remote,
        url: String,
        part: File,
        onProgress: (Int) -> Unit,
        cancelled: () -> Boolean,
    ) {
        val resumeFrom = if (part.isFile) part.length().coerceIn(0L, remote.size) else 0L
        val startAt = if (resumeFrom in 1 until remote.size) resumeFrom else 0L
        val extra: (Request.Builder) -> Unit = { builder ->
            if (startAt > 0) builder.header("Range", "bytes=$startAt-")
        }
        val response = when (remote.source) {
            "github" -> githubAsset(downloadClient, url, Prefs.updateRepository(ctx), extra)
            "pc", "" -> {
                val expected = pcUrl(remote.host, remote.port, remote.token, "download").toString()
                require(remote.downloadUrl.isBlank() || remote.downloadUrl == expected) { "电脑更新下载地址与配对目标不一致" }
                val builder = Request.Builder().url(expected)
                extra(builder)
                downloadClient.newCall(builder.build()).execute()
            }
            else -> throw IOException("未知更新来源")
        }
        response.use { resp ->
            if (resp.code == 416) {
                // 服务端认为 Range 越界:删除残缺文件,下一次尝试从头下载
                part.delete()
                throw IOException("服务器拒绝续传，已重新开始")
            }
            if (!resp.isSuccessful) throw IOException(if (resp.code == 404) "下载地址已失效" else "下载失败 HTTP ${resp.code}")
            val body = resp.body ?: throw IOException("更新下载空响应")
            val appending = resp.code == 206 && startAt > 0
            val contentLength = body.contentLength()
            if (!appending && contentLength >= 0 && contentLength != remote.size) {
                throw IOException("下载内容大小与声明不一致")
            }
            var written = if (appending) startAt else 0L
            body.byteStream().use { input ->
                FileOutputStream(part, appending).use { out ->
                    val buffer = ByteArray(256 * 1024)
                    while (true) {
                        if (cancelled() || Thread.currentThread().isInterrupted) {
                            throw InterruptedIOException("下载已取消")
                        }
                        val n = input.read(buffer)
                        if (n < 0) break
                        if (n == 0) continue
                        written += n
                        if (written > remote.size) throw IOException("下载内容超过声明大小")
                        out.write(buffer, 0, n)
                        onProgress(((written * 100) / remote.size).toInt().coerceIn(0, 99))
                    }
                    out.fd.sync()
                }
            }
            if (written != remote.size) throw IOException("下载未完成 (${written}/${remote.size} 字节)")
        }
    }

    @Suppress("DEPRECATION")
    private fun validatePackage(ctx: Context, apk: File, remote: Remote) {
        val flags = if (Build.VERSION.SDK_INT >= 28) PackageManager.GET_SIGNING_CERTIFICATES else PackageManager.GET_SIGNATURES
        val pm = ctx.packageManager
        val archive = pm.getPackageArchiveInfo(apk.absolutePath, flags) ?: throw IOException("下载文件不是有效 APK")
        val current = pm.getPackageInfo(ctx.packageName, flags)
        require(archive.packageName == ctx.packageName) { "APK 包名与本应用不一致" }
        val code = if (Build.VERSION.SDK_INT >= 28) archive.longVersionCode else archive.versionCode.toLong()
        val localCode = if (Build.VERSION.SDK_INT >= 28) current.longVersionCode else current.versionCode.toLong()
        require(code == remote.versionCode && archive.versionName == remote.versionName && code > localCode) { "APK 版本不符合更新清单" }
        val installedCerts = certificates(current)
        val downloadedCerts = certificates(archive)
        require(installedCerts.isNotEmpty() && downloadedCerts == installedCerts) { "APK 签名证书与本应用不一致，拒绝安装" }
    }

    @Suppress("DEPRECATION")
    private fun certificates(info: PackageInfo): Set<String> {
        val signatures = if (Build.VERSION.SDK_INT >= 28) info.signingInfo?.apkContentsSigners else info.signatures
        return signatures?.map { signature ->
            java.security.MessageDigest.getInstance("SHA-256").digest(signature.toByteArray())
                .joinToString("") { "%02x".format(it.toInt() and 255) }
        }?.toSet() ?: emptySet()
    }

    /** 是否已被授予"安装未知应用"权限 */
    fun canInstall(ctx: Context): Boolean =
        Build.VERSION.SDK_INT < 26 || ctx.packageManager.canRequestPackageInstalls()

    /** 跳转"允许安装未知应用"授权页 */
    fun requestInstallPermission(ctx: Context) {
        if (Build.VERSION.SDK_INT >= 26) {
            ctx.startActivity(
                Intent(
                    android.provider.Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,
                    Uri.parse("package:${ctx.packageName}"),
                ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
            )
        }
    }

    /** 校验成功才交给系统安装器；从 UI 调用时校验自动移到后台。不会自动授权未知来源。 */
    fun install(ctx: Context, apk: File) {
        val app = ctx.applicationContext
        if (Looper.myLooper() == Looper.getMainLooper()) {
            Thread({
                try {
                    installVerified(app, apk)
                } catch (e: Exception) {
                    LinkService.notifyPublic("更新安装失败", e.message ?: "APK 验证失败")
                    LinkService.onStatus?.invoke(LinkService.connected, e.message ?: "APK 验证失败")
                }
            }, "update-install-check").start()
        } else {
            installVerified(app, apk)
        }
    }

    /** 主线程调用：校验通过立即拉起安装界面；失败抛出可展示的异常 */
    fun installBlocking(ctx: Context, apk: File) = installVerified(ctx, apk)

    private fun installVerified(ctx: Context, apk: File) {
        val remote = verifiedDownloads[apk.canonicalPath] ?: throw IOException("请重新下载并验证更新")
        require(apk.isFile && apk.length() == remote.size && TransferIntegrity.sha256(apk).equals(remote.sha256, true)) {
            "APK 已改变，拒绝安装"
        }
        validatePackage(ctx, apk, remote)
        check(canInstall(ctx)) { "请由用户确认并允许安装未知应用后重试" }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW).apply {
                setDataAndType(uri, "application/vnd.android.package-archive")
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
            },
        )
    }
}
