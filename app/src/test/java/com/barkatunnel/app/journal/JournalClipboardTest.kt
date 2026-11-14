package com.barkatunnel.app.journal

import android.app.Application
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class JournalClipboardTest {
    @Test fun copiesAllStoredLinesInOrderWithoutDeletingThem() {
        val context = RuntimeEnvironment.getApplication()
        val text = (1..600).joinToString("\n") { "[12:00:00] Ligne $it" }
        context.getSharedPreferences("barka_journal", Context.MODE_PRIVATE).edit().putString("logs", text).commit()
        JournalClipboard.copyAll(context)
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        assertEquals(text, clipboard.primaryClip!!.getItemAt(0).text.toString())
        assertEquals(text, AppLogStore.getAll(context))
    }

    @Test fun emptyJournalKeepsExistingClipboardAndCredentialsStayMasked() {
        val context = RuntimeEnvironment.getApplication()
        val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Existing", "keep"))
        AppLogStore.clear(context)
        JournalClipboard.copyAll(context)
        assertEquals("keep", clipboard.primaryClip!!.getItemAt(0).text.toString())
        context.getSharedPreferences("barka_journal", Context.MODE_PRIVATE).edit()
            .putString("logs", "[12:00:00] uuid=aaaa1111-bbbb-4ccc-8ddd-eeeeffff0000\n[12:00:01] Fin").commit()
        JournalClipboard.copyAll(context)
        val copied = clipboard.primaryClip!!.getItemAt(0).text.toString()
        assertFalse(copied.contains("aaaa1111"))
        assertTrue(copied.endsWith("\n[12:00:01] Fin"))
    }
}
