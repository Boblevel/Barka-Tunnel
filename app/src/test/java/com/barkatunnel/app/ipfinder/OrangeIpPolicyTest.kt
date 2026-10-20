package com.barkatunnel.app.ipfinder

import org.junit.Assert.*
import org.junit.Test

class OrangeIpPolicyTest {
    private val expected = setOf(161,76,74,102,75,195,196,197,198,199,204,205,206,207,208,209,210,212,213,214,215,216,217,218,219,220,221,222,223,224,225,226,227,228,229,230,143,165)

    @Test fun exactlyThe38PrefixesAreAcceptedAcrossEverySecondOctet() {
        assertEquals(38, expected.size)
        assertEquals(expected.map { "10.$it" }.toSet(), OrangeIpPolicy.PATTERNS.split(';').toSet())
        for (second in 0..255) {
            for (tail in listOf("0.0", "127.179", "10.161", "10.165", "255.255")) {
                assertEquals("10.$second.$tail", second in expected, OrangeIpPolicy.matches("10.$second.$tail"))
            }
        }
    }
    @Test fun otherNetworksPartialAndMalformedAddressesNeverMatch() {
        for (ip in listOf(null,"","10.161","10.161.1","10.161.1.2.3","10.161.-1.2","10.161.256.2","10.161.1.999","10.161.01.2"," 10.161.1.2","110.161.1.2","192.168.10.161","10.155.10.165","10.10.0.1")) {
            assertFalse("Must reject $ip", OrangeIpPolicy.matches(ip))
        }
        for (first in 0..255) if(first != 10) assertFalse(OrangeIpPolicy.matches("$first.161.1.2"))
    }
}
