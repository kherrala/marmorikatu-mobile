package fi.marmorikatu.core.background

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.IBinder
import fi.marmorikatu.core.config.ConfigStore
import fi.marmorikatu.core.haptics.Haptics
import fi.marmorikatu.core.log.logger
import fi.marmorikatu.core.model.Announcement
import fi.marmorikatu.core.repository.AnnouncementsRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import org.koin.core.context.GlobalContext

/**
 * A foreground service that keeps the announcements SSE stream open while the
 * app is backgrounded, turning each event into a notification.
 *
 * It deliberately does NOT hold the MQTT connection: live room temperatures are
 * worthless when nobody is looking, and the radio cost is not.
 */
class AnnouncementService : Service() {

    private val log = logger("bg-service")
    private var scope: CoroutineScope? = null
    private var job: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannels()
        startForeground(ONGOING_ID, ongoingNotification())

        val koin = GlobalContext.getOrNull()
        if (koin == null) {
            log.w { "Koin not started; background service cannot run" }
            stopSelf()
            return
        }
        val announcements = koin.get<AnnouncementsRepository>()
        val haptics = koin.get<Haptics>()
        val config = koin.get<ConfigStore>()

        val newScope = CoroutineScope(SupervisorJob())
        scope = newScope
        announcements.start()
        job = newScope.launch {
            announcements.announcements.collect { event ->
                notify(event)
                vibrate(event, haptics, config.config.value.hapticsEnabled)
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY

    override fun onDestroy() {
        job?.cancel()
        scope?.cancel()
        scope = null
        super.onDestroy()
    }

    /** Priority 0 always buzzes; everything else respects the user's choice. */
    private fun vibrate(event: Announcement, haptics: Haptics, enabled: Boolean) {
        when {
            event.priority == 0 -> haptics.alert()
            event.priority == 1 && enabled -> haptics.warn()
        }
    }

    private fun notify(event: Announcement) {
        // Priority 3 is debug chatter (every individual light) — never notify.
        if (event.priority >= 3) return
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            return
        }

        val channel = if (event.priority == 0) CHANNEL_ALERTS else CHANNEL_EVENTS
        val notification = Notification.Builder(this, channel)
            .setContentTitle(if (event.priority == 0) "Marmorikatu · hälytys" else "Marmorikatu")
            .setContentText(event.text)
            .setStyle(Notification.BigTextStyle().bigText(event.text))
            .setSmallIcon(applicationInfo.icon)
            .setAutoCancel(true)
            .setContentIntent(launchIntent(routeFor(event.kind), requestCode = event.id.toInt()))
            .build()

        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(event.id.toInt(), notification)
    }

    /**
     * Which app view a tapped notification should open, from its announcement
     * kind (see [Announcement.kind]). Unknown kinds fall back to the event log.
     */
    private fun routeFor(kind: String): String {
        val k = kind.lowercase()
        return when {
            k.startsWith("news") -> "news"
            k.startsWith("light") -> "valot"
            k.startsWith("sauna") || k.startsWith("hvac") || k.startsWith("lto") ||
                k.startsWith("outdoor") || k.startsWith("aux_heater") -> "ilmasto"
            k.startsWith("price") || k.contains("energy") -> "energia"
            k.startsWith("garbage") || k.startsWith("calendar") -> "kalenteri"
            else -> "loki" // person/door/camera + anything else → the event log
        }
    }

    /**
     * PendingIntent to the launcher activity, optionally carrying a [route] the
     * activity reads to switch views. Each notification uses a distinct
     * [requestCode] so one notification's target can't overwrite another's.
     */
    private fun launchIntent(route: String? = null, requestCode: Int = 0): PendingIntent? {
        val intent = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        route?.let { intent.putExtra(EXTRA_NAV, it) }
        return PendingIntent.getActivity(
            this, requestCode, intent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
        )
    }

    private fun ongoingNotification(): Notification =
        Notification.Builder(this, CHANNEL_ONGOING)
            .setContentTitle("Marmorikatu kuuntelee taloa")
            .setContentText("Tapahtumat saapuvat taustalla")
            .setSmallIcon(applicationInfo.icon)
            .setOngoing(true)
            .setContentIntent(launchIntent())
            .build()

    private fun createChannels() {
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ONGOING, "Taustayhteys", NotificationManager.IMPORTANCE_MIN)
        )
        // Informational events (priority 1–2): quiet by design — they appear in
        // the shade with no sound or vibration. Any light buzz for a priority-1
        // event comes from the app's own haptics, gated on the user's setting,
        // rather than the OS channel firing for every event.
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_EVENTS, "Tapahtumat", NotificationManager.IMPORTANCE_LOW)
                .apply {
                    enableVibration(false)
                    setSound(null, null)
                }
        )
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ALERTS, "Hälytykset", NotificationManager.IMPORTANCE_HIGH)
                .apply { enableVibration(true) }
        )
        // A channel's importance can't be lowered once created, so the old
        // default-importance events channel is retired in favour of the quiet one.
        manager.deleteNotificationChannel(CHANNEL_EVENTS_LEGACY)
    }

    companion object {
        /** Intent extra carrying the view a tapped notification should open (see routeFor). */
        const val EXTRA_NAV = "mk.nav"
        private const val ONGOING_ID = 1
        private const val CHANNEL_ONGOING = "mk.ongoing"
        // New id so the lowered (silent) importance actually applies; the old
        // default-importance channel below is deleted on channel creation.
        private const val CHANNEL_EVENTS = "mk.events.quiet"
        private const val CHANNEL_EVENTS_LEGACY = "mk.events"
        private const val CHANNEL_ALERTS = "mk.alerts"

        fun start(context: Context) {
            val intent = Intent(context, AnnouncementService::class.java)
            if (Build.VERSION.SDK_INT >= 26) context.startForegroundService(intent)
            else context.startService(intent)
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, AnnouncementService::class.java))
        }
    }
}
