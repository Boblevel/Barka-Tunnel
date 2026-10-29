package com.barkatunnel.app.tools

import android.app.Application
import android.content.Intent
import android.view.View
import android.widget.Spinner
import android.widget.TextView
import com.barkatunnel.app.R
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [28], application = Application::class)
class VpnShareInstructionsTest {
    @Test fun sharingShowsInstructionsAndNeverUsesInstructionsAsAnAddress() {
        val controller = Robolectric.buildActivity(VpnToolsActivity::class.java,
            Intent(RuntimeEnvironment.getApplication(), VpnToolsActivity::class.java).putExtra("share", true)).setup()
        try {
            val activity = controller.get()
            val help = activity.findViewById<TextView>(R.id.toolHelp)
            assertEquals(View.VISIBLE, help.visibility)
            assertEquals(activity.getString(R.string.share_start_instructions), help.text.toString())
            assertTrue(help.text.contains("\n"))
            val addresses = activity.findViewById<Spinner>(R.id.toolAddresses)
            for (i in 0 until addresses.count) {
                assertTrue(addresses.getItemAtPosition(i).toString().matches(Regex("[0-9.]+")))
            }
            assertEquals(if (addresses.count == 0) View.GONE else View.VISIBLE, addresses.visibility)
            assertEquals(addresses.visibility, activity.findViewById<View>(R.id.shareAddressLabel).visibility)
            activity.findViewById<View>(R.id.toolAction).performClick()
            assertEquals(activity.getString(R.string.tools_vpn_required),
                activity.findViewById<TextView>(R.id.toolStatus).text.toString())
        } finally { controller.pause().stop().destroy() }
    }

    @Test fun speedTestKeepsItsOriginalHelpAndHidesSharingControls() {
        val controller = Robolectric.buildActivity(VpnToolsActivity::class.java).setup()
        try {
            val activity = controller.get()
            assertEquals(activity.getString(R.string.vpn_speed_help), activity.findViewById<TextView>(R.id.toolHelp).text.toString())
            assertEquals(View.GONE, activity.findViewById<View>(R.id.toolAddresses).visibility)
            assertEquals(View.GONE, activity.findViewById<View>(R.id.shareAddressLabel).visibility)
        } finally { controller.pause().stop().destroy() }
    }
}
