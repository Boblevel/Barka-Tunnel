package com.barkatunnel.app.vpnc6

import android.os.Build
import android.os.SystemClock
import java.io.File
import java.util.concurrent.TimeUnit

internal object ProcessCompat {
    fun startWithLog(builder: ProcessBuilder, output: File): Process {
        builder.redirectErrorStream(true)
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.redirectOutput(output).start()
        } else {
            builder.start().also { process ->
                Thread(
                    {
                        runCatching {
                            process.inputStream.buffered().use { input ->
                                output.outputStream().buffered().use { sink ->
                                    input.copyTo(sink)
                                }
                            }
                        }
                    },
                    "BarkaNativeLog"
                ).apply {
                    isDaemon = true
                    start()
                }
            }
        }
    }

    fun isAlive(process: Process?): Boolean {
        process ?: return false
        return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            process.isAlive
        } else {
            try {
                process.exitValue()
                false
            } catch (_: IllegalThreadStateException) {
                true
            }
        }
    }

    fun stop(process: Process?, timeoutMs: Long = 700L): Boolean {
        process ?: return true
        var interrupted = Thread.interrupted()

        return try {
            runCatching { process.destroy() }
            if (waitForExit(process, timeoutMs) { interrupted = true }) {
                true
            } else {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    runCatching { process.destroyForcibly() }
                } else {
                    runCatching { process.destroy() }
                }
                waitForExit(process, FORCED_STOP_TIMEOUT_MS) {
                    interrupted = true
                }
            }
        } finally {
            if (interrupted) {
                Thread.currentThread().interrupt()
            }
        }
    }

    private fun waitForExit(
        process: Process,
        timeoutMs: Long,
        onInterrupted: () -> Unit
    ): Boolean {
        val deadline = SystemClock.elapsedRealtime() + timeoutMs.coerceAtLeast(0L)
        while (isAlive(process) && SystemClock.elapsedRealtime() < deadline) {
            val remainingMs = deadline - SystemClock.elapsedRealtime()
            try {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    if (
                        process.waitFor(
                            minOf(100L, remainingMs.coerceAtLeast(1L)),
                            TimeUnit.MILLISECONDS
                        )
                    ) {
                        return true
                    }
                } else {
                    Thread.sleep(minOf(25L, remainingMs.coerceAtLeast(1L)))
                }
            } catch (_: InterruptedException) {
                onInterrupted()
            }
        }
        return !isAlive(process)
    }

    private const val FORCED_STOP_TIMEOUT_MS = 2_500L
}
