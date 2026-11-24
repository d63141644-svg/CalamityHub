package com.calamity.hub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import rikka.shizuku.Shizuku
import kotlin.concurrent.thread

/** Roda um comando de shell com privilégio via Shizuku (sem root). */
object Sh {
    fun run(cmd: String): Pair<Int, String> {
        val m = Shizuku::class.java.getDeclaredMethod(
            "newProcess",
            Array<String>::class.java, Array<String>::class.java, String::class.java
        )
        m.isAccessible = true
        val p = m.invoke(null, arrayOf("sh", "-c", cmd), null, null) as Process
        val out = p.inputStream.bufferedReader().readText()
        val err = p.errorStream.bufferedReader().readText()
        val code = p.waitFor()
        return code to (out + err).trim()
    }
}

class MainActivity : Activity() {
    private lateinit var status: TextView
    private lateinit var log: TextView
    private lateinit var alwaysBtn: Button
    private lateinit var root: LinearLayout

    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    private fun prefs() = getSharedPreferences("hub", MODE_PRIVATE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 &&
            checkSelfPermission("android.permission.POST_NOTIFICATIONS") != PackageManager.PERMISSION_GRANTED
        ) requestPermissions(arrayOf("android.permission.POST_NOTIFICATIONS"), 1)

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            setBackgroundColor(Color.parseColor("#101418"))
        }
        root.addView(TextView(this).apply {
            text = "Calamity Hub  v0.4"
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#9FD3FF"))
            setPadding(0, 24, 0, 24)
        }
        root.addView(status)

        alwaysBtn = btn("") {
            val on = !prefs().getBoolean("always", false)
            prefs().edit().putBoolean("always", on).apply()
            val i = Intent(this, TurboService::class.java)
            if (on) startForegroundService(i) else stopService(i)
            updateAlwaysText()
        }
        root.addView(alwaysBtn)
        root.addView(btn("1. Dar permissão ao Shizuku") {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                Shizuku.requestPermission(100)
            else refresh()
        })
        root.addView(btn("2. Escolher meus jogos") { pickGames() })

        root.addView(btn("Modo Performance nos meus jogos (70%)") {
            val games = prefs().getStringSet("games", emptySet()) ?: emptySet()
            if (games.isEmpty()) {
                log.text = "Escolha seus jogos primeiro (botão 2)."
            } else {
                exec(games.joinToString("; ") {
                    "device_config put game_overlay $it mode=2,downscaleFactor=0.7; cmd game mode performance $it; echo 'Performance ligado: $it'"
                } + "; echo 'Feche e abra o jogo de novo'")
            }
        })
        root.addView(btn("Voltar meus jogos ao normal") {
            val games = prefs().getStringSet("games", emptySet()) ?: emptySet()
            if (games.isEmpty()) {
                log.text = "Escolha seus jogos primeiro (botão 2)."
            } else {
                exec(games.joinToString("; ") {
                    "cmd game mode standard $it; device_config delete game_overlay $it; echo 'Normal: $it'"
                })
            }
        })
        root.addView(sh("Tela fixa em 90Hz (ON)",
            "settings put system min_refresh_rate 90.0; settings put system peak_refresh_rate 90.0; echo '90Hz fixo ligado'"))
        root.addView(sh("Tela 90Hz automática (OFF)",
            "settings delete system min_refresh_rate; settings delete system peak_refresh_rate; echo 'Voltou ao automático'"))
        root.addView(sh("Toque mais rápido (ON)",
            "settings put secure long_press_timeout 250; settings put secure multi_press_timeout 250; echo 'Toque rápido ligado'"))
        root.addView(sh("Toque normal (OFF)",
            "settings put secure long_press_timeout 400; settings put secure multi_press_timeout 300; echo 'Toque normal'"))

        root.addView(sh("Diagnóstico de janelas", "pm list packages | grep -i -E 'float|multiwindow|multi_window|panel|sidebar|shalltry|transsion'; echo ---; pm list packages | grep -i -E 'whatsapp|youtube'"))
        root.addView(sh("Resolução leve (80%) ON",
            "d=\$(wm density | grep -o '[0-9]*' | head -1); wm size 576x1290; wm density \$((d*80/100)); echo 'Resolução leve ligada'"))
        root.addView(sh("Resolução normal (OFF)", "wm size reset; wm density reset; echo 'Resolução normal'"))
        root.addView(sh("Animações rápidas (0.5x)",
            "settings put global window_animation_scale 0.5; settings put global transition_animation_scale 0.5; settings put global animator_duration_scale 0.5; echo 'Animações 0.5x'"))
        root.addView(sh("Animações normais (1x)",
            "settings put global window_animation_scale 1; settings put global transition_animation_scale 1; settings put global animator_duration_scale 1; echo 'Animações 1x'"))
        root.addView(sh("Otimizar apps (demora alguns minutos)",
            "cmd package compile -m speed-profile -a"))
        root.addView(sh("Auto-início do Shizuku (permissão)",
            "pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS && echo 'Permissão concedida'"))

        log = TextView(this).apply {
            textSize = 13f
            setTextColor(Color.parseColor("#C8FFC8"))
            setPadding(0, 32, 0, 0)
        }
        root.addView(log)
        setContentView(ScrollView(this).apply { addView(root) })

        Shizuku.addRequestPermissionResultListener(permListener)
        Shizuku.addBinderReceivedListenerSticky(binderListener)
        Shizuku.addBinderDeadListener(deadListener)

        updateAlwaysText()
        if (prefs().getBoolean("always", false)) {
            startForegroundService(Intent(this, TurboService::class.java))
        }
        refresh()
    }

    override fun onDestroy() {
        Shizuku.removeRequestPermissionResultListener(permListener)
        Shizuku.removeBinderReceivedListener(binderListener)
        Shizuku.removeBinderDeadListener(deadListener)
        super.onDestroy()
    }

    private fun btn(label: String, onClick: () -> Unit) = Button(this).apply {
        text = label
        isAllCaps = false
        setOnClickListener { onClick() }
    }

    private fun sh(label: String, cmd: String) = btn(label) { exec(cmd) }

    private fun updateAlwaysText() {
        alwaysBtn.text = "Turbo SEMPRE ligado: " + if (prefs().getBoolean("always", false)) "ON ✔" else "OFF"
    }

    private fun pickGames() {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinct()
            .sortedBy { it.second.lowercase() }
        val saved = (prefs().getStringSet("games", emptySet()) ?: emptySet()).toMutableSet()
        val checked = BooleanArray(apps.size) { apps[it].first in saved }
        AlertDialog.Builder(this)
            .setTitle("Quais são seus jogos?")
            .setMultiChoiceItems(apps.map { it.second }.toTypedArray(), checked) { _, w, c ->
                if (c) saved.add(apps[w].first) else saved.remove(apps[w].first)
            }
            .setPositiveButton("Salvar") { _, _ ->
                prefs().edit().putStringSet("games", saved).apply()
            }
            .show()
    }

    private fun refresh() {
        val running = Shizuku.pingBinder()
        val granted = running && Shizuku.checkSelfPermission() == PackageManager.PERMISSION_GRANTED
        status.text = when {
            !running -> "Shizuku: NÃO está rodando. Abra o Shizuku e toque em Iniciar."
            !granted -> "Shizuku: rodando. Falta dar permissão (botão 1)."
            else -> "Shizuku: rodando e autorizado ✔"
        }
    }

    private fun exec(cmd: String) {
        if (!Shizuku.pingBinder() || Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
            log.text = "Shizuku não está pronto. Veja o status acima."
            return
        }
        log.text = "Executando..."
        thread {
            val r = try { Sh.run(cmd) } catch (e: Throwable) { -1 to "Erro: $e" }
            runOnUiThread { log.text = "código ${r.first}\n${r.second}" }
        }
    }
}
