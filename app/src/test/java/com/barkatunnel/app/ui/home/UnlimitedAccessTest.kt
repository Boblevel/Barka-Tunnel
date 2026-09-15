package com.barkatunnel.app.ui.home

import android.app.Application
import android.os.Looper
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.barkatunnel.app.R
import com.barkatunnel.app.backend.BarkaBackendClient
import com.barkatunnel.app.backend.BackendAccessState
import com.google.android.material.button.MaterialButton
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class UnlimitedAccessTest {
    @Test fun serverFlagSurvivesCacheAndTimerWithoutCountingDown() {
        val app=RuntimeEnvironment.getApplication()
        val client=BarkaBackendClient(app)
        val parse=BarkaBackendClient::class.java.getDeclaredMethod("parseAccess",JSONObject::class.java).apply { isAccessible=true }
        val data=JSONObject().put("allowed",true).put("unlimited",true).put("access_type","SUBSCRIPTION").put("remaining_seconds",0)
        val access=parse.invoke(client,data) as BackendAccessState
        val marker=ConnectionTimeFormatter.UNLIMITED_SECONDS
        assertEquals(marker,access.remainingSeconds)
        assertEquals("Illimité",ConnectionTimeFormatter.formatRemaining(marker))
        HomeAccessSnapshotStore.save(app,HomeAccessState(true,marker))
        assertEquals(marker,HomeAccessSnapshotStore.restore(app)!!.remainingSeconds)
        var tick=0L
        val timer=HomeTimerController({tick=it},{})
        timer.syncAccessRemaining(marker);timer.start()
        shadowOf(Looper.getMainLooper()).idleFor(Duration.ofSeconds(3))
        timer.stop()
        assertEquals(marker,tick)
        HomeAccessSnapshotStore.save(app,HomeAccessState(true,600))
        assertTrue(HomeAccessSnapshotStore.restore(app)!!.remainingSeconds in 590L..600L)
        HomeAccessSnapshotStore.save(app,HomeAccessState())
        assertNull(HomeAccessSnapshotStore.restore(app))
        assertEquals(0L,(parse.invoke(client,data.put("allowed",false)) as BackendAccessState).remainingSeconds)
    }

    @Test fun unlimitedIsGreenAndFiniteAccessRestoresItsColor() {
        val app=RuntimeEnvironment.getApplication()
        val context=android.view.ContextThemeWrapper(app,R.style.Theme_BarkaTunnel)
        val remaining=TextView(context)
        val binder=HomeUiBinder(TextView(context),TextView(context),TextView(context),TextView(context),remaining,TextView(context),MaterialButton(context))
        binder.showAccessRemaining(ConnectionTimeFormatter.UNLIMITED_SECONDS)
        assertEquals("Illimité",remaining.text.toString())
        assertEquals(ContextCompat.getColor(context,R.color.barka_green),remaining.currentTextColor)
        binder.showAccessRemaining(3600)
        assertNotEquals("Illimité",remaining.text.toString())
        assertEquals(ContextCompat.getColor(context,R.color.barka_text),remaining.currentTextColor)
    }
}
