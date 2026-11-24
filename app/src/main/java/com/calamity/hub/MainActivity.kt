package com.calamity.hub

import android.app.Activity
import android.content.pm.PackageManager
import android.graphics.Color
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

    private val permListener =
        Shizuku.OnRequestPermissionResultListener { _, _ -> runOnUiThread { refresh() } }
    private val binderListener = Shizuku.OnBinderReceivedListener { runOnUiThread { refresh() } }
    private val deadListener = Shizuku.OnBinderDeadListener { runOnUiThread { refresh() } }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 80, 40, 40)
            setBackgroundColor(Color.parseColor("#101418"))
        }
        root.addView(TextView(this).apply {
            text = "Calamity Hub  v0.1"
            textSize = 24f
            setTextColor(Color.WHITE)
        })
        status = TextView(this).apply {
            textSize = 15f
            setTextColor(Color.parseColor("#9FD3FF"))
            setPadding(0, 24, 0, 24)
        }
        root.addView(status)

        root.addView(btn("1. Dar permissão ao Shizuku") {
            if (Shizuku.pingBinder() && Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED)
                Shizuku.requestPermission(100)
            else refresh()
        })
        root.addView(btn("2. Testar se meu celular aceita o Turbo") {
            exec("cmd power help | grep -i fixed-performance || echo 'NÃO ENCONTRADO: este celular não tem o comando'")
        })
        root.addView(btn("TURBO  ON") {
            exec("cmd power set-fixed-performance-mode-enabled true && echo 'Turbo ligado'")
        })
        root.addView(btn("TURBO  OFF") {
            exec("cmd power set-fixed-performance-mode-enabled false && echo 'Turbo desligado'")
        })
        root.addView(btn("Auto-início do Shizuku (conceder WRITE_SECURE_SETTINGS)") {
            exec("pm grant moe.shizuku.privileged.api android.permission.WRITE_SECURE_SETTINGS && echo 'Permissão concedida ao Shizuku'")
        })

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
            val r = try { Sh.run(cmd) } catch (e: Throwable) { -1 to "Erro: ${e}" }
            runOnUiThread { log.text = "código ${r.first}\n${r.second}" }
        }
    }
}
