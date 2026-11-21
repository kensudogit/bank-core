package com.example.bankcore.loan;

import com.example.bankcore.common.Exceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 融資コマンドの共通検証：冪等キー・必須文字列・リクエストハッシュ。 */
class LoanCommandsTest {

    @Test
    void requireKey() {
        assertThatCode(() -> LoanCommands.requireKey("loan-1.a:b_c")).doesNotThrowAnyException();
        assertThatThrownBy(() -> LoanCommands.requireKey(null)).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> LoanCommands.requireKey("")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> LoanCommands.requireKey("has space")).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> LoanCommands.requireKey("x".repeat(101))).isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void requireTextTrimsAndLimitsLength() {
        assertThat(LoanCommands.requireText("  manager1 ", "by", 50)).isEqualTo("manager1");
        assertThatThrownBy(() -> LoanCommands.requireText(" ", "by", 50)).hasMessage("by is required");
        assertThatThrownBy(() -> LoanCommands.requireText(null, "by", 50)).hasMessage("by is required");
        assertThatThrownBy(() -> LoanCommands.requireText("x".repeat(51), "by", 50))
                .hasMessage("by must be at most 50 characters");
    }

    @Test
    void hashIsDeterministicAndSensitiveToEveryPart() {
        String h = LoanCommands.hash("REPAY", 1L, 100L);
        assertThat(h).isEqualTo(LoanCommands.hash("REPAY", 1L, 100L)).hasSize(64);
        assertThat(LoanCommands.hash("REPAY", 1L, 101L)).isNotEqualTo(h);
        assertThat(LoanCommands.hash("PREPAY", 1L, 100L)).isNotEqualTo(h);
        // null は空文字として扱う（区切りがあるので部品の境界は保たれる）
        assertThat(LoanCommands.hash("A", null)).isEqualTo(LoanCommands.hash("A", ""));
        assertThat(LoanCommands.hash("AB", "")).isNotEqualTo(LoanCommands.hash("A", "B"));
    }
}
