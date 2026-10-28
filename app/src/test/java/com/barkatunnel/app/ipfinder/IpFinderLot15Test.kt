package com.barkatunnel.app.ipfinder

import android.app.Application
import android.app.NotificationManager
import android.content.Context
import android.net.*
import android.os.Vibrator
import android.widget.TextView
import com.barkatunnel.app.R
import org.junit.Assert.*
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.*
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.*
import org.robolectric.util.ReflectionHelpers
import org.robolectric.util.ReflectionHelpers.ClassParameter
import java.net.InetAddress

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[28], application=Application::class)
class IpFinderLot15Test {
    private val context get()=RuntimeEnvironment.getApplication()
    private fun state(name:String,value:Any?) = IpSearchService::class.java.getDeclaredField(name).apply {isAccessible=true}.set(null,value)
    @After fun reset() {state("foundIp",null);state("status","");state("active",false)}
    @Test fun staleSuccessDisappearsOnAddressChangeAndResume() {
        shadowOf(context).grantPermissions("com.barkatunnel.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        context.getSharedPreferences("barka_home_preferences",Context.MODE_PRIVATE).edit().putString("selected_network_id","orange_bf").commit()
        val cm=shadowOf(context.getSystemService(ConnectivityManager::class.java));cm.clearAllNetworks()
        val network=ShadowNetwork.newInstance(42)
        cm.addNetwork(network,ShadowNetworkInfo.newInstance(NetworkInfo.DetailedState.CONNECTED,ConnectivityManager.TYPE_MOBILE,0,true,true))
        val caps=ShadowNetworkCapabilities.newInstance();shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        cm.setNetworkCapabilities(network,caps)
        fun address(ip:String) {
            val link=LinkProperties()
            val addr=ReflectionHelpers.callConstructor(LinkAddress::class.java,
                ClassParameter.from(InetAddress::class.java,InetAddress.getByName(ip)),ClassParameter.from(Int::class.javaPrimitiveType,24))
            ReflectionHelpers.callInstanceMethod<Boolean>(link,"addLinkAddress",ClassParameter.from(LinkAddress::class.java,addr))
            cm.setLinkProperties(network,link)
        }
        state("foundIp","10.215.87.26");state("status",context.getString(R.string.ip_finder_found_detail,"10.215.87.26"))
        address("10.215.87.26")
        val controller=Robolectric.buildActivity(IpFinderActivity::class.java).setup()
        try {
            val activity=controller.get()
            fun status()=activity.findViewById<TextView>(R.id.scanStatus)
            assertTrue(status().text.contains("10.215.87.26"))
            address("10.134.214.70")
            controller.pause().resume()
            assertFalse(status().text.contains("10.215.87.26"))
            assertEquals(activity.getString(R.string.ip_finder_idle),status().text.toString())
            assertEquals("10.134.214.70",activity.findViewById<TextView>(R.id.scanResult).text.toString())
            cm.clearAllNetworks();controller.pause().resume()
            assertFalse(status().text.contains("10.215.87.26"))
            assertEquals("—",activity.findViewById<TextView>(R.id.scanResult).text.toString())
        } finally {controller.pause().stop().destroy()}
    }
    @Test @Config(sdk=[28,33]) fun serviceVibratesWithoutAnActivityAndHonorsDoNotDisturb() {
        shadowOf(context).grantPermissions("com.barkatunnel.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val controller=Robolectric.buildService(IpSearchService::class.java).create()
        try {
            val service=controller.get()
            val manager=service.getSystemService(NotificationManager::class.java)
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_ALL)
            val vibrator=shadowOf(service.getSystemService(Vibrator::class.java));vibrator.setHasVibrator(true)
            val alert=IpSearchService::class.java.getDeclaredMethod("vibrateFound").apply {isAccessible=true}
            alert.invoke(service)
            assertTrue(vibrator.isVibrating)
            service.getSystemService(Vibrator::class.java).cancel()
            manager.setInterruptionFilter(NotificationManager.INTERRUPTION_FILTER_NONE)
            alert.invoke(service)
            assertFalse(vibrator.isVibrating)
        } finally {controller.destroy()}
    }
}
