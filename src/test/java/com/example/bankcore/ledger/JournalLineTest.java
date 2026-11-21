package com.example.bankcore.ledger;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 仕訳明細：借方か貸方のどちらか一方だけが正であること、各ファクトリの組み立て。 */
class JournalLineTest {

    @Test
    void debitAndCreditFactories() {
        JournalLine d = JournalLine.debit(GlCode.CASH, null, 100, "JPY");
        assertThat(d.debitMinor()).isEqualTo(100);
        assertThat(d.creditMinor()).isZero();
        assertThat(d.accountId()).isNull();
        assertThat(d.loanId()).isNull();

        JournalLine c = JournalLine.credit(GlCode.CUSTOMER_DEPOSITS, 7L, 100, "JPY");
        assertThat(c.creditMinor()).isEqualTo(100);
        assertThat(c.accountId()).isEqualTo(7L);
    }

    @Test
    void loanFactoriesCarryLoanIdAndNoAccount() {
        JournalLine d = JournalLine.debitLoan(GlCode.LOANS_RECEIVABLE, 5, 1_000, "JPY");
        assertThat(d.loanId()).isEqualTo(5L);
        assertThat(d.accountId()).isNull();
        assertThat(d.debitMinor()).isEqualTo(1_000);
        JournalLine c = JournalLine.creditLoan(GlCode.LOANS_RECEIVABLE, 5, 400, "JPY");
        assertThat(c.creditMinor()).isEqualTo(400);
    }

    @Test
    void rejectsNegativeAmounts() {
        assertThatThrownBy(() -> new JournalLine(GlCode.CASH, null, -1, 0, "JPY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalLine(GlCode.CASH, null, 0, -1, "JPY"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rejectsBothOrNeitherSide() {
        assertThatThrownBy(() -> new JournalLine(GlCode.CASH, null, 0, 0, "JPY"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new JournalLine(GlCode.CASH, null, 1, 1, "JPY"))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
