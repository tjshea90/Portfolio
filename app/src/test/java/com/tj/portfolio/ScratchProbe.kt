package com.tj.portfolio

import com.tj.portfolio.net.ClaudeBridge
import org.junit.Test

class ScratchProbe {
    @Test fun probe() {
        val bare = """{"transactions":[{"type":"BUY","symbol":"NVDA","quantity":5,"price":120.50,"amount":-602.50,"fees":0,"date":"2026-06-11"}]}"""
        val r1 = ClaudeBridge.parse(bare)
        println("BARE   -> txns=${r1.transactions.size} error=${r1.error}")
        val fenced = "Here you go:\n```json\n$bare\n```"
        val r2 = ClaudeBridge.parse(fenced)
        println("FENCED -> txns=${r2.transactions.size} error=${r2.error}")
    }
}
