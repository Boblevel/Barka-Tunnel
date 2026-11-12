package com.barkatunnel.app.journal

import android.app.Application
import android.widget.LinearLayout
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(application = Application::class, sdk = [29])
class JournalDiagnosticTest {
    private fun binder(): JournalUiBinder {
        val context = RuntimeEnvironment.getApplication()
        return JournalUiBinder(context, LinearLayout(context))
    }
    @Test fun diagnosticsAreHiddenWithoutChangingOtherEvents() {
        val binder = binder()
        val parse = JournalUiBinder::class.java.getDeclaredMethod("toJournalEvent", String::class.java).apply { isAccessible = true }
        assertNull(parse.invoke(binder, "[10:00:00] Diagnostic VLESS • CONNECT • proxy HTTP : statut 403"))
        assertNull(parse.invoke(binder, "[10:00:01] Diagnostic VPN • tentative 1 • délai dépassé"))
        assertNotNull(parse.invoke(binder, "[10:00:02] VPN connecté • ORANGE BF"))
        assertNull(parse.invoke(binder, "[10:00:03] Profils à jour"))
    }
    @Test fun secretsAndConfigurationAreNotShown() {
        val binder = binder()
        for (secret in listOf("password=secret phrase", "Authorization: Bearer abcd", "token=abcdef")) {
            val output = binder.safeDiagnostic("Diagnostic VPN • $secret")
            assertTrue(output.endsWith("=[masqué]"))
        }
        assertFalse(binder.safeDiagnostic("Diagnostic VLESS • aaaa1111-bbbb-4ccc-8ddd-eeeeffff0000").contains("aaaa1111"))
        assertFalse(binder.safeDiagnostic("Diagnostic VPN • vless://private@example.invalid/path").contains("private"))
        assertFalse(binder.safeDiagnostic("Diagnostic VLESS • config VLESS • WS=private.example/secret").contains("private"))
        assertTrue(binder.safeDiagnostic("Diagnostic VLESS • CONNECT • statut 403").contains("403"))
    }
    @Test fun temporaryDiagnosticsDoNotEvictNormalJournalEntries() {
        val context = RuntimeEnvironment.getApplication()
        AppLogStore.clear(context)
        AppLogStore.add(context, "VPN connecté • ORANGE BF")
        repeat(300) { AppLogStore.add(context, "Diagnostic VLESS • CONNECT • statut 429") }
        assertTrue(AppLogStore.getAll(context).contains("VPN connecté"))
        assertFalse(AppLogStore.getAll(context).contains("Diagnostic"))
        AppLogStore.clear(context)
    }
}
