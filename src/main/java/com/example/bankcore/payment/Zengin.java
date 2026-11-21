package com.example.bankcore.payment;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * 全銀フォーマット（総合振込）の作成と、全銀で使える文字への変換。
 *
 * <p>レコードは ヘッダー（1）・データ（2）・トレーラー（8）・エンド（9）の各 120 文字で、改行は CRLF。
 * 実際の送信は Shift_JIS（半角カナは 1 バイト）だが、ここでは文字数で桁をそろえる。</p>
 */
public final class Zengin {

    private static final String FULL = "アイウエオカキクケコサシスセソタチツテトナニヌネノハヒフヘホマミムメモヤユヨラリルレロワヲン"
            + "ァィゥェォッャュョ";
    private static final String HALF = "ｱｲｳｴｵｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾅﾆﾇﾈﾉﾊﾋﾌﾍﾎﾏﾐﾑﾒﾓﾔﾕﾖﾗﾘﾙﾚﾛﾜｦﾝ"
            + "ｱｲｳｴｵﾂﾔﾕﾖ";
    private static final String DAKUTEN = "ガギグゲゴザジズゼゾダヂヅデドバビブベボ";
    private static final String DAKUTEN_BASE = "ｶｷｸｹｺｻｼｽｾｿﾀﾁﾂﾃﾄﾊﾋﾌﾍﾎ";
    private static final String HANDAKUTEN = "パピプペポ";
    private static final String HANDAKUTEN_BASE = "ﾊﾋﾌﾍﾎ";
    private static final Map<Character, String> MAP = new HashMap<>();

    static {
        for (int i = 0; i < FULL.length(); i++) {
            MAP.put(FULL.charAt(i), String.valueOf(HALF.charAt(i)));
        }
        for (int i = 0; i < DAKUTEN.length(); i++) {
            MAP.put(DAKUTEN.charAt(i), DAKUTEN_BASE.charAt(i) + "ﾞ");
        }
        for (int i = 0; i < HANDAKUTEN.length(); i++) {
            MAP.put(HANDAKUTEN.charAt(i), HANDAKUTEN_BASE.charAt(i) + "ﾟ");
        }
        MAP.put('ヴ', "ｳﾞ");
        MAP.put('ー', "-");
        MAP.put('　', " ");
    }

    private Zengin() {
    }

    /** 全角カナ → 半角カナ（小書きは大書き）、英字は大文字、全銀で使えない文字は除く。 */
    public static String toZenginChars(String s) {
        if (s == null) {
            return "";
        }
        StringBuilder sb = new StringBuilder();
        for (char ch : s.toCharArray()) {
            String m = MAP.get(ch);
            if (m != null) {
                sb.append(m);
            } else if (ch >= 'ｦ' && ch <= 'ﾟ') {
                sb.append(ch);
            } else if (ch >= 'a' && ch <= 'z') {
                sb.append(Character.toUpperCase(ch));
            } else if ((ch >= 'A' && ch <= 'Z') || (ch >= '0' && ch <= '9') || " ().-/,".indexOf(ch) >= 0) {
                sb.append(ch);
            }
        }
        return sb.toString();
    }

    /** 名義照合用：全銀文字に変換し、空白と記号を除く（表記ゆれで不一致にしない）。 */
    public static String normalizeName(String s) {
        return toZenginChars(s).replaceAll("[ ().\\-/,]", "");
    }

    /** 右を空白で埋めた固定長（長すぎる場合は切り詰める）。 */
    public static String text(String s, int width) {
        String v = s == null ? "" : s;
        return v.length() >= width ? v.substring(0, width) : v + " ".repeat(width - v.length());
    }

    /** 左を 0 で埋めた固定長の数字。桁あふれは金額が変わってしまうので例外にする。 */
    public static String number(long value, int width) {
        String v = Long.toString(value);
        if (v.length() > width) {
            throw new IllegalArgumentException("number too long for " + width + " digits: " + v);
        }
        return "0".repeat(width - v.length()) + v;
    }

    /** データレコードの元になる仕向送金 1 件。 */
    public record Item(long remittanceId, String bankCode, String bankNameKana, String branchCode, char accountType,
                       String accountNo, String nameKana, long amountMinor) {
    }

    /**
     * 総合振込のファイル内容。
     *
     * @param ourBankCode 依頼人（当行）の金融機関コード
     */
    public static String transferFile(List<Item> items, LocalDate tradeDate, String ourBankCode) {
        StringBuilder sb = new StringBuilder();
        String mmdd = tradeDate.format(DateTimeFormatter.ofPattern("MMdd"));
        // ヘッダー：種別コード 21（総合振込）・依頼人・取組日・仕向銀行
        sb.append('1').append("21").append('0').append(number(1, 10)).append(text("ﾊﾞﾝｸｺｱ", 40)).append(mmdd)
          .append(ourBankCode).append(text("ﾊﾞﾝｸｺｱ", 15)).append("001").append(text("ﾎﾝﾃﾝ", 15))
          .append('1').append(number(0, 7)).append(text("", 17)).append("\r\n");
        long total = 0;
        for (Item i : items) {
            total += i.amountMinor();
            sb.append('2').append(i.bankCode()).append(text(i.bankNameKana(), 15)).append(i.branchCode())
              .append(text("", 15)).append(text("", 4)).append(i.accountType()).append(i.accountNo())
              .append(text(i.nameKana(), 30)).append(number(i.amountMinor(), 10)).append('0')
              .append(number(i.remittanceId(), 10)).append(text("", 10)).append('7').append(' ')
              .append(text("", 7)).append("\r\n");
        }
        sb.append('8').append(number(items.size(), 6)).append(number(total, 12)).append(text("", 101)).append("\r\n");
        sb.append('9').append(text("", 119)).append("\r\n");
        return sb.toString();
    }
}
