package com.tj.portfolio

import com.tj.portfolio.net.ClaudeBridge
import org.junit.Test

class ScratchProbe {
    @Test fun probe() {
        val wrapped = """{"portfolioAppResponse":1,"transactions":[{"type":"BUY","symbol":"NVDA","quantity":5,"price":120.50,"amount":-602.50,"fees":0,"date":"2026-06-11"}]}"""
        println("FOUND -> " + ClaudeBridge.findObject(wrapped))
        println("ISPROMPTFILE -> " + ClaudeBridge.isPromptFile(wrapped))
        val r = ClaudeBridge.parse(wrapped)
        println("PARSE -> txns=${r.transactions.size} err=${r.error} notes=${r.notes}")
    }
}
