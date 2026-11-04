package com.kakario.cardsummary

import android.content.Context
import android.net.Uri

/** 받은 문자(SMS + 장문 MMS) 한 통 */
data class Msg(val address: String, val body: String, val time: Long)

object SmsReader {

    /** 기간 안의 받은 문자 전체 */
    fun inbox(ctx: Context, from: Long, to: Long): List<Msg> {
        val out = ArrayList<Msg>()
        readSms(ctx, from, to, out)
        try { readMms(ctx, from, to, out) } catch (_: Exception) { }
        return out
    }

    private fun readSms(ctx: Context, from: Long, to: Long, out: MutableList<Msg>) {
        val c = ctx.contentResolver.query(
            Uri.parse("content://sms/inbox"),
            arrayOf("address", "body", "date"),
            "date >= ? AND date <= ?",
            arrayOf(from.toString(), to.toString()),
            "date ASC"
        ) ?: return
        c.use {
            while (it.moveToNext()) {
                val addr = it.getString(0) ?: continue
                val body = it.getString(1) ?: continue
                out.add(Msg(addr, body, it.getLong(2)))
            }
        }
    }

    /** [Web발신] 장문은 MMS 칸에 들어가는 경우가 많아 같이 읽음 */
    private fun readMms(ctx: Context, from: Long, to: Long, out: MutableList<Msg>) {
        val cr = ctx.contentResolver
        val c = cr.query(
            Uri.parse("content://mms"),
            arrayOf("_id", "date", "msg_box"),
            "date >= ? AND date <= ? AND msg_box = 1",
            arrayOf((from / 1000).toString(), (to / 1000).toString()),
            "date ASC"
        ) ?: return
        c.use {
            while (it.moveToNext()) {
                val id = it.getLong(0)
                val time = it.getLong(1) * 1000
                val addr = mmsAddress(ctx, id) ?: continue
                val body = mmsText(ctx, id)
                if (body.isNotBlank()) out.add(Msg(addr, body, time))
            }
        }
    }

    private fun mmsAddress(ctx: Context, id: Long): String? {
        val c = ctx.contentResolver.query(
            Uri.parse("content://mms/$id/addr"),
            arrayOf("address", "type"), "type = 137", null, null
        ) ?: return null
        c.use { if (it.moveToFirst()) return it.getString(0) }
        return null
    }

    private fun mmsText(ctx: Context, id: Long): String {
        val cr = ctx.contentResolver
        val sb = StringBuilder()
        val c = cr.query(
            Uri.parse("content://mms/part"),
            arrayOf("_id", "ct", "_data", "text"), "mid = ?", arrayOf(id.toString()), null
        ) ?: return ""
        c.use {
            while (it.moveToNext()) {
                if (it.getString(1) != "text/plain") continue
                val data = it.getString(2)
                if (data != null) {
                    try {
                        cr.openInputStream(Uri.parse("content://mms/part/${it.getLong(0)}"))?.use { s ->
                            sb.append(s.readBytes().toString(Charsets.UTF_8))
                        }
                    } catch (_: Exception) { }
                } else {
                    sb.append(it.getString(3) ?: "")
                }
            }
        }
        return sb.toString()
    }

    private fun matches(addr: String, numbers: List<String>): Boolean {
        val a = digits(addr)
        if (a.isEmpty()) return false
        return numbers.any { n -> n.isNotEmpty() && (a == n || a.endsWith(n) || n.endsWith(a)) }
    }

    /** 등록된 번호 기준으로 두 카드의 결제 내역 만들기 */
    fun collect(ctx: Context, cards: List<CardConf>, from: Long, to: Long): List<Txn> {
        // 기간 마지막 날 밤 결제가 다음 날 문자로 오는 경우까지 하루 더 읽음
        val msgs = inbox(ctx, from, to + 24L * 3600 * 1000)
        val result = ArrayList<Txn>()
        val seen = HashSet<String>()
        var id = 1L
        for (m in msgs) {
            val card = cards.indexOfFirst { matches(m.address, it.numbers) }
            if (card < 0) continue
            if (!SmsParser.looksLikePayment(m.body)) continue
            val r = SmsParser.parse(m.body, m.time)
            if (r.time < from || r.time > to) continue
            val key = "$card|${r.amount}|${r.cancel}|${r.time / 60000}|${r.merchant}"
            if (!seen.add(key)) continue   // 같은 문자가 SMS·MMS로 두 번 온 경우
            result.add(
                Txn(
                    id = id++, card = card, time = r.time, merchant = r.merchant,
                    amount = r.amount, cancel = r.cancel, installment = r.installment,
                    include = r.ok, raw = m.body.trim(), parsed = r.ok
                )
            )
        }
        return result.sortedBy { it.time }
    }

    /** 최근 문자에서 키워드가 들어간 결제 문자를 보낸 번호 찾기 (번호 → 건수) */
    fun findSenders(ctx: Context, keyword: String, days: Int = 90): List<Pair<String, Int>> {
        if (keyword.isBlank()) return emptyList()
        val to = System.currentTimeMillis()
        val from = to - days * 24L * 3600 * 1000
        val count = HashMap<String, Int>()
        for (m in inbox(ctx, from, to)) {
            if (!m.body.contains(keyword)) continue
            if (!SmsParser.looksLikePayment(m.body)) continue
            val n = digits(m.address)
            if (n.isEmpty()) continue
            count[n] = (count[n] ?: 0) + 1
        }
        return count.entries.sortedByDescending { it.value }.map { it.key to it.value }
    }
}
