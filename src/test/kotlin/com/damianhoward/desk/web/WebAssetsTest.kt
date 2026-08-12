package com.damianhoward.desk.web

import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WebAssetsTest {
    @Test
    fun `loads the shell assets from the classpath`() {
        val assets = WebAssets.load()
        assertTrue(assets.indexHtml.contains("TRADING DESK"), "index should carry the desk brand")
        assertTrue(assets.indexHtml.contains("/orderbook/?embed=1"), "index should embed the order book tab")
        assertTrue(assets.appCss.contains("--brand: #14b8a6"), "css should carry the shared brand token")
        assertTrue(assets.appJs.contains("activate"), "js should carry the tab controller")
    }
}
