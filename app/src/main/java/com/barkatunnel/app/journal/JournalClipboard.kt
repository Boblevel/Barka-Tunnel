package com.barkatunnel.app.journal

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.widget.Toast
import com.barkatunnel.app.R

object JournalClipboard {
    fun copyAll(context: Context) {
        val text = AppLogStore.getAll(context).lineSequence()
            .map(DiagnosticRedactor::clean).joinToString("\n")
        if (text.isBlank()) {
            Toast.makeText(context, R.string.journal_empty, Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText(context.getString(R.string.journal_title), text))
        Toast.makeText(context, R.string.journal_copied, Toast.LENGTH_SHORT).show()
    }
}
