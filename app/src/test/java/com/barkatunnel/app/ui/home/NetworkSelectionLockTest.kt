package com.barkatunnel.app.ui.home

import android.app.Application
import android.view.View
import com.barkatunnel.app.MainActivity
import com.barkatunnel.app.R
import com.barkatunnel.app.vpnc6.BarkaVpnService
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowDialog
import org.robolectric.shadows.ShadowToast

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28],application=Application::class)
class NetworkSelectionLockTest {
    private fun flag(a: MainActivity, name: String, value: Boolean) =
        MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.setBoolean(a,value)
    private fun runtime(value: BarkaVpnService.RuntimeConnectionState) =
        BarkaVpnService::class.java.getDeclaredField("runtimeState").apply {isAccessible=true}.set(null,value)
    private fun call(a: MainActivity, name: String): Any? =
        MainActivity::class.java.getDeclaredMethod(name).apply {isAccessible=true}.invoke(a)
    @Test fun selectionLockedThroughoutConnectionAndUnlockedAfterStop() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get()
        for(state in BarkaVpnService.RuntimeConnectionState.values()) {
            runtime(state)
            assertEquals(state!=BarkaVpnService.RuntimeConnectionState.DISCONNECTED,call(a,"networkSelectionLocked"))
        }
        runtime(BarkaVpnService.RuntimeConnectionState.DISCONNECTED)
        assertEquals(false,call(a,"networkSelectionLocked"))
    }
    @Test fun pendingPermissionAndStartupAlsoProtectSelection() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get()
        runtime(BarkaVpnService.RuntimeConnectionState.DISCONNECTED)
        for(name in listOf("connectionStartRequested","disconnectRequested","pendingConnectAfterInitialSync","pendingConnectAfterVpnPermission")) {
            flag(a,name,true);assertEquals(true,call(a,"networkSelectionLocked"))
            flag(a,name,false);assertEquals(false,call(a,"networkSelectionLocked"))
        }
    }
    @Test fun connectedTapShowsHelpWithoutOpeningSelection() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get()
        runtime(BarkaVpnService.RuntimeConnectionState.CONNECTED)
        try {
            val before=ShadowDialog.getLatestDialog()
            call(a,"showNetworkDialog")
            assertSame(before,ShadowDialog.getLatestDialog())
            assertEquals(a.getString(R.string.disconnect_before_network_change),ShadowToast.getTextOfLatestToast())
        }finally{runtime(BarkaVpnService.RuntimeConnectionState.DISCONNECTED)}
    }
    @Test fun alreadyOpenDialogCannotChangeNetworkAfterConnectionStarts() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get()
        runtime(BarkaVpnService.RuntimeConnectionState.DISCONNECTED)
        call(a,"showNetworkDialog")
        val dialog=ShadowDialog.getLatestDialog();assertTrue(dialog.isShowing)
        flag(a,"connectionStartRequested",true)
        dialog.findViewById<View>(R.id.optionOrange).performClick()
        assertFalse(dialog.isShowing)
        assertNull(a.getSharedPreferences("barka_home_preferences",0).getString("selected_network_id",null))
    }
}
