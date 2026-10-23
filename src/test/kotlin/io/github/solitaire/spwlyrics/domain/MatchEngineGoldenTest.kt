package io.github.solitaire.spwlyrics.domain

import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals

/** Locks the prepared/scored refactor to the pre-optimization decision math. */
class MatchEngineGoldenTest {
    private val queries = listOf(
        TrackQuery("夜に駆ける", listOf("YOASOBI"), "THE BOOK", durationMs = 261_000),
        TrackQuery("Da Capo", listOf("崩坏3"), "「Da Capo」——《崩坏3》印象曲", durationMs = 240_000),
        TrackQuery("後書き", listOf("DUSTCELL"), "", durationMs = 210_000),
        TrackQuery("Hello, World!", listOf("BUMP OF CHICKEN"), "Hello, World!", durationMs = 300_000),
        TrackQuery("Lemon", listOf("米津玄師"), "BOOTLEG", albumArtists = listOf("米津玄师"), durationMs = 255_000),
    )

    private val candidates = listOf(
        candidate(LyricsSource.AMLL, "a1", "夜に駆ける", listOf("YOASOBI"), "THE BOOK", 261_000, LyricsQuality.KARAOKE_SYNCED),
        candidate(LyricsSource.QQ, "q1", "夜に駆ける (Live)", listOf("YOASOBI"), "THE BOOK", 262_000),
        candidate(LyricsSource.AMLL, "a2", "Da Capo", listOf("崩坏3", "Hanser"), "Da Capo", 240_500, LyricsQuality.KARAOKE_SYNCED),
        candidate(LyricsSource.KUGOU, "k1", "Da Capo (Instrumental)", listOf("崩坏3"), "", 241_000),
        candidate(LyricsSource.AMLL, "a3", "後書き", listOf("DUSTCELL"), "後書き", 210_000, LyricsQuality.KARAOKE_SYNCED),
        candidate(LyricsSource.NETEASE, "n1", "Hello, World!", listOf("BUMP OF CHICKEN"), "Hello, World!", 300_000),
        candidate(LyricsSource.AMLL, "a4", "Lemon", listOf("米津玄師"), "BOOTLEG", 255_000, LyricsQuality.KARAOKE_SYNCED),
        candidate(LyricsSource.QQ, "q2", "Lemon (Cover)", listOf("Someone"), "Cover", 254_000),
    )

    @Test
    fun preparedScoringMatchesLegacyScoring() {
        for (query in queries) {
            for (candidate in candidates) {
                val expected = legacyScore(query, candidate)
                val actual = MatchEngine.score(query, candidate)
                assertEquals(expected.score, actual.score, TOLERANCE, "${query.title} / ${candidate.title}")
                assertEquals(expected.titleScore, actual.titleScore, TOLERANCE)
                assertNear(expected.artistScore, actual.artistScore)
                assertNear(expected.albumScore, actual.albumScore)
                assertNear(expected.durationScore, actual.durationScore)
                assertEquals(expected.versionConflict, actual.versionConflict)
            }
        }
    }

    private fun assertNear(expected: Double?, actual: Double?) {
        if (expected == null || actual == null) {
            assertEquals(expected, actual)
        } else {
            assertEquals(expected, actual, TOLERANCE)
        }
    }

    private fun candidate(
        source: LyricsSource,
        id: String,
        title: String,
        artists: List<String>,
        album: String,
        durationMs: Long?,
        quality: LyricsQuality? = null,
    ) = LyricsCandidate(source, id, title, artists, album, durationMs, quality)

    private fun legacyScore(query: TrackQuery, candidate: LyricsCandidate): CandidateScore {
        val title = TextNormalizer.similarity(query.title, candidate.title)
        val localArtists = (query.artists + query.albumArtists).distinctBy(TextNormalizer::compact)
        val artist = if (localArtists.isEmpty() || candidate.artists.isEmpty()) null else {
            val localCoverage = localArtists.map { local ->
                candidate.artists.maxOf { remote -> TextNormalizer.similarity(local, remote) }
            }.average()
            val remoteCoverage = candidate.artists.map { remote ->
                localArtists.maxOf { local -> TextNormalizer.similarity(local, remote) }
            }.average()
            val strongestPair = localArtists.maxOf { local ->
                candidate.artists.maxOf { remote -> TextNormalizer.similarity(local, remote) }
            }
            val balancedCoverage = minOf(localCoverage, remoteCoverage) * 0.35 +
                maxOf(localCoverage, remoteCoverage) * 0.65
            maxOf(strongestPair * 0.85, balancedCoverage).coerceIn(0.0, 1.0)
        }
        val album = if (query.album.isBlank() || candidate.album.isBlank()) null
        else TextNormalizer.similarity(query.album, candidate.album)
        val duration = legacyDuration(query.durationMs, candidate.durationMs)

        val components = buildList {
            add(title to 0.55)
            artist?.let { add(it to 0.30) }
            album?.let { add(it to 0.10) }
            duration?.let { add(it to 0.20) }
        }
        val totalWeight = components.sumOf { it.second }
        val raw = components.sumOf { (value, weight) -> value * weight } / totalWeight

        val localVersions = TextNormalizer.versionTokens(query.title)
        val remoteVersions = TextNormalizer.versionTokens(candidate.title)
        val conflict = localVersions != remoteVersions && (localVersions.isNotEmpty() || remoteVersions.isNotEmpty())

        return CandidateScore(candidate, if (conflict) raw - 0.25 else raw, title, artist, album, duration, conflict)
    }

    private fun legacyDuration(local: Long?, remote: Long?): Double? {
        if (local == null || remote == null || local <= 0 || remote <= 0) return null
        return when (abs(local - remote)) {
            in 0..1_500 -> 1.0
            in 1_501..3_000 -> 0.85
            in 3_001..5_000 -> 0.60
            in 5_001..8_000 -> 0.30
            else -> 0.0
        }
    }

    private companion object {
        const val TOLERANCE = 1e-12
    }
}