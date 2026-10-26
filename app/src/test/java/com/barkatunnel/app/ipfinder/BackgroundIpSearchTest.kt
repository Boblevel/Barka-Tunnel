package com.barkatunnel.app.ipfinder

import android.app.Application
import android.app.Notification
import android.app.NotificationManager
import android.content.Intent
import com.barkatunnel.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk=[29], application=Application::class)
class BackgroundIpSearchTest {
    private fun alertCase(closeActivity: Boolean) {
        val app=RuntimeEnvironment.getApplication()
        shadowOf(app).grantPermissions("com.barkatunnel.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val serviceController=Robolectric.buildService(IpSearchService::class.java).create()
        val service=serviceController.get()
        val screen=Robolectric.buildActivity(IpFinderActivity::class.java).setup()
        try {
            service.onStartCommand(Intent(app, IpSearchService::class.java),0,1)
            assertTrue(IpSearchService.active)
            if(closeActivity)screen.pause().stop().destroy()
            assertTrue("Activity destruction must not stop the search",IpSearchService.active)
            IpSearchService::class.java.getDeclaredMethod("success",String::class.java).apply {isAccessible=true}.invoke(service,"10.161.1.2")
            assertFalse(IpSearchService.active)
            val manager=app.getSystemService(NotificationManager::class.java)
            val alert=shadowOf(manager).getNotification(741)
            assertNotNull(alert)
            assertNotNull(manager.getNotificationChannel("ip_search_found").sound)
            assertEquals(NotificationManager.IMPORTANCE_HIGH,manager.getNotificationChannel("ip_search_found").importance)
            assertTrue(manager.getNotificationChannel("ip_search_found").shouldVibrate())
            assertEquals("10.161.1.2",app.getSharedPreferences(IpFinderActivity.PREFERENCES_NAME,0).getString(IpFinderActivity.KEY_LAST_FOUND_IP,null))
        } finally {
            if(!closeActivity)screen.pause().stop().destroy()
            serviceController.destroy()
        }
    }
    @Test fun alertsInsideApplication()=alertCase(false)
    @Test fun alertsAfterActivityIsDestroyed()=alertCase(true)
    @Test @Config(sdk=[23]) fun stopSupportsAndroidSix() {
        shadowOf(RuntimeEnvironment.getApplication()).grantPermissions("com.barkatunnel.app.DYNAMIC_RECEIVER_NOT_EXPORTED_PERMISSION")
        val c=Robolectric.buildService(IpSearchService::class.java).create()
        try { c.get().onStartCommand(Intent(c.get(),IpSearchService::class.java).setAction(IpSearchService.STOP),0,1);assertFalse(IpSearchService.active) }
        finally {c.destroy()}
    }
    @Test fun searchCriteriaStayIndependentOfLaterProfileChanges() {
        val c=RuntimeEnvironment.getApplication()
        c.getSharedPreferences("barka_home_preferences",0).edit().putString("selected_network_id","orange_bf").commit()
        val criteria=IpFinderActivity.captureCriteria(c)
        c.getSharedPreferences("barka_home_preferences",0).edit().putString("selected_network_id","telecel_bf").commit()
        c.getSharedPreferences(IpFinderActivity.PREFERENCES_NAME,0).edit().putString("search_pattern","=192.168.1.1").commit()
        assertTrue(criteria.matches("10.161.1.2"));assertFalse(criteria.matches("192.168.1.1"))
    }
}
