package com.kakario.cardsummary

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import java.text.SimpleDateFormat
import java.util.Locale

/** 결제 한 건 */
data class Txn(
    val id: Long,
    val card: Int,              // 0 = 첫째 카드, 1 = 둘째 카드
    val time: Long,
    val merchant: String,
    val amount: Long,           // 양수
    val cancel: Boolean,
    val installment: String,
    val include: Boolean,
    val raw: String,
    val parsed: Boolean,        // 금액을 문자에서 읽었는가
) {
    val signed: Long get() = if (cancel) -amount else amount
}

/** 카드 설정 */
data class CardConf(val name: String, val keyword: String, val numbers: List<String>)

object Store {
    private const val P = "settings"

    fun load(ctx: Context): List<CardConf> {
        val p = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)
        return listOf(
            CardConf(
                p.getString("name0", "현대카드")!!,
                p.getString("kw0", "현대")!!,
                split(p.getString("num0", "15776000")!!)
            ),
            CardConf(
                p.getString("name1", "우리카드")!!,
                p.getString("kw1", "우리")!!,
                split(p.getString("num1", "15889955")!!)
            ),
        )
    }

    fun save(ctx: Context, cards: List<CardConf>) {
        val e = ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit()
        cards.forEachIndexed { i, c ->
            e.putString("name$i", c.name.trim())
            e.putString("kw$i", c.keyword.trim())
            e.putString("num$i", c.numbers.joinToString(","))
        }
        e.apply()
    }

    fun lastSummary(ctx: Context): String =
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).getString("last", "") ?: ""

    fun saveLastSummary(ctx: Context, s: String) {
        ctx.getSharedPreferences(P, Context.MODE_PRIVATE).edit().putString("last", s).apply()
    }

    private fun split(s: String) = s.split(",").map { digits(it) }.filter { it.isNotEmpty() }
}

fun digits(s: String) = s.filter { it.isDigit() }

fun won(v: Long): String = String.format(Locale.KOREA, "%,d원", v)

private val dayFmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
private val rowFmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
private val fileFmt = SimpleDateFormat("yyyyMMdd", Locale.KOREA)

fun dayText(t: Long): String = dayFmt.format(t)
fun rowTime(t: Long): String = rowFmt.format(t)

object Exporter {

    /** 카드 한 장의 메모장 내용 */
    fun memoText(card: CardConf, items: List<Txn>, from: Long, to: Long): String {
        val sb = StringBuilder()
        sb.append("${card.name} 결제내역\n")
        sb.append("기간: ${dayText(from)} ~ ${dayText(to)}\n")
        sb.append("--------------------------------\n")
        val list = items.filter { it.include }.sortedBy { it.time }
        if (list.isEmpty()) sb.append("(내역 없음)\n")
        for (t in list) {
            val name = t.merchant.ifBlank { "(가맹점 없음)" }
            val extra = buildString {
                if (t.cancel) append(" [취소]")
                if (t.installment.isNotBlank() && t.installment != "일시불") append(" [${t.installment}]")
            }
            sb.append("${rowTime(t.time)}  $name$extra  ${won(t.signed)}\n")
        }
        sb.append("--------------------------------\n")
        val pay = list.filter { !it.cancel }
        val cancel = list.filter { it.cancel }
        sb.append("결제 ${pay.size}건  ${won(pay.sumOf { it.amount })}\n")
        if (cancel.isNotEmpty()) sb.append("취소 ${cancel.size}건  -${won(cancel.sumOf { it.amount })}\n")
        sb.append("합계  ${won(list.sumOf { it.signed })}\n")
        return sb.toString()
    }

    fun fileName(card: CardConf, from: Long, to: Long) =
        "${card.name}_${fileFmt.format(from)}-${fileFmt.format(to)}.txt"

    /** 다운로드/카드정리 폴더에 저장 */
    fun saveToDownloads(ctx: Context, name: String, text: String): Uri? {
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, "text/plain")
            put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/카드정리")
        }
        val resolver = ctx.contentResolver
        val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values) ?: return null
        resolver.openOutputStream(uri)?.use { it.write(text.toByteArray(Charsets.UTF_8)) }
        return uri
    }
}
