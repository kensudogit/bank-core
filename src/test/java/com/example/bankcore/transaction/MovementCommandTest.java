package com.example.bankcore.transaction;

import com.example.bankcore.common.Exceptions.InvalidRequestException;
import org.junit.jupiter.api.Test;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 資金移動コマンド：ファクトリ・入力検証・リクエストハッシュ。 */
class MovementCommandTest {

    @Test
    void factoriesSetTypeAndDirection() {
        MovementCommand t = MovementCommand.transfer("k", 1, 2, 100, "JPY", "m", "c");
        assertThat(t.type()).isEqualTo(TransactionType.TRANSFER);
        assertThat(t.sourceAccountId()).isEqualTo(1L);
        assertThat(t.targetAccountId()).isEqualTo(2L);

        MovementCommand d = MovementCommand.deposit("k", 2, 100, "JPY", null, null);
        assertThat(d.sourceAccountId()).isNull();
        assertThat(d.targetAccountId()).isEqualTo(2L);

        MovementCommand w = MovementCommand.withdrawal("k", 1, 100, "JPY", null, null);
        assertThat(w.sourceAccountId()).isEqualTo(1L);
        assertThat(w.targetAccountId()).isNull();

        MovementCommand i = MovementCommand.interest("k", 3, 5, "JPY", "利息");
        assertThat(i.type()).isEqualTo(TransactionType.INTEREST);
        assertThat(i.correlationId()).isNull();
    }

    @Test
    void reversalSwapsSourceAndTargetAndReferencesOriginal() {
        MoneyTransaction original = new MoneyTransaction(42, "orig", "h", TransactionType.TRANSFER, 1L, 2L, 300, "JPY",
                null, TransactionStatus.COMPLETED, null, null, null, OffsetDateTime.now(), OffsetDateTime.now(), null);
        MovementCommand r = MovementCommand.reversalOf(original, "rev", "corr");
        assertThat(r.type()).isEqualTo(TransactionType.REVERSAL);
        assertThat(r.sourceAccountId()).isEqualTo(2L);
        assertThat(r.targetAccountId()).isEqualTo(1L);
        assertThat(r.amountMinor()).isEqualTo(300);
        assertThat(r.reversesTransactionId()).isEqualTo(42L);
        assertThat(r.memo()).contains("42");
        assertThatCode(r::validate).doesNotThrowAnyException();
    }

    @Test
    void validatesKeyAmountCurrencyAndMemo() {
        assertThatThrownBy(() -> MovementCommand.deposit(null, 1, 1, "JPY", null, null).validate())
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> MovementCommand.deposit("bad key!", 1, 1, "JPY", null, null).validate())
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> MovementCommand.deposit("k".repeat(101), 1, 1, "JPY", null, null).validate())
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> MovementCommand.deposit("k", 1, 0, "JPY", null, null).validate())
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> MovementCommand.deposit("k", 1, 1, "jpy", null, null).validate())
                .isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> MovementCommand.deposit("k", 1, 1, "JPY", "x".repeat(201), null).validate())
                .isInstanceOf(InvalidRequestException.class);
        assertThatCode(() -> MovementCommand.deposit("a.b_c:d-1", 1, 1, "JPY", "x".repeat(200), null).validate())
                .doesNotThrowAnyException();
    }

    @Test
    void validatesShapePerType() {
        assertThatThrownBy(() -> MovementCommand.transfer("k", 1, 1, 1, "JPY", null, null).validate())
                .hasMessageContaining("differ");
        assertThatThrownBy(() -> new MovementCommand(TransactionType.DEPOSIT, "k", 1L, 2L, 1, "JPY", null, null, null)
                .validate()).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new MovementCommand(TransactionType.WITHDRAWAL, "k", null, 2L, 1, "JPY", null, null, null)
                .validate()).isInstanceOf(InvalidRequestException.class);
        assertThatThrownBy(() -> new MovementCommand(TransactionType.REVERSAL, "k", 1L, 2L, 1, "JPY", null, null, null)
                .validate()).isInstanceOf(InvalidRequestException.class);
        // 業務 API 専用の種別は汎用の資金移動では実行できない
        assertThatThrownBy(() -> new MovementCommand(TransactionType.LOAN_REPAYMENT, "k", 1L, null, 1, "JPY", null, null,
                null).validate()).hasMessageContaining("business API");
    }

    @Test
    void requestHashIsStableAndIgnoresKeyAndCorrelation() {
        String h1 = MovementCommand.transfer("k1", 1, 2, 100, "JPY", "m", "c1").requestHash();
        String h2 = MovementCommand.transfer("k2", 1, 2, 100, "JPY", "m", "c2").requestHash();
        assertThat(h1).isEqualTo(h2).hasSize(64).matches("[0-9a-f]+");
        assertThat(MovementCommand.transfer("k1", 1, 2, 101, "JPY", "m", null).requestHash()).isNotEqualTo(h1);
        assertThat(MovementCommand.transfer("k1", 1, 2, 100, "JPY", "other", null).requestHash()).isNotEqualTo(h1);
    }
}
