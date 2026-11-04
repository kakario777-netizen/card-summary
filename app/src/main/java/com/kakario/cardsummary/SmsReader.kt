package com.kakario.cardsummary

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.database.Cursor
import android.net.Uri
import androidx.core.content.ContextCompat

/** 받은 문자 한 통. src = SMS / MMS / RCS / 붙여넣기 */
data class Msg(val address: String, val body: String, val time: Long, val src: String)

object SmsReader {

    private const val DAY = 24L * 3600 * 1000

    /** 읽기 결과 + 소스별 상태 (진단용) */
    class ReadResult {
        val msgs = ArrayList<Msg>()
        val status = LinkedHashMap<String, String>()
    }

    /** 초/밀리초가 섞여 있어도 밀리초로 */
    private fun ms(v: Long) = if (v in 1..99_999_999_999L) v * 1000 else v

    private fun Cursor.str(name: String): String? {
        val i = getColumnIndex(name); return if (i >= 0) getString(i) else null
    }

    private fun Cursor.lng(name: String): Long {
        val i = getColumnIndex(name); return if (i >= 0) getLong(i) else 0L
    }

    fun read(ctx: Context, from: Long, to: Long): ReadResult {
        val r0 = readRaw(ctx, from, to)
        val r = ReadResult()
        r.status.putAll(r0.status)
        r0.msgs.forEach { r.msgs.add(it.copy(body = cleanBody(it.body))) }
        return r
    }

    /** 채팅+ 카드형 메시지(JSON)면 글만 뽑고, 줄바꿈 정리 */
    fun cleanBody(body: String): String {
        val rich = richText(body)
        return SmsParser.normalize(rich ?: body).trim()
    }

    /**
     * 채팅+ 카드형 메시지: {"messageHeader":"[Web발신]", ..., "layout":{"widget":"LinearLayout","children":[...]}}
     * 화면에 보이는 글("text")만 순서대로 모음. 버튼 글자(채팅상담하기 등)는 뺌.
     */
    fun richText(body: String): String? {
        val t = body.trim()
        if (!t.startsWith("{") || !t.contains("\"widget\"")) return null
        return try {
            val out = ArrayList<String>()
            fun walk(v: Any?) {
                when (v) {
                    is org.json.JSONObject -> {
                        if (v.optString("widget") == "Button") return
                        val txt = v.opt("text")
                        if (txt is String && txt.isNotBlank()) out.add(txt.trim())
                        val keys = v.keys()
                        while (keys.hasNext()) {
                            val k = keys.next()
                            val c = v.opt(k)
                            if (c is org.json.JSONObject || c is org.json.JSONArray) walk(c)
                        }
                    }
                    is org.json.JSONArray -> for (i in 0 until v.length()) walk(v.opt(i))
                }
            }
            walk(org.json.JSONObject(t))
            if (out.isEmpty()) null else out.joinToString("\n")
        } catch (_: Exception) {
            // 형식이 깨졌으면 "text":"..." 만 순서대로 뽑기
            val m = Regex("\"text\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").findAll(t)
                .map { it.groupValues[1].replace("\\n", "\n").replace("\\\"", "\"").replace("\\/", "/") }
                .filter { it.isNotBlank() }.toList()
            if (m.isEmpty()) null else m.joinToString("\n")
        }
    }

    private fun readRaw(ctx: Context, from: Long, to: Long): ReadResult {
        val r = ReadResult()
        for ((name, fn) in listOf<Pair<String, () -> Int>>(
            "SMS" to { readSms(ctx, from, to, r.msgs) },
            "MMS(장문)" to { readMms(ctx, from, to, r.msgs) },
            "RCS(채팅+)" to { readRcs(ctx, from, to, r.msgs) },
        )) {
            r.status[name] = try { "${fn()}통" } catch (e: SecurityException) {
                "권한 없음"
            } catch (e: Exception) {
                "읽기 실패 (${e.javaClass.simpleName}: ${e.message?.take(60)})"
            }
        }
        return r
    }

    fun inbox(ctx: Context, from: Long, to: Long): List<Msg> = read(ctx, from, to).msgs

    private fun readSms(ctx: Context, from: Long, to: Long, out: MutableList<Msg>): Int {
        var n = 0
        val c = ctx.contentResolver.query(
            Uri.parse("content://sms"), null,
            "type = 1 AND date >= ? AND date <= ?",
            arrayOf(from.toString(), to.toString()), "date ASC"
        ) ?: return -1
        c.use {
            while (it.moveToNext()) {
                val body = it.str("body") ?: continue
                out.add(Msg(it.str("address") ?: "", body, ms(it.lng("date")), "SMS")); n++
            }
        }
        return n
    }

    /** [Web발신] 장문은 MMS 칸에 들어감 */
    private fun readMms(ctx: Context, from: Long, to: Long, out: MutableList<Msg>): Int {
        val cr = ctx.contentResolver
        val ids = ArrayList<Pair<Long, Long>>()
        // 날짜 단위(초/밀리초)가 기기마다 달라 둘 다 넓게 걸러 받은 뒤 코드에서 정확히 거름
        val c = cr.query(
            Uri.parse("content://mms"), arrayOf("_id", "date", "msg_box"),
            "msg_box = 1 AND ((date >= ? AND date <= ?) OR (date >= ? AND date <= ?))",
            arrayOf((from / 1000).toString(), (to / 1000).toString(), from.toString(), to.toString()),
            "date ASC"
        ) ?: return -1
        c.use { while (it.moveToNext()) ids.add(it.getLong(0) to ms(it.getLong(1))) }
        var n = 0
        for ((id, time) in ids) {
            if (time < from || time > to) continue
            val body = mmsText(ctx, id)
            if (body.isBlank()) continue
            out.add(Msg(mmsAddress(ctx, id) ?: "", body, time, "MMS")); n++
        }
        return n
    }

    private fun mmsAddress(ctx: Context, id: Long): String? {
        val c = ctx.contentResolver.query(
            Uri.parse("content://mms/$id/addr"), arrayOf("address", "type"), null, null, null
        ) ?: return null
        var any: String? = null
        c.use {
            while (it.moveToNext()) {
                val a = it.getString(0) ?: continue
                if (it.getInt(1) == 137) return a
                if (any == null && a != "insert-address-token") any = a
            }
        }
        return any
    }

    private fun mmsText(ctx: Context, id: Long): String {
        val cr = ctx.contentResolver
        val sb = StringBuilder()
        val c = cr.query(Uri.parse("content://mms/part"), null, "mid = ?", arrayOf(id.toString()), null) ?: return ""
        c.use {
            while (it.moveToNext()) {
                val ct = it.str("ct") ?: continue
                if (!ct.startsWith("text/plain")) continue
                val text = it.str("text")
                if (!text.isNullOrEmpty()) { sb.append(text); continue }
                try {
                    cr.openInputStream(Uri.parse("content://mms/part/${it.lng("_id")}"))?.use { s ->
                        sb.append(s.readBytes().toString(Charsets.UTF_8))
                    }
                } catch (_: Exception) { }
            }
        }
        return sb.toString()
    }

    /** 삼성 메시지 채팅+(RCS) 저장소. 기기마다 없을 수 있음 */
    private fun readRcs(ctx: Context, from: Long, to: Long, out: MutableList<Msg>): Int {
        val c = ctx.contentResolver.query(Uri.parse("content://im/chat"), null, null, null, null) ?: return -1
        var n = 0
        c.use {
            while (it.moveToNext()) {
                val body = it.str("body") ?: continue
                val time = ms(it.lng("date"))
                if (time < from || time > to) continue
                val addr = listOf("address", "sender", "from_address", "remote_uri", "recipients")
                    .mapNotNull { k -> it.str(k) }.firstOrNull { a -> digits(a).length >= 4 } ?: ""
                out.add(Msg(addr, body, time, "RCS")); n++
            }
        }
        return n
    }

    /* ---------- 카드 판별 ---------- */

    private fun numberMatch(addr: String, numbers: List<String>): Boolean {
        val a = digits(addr)
        if (a.length < 4) return false
        return numbers.any { n -> n.length >= 4 && (a == n || a.endsWith(n) || n.endsWith(a)) }
    }

    /** 문자 내용에 카드 이름이 있는지 (현대카드 / 롯데카드 / 우리(1234) 등) */
    private fun bodyMatch(body: String, c: CardConf): Boolean {
        val head = body.take(80)
        if (c.name.isNotBlank() && head.contains(c.name)) return true
        val k = c.keyword.trim()
        if (k.isEmpty()) return false
        return head.contains("${k}카드") || Regex(Regex.escape(k) + "\\s*[(\\[]").containsMatchIn(head)
    }

    /** 이 문자가 어느 카드 결제인지. 없으면 null */
    fun cardFor(m: Msg, cards: List<CardConf>): CardConf? {
        if (!SmsParser.looksLikePayment(m.body)) return null
        val on = cards.filter { it.enabled }
        return on.firstOrNull { numberMatch(m.address, it.numbers) }
            ?: on.firstOrNull {
                bodyMatch(m.body, it) && (m.body.contains("승인") || m.body.contains("취소") || SmsParser.kindOf(m.body).isNotEmpty())
            }
    }

    fun toTxns(
        msgs: List<Msg>, cards: List<CardConf>, from: Long, to: Long, startId: Long,
        forced: String? = null, rules: Map<String, Int> = emptyMap(),
    ): List<Txn> {
        val result = ArrayList<Txn>()
        val seen = HashSet<String>()
        var id = startId
        for (m in msgs.sortedBy { it.time }) {
            val card = forced?.takeIf { SmsParser.looksLikePayment(m.body) } ?: cardFor(m, cards)?.id ?: continue
            val r = SmsParser.parse(m.body, m.time)
            if (r.time < from || r.time > to) continue
            val key = "$card|${r.amount}|${r.cancel}|${r.kind}|${r.time / 60000}"
            if (!seen.add(key)) continue   // 같은 문자가 여러 곳에 저장된 경우
            var t = Txn(
                id = id++, card = card, time = r.time, merchant = r.merchant,
                amount = r.amount, cancel = r.cancel, installment = r.installment,
                include = r.ok, raw = m.body.trim(), parsed = r.ok, kind = r.kind,
            )
            if (t.kind.isNotEmpty()) t = t.copy(decision = rules[t.ruleKey] ?: rules[t.kindKey] ?: 0)
            result.add(t)
        }
        return result
    }

    fun collect(ctx: Context, cards: List<CardConf>, from: Long, to: Long, rules: Map<String, Int>): List<Txn> {
        // 기간 마지막 날 밤 결제가 다음 날 문자로 오는 경우까지 하루 더 읽음
        val msgs = inbox(ctx, from, to + DAY)
        return toTxns(msgs, cards, from, to, 1L, rules = rules)
    }

    /** 최근 문자에서 키워드가 들어간 결제 문자를 보낸 번호 찾기 (번호 → 건수) */
    fun findSenders(ctx: Context, keyword: String, days: Int = 90): List<Pair<String, Int>> {
        if (keyword.isBlank()) return emptyList()
        val to = System.currentTimeMillis()
        val from = to - days * DAY
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

    /* ---------- 진단 ---------- */

    fun diagnose(ctx: Context, cards: List<CardConf>, days: Int = 30): String {
        val sb = StringBuilder()
        val perm = ContextCompat.checkSelfPermission(ctx, Manifest.permission.READ_SMS) == PackageManager.PERMISSION_GRANTED
        sb.append("문자 읽기 권한: ${if (perm) "허용됨" else "없음"}\n")
        if (!perm) {
            sb.append("→ 휴대폰 설정 → 애플리케이션 → 카드 정리 → 권한 → SMS → 허용\n")
            sb.append("  (회색으로 막혀 있으면 앱 정보 화면 오른쪽 위 ⋮ → '제한된 설정 허용' 후 다시)\n")
        }
        val to = System.currentTimeMillis()
        val from = to - days * DAY
        val r = read(ctx, from, to)
        sb.append("최근 ${days}일 받은 문자\n")
        try {
            ctx.contentResolver.query(Uri.parse("content://im/chat"), null, null, null, "_id DESC LIMIT 1")?.use {
                sb.append("- RCS 칸 이름: ${it.columnNames.joinToString(",").take(300)}\n")
            }
        } catch (_: Exception) { }
        r.status.forEach { (k, v) -> sb.append("- $k: $v\n") }

        val senders = r.msgs.groupingBy { digits(it.address).ifEmpty { "(번호없음)" } }.eachCount()
            .entries.sortedByDescending { it.value }.take(8)
        if (senders.isNotEmpty()) {
            sb.append("\n많이 온 번호\n")
            senders.forEach { (n, c) -> sb.append("- $n: ${c}통\n") }
        }

        val cardish = r.msgs.filter { it.body.contains("카드") || it.body.contains("승인") }
            .sortedByDescending { it.time }.take(15)
        sb.append("\n카드·승인 단어가 들어간 문자 ${cardish.size}통 (최근순)\n")
        for (m in cardish) {
            val pay = SmsParser.looksLikePayment(m.body)
            val c = cardFor(m, cards)
            val verdict = when {
                c != null -> "→ ${c.name}로 인식" + if (numberMatchAny(m.address, cards)) " (번호)" else " (문자 내용)"
                !pay -> "→ 결제 문자 아님(금액/승인 못 찾음)"
                else -> "→ 결제 문자지만 카드 못 정함"
            }
            sb.append("\n[${m.src}] ${m.address} ${rowTime(m.time)}\n")
            sb.append(m.body.replace("\n", " ").take(70)).append('\n')
            sb.append(verdict).append('\n')
        }
        return sb.toString()
    }

    private fun numberMatchAny(addr: String, cards: List<CardConf>) = cards.any { numberMatch(addr, it.numbers) }

    /* ---------- 붙여넣기 ---------- */

    /** 붙여넣은 글을 문자 단위로 나눔 */
    fun splitPasted(text: String): List<Msg> {
        val now = System.currentTimeMillis()
        val t = text.replace("\r", "")
        val parts = if (t.contains("[Web발신]")) t.split("[Web발신]")
        else t.split(Regex("\n\\s*\n"))
        val out = ArrayList<String>()
        for (p in parts.map { it.trim() }.filter { it.isNotEmpty() }) {
            val lines = p.lines().map { it.trim() }.filter { it.isNotEmpty() }
            // 한 줄짜리 결제 문자가 줄줄이 붙어 있으면 줄마다 나눔
            if (lines.size > 1 && lines.all { SmsParser.looksLikePayment(it) }) out.addAll(lines) else out.add(p)
        }
        return out.map { Msg("", cleanBody(it), now, "붙여넣기") }
    }
}
