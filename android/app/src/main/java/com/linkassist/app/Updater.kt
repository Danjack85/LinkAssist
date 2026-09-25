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
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit

/** GitHub Release 优先、已配对电脑回退的更新器。检查与下载必须由后台线程调用。 */
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
        val sha256: String = "",
    )

    // 禁用自动重定向，每一跳在发出请求前验证，避免 HTTPS 降级与凭据外泄。
    private val client = OkHttpClient.Builder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(15, TimeUnit.SECONDS)
        .callTimeout(30, TimeUnit.SECONDS)
        .followRedirects(false)
        .followSslRedirects(false)
        .build()
    private val downloadClient = client.newBuilder().callTimeout(10, TimeUnit.MINUTES).build()
    private val verifiedDownloads = ConcurrentHashMap<String, Remote>()

    private fun localVersionCode(ctx: Context): Long = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).let {
            if (Build.VERSION.SDK_INT >= 28) it.longVersionCode
            else @Suppress("DEPRECATION") it.versionCode.toLong()
        }
    } catch (_: Exception) {
        0L
    }

    private fun localVersionName(ctx: Context): String = try {
        ctx.packageManager.getPackageInfo(ctx.packageName, 0).versionName ?: ""
    } catch (_: Exception) {
        ""
    }

    /** 返回 (比本机新的可信更新, 是否成功读取有效更新源, 提示/错误)。不需要先配对电脑。 */
    fun checkAll(ctx: Context): Triple<Remote?, Boolean, String> {
        if (Looper.myLooper() == Looper.getMainLooper()) return Triple(null, false, "请在后台线程检查更新")
        val local = localVersionCode(ctx)
        val githubError: String
        try {
            val remote = githubLatest(Prefs.updateRepository(ctx))
            return Triple(remote.takeIf { it.versionCode > local }, true, "")
        } catch (e: Exception) {
            githubError = e.message?.take(240) ?: "GitHub 更新检查失败"
        }
        var best: Remote? = null
        var reached = false
        val errors = mutableListOf(githubError)
        val targets = LinkService.runtimeTargets().toMutableList()
        for (p in Prefs.profiles(ctx)) {
            if (targets.none { it.first == p.host && it.second == p.port }) targets.add(Triple(p.host, p.port, p.token))
        }
        for ((host, port, token) in targets.distinct().take(20)) {
            try {
                val url = pcUrl(host, port, token, "version")
                client.newCall(Request.Builder().url(url).build()).execute().use { resp ->
                    if (!resp.isSuccessful) throw IOException(if (resp.code == 404) "电脑尚未发布 APK 或版本过旧" else "电脑更新接口 HTTP ${resp.code}")
                    val o = jsonBody(resp)
                    val name = o.getString("versionName")
                    val code = strictLong(o, "versionCode")
                    val size = strictLong(o, "size")
                    val hash = o.optString("sha256")
                    UpdatePolicy.apkMetadata(name, code, size, hash)
                    reached = true
                    if (code > local && code > (best?.versionCode ?: 0L)) {
                        best = Remote(host, port, token, name, code, size, sha256 = hash,
                            downloadUrl = pcUrl(host, port, token, "download").toString())
                    }
                }
            } catch (e: Exception) {
                errors.add(e.message?.take(160) ?: "电脑更新检查失败")
            }
        }
        if (targets.isEmpty()) errors.add("未配置可回退的已配对电脑")
        return Triple(best, reached, errors.distinct().joinToString("；"))
    }

    private fun githubLatest(repo: String): Remote {
        val release = client.newCall(Request.Builder().url(UpdatePolicy.latestApi(repo))
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", "2022-11-28")
            .header("User-Agent", "LinkAssist-Android").build()).execute().use { resp ->
            if (resp.code == 404) throw IOException("GitHub 仓库私有或尚未发布")
            if (resp.code == 403 || resp.code == 429) throw IOException("GitHub 访问受限或请求频率超限")
            if (!resp.isSuccessful) throw IOException("GitHub HTTP ${resp.code}")
            jsonBody(resp)
        }
        require(!release.optBoolean("draft") && !release.optBoolean("prerelease")) { "未找到正式 Release" }
        val tag = release.getString("tag_name")
        require(tag.length in 1..128) { "Release tag 无效" }
        val releaseUrl = UpdatePolicy.releaseUrl(release.getString("html_url"), repo, tag)
        val assets = release.getJSONArray("assets")
        val manifests = (0 until assets.length()).map { assets.getJSONObject(it) }
            .filter { it.optString("name") == "linkassist-update.json" }
        require(manifests.size == 1) { "Release 未发布唯一的 linkassist-update.json" }
        val manifestAsset = manifests.single()
        require(strictLong(manifestAsset, "size") in 1..UpdatePolicy.MAX_METADATA_BYTES.toLong()) { "更新清单大小无效" }
        val manifestUrl = UpdatePolicy.assetUrl(manifestAsset.getString("browser_download_url"), repo, tag, "linkassist-update.json")
        val manifest = githubAsset(client, manifestUrl, repo).use { resp ->
            if (!resp.isSuccessful) throw IOException(if (resp.code == 404) "GitHub 仓库私有或尚未发布" else "更新清单 HTTP ${resp.code}")
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
        val downloadUrl = UpdatePolicy.assetUrl(android.getString("url"), repo, tag, file)
        return Remote("", 443, "", name, code, size, "github", notes, releaseUrl, downloadUrl, hash)
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

    private fun githubAsset(http: OkHttpClient, initial: String, repo: String): Response {
        var url = UpdatePolicy.assetUrl(initial, repo).toHttpUrl()
        repeat(6) { hop ->
            val resp = http.newCall(Request.Builder().url(url).header("User-Agent", "LinkAssist-Android").build()).execute()
            if (resp.code !in setOf(301, 302, 303, 307, 308)) return resp
            val next = resp.header("Location")?.let { url.resolve(it) }
            resp.close()
            if (hop == 5 || next == null) throw IOException("GitHub 资产重定向无效或次数过多")
            UpdatePolicy.assetRedirect(next.toString(), repo)
            url = next
        }
        throw IOException("GitHub 资产重定向失败")
    }

    /** 流式下载、大小/摘要/包信息验证全部通过后，才发布为 .apk。 */
    fun download(ctx: Context, remote: Remote, onProgress: (Int) -> Unit): Pair<File?, String?> {
        if (Looper.myLooper() == Looper.getMainLooper()) return null to "请在后台线程下载更新"
        var part: File? = null
        var completed: File? = null
        try {
            UpdatePolicy.apkMetadata(remote.versionName, remote.versionCode, remote.size, remote.sha256)
            require(remote.versionCode > localVersionCode(ctx)) { "更新版本不高于本机版本" }
            val dir = File(ctx.cacheDir, "apk").apply { if (!isDirectory && !mkdirs()) throw IOException("无法创建更新缓存") }
            val tmp = File.createTempFile("update-${remote.versionCode}-", ".part", dir)
            part = tmp
            val response = when (remote.source) {
                "github" -> githubAsset(downloadClient, remote.downloadUrl, Prefs.updateRepository(ctx))
                "pc", "" -> {
                    val url = pcUrl(remote.host, remote.port, remote.token, "download")
                    require(remote.downloadUrl.isBlank() || remote.downloadUrl == url.toString()) { "电脑更新下载地址与配对目标不一致" }
                    downloadClient.newCall(Request.Builder().url(url).build()).execute()
                }
                else -> throw IOException("未知更新来源")
            }
            response.use { resp ->
                if (!resp.isSuccessful) throw IOException("下载失败 HTTP ${resp.code}")
                val body = resp.body ?: throw IOException("更新下载空响应")
                if (body.contentLength() >= 0 && body.contentLength() != remote.size) throw IOException("APK 响应大小不一致")
                body.byteStream().use { input ->
                    TransferIntegrity.writeVerified(input, tmp, remote.size, remote.sha256, onProgress = { total ->
                        onProgress((total * 100 / remote.size).toInt().coerceIn(0, 99))
                    })
                }
            }
            validatePackage(ctx, tmp, remote)
            val out = File(dir, tmp.name.removeSuffix(".part") + ".apk")
            if (!tmp.renameTo(out)) throw IOException("无法完成 APK 缓存落盘")
            completed = out
            verifiedDownloads[out.canonicalPath] = remote
            onProgress(100)
            return out to null
        } catch (e: Exception) {
            completed?.let { verifiedDownloads.remove(it.canonicalPath); it.delete() }
            return null to (e.message?.take(240) ?: "下载异常")
        } finally {
            part?.delete()
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
