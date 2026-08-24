package com.barkatunnel.app.vpnc6

import android.content.Context
import java.io.File

object NativeCoreLocator {
    fun xray(context: Context): File = nativeFile(context, "libbarka_xray.so")
    fun dnstt(context: Context): File = nativeFile(context, "libbarka_dnstt.so")

    private fun nativeFile(context: Context, name: String): File {
        val file = File(context.applicationInfo.nativeLibraryDir, name)
        if (!file.isFile) {
            throw IllegalStateException("Moteur natif C6 absent : $name")
        }
        return file
    }
}
