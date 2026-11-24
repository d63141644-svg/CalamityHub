package com.calamity.hub

import android.app.ActivityManager
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.ApplicationInfo
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.PixelFormat
import android.os.BatteryManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.provider.Settings
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import org.json.JSONArray
import org.json.JSONObject
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread
import kotlin.math.abs

class TurboService : Service() {
    private val h = Handler(Looper.getMainLooper())
    private val lock = Any()
    private val on = "cmd power set-fixed-performance-mode-enabled true"
    private val off = "cmd power set-fixed-performance-mode-enabled false"
    private var paused = false
    private var granted = false
    private var lastTurbo = 0L
    private var lastQuery = 0L
    private var lastClean = 0L
    private var current = ""
    private var statPkg = ""
    private var wasGame = false
    private var goodLayer = ""
    private var goodPkg = ""
    private val ignore = setOf("com.android.systemui", "android")

    private var sessionStart = 0L
    private var sum = 0L
    private var cnt = 0
    private var minFps = Int.MAX_VALUE
    private var maxTemp = 0f
    private var modeLabel = "Normal"
    private var expanded = false

    @Volatile private var fpsBusy = false
    private var box: LinearLayout? = null
    private var fpsText: TextView? = null
    private var detailText: TextView? = null
    private var wm: WindowManager? = null

    private val binderListener = Shizuku.OnBinderReceivedListener { turbo() }
    private val loop = object : Runnable {
        override fun run() {
            try { checkGame() } catch (e: Throwable) {}
            if (System.currentTimeMillis() - lastTurbo > 120_000L) turbo()
            h.postDelayed(this, 2000L)
        }
    }

    override fun onCreate() {
        super.onCreate()
        getSystemService(NotificationManager::class.java)
            .createNotificationChannel(NotificationChannel("turbo", "Turbo", NotificationManager.IMPORTANCE_LOW))
        startForeground(1, notif("Iniciando..."))
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        h.post(loop)
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_STICKY
    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        h.removeCallbacks(loop)
        try { endSession() } catch (e: Throwable) {}
        hideOverlay()
        Shizuku.removeBinderReceivedListener(binderListener)
        thread { try { if (Shizuku.pingBinder()) Sh.run(off) } catch (e: Throwable) {} }
        super.onDestroy()
    }

    private fun ready() = Shizuku.pingBinder() &&
        Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED

    private fun notif(text: String): Notification =
        Notification.Builder(this, "turbo")
            .setSmallIcon(android.R.drawable.stat_notify_sync)
            .setContentTitle("Calamity Hub")
            .setContentText(text)
            .setOngoing(true)
            .build()

    private fun note(text: String) {
        getSystemService(NotificationManager::class.java).notify(1, notif(text))
    }

    private fun batteryTemp(): Float {
        val i = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        return (i?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
    }

    private fun turbo() {
        lastTurbo = System.currentTimeMillis()
        thread {
            try {
                synchronized(lock) {
                    if (!ready()) { note("Aguardando o Shizuku..."); return@thread }
                    if (!granted) {
                        Sh.run("appops set $packageName SYSTEM_ALERT_WINDOW allow; appops set $packageName GET_USAGE_STATS allow")
                        granted = true
                    }
                    val temp = batteryTemp()
                    val t = "%.1f".format(temp)
                    if (temp >= 43f) {
                        if (!paused) { Sh.run(off); paused = true }
                        note("Pausado: celular quente ($t°C)")
                        return@thread
                    }
                    if (paused && temp > 40f) { note("Pausado: esfriando ($t°C)"); return@thread }
                    paused = false
                    val r = Sh.run(on)
                    note(if (r.first == 0) "Turbo ligado • $t°C" else "Erro: ${r.second}")
                }
            } catch (e: Throwable) { note("Erro: $e") }
        }
    }

    private fun isGame(pkg: String): Boolean {
        if (pkg.isEmpty() || pkg == packageName) return false
        val saved = getSharedPreferences("hub", MODE_PRIVATE).getStringSet("games", emptySet()) ?: emptySet()
        if (pkg in saved) return true
        return try {
            packageManager.getApplicationInfo(pkg, 0).category == ApplicationInfo.CATEGORY_GAME
        } catch (e: Exception) { false }
    }

    private fun onGameStart() {
        statPkg = current
        sessionStart = System.currentTimeMillis()
        sum = 0L
        cnt = 0
        minFps = Int.MAX_VALUE
        maxTemp = 0f
        modeLabel = "Normal"
        goodLayer = ""
        val pkg = statPkg
        if (ready()) {
            thread {
                try {
                    val c = Sh.run("device_config get game_overlay $pkg").second
                    modeLabel = when {
                        c.contains("fps=30") -> "Estável"
                        c.contains("mode=2") -> "Performance"
                        else -> "Normal"
                    }
                } catch (e: Throwable) {}
            }
        }
        val clean = getSharedPreferences("hub", MODE_PRIVATE).getBoolean("clean", true)
        if (clean && ready() && System.currentTimeMillis() - lastClean > 180_000L) {
            lastClean = System.currentTimeMillis()
            thread { try { Sh.run("am kill-all") } catch (e: Throwable) {} }
        }
    }

    private fun endSession() {
        if (statPkg.isEmpty()) return
        val secs = (System.currentTimeMillis() - sessionStart) / 1000
        if (secs >= 60) {
            try {
                val p = getSharedPreferences("hub", MODE_PRIVATE)
                val arr = JSONArray(p.getString("history", "[]"))
                val o = JSONObject()
                o.put("pkg", statPkg)
                o.put("t", sessionStart)
                o.put("secs", secs)
                o.put("avg", if (cnt > 0) (sum / cnt).toInt() else -1)
                o.put("min", if (minFps == Int.MAX_VALUE) -1 else minFps)
                o.put("temp", maxTemp.toDouble())
                o.put("mode", modeLabel + " + Turbo")
                val out = JSONArray()
                val start = if (arr.length() >= 100) arr.length() - 99 else 0
                for (i in start until arr.length()) out.put(arr.get(i))
                out.put(o)
                p.edit().putString("history", out.toString()).apply()
            } catch (e: Throwable) {}
        }
        statPkg = ""
    }

    private fun checkGame() {
        val usm = getSystemService(UsageStatsManager::class.java)
        val now = System.currentTimeMillis()
        val from = if (lastQuery == 0L) now - 6 * 3600_000L else lastQuery - 2000L
        lastQuery = now
        val ev = usm.queryEvents(from, now)
        val e = UsageEvents.Event()
        while (ev.hasNextEvent()) {
            ev.getNextEvent(e)
            if (e.eventType == UsageEvents.Event.MOVE_TO_FOREGROUND &&
                e.packageName !in ignore && !e.packageName.contains("inputmethod")
            ) current = e.packageName
        }
        val game = isGame(current)
        if (wasGame && (!game || current != statPkg)) endSession()
        if (game && (!wasGame || current != statPkg)) onGameStart()
        wasGame = game
        if (game) {
            val t = batteryTemp()
            if (t > maxTemp) maxTemp = t
        }
        if (game && box != null && box?.isAttachedToWindow == false) box = null
        if (game && box == null) showOverlay()
        if (!game && box != null) hideOverlay()
        if (game) measureFps()
    }

    private fun detailString(): String {
        val am = getSystemService(ActivityManager::class.java)
        val mi = ActivityManager.MemoryInfo()
        am.getMemoryInfo(mi)
        val freeMb = mi.availMem / 1048576
        val totalMb = mi.totalMem / 1048576
        val avg = if (cnt > 0) (sum / cnt).toInt() else 0
        val mn = if (minFps == Int.MAX_VALUE) 0 else minFps
        val secs = (System.currentTimeMillis() - sessionStart) / 1000
        val temp = "%.1f".format(batteryTemp())
        return "Média $avg  Mín $mn\nTemp $temp°C\nRAM livre $freeMb/$totalMb MB\nTempo ${secs / 60}:${"%02d".format(secs % 60)}"
    }

    private fun showOverlay() {
        if (!Settings.canDrawOverlays(this)) return
        val w = getSystemService(WindowManager::class.java)
        wm = w
        val b = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(Color.parseColor("#AA000000"))
            setPadding(16, 10, 16, 10)
        }
        val fps = TextView(this).apply {
            text = "FPS --"
            setTextColor(Color.GREEN)
            textSize = 15f
        }
        val detail = TextView(this).apply {
            setTextColor(Color.WHITE)
            textSize = 12f
            visibility = if (expanded) View.VISIBLE else View.GONE
            if (expanded) text = detailString()
        }
        b.addView(fps)
        b.addView(detail)

        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.START
        lp.x = 0
        lp.y = 120

        var sx = 0f
        var sy = 0f
        var ox = 0
        var oy = 0
        var moved = false
        b.setOnTouchListener { _, ev ->
            when (ev.action) {
                MotionEvent.ACTION_DOWN -> { sx = ev.rawX; sy = ev.rawY; ox = lp.x; oy = lp.y; moved = false }
                MotionEvent.ACTION_MOVE -> {
                    val dx = (ev.rawX - sx).toInt()
                    val dy = (ev.rawY - sy).toInt()
                    if (abs(dx) > 10 || abs(dy) > 10) moved = true
                    lp.x = ox + dx
                    lp.y = oy + dy
                    w.updateViewLayout(b, lp)
                }
                MotionEvent.ACTION_UP -> if (!moved) {
                    expanded = !expanded
                    detail.visibility = if (expanded) View.VISIBLE else View.GONE
                    if (expanded) detail.text = detailString()
                    w.updateViewLayout(b, lp)
                }
            }
            true
        }
        w.addView(b, lp)
        box = b
        fpsText = fps
        detailText = detail
    }

    private fun hideOverlay() {
        try { box?.let { wm?.removeView(it
