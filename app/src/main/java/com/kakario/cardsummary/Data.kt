package com.kakario.cardsummary

import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.Environment
import android.provider.MediaStore
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.util.Calendar
import java.util.Locale

/** 결제 한 건 */
data class Txn(
    val id: Long,
    val card: String,           // 카드 id (hyundai / woori / lotte)
    val time: Long,
    val merchant: String,
    val amount: Long,           // 양수
    val cancel: Boolean,
    val installment: String,
    val include: Boolean,
    val raw: String,
    val parsed: Boolean,        // 금액을 문자에서 읽었는가
    val kind: String = "",      // "" 일반 결제 / 자동납부 / 이용금액 안내 …
    val decision: Int = 0,      // 0 미정(확인 필요), 1 결제로, -1 무시 — 일반 결제는 항상 1
) {
    /** 목록·합계에 보이는 건 */
    val shown: Boolean get() = kind.isEmpty() || decision == 1
    val needsCheck: Boolean get() = kind.isNotEmpty() && decision == 0
    /** 확인 기억용 열쇠 (유형 + 가맹점) */
    val ruleKey: String get() = "$card|$kind|$merchant"
    val kindKey: String get() = "$card|$kind|*"

    val signed: Long get() = if (cancel) -amount else amount
}

/** 카드 설정 */
data class CardConf(
    val id: String,
    val name: String,
    val keyword: String,
    val numbers: List<String>,
    val enabled: Boolean,
) {
    val short: String get() = name.removeSuffix("카드").ifBlank { name }
}

/**
 * 기타 비용 (정산할 때 같이 넣는 돈)
 * startYm/endYm = 202611 같은 연월. endYm = 0 이면 매월 계속.
 */
data class Extra(
    val id: Long,
    val name: String,
    val amount: Long,
    val startYm: Int,
    val endYm: Int,
)

val DEFAULT_CARDS = listOf(
    CardConf("hyundai", "현대카드", "현대", listOf("15776200"), true),
    CardConf("woori", "우리카드", "우리", listOf("15889955"), true),
    CardConf("lotte", "롯데카드", "롯데", listOf("15888100"), true),
)

/** 배경색 프리셋 */
data class BgPreset(val name: String, val color: Long)

val BG_PRESETS = listOf(
    BgPreset("흰색", 0xFFFFFFFF),
    BgPreset("연회색", 0xFFF2F3F5),
    BgPreset("베이지", 0xFFFAF5EC),
    BgPreset("하늘", 0xFFEEF5FC),
    BgPreset("민트", 0xFFEDF7F2),
    BgPreset("분홍", 0xFFFCF0F3),
    BgPreset("어두운", 0xFF1E1F22),
    BgPreset("검정", 0xFF000000),
)

object Store {
    private const val P = "settings"

    private fun prefs(ctx: Context) = ctx.getSharedPreferences(P, Context.MODE_PRIVATE)

    /** 저장된 순서대로 카드 3종 전부 (꺼진 카드 포함) */
    fun loadCards(ctx: Context): List<CardConf> {
        val s = prefs(ctx).getString("cards", null) ?: return migrateOld(ctx)
        return try {
            val arr = JSONArray(s)
            val list = ArrayList<CardConf>()
            for (i in 0 until arr.length()) {
                val o = arr.getJSONObject(i)
                val nums = o.optString("numbers").split(",").map { digits(it) }.filter { it.isNotEmpty() }
                list.add(CardConf(o.getString("id"), o.getString("name"), o.optString("keyword"), nums, o.optBoolean("enabled", true)))
            }
            // 새로 생긴 기본 카드가 빠져 있으면 뒤에 붙임
            for (d in DEFAULT_CARDS) if (list.none { it.id == d.id }) list.add(d)
            // 첫 버전 기본값(현대 1577-6000)은 실제 문자 번호 1577-6200으로 보정
            list.map { if (it.id == "hyundai" && "15776200" !in it.numbers) it.copy(numbers = it.numbers + "15776200") else it }
        } catch (_: Exception) { DEFAULT_CARDS }
    }

    /** 첫 버전 설정(name0/num0…)을 이어받음 */
    private fun migrateOld(ctx: Context): List<CardConf> {
        val p = prefs(ctx)
        if (!p.contains("name0")) return DEFAULT_CARDS
        fun nums(k: String, def: List<String>) =
            p.getString(k, null)?.split(",")?.map { digits(it) }?.filter { it.isNotEmpty() } ?: def
        return listOf(
            DEFAULT_CARDS[0].copy(numbers = nums("num0", DEFAULT_CARDS[0].numbers)),
            DEFAULT_CARDS[1].copy(numbers = nums("num1", DEFAULT_CARDS[1].numbers)),
            DEFAULT_CARDS[2],
        )
    }

    fun saveCards(ctx: Context, cards: List<CardConf>) {
        val arr = JSONArray()
        cards.forEach { c ->
            arr.put(JSONObject().apply {
                put("id", c.id); put("name", c.name.trim()); put("keyword", c.keyword.trim())
                put("numbers", c.numbers.joinToString(",")); put("enabled", c.enabled)
            })
        }
        prefs(ctx).edit().putString("cards", arr.toString()).apply()
    }

    fun loadExtras(ctx: Context): List<Extra> {
        val s = prefs(ctx).getString("extras", null) ?: return emptyList()
        return try {
            val arr = JSONArray(s)
            (0 until arr.length()).map { i ->
                val o = arr.getJSONObject(i)
                Extra(o.getLong("id"), o.getString("name"), o.getLong("amount"), o.getInt("start"), o.optInt("end", 0))
            }
        } catch (_: Exception) { emptyList() }
    }

    fun saveExtras(ctx: Context, list: List<Extra>) {
        val arr = JSONArray()
        list.forEach { e ->
            arr.put(JSONObject().apply {
                put("id", e.id); put("name", e.name); put("amount", e.amount)
                put("start", e.startYm); put("end", e.endYm)
            })
        }
        prefs(ctx).edit().putString("extras", arr.toString()).apply()
    }

    /** 확인 필요 건에 대한 선택 기억: 열쇠 → 1 결제로 / -1 무시 */
    fun loadRules(ctx: Context): Map<String, Int> {
        val s = prefs(ctx).getString("rules", null) ?: return emptyMap()
        return try {
            val o = JSONObject(s); o.keys().asSequence().associateWith { o.getInt(it) }
        } catch (_: Exception) { emptyMap() }
    }

    fun saveRules(ctx: Context, m: Map<String, Int>) {
        val o = JSONObject(); m.forEach { (k, v) -> o.put(k, v) }
        prefs(ctx).edit().putString("rules", o.toString()).apply()
    }

    fun bgColor(ctx: Context): Long = prefs(ctx).getLong("bg", 0xFFFFFFFF)
    fun saveBg(ctx: Context, c: Long) = prefs(ctx).edit().putLong("bg", c).apply()

    fun settleMode(ctx: Context): Int = prefs(ctx).getInt("settleMode", 0)
    fun saveSettleMode(ctx: Context, m: Int) = prefs(ctx).edit().putInt("settleMode", m).apply()
}

fun digits(s: String) = s.filter { it.isDigit() }

fun won(v: Long): String = String.format(Locale.KOREA, "%,d원", v)

private val dayFmt = SimpleDateFormat("yyyy.MM.dd", Locale.KOREA)
private val rowFmt = SimpleDateFormat("MM/dd HH:mm", Locale.KOREA)
private val fileFmt = SimpleDateFormat("yyyyMMdd", Locale.KOREA)

fun dayText(t: Long): String = dayFmt.format(t)
fun rowTime(t: Long): String = rowFmt.format(t)

/* ---------- 연월 ---------- */

fun ymOf(t: Long): Int {
    val c = Calendar.getInstance(); c.timeInMillis = t
    return c.get(Calendar.YEAR) * 100 + c.get(Calendar.MONTH) + 1
}

fun ymAdd(ym: Int, n: Int): Int {
    val total = (ym / 100) * 12 + (ym % 100 - 1) + n
    return (total / 12) * 100 + total % 12 + 1
}

fun ymText(ym: Int) = String.format(Locale.KOREA, "%d.%02d", ym / 100, ym % 100)

/** 기간에 걸친 연월 목록 */
fun monthsIn(from: Long, to: Long): List<Int> {
    val out = ArrayList<Int>()
    var ym = ymOf(from)
    val end = ymOf(to)
    while (ym <= end && out.size < 240) { out.add(ym); ym = ymAdd(ym, 1) }
    return out
}

fun Extra.periodText(): String = when {
    endYm == 0 -> "매월 · ${ymText(startYm)}부터"
    endYm == startYm -> "한 번 · ${ymText(startYm)}"
    else -> "${ymText(startYm)} ~ ${ymText(endYm)} 매월"
}

/** 정산 기간 안에 이 비용이 몇 번 들어가는가 */
fun Extra.countIn(from: Long, to: Long): Int =
    monthsIn(from, to).count { it >= startYm && (endYm == 0 || it <= endYm) }

fun Extra.totalIn(from: Long, to: Long): Long = amount * countIn(from, to)

/* ---------- 정산 ---------- */

object Settle {
    const val BY_DATE = 0
    const val BY_CARD = 1

    private const val LINE = "────────────────"
    private val dayHead = SimpleDateFormat("M월 d일 (E)", Locale.KOREA)
    private val dayKey = SimpleDateFormat("yyyyMMdd", Locale.KOREA)

    private fun label(t: Txn): String {
        val name = t.merchant.ifBlank { t.kind.ifBlank { "결제" } }
        val extra = buildString {
            if (t.cancel) append(" (취소)")
            if (t.installment.isNotBlank() && t.installment != "일시불") append(" (${t.installment})")
        }
        return name + extra
    }

    /** 날짜별로 묶어서 적기 */
    private fun byDay(sb: StringBuilder, list: List<Txn>, tagOf: (Txn) -> String) {
        var last = ""
        for (t in list) {
            val k = dayKey.format(t.time)
            if (k != last) {
                if (last.isNotEmpty()) sb.append('\n')
                sb.append(dayHead.format(t.time)).append('\n')
                last = k
            }
            val tag = tagOf(t)
            sb.append("  ").append(label(t))
            if (tag.isNotEmpty()) sb.append(" · ").append(tag)
            sb.append("   ").append(won(t.signed)).append('\n')
        }
    }

    private fun extraLines(sb: StringBuilder, extras: List<Extra>, from: Long, to: Long): Long {
        var sum = 0L
        for (e in extras) {
            val n = e.countIn(from, to)
            if (n == 0) continue
            val tot = e.amount * n
            sum += tot
            if (n == 1) sb.append("  ${e.name}   ${won(tot)}\n")
            else sb.append("  ${e.name}   ${won(e.amount)} × ${n}개월 = ${won(tot)}\n")
        }
        return sum
    }

    fun visible(txns: List<Txn>) = txns.filter { it.include && it.shown }.sortedBy { it.time }

    fun build(
        mode: Int, cards: List<CardConf>, txns: List<Txn>, extras: List<Extra>,
        withExtras: Boolean, from: Long, to: Long,
    ): String {
        val sb = StringBuilder()
        val list = visible(txns)
        val activeExtras = if (withExtras) extras.filter { it.countIn(from, to) > 0 } else emptyList()
        sb.append("결제 정산  ${dayText(from)} ~ ${dayText(to)}\n\n")

        if (mode == BY_DATE) {
            val nameOf = cards.associate { it.id to it.short }
            if (list.isEmpty()) sb.append("카드 결제 없음\n")
            byDay(sb, list) { nameOf[it.card] ?: "" }
            sb.append('\n').append(LINE).append('\n')
            val cardSum = list.sumOf { it.signed }
            sb.append("카드 결제 ${list.size}건   ${won(cardSum)}\n")
            var extraSum = 0L
            if (activeExtras.isNotEmpty()) {
                sb.append("기타 비용\n")
                extraSum = extraLines(sb, activeExtras, from, to)
            }
            sb.append(LINE).append('\n')
            sb.append("총금액   ${won(cardSum + extraSum)}\n")
        } else {
            val sums = ArrayList<Pair<String, Long>>()
            for (c in cards) {
                val mine = list.filter { it.card == c.id }
                val s = mine.sumOf { it.signed }
                sb.append("■ ${c.name}  ${mine.size}건\n\n")
                if (mine.isEmpty()) sb.append("  결제 없음\n")
                byDay(sb, mine) { "" }
                sb.append("\n${c.name} 합계   ${won(s)}\n\n")
                sums.add(c.name to s)
            }
            if (activeExtras.isNotEmpty()) {
                sb.append("■ 기타 비용\n\n")
                val s = extraLines(sb, activeExtras, from, to)
                sb.append("\n기타 비용 합계   ${won(s)}\n\n")
                sums.add("기타 비용" to s)
            }
            sb.append(LINE).append('\n')
            for ((n, v) in sums) sb.append("$n   ${won(v)}\n")
            sb.append(LINE).append('\n')
            sb.append("총금액   ${won(sums.sumOf { it.second })}\n")
        }
        return sb.toString()
    }

    fun total(txns: List<Txn>, extras: List<Extra>, withExtras: Boolean, from: Long, to: Long): Long =
        visible(txns).sumOf { it.signed } +
                (if (withExtras) extras.sumOf { it.totalIn(from, to) } else 0L)

    fun fileName(from: Long, to: Long) = "정산_${fileFmt.format(from)}-${fileFmt.format(to)}.txt"
}

object Exporter {
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
