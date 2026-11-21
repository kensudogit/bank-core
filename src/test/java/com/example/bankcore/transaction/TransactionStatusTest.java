package com.example.bankcore.transaction;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 取引状態：予約（PENDING）から完了か拒否のどちらかに確定する 3 値。 */
class TransactionStatusTest {

    @Test
    void hasPendingAndTwoFinalStates() {
        assertThat(TransactionStatus.values()).extracting(Enum::name)
                .containsExactly("PENDING", "COMPLETED", "REJECTED");
    }
}
