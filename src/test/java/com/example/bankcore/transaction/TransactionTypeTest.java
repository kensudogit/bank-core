package com.example.bankcore.transaction;

import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;

/** 取引種別：分類メソッドと、DB の CHECK 制約で許された種別との一致。 */
class TransactionTypeTest extends AbstractIntegrationTest {

    @Test
    void loanTypesAreThoseWithLoanPrefix() {
        assertThat(Arrays.stream(TransactionType.values()).filter(TransactionType::isLoan))
                .containsExactlyInAnyOrder(TransactionType.LOAN_DISBURSEMENT, TransactionType.LOAN_REPAYMENT,
                        TransactionType.LOAN_PROVISION, TransactionType.LOAN_WRITE_OFF);
    }

    @Test
    void genericMovementsAreTheFiveTellerOperations() {
        assertThat(Arrays.stream(TransactionType.values()).filter(TransactionType::isGenericMovement))
                .containsExactlyInAnyOrder(TransactionType.TRANSFER, TransactionType.DEPOSIT, TransactionType.WITHDRAWAL,
                        TransactionType.INTEREST, TransactionType.REVERSAL);
    }

    @Test
    void everyTypeIsAllowedByTheDatabaseConstraint() {
        String check = jdbc.sql("""
                        SELECT pg_get_constraintdef(oid) FROM pg_constraint WHERE conname = 'transactions_type_check'""")
                .query(String.class).single();
        for (TransactionType t : TransactionType.values()) {
            assertThat(check).as(t.name()).contains("'" + t.name() + "'");
        }
    }
}
