package com.barkatunnel.app.vpnc6

import java.util.concurrent.Callable
import java.util.concurrent.FutureTask

/**
 * Future complétable compatible avec Android 6 (API 23).
 *
 * [java.util.concurrent.CompletableFuture] n'existe qu'à partir de l'API 24.
 * FutureTask fournit les mêmes opérations bloquantes utilisées par le VPN
 * (complete/get/cancel) sans changer le protocole ni le moteur natif.
 */
class SettableFutureCompat<T> : FutureTask<T>(Callable {
    throw IllegalStateException("Ce future doit être complété explicitement.")
}) {
    fun complete(value: T): Boolean {
        if (isDone) return false
        set(value)
        return true
    }
}
