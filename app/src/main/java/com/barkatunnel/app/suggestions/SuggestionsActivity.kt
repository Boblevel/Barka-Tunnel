package com.barkatunnel.app.suggestions

import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.widget.EditText
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.ui.SystemBars
import com.google.android.material.button.MaterialButton
import java.util.UUID
import java.util.concurrent.Executors

class SuggestionsActivity : AppCompatActivity() {
    private val worker = Executors.newSingleThreadExecutor()
    private val handler = Handler(Looper.getMainLooper())
    private val prefs by lazy { getSharedPreferences("barka_suggestions", MODE_PRIVATE) }
    private val client by lazy { BarkaBackendClient(this) }
    private lateinit var message: EditText
    private lateinit var status: TextView
    private lateinit var send: MaterialButton
    private var enabled = false
    private var sending = false
    private var checking = false
    private var resumed = false
    private val poll = object : Runnable {
        override fun run() {
            if (!resumed) return
            refresh()
            handler.postDelayed(this, 15_000)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_suggestions)
        SystemBars.apply(this)
        message = findViewById(R.id.suggestionMessage)
        status = findViewById(R.id.suggestionStatus)
        send = findViewById(R.id.suggestionSend)
        message.setText(prefs.getString("draft", ""))
        findViewById<android.view.View>(R.id.suggestionBack).setOnClickListener { finish() }
        findViewById<android.view.View>(R.id.suggestionRefresh).setOnClickListener { refresh() }
        send.setOnClickListener { submit() }
    }

    override fun onResume() {
        super.onResume()
        resumed = true
        handler.post(poll)
    }

    override fun onPause() {
        resumed = false
        handler.removeCallbacks(poll)
        prefs.edit().putString("draft", message.text.toString()).apply()
        super.onPause()
    }

    private fun refresh() {
        if (sending || checking) return
        checking = true
        worker.execute {
            val result = runCatching { client.suggestionsEnabled() }
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                checking = false
                enabled = result.getOrDefault(false)
                if (!sending) {
                    send.isEnabled = enabled
                    status.text = result.fold(
                        { getString(if (it) R.string.suggestions_open else R.string.suggestions_closed) },
                        { it.message ?: getString(R.string.suggestions_checking) })
                }
            }
        }
    }

    private fun submit() {
        if (sending || !enabled) return
        val text = message.text.toString().trim()
        if (text.length !in 3..2000) {
            message.error = getString(R.string.suggestions_invalid)
            return
        }
        // Reuse the receipt after a timeout or activity recreation.
        val requestId = if (prefs.getString("pending_text", null) == text)
            prefs.getString("pending_id", null) ?: UUID.randomUUID().toString()
        else UUID.randomUUID().toString()
        prefs.edit().putString("pending_text", text).putString("pending_id", requestId)
            .putString("draft", text).commit()
        sending = true
        send.isEnabled = false
        message.isEnabled = false
        status.setText(R.string.suggestions_sending)
        worker.execute {
            val result = runCatching { client.sendSuggestion(text, requestId) }
            // Keep the receipt while a recreated activity might still show this draft.
            if (result.isSuccess) prefs.edit().remove("draft").commit()
            runOnUiThread {
                if (isDestroyed || isFinishing) return@runOnUiThread
                sending = false
                message.isEnabled = true
                send.isEnabled = enabled
                result.fold({
                    message.text.clear()
                    status.setText(R.string.suggestions_sent)
                    Toast.makeText(this, R.string.suggestions_sent, Toast.LENGTH_LONG).show()
                }, {
                    status.text = it.message
                    Toast.makeText(this, it.message, Toast.LENGTH_LONG).show()
                    refresh()
                })
            }
        }
    }

    override fun onDestroy() {
        handler.removeCallbacks(poll)
        worker.shutdown()
        super.onDestroy()
    }
}
