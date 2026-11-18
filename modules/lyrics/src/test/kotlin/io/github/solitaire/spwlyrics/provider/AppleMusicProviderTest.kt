package io.github.solitaire.spwlyrics.provider

import io.github.solitaire.spwlyrics.codec.TtmlCodec
import io.github.solitaire.spwlyrics.domain.LyricsQuality
import io.github.solitaire.spwlyrics.domain.LyricsSource
import io.github.solitaire.spwlyrics.domain.TrackQuery
import java.io.IOException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

class AppleMusicProviderTest {
    private val query = TrackQuery("Example", listOf("Artist"), "")
    private val ttml = """
        <tt xmlns="http://www.w3.org/ns/ttml">
          <body><div><p begin="0s" end="2s">Example lyric</p></div></body>
        </tt>
    """.trimIndent()

    @Test fun catalogUsesIsrcAndPreservesCreditInTtml() {
        val provider = AppleMusicProvider(http { url ->
            when {
                url.startsWith("https://lrc.red/search.json?") ->
                    """{"hits":[{"isrc":"USABC2600001","title":"Example","artist":"Artist","duration":120}]}"""
                url == "https://lrc.red/s/USABC2600001.ttml" -> ttml
                else -> error("Unexpected request: $url")
            }
        })

        val candidate = provider.search(query, "Example Artist").single()
        assertEquals("USABC2600001", candidate.externalIds["isrc"])
        val document = assertNotNull(provider.fetch(candidate))
        assertEquals("Example lyric", document.lines.first().text)
        assertEquals(LyricsQuality.LINE_SYNCED, document.quality)
        val reparsed = TtmlCodec().parse(assertNotNull(document.rawTtml), LyricsSource.APPLE)
        assertTrue(reparsed.lines.last().sourceCredit)
        assertEquals("lrc.red · https://lrc.red/s/USABC2600001", reparsed.lines.last().text)
    }

    @Test fun legacySelectionCanFetchExactAppleIdFromCache() {
        val provider = AppleMusicProvider(http { url ->
            when {
                url.startsWith("https://lrc.red/search.json?") -> """{"hits":[]}"""
                url.startsWith("https://itunes.apple.com/search?") ->
                    """{"results":[{"trackId":123,"trackName":"Example","artistName":"Artist"}]}"""
                url == "https://lyrics.paxsenix.org/apple-music/cache/123" -> ttml
                else -> error("Unexpected request: $url")
            }
        })

        val candidate = provider.search(query, "Example Artist").single()
        assertEquals("123", candidate.remoteId)
        assertEquals("Example lyric", provider.fetch(candidate)?.lines?.single()?.text)
    }

    @Test fun failedLyricCatalogWithEmptyFallbackDoesNotLookLikeACompleteSearch() {
        val provider = AppleMusicProvider(http { url ->
            if (url.startsWith("https://lrc.red/")) throw IOException("temporary failure")
            """{"results":[]}"""
        })
        assertFailsWith<IOException> { provider.search(query, "Example Artist") }
    }

    private fun http(get: (String) -> String) = object : ProviderHttp {
        override fun get(url: String, headers: Map<String, String>) = get(url)
        override fun postJson(url: String, body: String, headers: Map<String, String>): String =
            error("Unexpected POST")
        override fun postForm(url: String, values: Map<String, String>, headers: Map<String, String>): String =
            error("Unexpected POST")
    }
}
