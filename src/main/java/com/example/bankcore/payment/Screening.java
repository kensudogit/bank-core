package com.example.bankcore.payment;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * 名前の照合（制裁リスト・名寄せ）。英字以外を除いて単語に分け、法人格や敬称を外したうえで、
 * 語順の違いを吸収するため「語の集合の一致率」と「並べ替えた文字列の編集距離による類似度」の大きい方を使う。
 */
public final class Screening {

    /** これ以上の類似度なら該当の可能性ありとして保留する */
    public static final double MATCH_THRESHOLD = 0.85;

    private static final Set<String> NOISE = Set.of("LLC", "LTD", "INC", "CO", "CORP", "MR", "MRS", "MS");

    private Screening() {
    }

    /** 名前の類似度（0〜1）。 */
    public static double similarity(String a, String b) {
        List<String> ta = tokens(a);
        List<String> tb = tokens(b);
        if (ta.isEmpty() || tb.isEmpty()) {
            return 0;
        }
        Set<String> sa = new HashSet<>(ta);
        Set<String> sb = new HashSet<>(tb);
        Set<String> inter = new HashSet<>(sa);
        inter.retainAll(sb);
        double jaccard = (double) inter.size() / Math.max(sa.size(), sb.size());
        String ja = String.join(" ", ta.stream().sorted().toList());
        String jb = String.join(" ", tb.stream().sorted().toList());
        double lev = 1.0 - (double) levenshtein(ja, jb) / Math.max(ja.length(), jb.length());
        return Math.max(jaccard, lev);
    }

    static List<String> tokens(String s) {
        List<String> list = new ArrayList<>();
        if (s == null) {
            return list;
        }
        for (String t : s.toUpperCase(Locale.ROOT).replaceAll("[^A-Z0-9 ]", " ").trim().split("\\s+")) {
            if (!t.isEmpty() && !NOISE.contains(t)) {
                list.add(t);
            }
        }
        return list;
    }

    /** 編集距離（挿入・削除・置換の最小回数）。2 行分の配列だけで計算する。 */
    static int levenshtein(String a, String b) {
        int[] prev = new int[b.length() + 1];
        int[] cur = new int[b.length() + 1];
        for (int j = 0; j <= b.length(); j++) {
            prev[j] = j;
        }
        for (int i = 1; i <= a.length(); i++) {
            cur[0] = i;
            for (int j = 1; j <= b.length(); j++) {
                int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                cur[j] = Math.min(Math.min(cur[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
            }
            int[] t = prev;
            prev = cur;
            cur = t;
        }
        return prev[b.length()];
    }
}
