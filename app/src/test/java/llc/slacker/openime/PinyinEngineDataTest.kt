package llc.slacker.openime

import llc.slacker.openime.core.FuzzyRule
import llc.slacker.openime.engine.BigramIndex
import llc.slacker.openime.engine.LexiconIndex
import llc.slacker.openime.engine.PinyinEngine
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.File
import java.nio.channels.FileChannel

/**
 * Quality gate for the clean-room pinyin engine against the locally compiled
 * tables. The tables are not part of the repository, so the test skips when
 * they are absent; run scripts/build_imengine_data.py first.
 */
class PinyinEngineDataTest {

    private fun engineAsset(name: String): File? = sequenceOf(
        File("src/main/assets/imengine/$name"),
        File("app/src/main/assets/imengine/$name"),
    ).firstOrNull { it.isFile }

    private fun loadEngine(): PinyinEngine {
        val lex = engineAsset("lex.bin")
        val bigram = engineAsset("bigram.bin")
        assumeTrue("imengine/lex.bin not built", lex != null)
        val lexicon = LexiconIndex(map(lex!!))
        val bigramIndex = bigram?.let { BigramIndex(map(it)) }
        return PinyinEngine(lexicon, bigramIndex)
    }

    private fun map(file: File) =
        java.io.RandomAccessFile(file, "r").use { raf ->
            raf.channel.map(FileChannel.MapMode.READ_ONLY, 0, raf.length())
                .order(java.nio.ByteOrder.LITTLE_ENDIAN)
        }

    @Test
    fun candidatesHitTheExpectedWords() {
        val engine = loadEngine()
        val cases = listOf(
            "nihao" to "你好",
            "beijing" to "北京",
            "zhongguo" to "中国",
            "shouji" to "手机",
            "diannao" to "电脑",
            "xiexie" to "谢谢",
            "nihaoma" to "你好吗",
            "jintian" to "今天",
            "zaijian" to "再见",
            "mingtian" to "明天",
            "pengyou" to "朋友",
            "gongzuo" to "工作",
        )
        var top1 = 0
        var top3 = 0
        for ((input, expected) in cases) {
            val result = engine.candidates(input)
            val hit1 = result.firstOrNull() == expected
            val hit3 = result.take(3).contains(expected)
            if (hit1) top1++
            if (hit3) top3++
            println("candidates %-12s -> %s   %s".format(input, result.take(3), if (hit1) "TOP1" else if (hit3) "top3" else "MISS"))
        }
        println("candidates: top1=$top1/${cases.size} top3=$top3/${cases.size}")
        assertTrue("candidates top1 below floor: $top1/${cases.size}", top1 * 100 >= cases.size * 80)
        assertTrue("candidates top3 below floor: $top3/${cases.size}", top3 * 100 >= cases.size * 95)
    }

    @Test
    fun fuzzyRulesExpandCandidatesAndKeepExactReadingsFirst() {
        val engine = loadEngine()
        val rules = setOf(FuzzyRule.Z_ZH, FuzzyRule.N_L, FuzzyRule.S_SH)

        // 中国 needs zh↔z: it only appears once the rule is on.
        assertTrue(engine.candidates("zongguo").none { it == "中国" })
        assertEquals("中国", engine.candidates("zongguo", fuzzyRules = rules).firstOrNull())

        // 牛奶 via n↔l (liulai spells liu-lai); the frequent fuzzy reading beats
        // the rare word that spells the letters literally.
        assertEquals("牛奶", engine.candidates("liulai", fuzzyRules = rules).firstOrNull())
        // 农村 via n↔l on the first syllable.
        assertEquals("农村", engine.candidates("longcun", fuzzyRules = rules).firstOrNull())
        // 师傅 via sh↔s, 中学 via zh↔z.
        assertEquals("师傅", engine.candidates("sifu", fuzzyRules = rules).firstOrNull())
        assertEquals("中学", engine.candidates("zongxue", fuzzyRules = rules).firstOrNull())

        // The exact spelling of an equally frequent word is not displaced.
        val plain = engine.candidates("xiexie")
        val fuzzy = engine.candidates("xiexie", fuzzyRules = rules)
        assertEquals(plain.first(), fuzzy.first())
    }

    @Test
    fun sentencesDecodeToTheExpectedWords() {
        val engine = loadEngine()
        val cases = listOf(
            "nihao" to "你好",
            "nihaoshijie" to "你好世界",
            "woaini" to "我爱你",
            "woaibeijing" to "我爱北京",
            "zhongguogongchandang" to "中国共产党",
            "jintianxingqiwu" to "今天星期五",
            "meiguanxi" to "没关系",
            "buhaoyisi" to "不好意思",
            "duibuqi" to "对不起",
            "xiexie" to "谢谢",
            "nizaijia" to "你在家",
            "haodexiexie" to "好的谢谢",
            "zhongguoshijie" to "中国世界",
            "wohenhaoxiexie" to "我很好谢谢",
        )
        var top1 = 0
        var top3 = 0
        for ((input, expected) in cases) {
            val result = engine.decode(input)
            val hit1 = result.firstOrNull() == expected
            val hit3 = result.take(3).contains(expected)
            if (hit1) top1++
            if (hit3) top3++
            println("decode     %-24s -> %s   %s".format(input, result.take(3), if (hit1) "TOP1" else if (hit3) "top3" else "MISS"))
        }
        println("decode: top1=$top1/${cases.size} top3=$top3/${cases.size}")
        assertTrue("decode top1 below floor: $top1/${cases.size}", top1 * 100 >= cases.size * 85)
        assertTrue("decode top3 below floor: $top3/${cases.size}", top3 * 100 >= cases.size * 95)
    }
}
