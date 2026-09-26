package com.linkassist.app

import java.net.URI
import java.net.URLDecoder
import java.util.Locale

/** 无 Android 依赖的更新信任边界，便于用 JVM 单测覆盖。 */
internal object UpdatePolicy {
    const val DEFAULT_REPOSITORY = "Danjack85/LinkAssist"
    const val MAX_METADATA_BYTES = 1024 * 1024
    const val MAX_APK_BYTES = 512L * 1024 * 1024
    const val MAX_VERSION_CODE = 2_100_000_000L
    private val officialAssetHosts = setOf(
        "release-assets.githubusercontent.com",
        "objects.githubusercontent.com",
        "github-releases.githubusercontent.com",
    )

    fun repository(raw: String): String {
        val repo = raw.trim()
        require(Regex("[A-Za-z0-9](?:[A-Za-z0-9-]{0,38})/[A-Za-z0-9_.-]{1,100}").matches(repo)) {
            "仓库格式应为 owner/repo"
        }
        require(repo.substringAfter('/') !in setOf(".", "..")) { "仓库名称无效" }
        return repo
    }

    fun latestApi(repo: String): String = "https://api.github.com/repos/${repository(repo)}/releases/latest"

    /** Release 资产的 API 直达地址;部分网络到不了 github.com 主站但 api.github.com 可达 */
    fun assetApiUrl(repo: String, assetId: Long): String {
        require(assetId in 1..99_999_999_999L) { "Release 资产 ID 无效" }
        return "https://api.github.com/repos/${repository(repo)}/releases/assets/$assetId"
    }

    private fun https(raw: String): URI {
        require(raw.length in 1..8192 && raw.none { it.isWhitespace() || it.isISOControl() || it == '\\' }) {
            "更新地址无效"
        }
        val uri = URI(raw)
        require(uri.scheme.equals("https", true) && !uri.host.isNullOrBlank() &&
            uri.rawUserInfo == null && uri.rawFragment == null && uri.port in setOf(-1, 443)) {
            "更新地址必须使用可信 HTTPS，不能含用户凭据"
        }
        return uri
    }

    private fun segments(uri: URI): List<String> {
        val pieces = uri.rawPath.removePrefix("/").split('/')
        return pieces.map {
            val decoded = URLDecoder.decode(it.replace("+", "%2B"), "UTF-8")
            require(decoded.isNotEmpty() && decoded !in setOf(".", "..") &&
                decoded.none { c -> c == '/' || c == '\\' || c.isISOControl() }) { "更新路径无效" }
            decoded
        }
    }

    fun assetUrl(raw: String, repo: String, tag: String? = null, file: String? = null): String {
        val uri = https(raw)
        val parts = segments(uri)
        require(uri.host.equals("github.com", true) && uri.rawQuery == null && parts.size == 6 &&
            "${parts[0]}/${parts[1]}".equals(repository(repo), true) &&
            parts[2] == "releases" && parts[3] == "download" &&
            (tag == null || parts[4] == tag) && (file == null || parts[5] == file)) {
            "更新下载必须来自同一 GitHub 仓库的 Release 资产"
        }
        return raw
    }

    fun releaseUrl(raw: String, repo: String, tag: String): String {
        val uri = https(raw)
        val parts = segments(uri)
        require(uri.host.equals("github.com", true) && uri.rawQuery == null && parts.size == 5 &&
            "${parts[0]}/${parts[1]}".equals(repository(repo), true) &&
            parts[2] == "releases" && parts[3] == "tag" && parts[4] == tag) { "Release 地址无效" }
        return raw
    }

    /** 只在从已验证的 Release 资产开始下载后使用；不允许 CDN URL 作为元数据入口。 */
    fun assetRedirect(raw: String, repo: String): String {
        val uri = https(raw)
        if (uri.host.equals("github.com", true)) return assetUrl(raw, repo)
        require(uri.host.lowercase(Locale.ROOT) in officialAssetHosts) { "拒绝非 GitHub 官方资产重定向" }
        return raw
    }

    fun versionName(value: String): String {
        require(value.length in 1..80 && Regex("[0-9]{1,8}(?:\\.[0-9]{1,8}){1,3}(?:[-+][A-Za-z0-9][A-Za-z0-9.+-]{0,48})?").matches(value)) {
            "版本号无效"
        }
        return value
    }

    fun apkMetadata(versionName: String, versionCode: Long, size: Long, sha256: String) {
        versionName(versionName)
        require(versionCode in 1..MAX_VERSION_CODE) { "Android versionCode 无效" }
        require(size in 1..MAX_APK_BYTES) { "APK 大小无效或超过 512 MiB" }
        require(TransferIntegrity.validSha256(sha256)) { "缺少有效 SHA-256，无法验证更新，拒绝下载安装" }
    }
}
