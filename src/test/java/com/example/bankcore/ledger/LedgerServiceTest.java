package com.example.bankcore.ledger;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** DB を使わない単体テスト：仕訳の貸借一致検証。 */
class LedgerServiceTest {

    @Test
    void acceptsBalancedEntry() {
        List<JournalLine> lines = List.of(
                JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, 1L, 500, "JPY"),
                JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, 2L, 500, "JPY"));
        assertThatCode(() -> LedgerService.assertBalanced(lines)).doesNotThrowAnyException();
    }

    @Test
    void rejectsUnbalancedEntry() {
        List<JournalLine> lines = List.of(
                JournalLine.debit(GlCode.CUSTOMER_DEPOSITS, 1L, 500, "JPY"),
                JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, 2L, 499, "JPY"));
        assertThatThrownBy(() -> LedgerService.assertBalanced(lines)).hasMessageContaining("not balanced");
    }

    @Test
    void balanceIsCheckedPerCurrency() {
        List<JournalLine> lines = List.of(
                JournalLine.debit(GlCode.CASH, null, 100, "JPY"),
                JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, 1L, 100, "USD"));
        assertThatThrownBy(() -> LedgerService.assertBalanced(lines)).hasMessageContaining("not balanced");
    }

    @Test
    void lineMustBeOneSided() {
        assertThatThrownBy(() -> new JournalLine(GlCode.CASH, null, 100, 100, "JPY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalLine(GlCode.CASH, null, 0, 0, "JPY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> LedgerService.assertBalanced(List.of(JournalLine.debit(GlCode.CASH, null, 1, "JPY"))))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
