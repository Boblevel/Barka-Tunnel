package com.barkatunnel.app.ipfinder

import android.app.Application
import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.view.View
import com.barkatunnel.app.R
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.annotation.Implementation
import org.robolectric.annotation.Implements

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], application = Application::class, shadows = [IpFinderUpdate112Test.AssistantRoles::class])
class IpFinderUpdate112Test {
    @Implements(RoleManager::class)
    class AssistantRoles {
        @Implementation fun isRoleAvailable(role: String) = available
        @Implementation fun isRoleHeld(role: String) = held
        @Implementation fun createRequestRoleIntent(role: String): Intent =
            Intent("android.app.role.action.REQUEST_ROLE").putExtra("android.app.role.extra.ROLE_NAME", role)
        companion object { var available = true; var held = false }
    }
    private val context get() = RuntimeEnvironment.getApplication()
    private val preferences get() = context.getSharedPreferences(IpFinderActivity.PREFERENCES_NAME, Context.MODE_PRIVATE)

    @Before fun reset() {
        shadowOf(context).grantPermissions("com.barkatunnel.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        preferences.edit().clear().commit()
        AssistantRoles.available = true
        AssistantRoles.held = false
    }

    @Test fun newInstallRejectsRetiredPrefixButKeepsOtherAddresses() {
        assertFalse(IpFinderActivity.isIpCompatible(context, "10.46.1.2"))
        assertTrue(IpFinderActivity.isIpCompatible(context, "10.161.1.2"))
        assertTrue(IpFinderActivity.isIpCompatible(context, "10.209.1.2"))
    }

    @Test fun savedPatternsAreMigratedWithoutRemovingSimilarPrefixesOrCustomRules() {
        preferences.edit().putString("search_pattern", "10.46;^10.46;=10.46;10.148,10.146\n^10.75;=192.168.1.2").commit()
        assertFalse(IpFinderActivity.isIpCompatible(context, "10.46.1.2"))
        assertEquals("10.146;^10.75;=192.168.1.2", preferences.getString("search_pattern", ""))
        assertTrue(IpFinderActivity.isIpCompatible(context, "10.146.1.2"))
        assertTrue(IpFinderActivity.isIpCompatible(context, "192.168.1.2"))
        assertFalse(IpFinderActivity.isIpCompatible(context, "192.168.1.3"))
    }

    @Test fun onlyRetiredPatternsRestoreTheSupportedDefaults() {
        preferences.edit().putString("search_pattern", "10.46;^10.148").commit()
        assertFalse(IpFinderActivity.isIpCompatible(context, "10.46.1.2"))
        assertTrue(IpFinderActivity.isIpCompatible(context, "10.161.1.2"))
    }

    @Test fun removesRetiredPrefixesAndPreservesLaterUserEdits() {
        preferences.edit().putString("search_pattern", "10.193;10.194;10.80;10.165").commit()
        assertFalse(IpFinderActivity.isIpCompatible(context, "10.194.1.2"))
        assertEquals("10.165",preferences.getString("search_pattern", ""))
        preferences.edit().putString("search_pattern", "=192.168.1.1").commit()
        assertFalse(IpFinderActivity.isIpCompatible(context, "10.194.1.2"))
        assertTrue(IpFinderActivity.isIpCompatible(context, "192.168.1.1"))
    }

    private fun launchSelection(expectedAction: String) {
        val controller = Robolectric.buildActivity(IpFinderActivity::class.java).setup()
        try {
            val activity = controller.get()
            activity.findViewById<View>(R.id.setAssistantButton).performClick()
            val intent = shadowOf(activity).nextStartedActivity
            assertNotNull(intent)
            assertEquals(expectedAction, intent.action)
            assertNull(shadowOf(activity).nextStartedActivity)
            assertTrue(activity.findViewById<View>(R.id.setAssistantButton).isEnabled)
            activity.findViewById<View>(R.id.setAssistantButton).performClick()
            assertEquals(expectedAction, shadowOf(activity).nextStartedActivity.action)
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun directAssistantSettingsHavePriorityEvenWhenRoleRequestIsAvailable() {
        launchSelection(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }

    @Test fun unavailableRoleFallsBackToAssistantSettings() {
        AssistantRoles.available = false
        launchSelection(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }

    @Test fun alreadySelectedAssistantCanStillOpenSettings() {
        AssistantRoles.held = true
        launchSelection(Settings.ACTION_VOICE_INPUT_SETTINGS)
    }
    @Test fun detectsColorsAndHidesSearchAsSoonAsMobileIpMatches() {
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val manager=shadowOf(cm)
        manager.clearAllNetworks()
        val network=org.robolectric.shadows.ShadowNetwork.newInstance(42)
        manager.addNetwork(network,org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED,android.net.ConnectivityManager.TYPE_MOBILE,0,true,true))
        val capabilities=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(android.net.NetworkCapabilities.TRANSPORT_CELLULAR)
        manager.setNetworkCapabilities(network,capabilities)
        fun link(ip:String)=android.net.LinkProperties().apply {
            val address=org.robolectric.util.ReflectionHelpers.callConstructor(
                android.net.LinkAddress::class.java,
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(java.net.InetAddress::class.java,java.net.InetAddress.getByName(ip)),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,24))
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(this,"addLinkAddress",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.net.LinkAddress::class.java,address))
        }
        manager.setLinkProperties(network,link("10.161.1.2"))
        val controller=Robolectric.buildActivity(IpFinderActivity::class.java).setup()
        try {
            val activity=controller.get()
            val result=activity.findViewById<android.widget.TextView>(R.id.scanResult)
            val button=activity.findViewById<View>(R.id.scanButton)
            assertEquals("10.161.1.2",result.text.toString())
            assertEquals(View.GONE,button.visibility)
            assertEquals(androidx.core.content.ContextCompat.getColor(activity,R.color.barka_green),result.currentTextColor)
            val bad=link("10.193.1.2");manager.setLinkProperties(network,bad)
            manager.networkCallbacks.forEach {it.onLinkPropertiesChanged(network,bad)}
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(View.VISIBLE,button.visibility);assertTrue(button.isEnabled)
            assertEquals(androidx.core.content.ContextCompat.getColor(activity,R.color.barka_red),result.currentTextColor)
            val searching=IpFinderActivity::class.java.getDeclaredField("searching").apply {isAccessible=true}
            searching.setBoolean(activity,true)
            // During a network cut the last address remains labelled, never green.
            manager.setLinkProperties(network, android.net.LinkProperties())
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            assertTrue(result.text.toString().contains("10.193.1.2"))
            assertEquals(androidx.core.content.ContextCompat.getColor(activity,R.color.barka_text_secondary),result.currentTextColor)
            // Some ROMs omit the link callback. Polling must recover without leaving the page.
            val good=link("10.161.2.3");manager.setLinkProperties(network,good)
            shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofSeconds(1))
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals(View.GONE,button.visibility);assertFalse(searching.getBoolean(activity))
            assertEquals(androidx.core.content.ContextCompat.getColor(activity,R.color.barka_green),result.currentTextColor)
        } finally {controller.pause().stop().destroy()}
    }

    @Test fun wifiAddressIsDisplayedLiveWithRedGreenAndSearchAction() {
        val cm=context.getSystemService(Context.CONNECTIVITY_SERVICE) as android.net.ConnectivityManager
        val manager=shadowOf(cm);manager.clearAllNetworks()
        val network=org.robolectric.shadows.ShadowNetwork.newInstance(43)
        manager.addNetwork(network,org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            android.net.NetworkInfo.DetailedState.CONNECTED,android.net.ConnectivityManager.TYPE_WIFI,0,true,true))
        val capabilities=org.robolectric.shadows.ShadowNetworkCapabilities.newInstance()
        shadowOf(capabilities).addTransportType(android.net.NetworkCapabilities.TRANSPORT_WIFI)
        manager.setNetworkCapabilities(network,capabilities)
        fun link(ip:String)=android.net.LinkProperties().apply {
            val address=org.robolectric.util.ReflectionHelpers.callConstructor(
                android.net.LinkAddress::class.java,
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(java.net.InetAddress::class.java,java.net.InetAddress.getByName(ip)),
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(Int::class.javaPrimitiveType,24))
            org.robolectric.util.ReflectionHelpers.callInstanceMethod<Boolean>(this,"addLinkAddress",
                org.robolectric.util.ReflectionHelpers.ClassParameter.from(android.net.LinkAddress::class.java,address))
        }
        manager.setLinkProperties(network,link("192.168.1.50"))
        val controller=Robolectric.buildActivity(IpFinderActivity::class.java).setup()
        try {
            val activity=controller.get()
            val result=activity.findViewById<android.widget.TextView>(R.id.scanResult)
            val button=activity.findViewById<View>(R.id.scanButton)
            assertEquals("192.168.1.50",result.text.toString())
            assertEquals(View.VISIBLE,button.visibility);assertTrue(button.isEnabled)
            activity.findViewById<android.widget.EditText>(R.id.ipInput).setText("=192.168.1.50")
            assertEquals(View.GONE,button.visibility)
            assertEquals(androidx.core.content.ContextCompat.getColor(activity,R.color.barka_green),result.currentTextColor)
            val changed=link("192.168.1.51");manager.setLinkProperties(network,changed)
            manager.networkCallbacks.forEach { it.onLinkPropertiesChanged(network,changed) }
            shadowOf(android.os.Looper.getMainLooper()).idle()
            assertEquals("192.168.1.51",result.text.toString())
            assertEquals(View.VISIBLE,button.visibility)
            assertEquals(androidx.core.content.ContextCompat.getColor(activity,R.color.barka_red),result.currentTextColor)
        } finally {controller.pause().stop().destroy()}
    }
}
