package io.github.solitaire.spwlyrics.domain

import kotlin.math.abs

data class CandidateScore(
    val candidate: LyricsCandidate,
    val score: Double,
    val titleScore: Double,
    val artistScore: Double?,
    val albumScore: Double?,
    val durationScore: Double?,
    val versionConflict: Boolean,
) {
    val passesAutomaticGate: Boolean
        get() {
            if (versionConflict) return false
            if (durationScore != null && durationScore < MatchEngine.MIN_DURATION) return false
            val titleAndArtist = titleScore >= MatchEngine.MIN_TITLE &&
                artistScore != null && artistScore >= MatchEngine.MIN_ARTIST &&
                score >= MatchEngine.MIN_TOTAL
            val titleAndAlbum = titleScore >= MatchEngine.STRONG_TITLE &&
                albumScore != null && albumScore >= MatchEngine.MIN_ALBUM &&
                score >= MatchEngine.MIN_TITLE_ALBUM_TOTAL
            val corroboratedFields = titleScore >= MatchEngine.RELAXED_TITLE &&
                artistScore != null && artistScore >= MatchEngine.STRONG_ARTIST &&
                albumScore != null && albumScore >= MatchEngine.STRONG_ALBUM &&
                score >= MatchEngine.MIN_TOTAL
            return titleAndArtist || titleAndAlbum || corroboratedFields
        }
}
data class MatchDecision(
    val winner: CandidateScore?,
    val ranked: List<CandidateScore>,
    val ambiguous: Boolean,
)

/** Prepared once per resolve so repeated candidate scoring does not re-normalize the query. */
class PreparedQuery internal constructor(
    val query: TrackQuery,
    internal val title: TextNormalizer.PreparedText,
    internal val album: TextNormalizer.PreparedText?,
    internal val artists: List<TextNormalizer.PreparedText>,
    internal val versionTokens: Set<String>,
)

object MatchEngine {
    const val RELAXED_TITLE = 0.78
    const val MIN_TITLE = 0.84
    const val STRONG_TITLE = 0.90
    const val MIN_ARTIST = 0.72
    const val STRONG_ARTIST = 0.88
    const val MIN_ALBUM = 0.78
    const val STRONG_ALBUM = 0.82
    const val MIN_TOTAL = 0.80
    const val MIN_TITLE_ALBUM_TOTAL = 0.82
    const val MIN_DURATION = 0.60
    const val MIN_GAP = 0.04

    fun prepare(query: TrackQuery): PreparedQuery = PreparedQuery(
        query = query,
        title = TextNormalizer.prepare(query.title),
        album = query.album.takeIf(String::isNotBlank)?.let(TextNormalizer::prepare),
        artists = (query.artists + query.albumArtists)
            .distinctBy(TextNormalizer::compact)
            .map(TextNormalizer::prepare),
        versionTokens = TextNormalizer.versionTokens(query.title),
    )

    fun score(query: TrackQuery, candidate: LyricsCandidate): CandidateScore = score(prepare(query), candidate)

    fun score(query: PreparedQuery, candidate: LyricsCandidate): CandidateScore {
        val title = TextNormalizer.similarity(query.title, TextNormalizer.prepare(candidate.title))
        val artist = artistSimilarity(query, candidate)
        val album = query.album
            ?.takeIf { candidate.album.isNotBlank() }
            ?.let { TextNormalizer.similarity(it, TextNormalizer.prepare(candidate.album)) }
        val duration = durationSimilarity(query.query.durationMs, candidate.durationMs)

        val components = buildList {
            add(title to 0.55)
            artist?.let { add(it to 0.30) }
            album?.let { add(it to 0.10) }
            duration?.let { add(it to 0.20) }
        }
        val totalWeight = components.sumOf { it.second }
        val raw = components.sumOf { (value, weight) -> value * weight } / totalWeight

        val remoteVersions = TextNormalizer.versionTokens(candidate.title)
        val conflict = query.versionTokens != remoteVersions &&
            (query.versionTokens.isNotEmpty() || remoteVersions.isNotEmpty())

        return CandidateScore(candidate, if (conflict) raw - 0.25 else raw, title, artist, album, duration, conflict)
    }

    fun decide(
        query: TrackQuery,
        candidates: List<LyricsCandidate>,
        accepts: (CandidateScore) -> Boolean = CandidateScore::passesAutomaticGate,
        scoreCache: MutableMap<String, CandidateScore>? = null,
    ): MatchDecision = decide(prepare(query), candidates, accepts, scoreCache)

    fun decide(
        query: PreparedQuery,
        candidates: List<LyricsCandidate>,
        accepts: (CandidateScore) -> Boolean = CandidateScore::passesAutomaticGate,
        scoreCache: MutableMap<String, CandidateScore>? = null,
    ): MatchDecision {
        val track = query.query
        if (track.title.isBlank() || (track.artists.isEmpty() && track.album.isBlank())) {
            return MatchDecision(null, emptyList(), false)
        }
        val ranked = candidates.map { candidate ->
            val key = scoreKey(candidate)
            scoreCache?.get(key) ?: score(query, candidate).also { scoreCache?.put(key, it) }
        }.sortedWith(
            compareByDescending<CandidateScore> { it.score }
                .thenByDescending { it.candidate.qualityHint?.rank ?: -1 }
                .thenBy { it.candidate.remoteId },
        )
        val best = ranked.firstOrNull() ?: return MatchDecision(null, ranked, false)
        if (!accepts(best)) return MatchDecision(null, ranked, false)
        val second = ranked.drop(1).firstOrNull {
            accepts(it) && !sameMetadata(best.candidate, it.candidate)
        }
        val ambiguous = second != null && best.score - second.score < MIN_GAP
        return MatchDecision(if (ambiguous) null else best, ranked, ambiguous)
    }

    fun scoreKey(candidate: LyricsCandidate): String = "${candidate.source.name}\u0000${candidate.remoteId}"

    private fun artistSimilarity(query: PreparedQuery, candidate: LyricsCandidate): Double? {
        if (query.artists.isEmpty() || candidate.artists.isEmpty()) return null
        val remoteArtists = candidate.artists.map(TextNormalizer::prepare)

        val localCoverage = query.artists.map { local ->
            remoteArtists.maxOf { remote -> TextNormalizer.similarity(local, remote) }
        }.average()
        val remoteCoverage = remoteArtists.map { remote ->
            query.artists.maxOf { local -> TextNormalizer.similarity(local, remote) }
        }.average()
        val strongestPair = query.artists.maxOf { local ->
            remoteArtists.maxOf { remote -> TextNormalizer.similarity(local, remote) }
        }
        val balancedCoverage = minOf(localCoverage, remoteCoverage) * 0.35 +
            maxOf(localCoverage, remoteCoverage) * 0.65
        return maxOf(strongestPair * 0.85, balancedCoverage).coerceIn(0.0, 1.0)
    }

    private fun sameMetadata(left: LyricsCandidate, right: LyricsCandidate): Boolean {
        val sameTitleAndArtists = TextNormalizer.compact(left.title) == TextNormalizer.compact(right.title) &&
            left.artists.map(TextNormalizer::compact).toSet() == right.artists.map(TextNormalizer::compact).toSet()
        val sameAlbum = TextNormalizer.compact(left.album) == TextNormalizer.compact(right.album)
        val sameDuration = left.durationMs != null && right.durationMs != null &&
            abs(left.durationMs - right.durationMs) <= SAME_RECORDING_DURATION_TOLERANCE_MS
        return sameTitleAndArtists && (sameAlbum || sameDuration)
    }

    private fun durationSimilarity(local: Long?, remote: Long?): Double? {
        if (local == null || remote == null || local <= 0 || remote <= 0) return null
        return when (abs(local - remote)) {
            in 0..1_500 -> 1.0
            in 1_501..3_000 -> 0.85
            in 3_001..5_000 -> 0.60
            in 5_001..8_000 -> 0.30
            else -> 0.0
        }
    }

    private const val SAME_RECORDING_DURATION_TOLERANCE_MS = 3_000
}