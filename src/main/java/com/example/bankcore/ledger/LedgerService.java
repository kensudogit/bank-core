package com.example.bankcore.ledger;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * 複式簿記の記帳。呼び出し元のトランザクション（残高更新と同じもの）に参加する。
 */
@Service
public class LedgerService {

    private final JournalRepository journal;

    public LedgerService(JournalRepository journal) {
        this.journal = journal;
    }

    /** 仕訳を記帳する。業務日付は DB の business_calendar の現在値（{@code BusinessCalendar} 参照）。 */
    @Transactional(propagation = Propagation.MANDATORY)
    public long post(long transactionId, String description, List<JournalLine> lines) {
        assertBalanced(lines);
        return journal.insertEntryWithLines(transactionId, description, lines);
    }

    /** 通貨ごとに借方合計 = 貸方合計 であることを検証する。DB の遅延制約トリガーでも同じ検証を行う。 */
    public static void assertBalanced(List<JournalLine> lines) {
        if (lines.size() < 2) {
            throw new IllegalArgumentException("a journal entry needs at least two lines");
        }
        Map<String, Long> netByCurrency = lines.stream().collect(Collectors.groupingBy(
                JournalLine::currency,
                Collectors.reducing(0L, l -> Math.subtractExact(l.debitMinor(), l.creditMinor()), Math::addExact)));
        netByCurrency.forEach((currency, net) -> {
            if (net != 0) {
                throw new IllegalArgumentException("journal is not balanced for " + currency + ": net=" + net);
            }
        });
    }
}
