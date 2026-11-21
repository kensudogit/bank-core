package com.example.bankcore.payment;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全銀フォーマット：使用可能文字への変換、固定長の項目、総合振込ファイル（120 桁 × レコード）。 */
class ZenginTest {

    @Test
    void convertsFullWidthKatakanaToHalfWidthWithDakuten() {
        assertThat(Zengin.toZenginChars("ヤマダ　タロウ")).isEqualTo("ﾔﾏﾀﾞ ﾀﾛｳ");
        assertThat(Zengin.toZenginChars("パン")).isEqualTo("ﾊﾟﾝ");
        assertThat(Zengin.toZenginChars("ヴィー")).isEqualTo("ｳﾞｲ-");
        assertThat(Zengin.toZenginChars("ｶﾌﾞｼｷｶﾞｲｼｬ")).isEqualTo("ｶﾌﾞｼｷｶﾞｲｼｬ");
    }

    @Test
    void uppercasesLatinAndDropsUnsupportedCharacters() {
        assertThat(Zengin.toZenginChars("abc 123 (KK)")).isEqualTo("ABC 123 (KK)");
        assertThat(Zengin.toZenginChars("山田タロウ!")).isEqualTo("ﾀﾛｳ");
        assertThat(Zengin.toZenginChars(null)).isEmpty();
    }

    @Test
    void normalizeNameRemovesSeparatorsForComparison() {
        assertThat(Zengin.normalizeName("ヤマダ　タロウ")).isEqualTo(Zengin.normalizeName("ﾔﾏﾀﾞﾀﾛｳ"));
        assertThat(Zengin.normalizeName("(カ)サクラ")).isEqualTo("ｶｻｸﾗ");
    }

    @Test
    void fixedWidthFields() {
        assertThat(Zengin.text("ABC", 5)).isEqualTo("ABC  ");
        assertThat(Zengin.text("ABCDEF", 3)).isEqualTo("ABC");
        assertThat(Zengin.text(null, 2)).isEqualTo("  ");
        assertThat(Zengin.number(42, 5)).isEqualTo("00042");
        assertThatThrownBy(() -> Zengin.number(123_456, 5)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void transferFileHasHeaderDataTrailerEndAll120Wide() {
        List<Zengin.Item> items = List.of(
                new Zengin.Item(1, "0005", "ﾐﾂﾋﾞｼ", "001", '1', "1234567", "ﾔﾏﾀﾞ ﾀﾛｳ", 10_000),
                new Zengin.Item(2, "0009", "ﾐﾂｲｽﾐﾄﾓ", "002", '2', "7654321", "ｻｸﾗ ﾊﾅｺ", 25_000));
        String file = Zengin.transferFile(items, LocalDate.of(2030, 4, 1), "9999");
        String[] records = file.split("\r\n");
        assertThat(records).hasSize(5);   // ヘッダー・データ 2 件・トレーラ・エンド
        assertThat(records).allSatisfy(r -> assertThat(r).hasSize(120));
        assertThat(records[0]).startsWith("1210").contains("0401");
        assertThat(records[1]).startsWith("20005");
        assertThat(records[2]).startsWith("20009");
        // トレーラ：件数 6 桁と合計金額 12 桁
        assertThat(records[3]).startsWith("8000002000000035000");
        assertThat(records[4]).startsWith("9");
        assertThat(file).endsWith("\r\n");
    }
}
