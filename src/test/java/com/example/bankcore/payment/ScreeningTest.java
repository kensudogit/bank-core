package com.example.bankcore.payment;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

/** 制裁リスト照合の名前の類似度：表記ゆれ・語順・法人格の違いを吸収し、無関係な名前は低く出す。 */
class ScreeningTest {

    @Test
    void identicalNamesScoreOne() {
        assertThat(Screening.similarity("IVAN PETROV", "IVAN PETROV")).isEqualTo(1.0);
    }

    @Test
    void ignoresCaseOrderPunctuationAndLegalForms() {
        assertThat(Screening.similarity("Petrov, Ivan", "IVAN PETROV")).isEqualTo(1.0);
        assertThat(Screening.similarity("Acme Trading Ltd.", "ACME TRADING CO")).isEqualTo(1.0);
        assertThat(Screening.similarity("Mr. Ivan Petrov", "Ivan Petrov")).isEqualTo(1.0);
    }

    @Test
    void smallSpellingDifferenceStaysAboveThreshold() {
        assertThat(Screening.similarity("IVAN PETROVA", "IVAN PETROV")).isGreaterThanOrEqualTo(Screening.MATCH_THRESHOLD);
    }

    @Test
    void unrelatedNamesScoreLow() {
        assertThat(Screening.similarity("TARO YAMADA", "IVAN PETROV")).isLessThan(Screening.MATCH_THRESHOLD);
    }

    @Test
    void emptyOrNullScoresZero() {
        assertThat(Screening.similarity(null, "X")).isZero();
        assertThat(Screening.similarity("Ltd", "X")).isZero();   // 法人格だけ → トークンなし
    }

    @Test
    void tokensAndLevenshtein() {
        assertThat(Screening.tokens("Foo-Bar  Inc.")).containsExactly("FOO", "BAR");
        assertThat(Screening.levenshtein("kitten", "sitting")).isEqualTo(3);
        assertThat(Screening.levenshtein("", "abc")).isEqualTo(3);
        assertThat(Screening.similarity("AB", "AC")).isCloseTo(0.5, within(1e-9));
    }
}
