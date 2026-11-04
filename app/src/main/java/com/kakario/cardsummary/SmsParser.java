package com.kakario.cardsummary;

import java.util.ArrayList;
import java.util.Calendar;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * 카드 결제 문자 해석기.
 * 현대카드·우리카드 승인/취소 문자를 기본으로, 비슷한 형식이면 대부분 읽는다.
 *
 * 예)
 * [Web발신]
 * 현대카드 승인
 * 홍*동
 * 12,000원 일시불
 * 11/04 12:10
 * 스타벅스
 * 누적1,234,567원
 */
public final class SmsParser {

    public static final class Result {
        public boolean ok;          // 금액을 읽었는가
        public boolean cancel;      // 취소 문자인가
        public long amount;         // 원 단위(양수)
        public String merchant = "";
        public long time;           // 결제 시각(ms)
        public String installment = "";
    }

    private static final Pattern AMOUNT = Pattern.compile("([0-9]{1,3}(?:,[0-9]{3})+|[0-9]+)\\s*원");
    private static final Pattern DATE_TIME = Pattern.compile("(\\d{1,2})/(\\d{1,2})\\s*(\\d{1,2}):(\\d{2})");
    private static final Pattern INSTALL = Pattern.compile("(일시불|\\d{1,2}\\s*개월)");
    private static final String[] SKIP_AMOUNT_BEFORE = {"누적", "잔액", "한도", "잔여", "사용가능", "포인트", "결제예정"};
    private static final String[] SKIP_LINE = {"누적", "잔액", "한도", "잔여", "포인트", "web발신", "광고", "문의"};

    private SmsParser() {}

    /** 결제(승인/취소) 문자로 보이는지 */
    public static boolean looksLikePayment(String body) {
        if (body == null) return false;
        return (body.contains("승인") || body.contains("취소") || body.contains("결제"))
                && AMOUNT.matcher(body).find();
    }

    public static Result parse(String rawBody, long smsTime) {
        Result r = new Result();
        r.time = smsTime;
        if (rawBody == null) return r;
        String body = rawBody.replace("\r", "");

        r.cancel = body.contains("취소") && !body.contains("취소불가");

        // 금액: 누적/잔액 등 앞에 붙은 숫자는 건너뜀
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
                // 1월에 받은 12월 결제 문자
                if (month > ref.get(Calendar.MONTH) + 1) year -= 1;
                c.set(year, month - 1, day, hour, min, 0);
                r.time = c.getTimeInMillis();
            }
        }

        r.merchant = findMerchant(body);
        return r;
    }

    private static String findMerchant(String body) {
        String[] lines = body.split("\n");
        List<String> clean = new ArrayList<>();
        for (String l : lines) {
            String t = l.trim();
            if (!t.isEmpty()) clean.add(t);
        }
        // 1) 시각이 있는 줄의 뒷부분 또는 다음 줄
        for (int i = 0; i < clean.size(); i++) {
            Matcher dt = DATE_TIME.matcher(clean.get(i));
            if (dt.find()) {
                String rest = clean.get(i).substring(dt.end()).trim();
                rest = stripTail(rest);
                if (isMerchant(rest)) return rest;
                for (int j = i + 1; j < clean.size(); j++) {
                    String cand = stripTail(clean.get(j));
                    if (isMerchant(cand)) return cand;
                }
                break;
            }
        }
        // 2) 한 줄짜리 문자: 시각 뒤 텍스트
        Matcher dt = DATE_TIME.matcher(body);
        if (dt.find()) {
            String rest = stripTail(body.substring(dt.end()).replace("\n", " ").trim());
            if (isMerchant(rest)) return rest;
        }
        return "";
    }

    /** "스타벅스 누적1,234원" → "스타벅스" */
    private static String stripTail(String s) {
        String out = s;
        for (String k : new String[]{"누적", "잔액", "한도", "잔여"}) {
            int idx = out.indexOf(k);
            if (idx >= 0) out = out.substring(0, idx);
        }
        return out.trim();
    }

    private static boolean isMerchant(String s) {
        if (s == null || s.isEmpty()) return false;
        String low = s.toLowerCase();
        for (String k : SKIP_LINE) if (low.contains(k)) return false;
        if (AMOUNT.matcher(s).matches()) return false;
        return true;
    }
}
