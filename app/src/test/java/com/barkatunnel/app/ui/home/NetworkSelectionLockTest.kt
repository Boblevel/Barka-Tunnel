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
    @Test fun selectingSeveralNetworksKeepsDialogOpenUntilBack() {
        val a=Robolectric.buildActivity(MainActivity::class.java).get()
        a.setTheme(R.style.Theme_BarkaTunnel)
        runtime(BarkaVpnService.RuntimeConnectionState.DISCONNECTED)
        fun field(name:String,value:Any) = MainActivity::class.java.getDeclaredField(name).apply {isAccessible=true}.set(a,value)
        fun text()=android.widget.TextView(a)
        field("uiBinder",HomeUiBinder(text(),text(),text(),text(),text(),text(),com.google.android.material.button.MaterialButton(a)))
        for(name in listOf("networkLogo","networkFlagNiger","networkFlagTogo")) field(name,android.widget.ImageView(a))
        for(name in listOf("networkIpValue","networkIpStatus")) field(name,text())
        field("networkIpCard",View(a))
        call(a,"showNetworkDialog")
        val dialog=ShadowDialog.getLatestDialog()
        try {
            for((viewId,id) in listOf(R.id.optionGlobal to "global_world",R.id.optionTelecel to "telecel_bf",R.id.optionMoov to "moov_bf")) {
                dialog.findViewById<View>(viewId).performClick()
                assertTrue(dialog.isShowing)
                assertEquals(id,a.getSharedPreferences("barka_home_preferences",0).getString("selected_network_id",null))
                for(row in listOf(R.id.optionGlobal,R.id.optionTelecel,R.id.optionMoov,R.id.optionOrange,R.id.optionTiktok)) {
                    assertEquals(row==viewId,dialog.findViewById<View>(row).isSelected)
                }
            }
            dialog.findViewById<View>(R.id.networkDialogClose).performClick()
            assertFalse(dialog.isShowing)
        } finally {dialog.dismiss();a.getSharedPreferences("barka_home_preferences",0).edit().clear().commit()}
    }

}
