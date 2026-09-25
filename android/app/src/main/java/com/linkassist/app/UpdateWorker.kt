package com.linkassist.app

import android.Manifest
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean

/** Each execution performs one bounded check; it never starts a download or an installer. */
class UpdateWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {
    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val context = applicationContext
        if (!Prefs.autoCheckUpdates(context) || !running.compareAndSet(false, true)) {
            return@withContext Result.success()
        }
        try {
            val (remote, reached, _) = Updater.checkAll(context)
            if (Prefs.autoCheckUpdates(context) && reached && remote != null &&
                remote.versionCode > BuildConfig.VERSION_CODE &&
                remote.versionCode > Prefs.lastNotifiedUpdateVersionCode(context)) {
                if (notifyUpdate(context, remote)) {
                    Prefs.setLastNotifiedUpdateVersionCode(context, remote.versionCode)
                }
            }
            Result.success()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (_: Exception) {
            // Offline/private repository failures wait for the next scheduled check, not a retry storm.
            Result.success()
        } finally {
            running.set(false)
        }
    }

    companion object {
        private const val PERIODIC = "linkassist-update-periodic"
        private const val STARTUP = "linkassist-update-startup"
        private const val CHANNEL = "app_updates"
        private const val NOTIFICATION_ID = 3012
        private val running = AtomicBoolean(false)

        fun schedule(context: Context, checkAtStartup: Boolean = false) {
            val manager = WorkManager.getInstance(context.applicationContext)
            if (!Prefs.autoCheckUpdates(context)) {
                manager.cancelUniqueWork(PERIODIC)
                manager.cancelUniqueWork(STARTUP)
                return
            }
            val constraints = Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build()
            manager.enqueueUniquePeriodicWork(
                PERIODIC,
                ExistingPeriodicWorkPolicy.KEEP,
                PeriodicWorkRequestBuilder<UpdateWorker>(12, TimeUnit.HOURS)
                    .setConstraints(constraints)
                    .setInitialDelay(12, TimeUnit.HOURS)
                    .build(),
            )
            if (checkAtStartup) {
                manager.enqueueUniqueWork(
                    STARTUP,
                    ExistingWorkPolicy.KEEP,
                    OneTimeWorkRequestBuilder<UpdateWorker>()
                        .setConstraints(constraints)
                        .setInitialDelay(6, TimeUnit.SECONDS)
                        .build(),
                )
            }
        }

        private fun notifyUpdate(context: Context, remote: Updater.Remote): Boolean {
            if (Build.VERSION.SDK_INT >= 33 && ContextCompat.checkSelfPermission(
                    context, Manifest.permission.POST_NOTIFICATIONS,
                ) != PackageManager.PERMISSION_GRANTED) return false
            val compat = NotificationManagerCompat.from(context)
            if (!compat.areNotificationsEnabled()) return false
            val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(
                    CHANNEL, "应用更新", NotificationManager.IMPORTANCE_DEFAULT,
                ))
                if (manager.getNotificationChannel(CHANNEL)?.importance == NotificationManager.IMPORTANCE_NONE) {
                    return false
                }
            }
            val pending = PendingIntent.getActivity(
                context, NOTIFICATION_ID,
                Intent(context, MainActivity::class.java)
                    .putExtra("open_settings", true)
                    .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE,
            )
            val notification = NotificationCompat.Builder(context, CHANNEL)
                .setSmallIcon(R.drawable.ic_stat)
                .setContentTitle("LinkAssist v${remote.versionName} 可更新")
                .setContentText("点击进入设置查看更新；仅提醒，不会自动下载或安装")
                .setContentIntent(pending)
                .setAutoCancel(true)
                .setVisibility(NotificationCompat.VISIBILITY_PRIVATE)
                .build()
            return try {
                compat.notify(NOTIFICATION_ID, notification)
                true
            } catch (_: SecurityException) {
                false
            }
        }
    }
}
