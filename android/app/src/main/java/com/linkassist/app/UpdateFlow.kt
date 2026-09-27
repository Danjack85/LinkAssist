package com.linkassist.app

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.io.File

/**
 * 应用更新流程的状态机(对齐"计划事件 PlanFocus"的更新体验):
 *   打开应用自动检查(60 秒防抖) → 发现新版弹应用内弹窗(带更新说明)
 *   → 用户点"立即更新" → 应用内下载(进度、可取消、断点续传)
 *   → SHA-256 + 包名 + 版本 + 签名校验 → 拉起系统安装器由用户确认。
 * 只做"下载 + 校验 + 拉起安装界面",绝不静默安装。
 */
object UpdateFlow {

    data class State(
        val checking: Boolean = false,
        /** 设置页显示的一句话状态 */
        val message: String = "打开应用会自动检查更新；也可以手动检查。",
        /** 发现的新版本(可能来自 GitHub 或已配对电脑) */
        val available: Updater.Remote? = null,
        val downloading: Boolean = false,
        /** 0..100，-1 表示未在下载 */
        val progress: Int = -1,
        /** 已下载并通过校验、等待确认安装 */
        val readyFile: File? = null,
        /** 需要弹更新对话框的版本(自动检查发现且用户未选"稍后") */
        val dialog: Updater.Remote? = null,
    )

    var state by mutableStateOf(State())
        private set

    private val main = Handler(Looper.getMainLooper())

    @Volatile private var cancelRequested = false
    @Volatile private var busy = false

    private fun post(block: () -> Unit) {
        if (Looper.myLooper() == Looper.getMainLooper()) block() else main.post(block)
    }

    fun dismissDialog() {
        post { state = state.copy(dialog = null) }
    }

    /** 用户点"稍后再说":记住该版本,同一版本不再弹窗 */
    fun remindLater(context: Context) {
        val remote = state.dialog ?: state.available
        if (remote != null) Prefs.setSkippedUpdateVersion(context, remote.versionName)
        post { state = state.copy(dialog = null) }
    }

    /** 更新仓库变更后清掉旧结果 */
    fun onRepositoryChanged() {
        post {
            if (!state.downloading) {
                state = state.copy(available = null, dialog = null, readyFile = null, progress = -1,
                    message = "更新仓库已保存，可点击检查更新。")
            }
        }
    }

    /** 打开应用时的自动检查:60 秒防抖;发现新版本且未被"稍后"过才弹窗 */
    fun autoCheckOnOpen(context: Context) {
        val ctx = context.applicationContext
        if (busy || !Prefs.autoCheckUpdates(ctx)) return
        val now = System.currentTimeMillis()
        if (now - Prefs.lastUpdateCheckAt(ctx) < 60_000) return
        Prefs.setLastUpdateCheckAt(ctx, now)
        check(ctx, manual = false, showDialog = true)
    }

    /** 设置页"检查更新"按钮 */
    fun checkNow(context: Context) {
        if (state.downloading) return
        check(context.applicationContext, manual = true, showDialog = false)
    }

    /** 点击更新通知进入:立即检查并弹更新弹窗 */
    fun checkFromNotification(context: Context) {
        if (state.downloading) return
        check(context.applicationContext, manual = true, showDialog = true)
    }

    private fun check(ctx: Context, manual: Boolean, showDialog: Boolean) {
        if (busy) return
        busy = true
        post { state = state.copy(checking = true, message = "正在检查更新…", readyFile = null) }
        Thread({
            val result = try {
                Updater.checkAll(ctx)
            } catch (e: Exception) {
                Triple<Updater.Remote?, Boolean, String>(null, false, "检查更新失败，请稍后重试")
            }
            val (remote, reached, error) = result
            val message = when {
                remote != null -> "发现新版本 v${remote.versionName}（当前 v${Updater.currentVersionName(ctx)}）"
                reached -> "已是最新版本（v${Updater.currentVersionName(ctx)}）"
                else -> "检查失败：$error"
            }
            post {
                busy = false
                val skipDialog = remote == null ||
                    remote.versionName == Prefs.skippedUpdateVersion(ctx) ||
                    (!manual && !Prefs.autoCheckUpdates(ctx))
                state = state.copy(
                    checking = false,
                    message = message,
                    available = remote ?: state.available?.takeIf { state.downloading || state.readyFile != null },
                    dialog = if (showDialog && !skipDialog) remote else state.dialog,
                )
            }
        }, "update-check").start()
    }

    /** 用户点"立即更新":后台下载,进度回传界面 */
    fun startDownload(context: Context) {
        val ctx = context.applicationContext
        val remote = state.available ?: return
        if (state.downloading) return
        cancelRequested = false
        post { state = state.copy(downloading = true, progress = 0, message = "正在下载 v${remote.versionName}…") }
        Thread({
            val (file, error) = Updater.download(
                ctx,
                remote,
                onProgress = { percent -> post { state = state.copy(progress = percent.coerceIn(0, 100)) } },
                cancelled = { cancelRequested },
            )
            post {
                if (file != null) {
                    state = state.copy(downloading = false, progress = 100, readyFile = file,
                        message = "下载并校验完成，等待你确认安装。")
                } else {
                    state = state.copy(downloading = false, progress = -1,
                        message = "下载未完成：${error ?: "请稍后重试"}")
                }
            }
        }, "update-download").start()
    }

    /** 取消下载:进度保留,再次点击会断点续传 */
    fun cancelDownload() {
        if (!state.downloading) return
        cancelRequested = true
    }

    /** 拉起系统安装器(需要"安装未知应用"授权时先跳授权页) */
    fun install(context: Context) {
        val ctx = context.applicationContext
        val file = state.readyFile ?: return
        try {
            if (!Updater.canInstall(ctx)) {
                Updater.requestInstallPermission(ctx)
                post { state = state.copy(message = "请在系统设置中允许本应用安装更新，返回后再次点击安装。") }
            } else {
                Updater.installBlocking(ctx, file)
                post { state = state.copy(message = "已打开系统安装界面，请在系统弹窗中确认安装。") }
            }
        } catch (e: Exception) {
            post {
                state = state.copy(readyFile = null, progress = -1,
                    message = "安装被阻止：${e.message ?: "安装包校验未通过，请重新下载"}")
            }
        }
    }
}
