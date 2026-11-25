package com.calamity.hub

import android.app.Activity
import android.app.AlertDialog
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
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
    private lateinit var cleanBtn: Button
    private lateinit var autoBtn: Button
    private lateinit var root: LinearLayout

    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    private fun prefs() = getSharedPreferences("hub", MODE_PRIVATE)

    private fun games(): Set<String> = prefs().getStringSet("games", emptySet()) ?: emptySet()

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
            text = "Calamity Hub  v1.2"
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#9FD3FF"))
            setPadding(0, 24, 0, 24)
        }
        root.addView(status)

        root.addView(btn("⚠ REVERTER TUDO (voltar ao padrão)") {
            prefs().edit().putBoolean("always", false).apply()
            stopService(Intent(this, TurboService::class.java))
            updateAlwaysText()
            val mine = games().joinToString("; ") { p ->
                "cmd game mode standard $p; device_config delete game_overlay $p"
            }
            val parts = mutableListOf(
                "cmd power set-fixed-performance-mode-enabled false",
                "for p in \$(device_config list game_overlay | cut -d= -f1); do cmd game mode standard \$p; device_config delete game_overlay \$p; done"
            )
            if (mine.isNotEmpty()) parts.add(mine)
            parts.add("settings delete secure long_press_timeout")
            parts.add("settings delete secure multi_press_timeout")
            parts.add("settings put global window_animation_scale 1")
            parts.add("settings put global transition_animation_scale 1")
            parts.add("settings put global animator_duration_scale 1")
            parts.add("echo 'Revertido ao padrão (90Hz e tamanho da tela não foram alterados).'")
            exec(parts.joinToString("; "))
        })

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
            gamesCmd({ p ->
                "device_config put game_overlay $p mode=2,downscaleFactor=0.7; cmd game mode performance $p; echo 'Performance ligado: $p'"
            }, "echo 'Feche e abra o jogo de novo'")
        })
        root.addView(btn("Modo Estável: resolução normal + 40 FPS") {
            gamesCmd({ p ->
                "device_config put game_overlay $p mode=2,downscaleFactor=1.0,fps=40; cmd game mode performance $p; echo 'Estável 40 FPS ligado: $p'"
            }, "echo 'Feche e abra o jogo de novo'")
        })
        root.addView(btn("Voltar meus jogos ao normal") {
            gamesCmd({ p ->
                "cmd game mode standard $p; device_config delete game_overlay $p; echo 'Normal: $p'"
            }, "echo 'Feche e abra o jogo de novo'")
        })

        root.addView(btn("Otimizar meus jogos (compilar)") {
            gamesCmd({ p ->
                "echo 'Compilando $p...'; cmd package compile -m speed -f $p"
            }, "echo 'Pronto'")
        })
        root.addView(btn("Desfazer otimização dos jogos") {
            gamesCmd({ p ->
                "cmd package compile --reset $p; echo 'Padrão restaurado: $p'"
            }, "echo 'Pronto'")
        })

        cleanBtn = btn("") {
            val on = !prefs().getBoolean("clean", true)
            prefs().edit().putBoolean("clean", on).apply()
            updateCleanText()
        }
        root.addView(cleanBtn)

        root.addView(btn("🤖 Piloto: ativar na acessibilidade") {
            if (games().isEmpty()) log.text = "Antes, marque o Oniro em '2. Escolher meus jogos'."
            startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
        })
        root.addView(btn("🤖 Piloto: liberar configurações restritas") {
            startActivity(
                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName"))
            )
        })
        autoBtn = btn("") {
            val opts = intArrayOf(30, 60, 120, 240, 480)
            val cur = prefs().getInt("auto_minutes", 60)
            val nxt = opts[(opts.indexOf(cur) + 1) % opts.size]
            prefs().edit().putInt("auto_minutes", nxt).apply()
            updateAutoText()
        }
        root.addView(autoBtn)

        root.addView(sh("Tela fixa em 90Hz (ON)",
            "settings put system min_refresh_rate 90.0; settings put system peak_refresh_rate 90.0; echo '90Hz fixo ligado'"))
        root.addView(sh("Tela 90Hz automática (OFF)",
            "settings delete system min_refresh_rate; settings delete system peak_refresh_rate; echo 'Voltou ao automático'"))
        root.addView(sh("Toque mais rápido (ON)",
            "settings put secure long_press_timeout 250; settings put secure multi_press_timeout 250; echo 'Toque rápido ligado'"))
        root.addView(sh("Toque normal (OFF)",
            "settings delete secure long_press_timeout; settings delete secure multi_press_timeout; echo 'Toque normal'"))
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
        updateCleanText()
        updateAutoText()
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

    private fun gamesCmd(each: (String) -> String, tail: String) {
        val g = games()
        if (g.isEmpty()) {
            log.text = "Escolha seus jogos primeiro (botão 2)."
            return
        }
        exec(g.joinToString("; ") { each(it) } + "; " + tail)
    }

    private fun updateAlwaysText() {
        alwaysBtn.text = "Turbo SEMPRE ligado: " + if (prefs().getBoolean("always", false)) "ON ✔" else "OFF"
    }

    private fun updateCleanText() {
        cleanBtn.text = "Limpar RAM ao abrir jogo: " + if (prefs().getBoolean("clean", true)) "ON ✔" else "OFF"
    }

    private fun updateAutoText() {
        autoBtn.text = "🤖 Piloto para sozinho em: ${prefs().getInt("auto_minutes", 60)} min (toque p/ mudar)"
    }

    private fun pickGames() {
        val pm = packageManager
        val i = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
        val apps = pm.queryIntentActivities(i, 0)
            .map { it.activityInfo.packageName to it.loadLabel(pm).toString() }
            .distinct()
            .sortedBy { it.second.lowercase() }
        val saved = games().toMutableSet()
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
        log.text = "Executando... (pode demorar, não saia do Hub)"
        thread {
            val r = try { Sh.run(cmd) } catch (e: Throwable) { -1 to "Erro: $e" }
            runOnUiThread { log.text = "código ${r.first}\n${r.second}" }
        }
    }
}
