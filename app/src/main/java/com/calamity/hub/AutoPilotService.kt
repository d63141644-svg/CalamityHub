package com.calamity.hub

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.content.IntentFilter
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Path
import android.graphics.PixelFormat
import android.graphics.RectF
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.DisplayMetrics
import android.view.Display
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.accessibility.AccessibilityEvent
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.TextView
import android.widget.Toast
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

class AutoPilotService : AccessibilityService() {
    private class Tpl(
        val type: String, val x: Int, val y: Int,
        val rx: Int, val ry: Int, val w: Int, val h: Int,
        val sw: Int, val sh: Int, val bmp: Bitmap
    )

    private val ui = Handler(Looper.getMainLooper())
    private var running = false
    private var startedAt = 0L
    private var lastPkg = ""
    private var cycle = 0
    private var fails = 0
    private var unknownSince = 0L
    private var bar: LinearLayout? = null
    private var autoBtn: Button? = null
    private var capture: View? = null
    private var wm: WindowManager? = null
    private var tpls: List<Tpl> = emptyList()

    // posições das skills (fração da tela), lidas do vídeo
    private val skills = arrayOf(
        floatArrayOf(0.821f, 0.625f),
        floatArrayOf(0.899f, 0.569f),
        floatArrayOf(0.798f, 0.800f)
    )

    private fun prefs() = getSharedPreferences("hub", MODE_PRIVATE)
    private fun games(): Set<String> = prefs().getStringSet("games", emptySet()) ?: emptySet()
    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    override fun onServiceConnected() {
        super.onServiceConnected()
        loadTpls()
        if (bar == null) addBar()
        refreshBar()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val p = event?.packageName?.toString() ?: return
        if (p == packageName || p == "com.android.systemui") return
        lastPkg = p
        refreshBar()
    }

    override fun onInterrupt() {
        stopBot("Interrompido")
    }

    override fun onUnbind(intent: Intent?): Boolean {
        running = false
        ui.removeCallbacksAndMessages(null)
        try { capture?.let { wm?.removeView(it) } } catch (e: Throwable) {}
        try { bar?.let { wm?.removeView(it) } } catch (e: Throwable) {}
        capture = null
        bar = null
        return super.onUnbind(intent)
    }

    // ---------- modelos aprendidos ----------

    private fun loadTpls() {
        val out = mutableListOf<Tpl>()
        try {
            val arr = JSONArray(prefs().getString("tpls", "[]"))
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val bmp = BitmapFactory.decodeFile(File(filesDir, o.getString("file")).path) ?: continue
                out.add(
                    Tpl(
                        o.getString("type"), o.getInt("x"), o.getInt("y"),
                        o.getInt("rx"), o.getInt("ry"), o.getInt("w"), o.getInt("h"),
                        o.getInt("sw"), o.getInt("sh"), bmp
                    )
                )
            }
        } catch (e: Throwable) {}
        tpls = out
    }

    private fun clearTpls() {
        if (running) { toast("Pare o piloto antes."); return }
        try {
            val arr = JSONArray(prefs().getString("tpls", "[]"))
            for (i in 0 until arr.length()) File(filesDir, arr.getJSONObject(i).getString("file")).delete()
        } catch (e: Throwable) {}
        prefs().edit().remove("tpls").apply()
        loadTpls()
        toast("Tudo que o piloto aprendeu foi apagado.")
    }

    private fun saveTpl(type: String, x: Int, y: Int, shot: Bitmap) {
        try {
            val w = 240
            val h = 100
            val rx = (x - w / 2).coerceIn(0, shot.width - w)
            val ry = (y - h / 2).coerceIn(0, shot.height - h)
            val crop = Bitmap.createBitmap(shot, rx, ry, w, h)
            val name = "tpl_${System.currentTimeMillis()}.png"
            FileOutputStream(File(filesDir, name)).use { crop.compress(Bitmap.CompressFormat.PNG, 100, it) }
            val arr = JSONArray(prefs().getString("tpls", "[]"))
            val o = JSONObject()
            o.put("file", name)
            o.put("type", type)
            o.put("x", x)
            o.put("y", y)
            o.put("rx", rx)
            o.put("ry", ry)
            o.put("w", w)
            o.put("h", h)
            o.put("sw", shot.width)
            o.put("sh", shot.height)
            arr.put(o)
            prefs().edit().putString("tpls", arr.toString()).apply()
            loadTpls()
            val nb = tpls.count { it.type == "btn" }
            val nf = tpls.count { it.type == "fight" }
            toast("Aprendi! Botões: $nb • Marcas de luta: $nf")
        } catch (e: Throwable) {
            toast("Erro ao salvar: $e")
        }
    }

    // ---------- barra flutuante ----------

    private fun mini(label: String, bg: String, f: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        textSize = 11f
        setTextColor(Color.WHITE)
        setBackgroundColor(Color.parseColor(bg))
        setPadding(14, 0, 14, 0)
        minWidth = 0
        minimumWidth = 0
        minHeight = 0
        minimumHeight = 0
        setOnClickListener { f() }
    }

    private fun addBar() {
        val w = getSystemService(WINDOW_SERVICE) as WindowManager
        wm = w
        val row = LinearLayout(this)
        row.orientation = LinearLayout.HORIZONTAL
        val a = mini("▶ AUTO", "#CC1B5E20") { if (running) stopBot("Parado por você") else startBot() }
        autoBtn = a
        row.addView(a)
        row.addView(mini("＋BOTÃO", "#CC0D47A1") { startTeach("btn") })
        row.addView(mini("＋LUTA", "#CC4A148C") { startTeach("fight") })
        row.addView(mini("🗑", "#CC616161") { clearTpls() })
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        lp.gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
        lp.y = 8
        w.addView(row, lp)
        bar = row
    }

    private fun refreshBar() {
        if (capture != null) return
        bar?.visibility = if (running || lastPkg in games()) View.VISIBLE else View.GONE
    }

    // ---------- ensinar botões ----------

    private fun startTeach(type: String) {
        if (running) { toast("Pare o piloto antes de ensinar."); return }
        val w = wm ?: return
        if (capture != null) return
        val fl = FrameLayout(this)
        fl.setBackgroundColor(Color.parseColor("#66000000"))
        val tv = TextView(this)
        tv.text = if (type == "btn")
            "Toque EXATAMENTE no botão que o piloto deve apertar.\nDepois aperte o botão de verdade para seguir."
        else
            "Toque numa parte que SÓ aparece durante a luta\n(ex.: o nome da área no canto de cima)."
        tv.setTextColor(Color.WHITE)
        tv.textSize = 18f
        tv.gravity = Gravity.CENTER
        fl.addView(
            tv,
            FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT, Gravity.CENTER)
        )
        fl.setOnTouchListener { _, ev ->
            if (ev.action == MotionEvent.ACTION_UP) finishTeach(type, ev.rawX.toInt(), ev.rawY.toInt())
            true
        }
        val lp = WindowManager.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT,
            ViewGroup.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        w.addView(fl, lp)
        capture = fl
        bar?.visibility = View.GONE
    }

    private fun finishTeach(type: String, x: Int, y: Int) {
        try { capture?.let { wm?.removeView(it) } } catch (e: Throwable) {}
        capture = null
        ui.postDelayed({
            takeShot { shot ->
                if (shot == null) toast("Não consegui ler a tela. Desligue e ligue a acessibilidade e tente de novo.")
                else saveTpl(type, x, y, shot)
                refreshBar()
            }
        }, 500L)
    }

    // ---------- captura e comparação ----------

    private fun takeShot(cb: (Bitmap?) -> Unit) {
        if (Build.VERSION.SDK_INT < 30) { cb(null); return }
        try {
            takeScreenshot(Display.DEFAULT_DISPLAY, mainExecutor, object : TakeScreenshotCallback {
                override fun onSuccess(result: ScreenshotResult) {
                    var bmp: Bitmap? = null
                    try {
                        val hb = result.hardwareBuffer
                        val hw = Bitmap.wrapHardwareBuffer(hb, result.colorSpace)
                        bmp = hw?.copy(Bitmap.Config.ARGB_8888, false)
                        hb.close()
                    } catch (e: Throwable) {}
                    cb(bmp)
                }

                override fun onFailure(errorCode: Int) {
                    cb(null)
                }
            })
        } catch (e: Throwable) {
            cb(null)
        }
    }

    // diferença média (0 a 255) entre a tela atual e o que foi aprendido
    private fun diff(shot: Bitmap, t: Tpl): Float {
        if (shot.width != t.sw || shot.height != t.sh) return 999f
        return try {
            val c = Bitmap.createBitmap(shot, t.rx, t.ry, t.w, t.h)
            val a = Bitmap.createScaledBitmap(c, t.w / 4, t.h / 4, true)
            val b = Bitmap.createScaledBitmap(t.bmp, t.w / 4, t.h / 4, true)
            val n = a.width * a.height
            val pa = IntArray(n)
            val pb = IntArray(n)
            a.getPixels(pa, 0, a.width, 0, 0, a.width, a.height)
            b.getPixels(pb, 0, b.width, 0, 0, b.width, b.height)
            var s = 0L
            for (i in 0 until n) {
                val ga = (Color.red(pa[i]) + Color.green(pa[i]) + Color.blue(pa[i])) / 3
                val gb = (Color.red(pb[i]) + Color.green(pb[i]) + Color.blue(pb[i])) / 3
                s += abs(ga - gb)
            }
            s.toFloat() / n
        } catch (e: Throwable) {
            999f
        }
    }

    // ---------- piloto ----------

    private fun startBot() {
        if (running) return
        running = true
        startedAt = System.currentTimeMillis()
        cycle = 0
        fails = 0
        unknownSince = 0L
        autoBtn?.text = "■ PARAR"
        autoBtn?.setBackgroundColor(Color.parseColor("#CCB71C1C"))
        ui.post { step() }
    }

    private fun stopBot(why: String) {
        val was = running
        running = false
        ui.removeCallbacksAndMessages(null)
        autoBtn?.text = "▶ AUTO"
        autoBtn?.setBackgroundColor(Color.parseColor("#CC1B5E20"))
        if (was) toast("Piloto automático: $why")
        refreshBar()
    }

    private fun later(ms: Long) {
        ui.postDelayed({ step() }, ms)
    }

    @Suppress("DEPRECATION")
    private fun screenSize(): Pair<Float, Float> {
        val dm = DisplayMetrics()
        (getSystemService(WINDOW_SERVICE) as WindowManager).defaultDisplay.getRealMetrics(dm)
        return dm.widthPixels.toFloat() to dm.heightPixels.toFloat()
    }

    private fun step() {
        if (!running) return

        val maxMin = prefs().getInt("auto_minutes", 60)
        if (System.currentTimeMillis() - startedAt > maxMin * 60_000L) {
            stopBot("tempo acabou ($maxMin min)")
            return
        }
        val bi = registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val level = bi?.getIntExtra(BatteryManager.EXTRA_LEVEL, 100) ?: 100
        val temp = (bi?.getIntExtra(BatteryManager.EXTRA_TEMPERATURE, 0) ?: 0) / 10f
        val plugged = (bi?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0) != 0
        if (level < 15 && !plugged) { stopBot("bateria baixa"); return }
        if (temp >= 46f) { stopBot("celular quente (${"%.1f".format(temp)}°C)"); return }

        val pkg = rootInActiveWindow?.packageName?.toString() ?: lastPkg
        if (pkg !in games()) { later(2000L); return }

        val size = screenSize()
        if (size.first < size.second) { later(2000L); return }

        if (tpls.isEmpty()) { combat(); return }

        takeShot { shot ->
            if (!running) return@takeShot
            if (shot == null) {
                fails++
                if (fails >= 5) stopBot("não consegui ler a tela (captura bloqueada?)") else later(2000L)
                return@takeShot
            }
            fails = 0
            decide(shot)
        }
    }

    private fun decide(shot: Bitmap) {
        for (t in tpls) {
            if (t.type == "btn" && diff(shot, t) < 22f) {
                unknownSince = 0L
                tap(t.x.toFloat(), t.y.toFloat())
                later(3000L)
                return
            }
        }
        val fights = tpls.filter { it.type == "fight" }
        val inFight = fights.isEmpty() || fights.any { diff(shot, it) < 22f }
        if (inFight) {
            unknownSince = 0L
            combat()
            return
        }
        val now = System.currentTimeMillis()
        if (unknownSince == 0L) unknownSince = now
        if (now - unknownSince > 5 * 60_000L) {
            stopBot("tela desconhecida por 5 min")
            return
        }
        later(2000L)
    }

    private fun tap(x: Float, y: Float) {
        val p = Path()
        p.moveTo(x, y)
        val g = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(p, 0L, 80L))
            .build()
        dispatchGesture(g, null, null)
    }

    private fun combat() {
        val size = screenSize()
        val w = size.first
        val hh = size.second
        val dur = 3000L
        val b = GestureDescription.Builder()

        // joystick: anda em círculos
        val jx = w * 0.149f
        val jy = hh * 0.790f
        val r = w * 0.035f
        val jp = Path()
        jp.moveTo(jx, jy)
        jp.lineTo(jx + r, jy)
        jp.arcTo(RectF(jx - r, jy - r, jx + r, jy + r), 0f, if (cycle % 2 == 0) 360f else -360f)
        b.addStroke(GestureDescription.StrokeDescription(jp, 0L, dur))

        // ataque básico: segura
        val ap = Path()
        ap.moveTo(w * 0.887f, hh * 0.778f)
        b.addStroke(GestureDescription.StrokeDescription(ap, 0L, dur))

        // skills em rodízio
        for (k in 0 until 4) {
            val s = skills[(cycle + k) % skills.size]
            val sp = Path()
            sp.moveTo(w * s[0], hh * s[1])
            b.addStroke(GestureDescription.StrokeDescription(sp, 300L + k * 700L, 80L))
        }
        cycle++

        val ok = dispatchGesture(b.build(), object : GestureResultCallback() {
            override fun onCompleted(gestureDescription: GestureDescription?) { later(0L) }
            override fun onCancelled(gestureDescription: GestureDescription?) { later(500L) }
        }, null)
        if (!ok) later(1000L)
    }
}
