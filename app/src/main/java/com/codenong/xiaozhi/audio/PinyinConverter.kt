package com.codenong.xiaozhi.audio

import android.content.Context
import android.icu.text.Transliterator
import android.os.Build

class PinyinConverter(private val context: Context) {

    private val validTokens: Set<String> by lazy {
        runCatching {
            context.assets.open("kws/tokens.txt").bufferedReader().useLines { lines ->
                lines.mapNotNull { line ->
                    val token = line.trim().split(Regex("\\s+")).firstOrNull()
                    token?.takeIf { it.isNotEmpty() }
                }.toSet()
            }
        }.getOrDefault(emptySet())
    }

    fun toKeywordLine(phrase: String, score: Float, threshold: Float): Result<String> {
        val clean = phrase.replace(Regex("[\\s,，。！？!?\\u3000]+"), "")
        if (clean.isEmpty()) {
            return Result.failure(IllegalArgumentException("提示词不能为空"))
        }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            return Result.failure(IllegalArgumentException("当前系统版本低于 Android 10，暂不支持生成自定义拼音提示词"))
        }

        val transliterator = Transliterator.getInstance("Han-Latin")
        val tokens = mutableListOf<String>()

        for (ch in clean) {
            val rawSyl = transliterator.transliterate(ch.toString()).lowercase()
            val syllables = rawSyl.split(Regex("[^a-zāáǎàēéěèīíǐìōóǒòūúǔùǖǘǚǜü]+"))
                .filter { it.isNotBlank() }
            if (syllables.isEmpty()) {
                return Result.failure(IllegalArgumentException("无法识别「$ch」的拼音，请输入常用汉字"))
            }
            for (syl in syllables) {
                val parts = splitSyllableToPartialPinyin(syl)
                for (part in parts) {
                    val resolved = resolveValidToken(part)
                        ?: return Result.failure(
                            IllegalArgumentException("词库暂不支持「$ch」的音节（$part），请换一个常用字试试")
                        )
                    tokens.add(resolved)
                }
            }
        }

        if (tokens.isEmpty()) {
            return Result.failure(IllegalArgumentException("无法识别「$clean」的拼音"))
        }
        return Result.success("${tokens.joinToString(" ")} :$score #$threshold @$clean")
    }

    private fun resolveValidToken(token: String): String? {
        if (validTokens.isEmpty() || validTokens.contains(token)) return token
        val alt = token.replace('ü', 'u')
        if (validTokens.contains(alt)) return alt
        return null
    }

    private fun splitSyllableToPartialPinyin(syllable: String): List<String> {
        val twoCharInitials = listOf("zh", "ch", "sh")
        for (ini in twoCharInitials) {
            if (syllable.startsWith(ini) && syllable.length > ini.length) {
                return listOf(ini, syllable.substring(ini.length))
            }
        }
        val oneCharInitials = setOf(
            'b', 'p', 'm', 'f', 'd', 't', 'n', 'l',
            'g', 'k', 'h', 'j', 'q', 'x', 'r', 'z', 'c', 's', 'y', 'w'
        )
        val first = syllable.first()
        if (first in oneCharInitials && syllable.length > 1) {
            return listOf(first.toString(), syllable.substring(1))
        }
        return listOf(syllable)
    }
}
