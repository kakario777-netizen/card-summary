package com.kakario.cardsummary;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 카드 결제 문자 해석기.
 *
 * 현대)  [Web발신] / 현대 kt M할인형 승인 / 김*성 / 4,900원 일시불 / 11/03 11:42 / 네이버플러스멤 / 누적4,593,807원
 * 롯데)  알림 / 쿠팡(쿠페이) / 9,080원  승인 / 김*성 LG구독 5*4* / 일시불 10/27 18:16 / 누적 252,250원
 * 자동납부) [현대카드] 자동납부 승인 송*임님 KT인터넷제휴납부 27,500원
 * 이용안내) [현대카드] 서*희님 10월 버스+지하철 이용금액은 총 3,050원입니다
 */
public final class SmsParser {

    public static final class Result {
        public boolean ok;          // 금액을 읽었는가
        public boolean cancel;      // 취소 문자인가
        public long amount;         // 원 단위(양수)
        public String merchant = "";
        public long time;           // 결제 시각(ms)
        public String installment = "";
        /** "" = 일반 결제. 그 외(자동납부, 이용금액 안내, 결제 예정 등)는 사용자 확인 필요 */
        public String kind = "";
    }

    private static final Pattern AMOUNT = Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\s*원");
    private static final Pattern DATE_TIME = Pattern.compile("(\\d{1,2})/(\\d{1,2})\\s*(\\d{1,2}):(\\d{2})");
    private static final Pattern INSTALL = Pattern.compile("(일시불|\\d{1,2}\\s*개월)");
    private static final Pattern AUTO_PAY = Pattern.compile("님\\s*(.+?)\\s*([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\s*원");
    private static final String[] SKIP_AMOUNT_BEFORE = {"누적", "잔액", "한도", "잔여", "사용가능", "포인트"};
    private static final String[] NOT_MERCHANT = {"누적", "잔액", "한도", "잔여", "포인트", "web발신", "광고", "문의", "승인", "취소"};

    private SmsParser() {}

    /** 저장소에 따라 줄바꿈이 글자 그대로 "\n" 으로 들어 있는 경우가 있어 정리 */
    public static String normalize(String s) {
        if (s == null) return "";
        return s.replace("\\r\\n", "\n").replace("\\n", "\n").replace("\\r", "\n").replace("\r", "")
                .replace(' ', ' ');
    }

    /** 특수 유형 이름. 일반 결제면 "" */
    public static String kindOf(String body) {
        if (body.contains("자동납부") || body.contains("자동이체")) return "자동납부";
        if (body.contains("결제예정") || body.contains("결제 예정") || body.contains("청구")
                || body.contains("출금예정") || body.contains("결제일")) return "결제 예정 안내";
        if (body.contains("이용금액") || body.contains("이용액")) return "이용금액 안내";
        if (body.contains("해외")) return "해외 결제";
        return "";
    }

    /** 카드 결제(또는 확인이 필요한 카드 안내) 문자로 보이는지 */
    public static boolean looksLikePayment(String raw) {
        if (raw == null) return false;
        String body = normalize(raw);
        if (!AMOUNT.matcher(body).find()) return false;
        return body.contains("승인") || body.contains("취소") || body.contains("결제")
                || !kindOf(body).isEmpty();
    }

    public static Result parse(String rawBody, long smsTime) {
        Result r = new Result();
        r.time = smsTime;
        if (rawBody == null) return r;
        String body = normalize(rawBody);

        r.cancel = body.contains("취소") && !body.contains("취소불가");
        r.kind = kindOf(body);

        // 금액: 누적/잔액 등 앞에 붙은 숫자는 건너뜀
        int amountLineStart = -1;
        Matcher m = AMOUNT.matcher(body);
        while (m.find()) {
            int from = Math.max(0, m.start() - 6);
            String before = body.substring(from, m.start());
            boolean skip = false;
            for (String s : SKIP_AMOUNT_BEFORE) {
                if (before.contains(s)) { skip = true; break; }
            }
            if (skip) continue;
            try {
                r.amount = Long.parseLong(m.group(1).replace(",", ""));
                r.ok = r.amount > 0;
                amountLineStart = m.start();
            } catch (NumberFormatException ignored) {}
            break;
        }

        Matcher inst = INSTALL.matcher(body);
        if (inst.find()) r.installment = inst.group(1).replace(" ", "");

        // 날짜·시각
        Matcher dt = DATE_TIME.matcher(body);
        if (dt.find()) {
            Calendar ref = Calendar.getInstance();
            ref.setTimeInMillis(smsTime);
            int month = Integer.parseInt(dt.group(1));
            int day = Integer.parseInt(dt.group(2));
            int hour = Integer.parseInt(dt.group(3));
            int min = Integer.parseInt(dt.group(4));
            if (month >= 1 && month <= 12 && day >= 1 && day <= 31 && hour < 24 && min < 60) {
                Calendar c = Calendar.getInstance();
                c.clear();
                int year = ref.get(Calendar.YEAR);
                if (month > ref.get(Calendar.MONTH) + 1) year -= 1;   // 1월에 받은 12월 결제
                c.set(year, month - 1, day, hour, min, 0);
                r.time = c.getTimeInMillis();
            }
        }

        r.merchant = findMerchant(body, amountLineStart);
        return r;
    }

    private static String findMerchant(String body, int amountPos) {
        // 자동납부형: "...님 KT인터넷제휴납부 27,500원"
        Matcher ap = AUTO_PAY.matcher(body.replace("\n", " "));
        if (ap.find()) {
            String s = clean(ap.group(1));
            if (isMerchant(s)) return s;
        }

        List<String> lines = new ArrayList<>();
        for (String l : body.split("\n")) {
            String t = l.trim();
            if (!t.isEmpty()) lines.add(t);
        }

        // 금액이 있는 줄 번호 / 시각이 있는 줄 번호
        int amountLine = -1, timeLine = -1, pos = 0;
        {
            int idx = 0;
            for (String l : body.split("\n")) {
                String t = l.trim();
                int lineStart = pos;
                pos += l.length() + 1;
                if (t.isEmpty()) continue;
                if (amountLine < 0 && amountPos >= lineStart && amountPos < pos) amountLine = idx;
                if (timeLine < 0 && DATE_TIME.matcher(t).find()) timeLine = idx;
                idx++;
            }
        }

        if (lines.size() > 1) {
            // 1) 시각 줄 바로 다음 줄 (현대카드형)
            if (timeLine >= 0) {
                String rest = clean(afterTime(lines.get(timeLine)));
                if (isMerchant(rest)) return rest;
                if (timeLine + 1 < lines.size()) {
                    String next = clean(lines.get(timeLine + 1));
                    if (isMerchant(next)) return next;
                }
            }
            // 2) 금액 줄 바로 윗줄 (롯데카드형)
            if (amountLine > 0) {
                String prev = clean(lines.get(amountLine - 1));
                if (isMerchant(prev)) return prev;
            }
            // 3) 남은 줄 중 첫 후보
            for (String l : lines) {
                String c = clean(l);
                if (isMerchant(c)) return c;
            }
            return "";
        }

        // 한 줄짜리: 시각 뒤 텍스트
        if (timeLine >= 0) {
            String rest = clean(afterTime(body.replace("\n", " ")));
            if (isMerchant(rest)) return rest;
        }
        return "";
    }

    private static String afterTime(String s) {
        Matcher dt = DATE_TIME.matcher(s);
        return dt.find() ? s.substring(dt.end()) : "";
    }

    /** "스타벅스 누적1,234원" → "스타벅스" */
    private static String clean(String s) {
        String out = s;
        for (String k : new String[]{"누적", "잔액", "한도", "잔여", "이용금액", "이용액"}) {
            int idx = out.indexOf(k);
            if (idx >= 0) out = out.substring(0, idx);
        }
        return out.trim();
    }

    private static boolean isMerchant(String s) {
        if (s == null || s.isEmpty()) return false;
        String low = s.toLowerCase();
        if (low.equals("알림") || low.equals("일시불") || low.startsWith("[web")) return false;
        for (String k : NOT_MERCHANT) if (low.contains(k)) return false;
        if (s.contains("*")) return false;                   // 김*성, 5*4* 같은 이름·카드번호 줄
        if (AMOUNT.matcher(s).find()) return false;          // 금액 줄
        if (DATE_TIME.matcher(s).find()) return false;       // 시각 줄
        if (INSTALL.matcher(s).matches()) return false;
        if (s.startsWith("[") && s.endsWith("]")) return false;  // [현대카드]
        return true;
    }
}
