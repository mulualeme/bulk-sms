package com.example.bulksms

import android.annotation.SuppressLint
import android.app.Activity
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.telephony.SmsManager
import android.telephony.SubscriptionManager
import androidx.compose.runtime.mutableStateListOf
import kotlinx.serialization.json.Json
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** Campaigns live in files/campaigns/<id>.json; the list here is what the UI observes (main thread only). */
object Repo {
    val campaigns = mutableStateListOf<Campaign>()
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private lateinit var dir: File
    private val main = Handler(Looper.getMainLooper())

    fun init(ctx: Context) {
        if (::dir.isInitialized) return
        dir = File(ctx.filesDir, "campaigns").apply { mkdirs() }
        campaigns += dir.listFiles().orEmpty().mapNotNull { f -> runCatching { json.decodeFromString(Campaign.serializer(), f.readText()) }.getOrNull() }
            // the app was killed while sending: show it as paused so it can be resumed
            .map { if (it.state == State.Running && !SendService.running) it.copy(state = State.Paused) else it }
            .sortedByDescending { it.created }
    }

    fun get(id: Long) = campaigns.firstOrNull { it.id == id }

    /** Saves to disk on the calling thread, updates the UI list on the main thread. */
    fun save(c: Campaign) {
        File(dir, "${c.id}.json").writeText(json.encodeToString(Campaign.serializer(), c))
        main.post { val i = campaigns.indexOfFirst { it.id == c.id }; if (i >= 0) campaigns[i] = c else campaigns.add(0, c) }
    }

    fun delete(c: Campaign) { File(dir, "${c.id}.json").delete(); campaigns.removeAll { it.id == c.id } }
}

data class Sim(val subId: Int, val label: String)

@SuppressLint("MissingPermission")
fun sims(ctx: Context): List<Sim> = runCatching {
    ctx.getSystemService(SubscriptionManager::class.java).activeSubscriptionInfoList.orEmpty()
        .map { Sim(it.subscriptionId, "SIM ${it.simSlotIndex + 1}: ${it.carrierName ?: it.displayName}") }
}.getOrDefault(emptyList())

fun smsManager(ctx: Context, subId: Int): SmsManager {
    val m = ctx.getSystemService(SmsManager::class.java)
    return if (subId >= 0) m.createForSubscriptionId(subId) else m
}

/** Sends the running campaign one message at a time, waiting for each result, then sleeping delaySec. */
class SendService : Service() {
    companion object {
        @Volatile var running = false
        private const val ACTION_SENT = "com.example.bulksms.SENT"

        fun start(ctx: Context, c: Campaign) {
            Repo.save(c.copy(state = State.Running))
            ctx.startForegroundService(Intent(ctx, SendService::class.java))
        }
    }

    @Volatile private var result: Int = 0
    @Volatile private var latch: CountDownLatch? = null
    private val sentReceiver = object : BroadcastReceiver() {
        override fun onReceive(c: Context, i: Intent) { if (resultCode != Activity.RESULT_OK) result = resultCode; latch?.countDown() }
    }

    override fun onBind(i: Intent?) = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        Repo.init(this)
        startForeground(1, notification("Starting…"), if (Build.VERSION.SDK_INT >= 34) ServiceInfo.FOREGROUND_SERVICE_TYPE_REMOTE_MESSAGING else 0)
        if (!running) { running = true; Thread(::loop).start() }
        return START_NOT_STICKY
    }

    private fun loop() {
        val wake = getSystemService(PowerManager::class.java).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "bulksms:send")
        wake.acquire(12 * 60 * 60 * 1000L)
        registerReceiver(sentReceiver, IntentFilter(ACTION_SENT), RECEIVER_NOT_EXPORTED)
        try {
            while (true) {
                // read the latest copy each time so Pause / Stop from the UI take effect
                val c = awaitMain { Repo.campaigns.firstOrNull { it.state == State.Running } } ?: break
                val i = c.recipients.indexOfFirst { it.status == Status.Pending }
                if (i < 0) { Repo.save(c.copy(state = State.Done)); continue }
                val r = c.recipients[i]
                notify(c, "Sending to ${r.fields[NAME]?.takeIf { it.isNotBlank() } ?: r.phone}")
                val error = send(c, r)
                val latest = awaitMain { Repo.get(c.id) } ?: continue
                Repo.save(latest.copy(recipients = latest.recipients.toMutableList().also {
                    it[i] = r.copy(status = if (error == null) Status.Sent else Status.Failed, error = error, time = System.currentTimeMillis())
                }))
                if (latest.recipients.drop(i + 1).any { it.status == Status.Pending }) Thread.sleep(c.delaySec * 1000L)
            }
        } finally {
            unregisterReceiver(sentReceiver)
            wake.release()
            running = false
            stopSelf()
        }
    }

    /** Returns null on success or a short error. */
    private fun send(c: Campaign, r: Recipient): String? = try {
        val sms = smsManager(this, c.subId)
        val parts = sms.divideMessage(fill(c.template, r.fields))
        result = Activity.RESULT_OK
        latch = CountDownLatch(parts.size)
        val pi = PendingIntent.getBroadcast(this, 0, Intent(ACTION_SENT).setPackage(packageName), PendingIntent.FLAG_IMMUTABLE)
        sms.sendMultipartTextMessage(r.phone, null, parts, ArrayList(List(parts.size) { pi }), null)
        if (!latch!!.await(90, TimeUnit.SECONDS)) "No response from the network"
        else if (result == Activity.RESULT_OK) null else errorText(result)
    } catch (e: Exception) { e.message ?: e.javaClass.simpleName }

    private fun errorText(code: Int) = when (code) {
        SmsManager.RESULT_ERROR_NO_SERVICE -> "No service"
        SmsManager.RESULT_ERROR_RADIO_OFF -> "Airplane mode / radio off"
        SmsManager.RESULT_ERROR_NULL_PDU -> "Invalid message"
        SmsManager.RESULT_ERROR_LIMIT_EXCEEDED -> "Android SMS limit reached (allow sending in the system prompt)"
        SmsManager.RESULT_ERROR_SHORT_CODE_NOT_ALLOWED, SmsManager.RESULT_ERROR_SHORT_CODE_NEVER_ALLOWED -> "Short code not allowed"
        else -> "Send failed (error $code)"
    }

    private fun <T> awaitMain(f: () -> T): T {
        var v: T? = null; val l = CountDownLatch(1)
        Handler(Looper.getMainLooper()).post { v = f(); l.countDown() }
        l.await()
        @Suppress("UNCHECKED_CAST") return v as T
    }

    private fun notification(text: String, c: Campaign? = null): Notification {
        val nm = getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(NotificationChannel("send", "Sending", NotificationManager.IMPORTANCE_LOW))
        val open = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java), PendingIntent.FLAG_IMMUTABLE)
        return Notification.Builder(this, "send").setSmallIcon(R.drawable.ic_stat).setContentTitle(c?.name ?: APP_NAME).setContentText(text)
            .setContentIntent(open).setOngoing(true).apply {
                if (c != null) setProgress(c.recipients.size, c.recipients.size - c.count(Status.Pending), false)
            }.build()
    }

    private fun notify(c: Campaign, text: String) = getSystemService(NotificationManager::class.java).notify(1, notification(text, c))
}
