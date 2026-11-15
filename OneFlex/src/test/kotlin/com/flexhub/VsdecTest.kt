package com.flexhub

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Validates the VsWasm interpreter against real captured artifacts:
 * two (wasm, encrypted-blob) pairs fetched live from the vidsrc decryptor
 * API on different 5-minute windows. Ground truth was confirmed by
 * executing the same modules in a browser-grade WASM engine.
 */
class VsdecTest {

    private fun res(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream(name)!!.use { it.readBytes() }

    private fun urlLines(wasm: String, enc: String): List<String> {
        val out = VsWasm.decrypt(res(wasm), java.util.Base64.getDecoder().decode(res(enc)))
        val text = String(out, Charsets.UTF_8)
        println("=== $wasm decrypted (${out.size} bytes) ===")
        println(text)
        println("=== end ===")
        return text.split("\n").filter { it.isNotBlank() }
    }

    @Test
    fun `decrypts window 1 stream urls`() {
        val urls = urlLines("vs1.wasm", "enc1.b64")
        assertEquals(3, urls.size)
        assertTrue(urls.all { it.startsWith("https://nectarandnectary.space/pl/") })
        assertTrue(urls.all { it.endsWith("/master.m3u8") })
    }

    @Test
    fun `decrypts window 2 stream urls`() {
        val urls = urlLines("vs2.wasm", "enc2.b64")
        assertEquals(3, urls.size)
        assertTrue(urls.all { it.startsWith("https://nectarandnectary.space/pl/") })
        assertTrue(urls.all { it.endsWith("/master.m3u8") })
    }
}
