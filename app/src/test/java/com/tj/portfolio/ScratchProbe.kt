package com.tj.portfolio

import com.tj.portfolio.net.ClaudeBridge
import org.junit.Test

class ScratchProbe {
    @Test fun probe() {
        val rows = """{"type":"BUY","symbol":"NVDA","quantity":5,"price":120.50,"amount":-602.50,"fees":0,"date":"2026-06-11"}"""
        val wrapped = """{"portfolioAppResponse":true,"transactions":[$rows]}"""
        println("WRAPPED -> " + ClaudeBridge.parse(wrapped).let { "txns=${it.transactions.size} err=${it.error}" })
        val notesOnly = """{"notes":"ok","transactions":[$rows]}"""
        println("NOTES   -> " + ClaudeBridge.parse(notesOnly).let { "txns=${it.transactions.size} err=${it.error}" })
    }
}
