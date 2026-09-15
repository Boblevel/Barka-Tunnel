package com.barkatunnel.app.update
import org.junit.Assert.*
import org.junit.Test
class AppUpdateDestinationTest {
    @Test fun acceptsCustomHttpsDestinations() {
        for (url in listOf("https://example.org/a.apk?build=111&channel=stable", "https://other.example.org/download#apk", "https://example.org:8443/a")) assertTrue(url, AppUpdateDestination.isValid(url))
    }
    @Test fun rejectsUnsafeDestinations() {
        for (url in listOf("", "http://example.org/a", "javascript:alert(1)", "https://", "https://user:secret@example.org/a", "https://example.org:99999/a", "https://example.org/a b", "https://example.org/\nx", "https://example.org\\@evil.org")) assertFalse(url, AppUpdateDestination.isValid(url))
    }
}
