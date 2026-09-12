package com.tj.portfolio

import com.tj.portfolio.net.ClaudeBridge
import org.junit.Test

class ScratchProbe {
    @Test fun probe() {
        for (s in listOf(
            """{"summary":"hi"}""",
            """{"transactions":[]}""",
            """{"transactions":[{"type":"BUY"}]}""",
            """{"transactions":[{"type":"BUY","symbol":"NVDA","quantity":5,"price":120.5,"amount":-602.5,"fees":0,"date":"2026-06-11"}]}"""
        )) {
            println("PROBE ${s.take(40)} -> ${ClaudeBridge.findObject(s)?.let { "OK" } ?: "NULL"}")
        }
    }
}
