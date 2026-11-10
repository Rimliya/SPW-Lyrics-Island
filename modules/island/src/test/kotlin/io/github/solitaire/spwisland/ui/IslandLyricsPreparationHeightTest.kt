package io.github.solitaire.spwisland.ui

import io.github.solitaire.spwisland.core.*
import io.github.solitaire.spwisland.longtone.LongToneAnalysis
import java.util.concurrent.atomic.AtomicReference
import kotlin.test.*

class IslandLyricsPreparationHeightTest {
    @Test fun changingTrackOrFontDropsHistoricalPreparedHeight() {
        val failure = AtomicReference<Throwable?>()
        IslandLyricsPreparation(IslandLyricsLayout.Cache(), LongToneAnalysis(), { failure.set(it) }).use { preparation ->
            val lines = (0..5).map { LyricLine(0, 10_000, "lyric $it", null, emptyList()) }
            val first = PlaybackSnapshot(Track("A", "artist", "A.flac"), lines.first(), 0, false,
                PlaybackStatus.READY, lyrics = lines)
            val large = IslandSettings(fontSize = 72, experimentalMultiLine = true)
            preparation.update(first, large)
            await(preparation, failure)
            val tall = preparation.preparedHeight
            val single = first.copy(track = Track("B", "artist", "B.flac"), lyrics = lines.take(1))
            preparation.update(single, IslandSettings(fontSize = 22))
            await(preparation, failure)
            val short = preparation.preparedHeight
            assertTrue(short > 0 && short < tall, "$tall did not shrink to $short")
            preparation.update(single, IslandSettings(fontSize = 72))
            await(preparation, failure)
            val bigFont = preparation.preparedHeight
            preparation.update(single, IslandSettings(fontSize = 22))
            await(preparation, failure)
            assertTrue(preparation.preparedHeight < bigFont)
        }
    }

    private fun await(preparation: IslandLyricsPreparation, failure: AtomicReference<Throwable?>) {
        val deadline = System.nanoTime() + 5_000_000_000L
        while (preparation.preparedHeight == 0 && failure.get() == null && System.nanoTime() < deadline) Thread.sleep(5)
        preparation.awaitIdle()
        failure.get()?.let { throw it }
        assertTrue(preparation.preparedHeight > 0, "Preparation did not publish a height")
    }
}
