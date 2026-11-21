package com.example.bankcore.ledger;

import com.example.bankcore.account.Account;
import com.example.bankcore.ledger.JournalRepository.PostedLine;
import com.example.bankcore.ledger.JournalRepository.StatementLine;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.transaction.MovementCommand;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 仕訳リポジトリ：取引ごとの明細と口座明細（預金科目の行だけ）。 */
class JournalRepositoryTest extends AbstractIntegrationTest {

    @Autowired JournalRepository journal;

    @Test
    void linesForTransferAreBalancedAndOrdered() {
        Account a = openAccount("A", 1_000);
        Account b = openAccount("B", 0);
        long txId = movements.execute(MovementCommand.transfer(key(), a.id(), b.id(), 300, "JPY", "rent", null))
                .transaction().id();

        List<PostedLine> lines = journal.linesForTransaction(txId);
        assertThat(lines).extracting(PostedLine::lineNo).containsExactly(1, 2);
        assertThat(lines).allSatisfy(l -> assertThat(l.glCode()).isEqualTo(GlCode.CUSTOMER_DEPOSITS));
        assertThat(lines.stream().mapToLong(PostedLine::debitMinor).sum())
                .isEqualTo(lines.stream().mapToLong(PostedLine::creditMinor).sum()).isEqualTo(300);
        assertThat(lines).extracting(PostedLine::accountId).containsExactlyInAnyOrder(a.id(), b.id());
    }

    @Test
    void linesForUnknownTransactionAreEmpty() {
        assertThat(journal.linesForTransaction(999_999)).isEmpty();
    }

    @Test
    void statementShowsDepositLinesInPostingOrderWithBusinessDate() {
        Account a = openAccount("A", 1_000);
        Account b = openAccount("B", 0);
        movements.execute(MovementCommand.transfer(key(), a.id(), b.id(), 300, "JPY", null, null));

        List<StatementLine> st = journal.statement(a.id());
        assertThat(st).hasSize(2);
        assertThat(st.get(0).creditMinor()).isEqualTo(1_000);   // 入金：預金（負債）の貸方
        assertThat(st.get(1).debitMinor()).isEqualTo(300);      // 振込の出金：預金の借方
        assertThat(st).allSatisfy(l -> assertThat(l.businessDate()).isEqualTo(INITIAL_BUSINESS_DATE));
        assertThat(st.get(0).journalEntryId()).isLessThan(st.get(1).journalEntryId());
        assertThat(journal.statement(b.id())).hasSize(1);
    }
}
