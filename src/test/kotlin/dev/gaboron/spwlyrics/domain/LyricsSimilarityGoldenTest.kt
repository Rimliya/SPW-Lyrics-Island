package dev.gaboron.spwlyrics.domain

import kotlin.test.Test
import kotlin.test.assertEquals

class LyricsSimilarityGoldenTest {
    private fun document(vararg lines: String) = LyricsDocument(
        source = LyricsSource.LOCAL,
        format = LyricsFormat.LRC,
        lines = lines.mapIndexed { index, text -> LyricLine(index * 1000L, index * 1000L + 900, text) },
    )

    private val reference = document(
        "你便是世界上的美好", "永远闪耀", "愿你的旅途一路顺风",
        "我们终将在未来重逢", "把回忆留在星海", "继续向前走吧",
    )

    @Test
    fun preparedReferenceMatchesDirectComparison() {
        val same = reference.copy(lines = reference.lines + LyricLine(6000, 6900, "追加的一行歌词"))
        val different = document(
            "完全不同的一首歌", "没有任何相同的句子", "这里只是测试内容",
            "用来制造差异", "避免误判", "仍然不匹配",
        )
        val prepared = LyricsSimilarity.prepare(reference)!!
        for (candidate in listOf(same, different)) {
            val direct = LyricsSimilarity.compare(reference, candidate)
            val viaPrepared = LyricsSimilarity.compare(prepared, candidate)
            assertEquals(direct.coverage, viaPrepared.coverage, 1e-12)
            assertEquals(direct.comparable, viaPrepared.comparable)
        }
    }

    @Test
    fun prefilterNeverChangesAcceptance() {
        val samples = listOf(
            "你便是世界上的美好", "你便是世界上的美好，永远闪耀", "永遠に輝いて",
            "Hello world", "Hello, World!", "透明歌词与逐字高亮测试", "短",
        )
        for (left in samples) {
            for (right in samples) {
                val first = TextNormalizer.prepare(left)
                val second = TextNormalizer.prepare(right)
                val expected = TextNormalizer.similarity(first, second) >= LyricsSimilarity.LINE_MATCH
                assertEquals(expected, TextNormalizer.similarityAtLeast(first, second, LyricsSimilarity.LINE_MATCH))
            }
        }
    }
}