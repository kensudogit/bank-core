package com.example.bankcore.batch;

import com.example.bankcore.batch.DailyClosingService.ClosingRecord;
import com.example.bankcore.batch.DailyClosingService.ClosingReport;
import com.example.bankcore.ledger.ReconciliationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * ジョブスケジューラ（JP1/AJS など）から起動するジョブと、終了コードの取り決め。
 *
 * <table>
 *   <tr><th>終了コード</th><th>意味</th><th>スケジューラ側の扱いの例</th></tr>
 *   <tr><td>0</td><td>正常（照合合格。締めは承認待ちまたは完了）</td><td>後続ジョブへ</td></tr>
 *   <tr><td>1</td><td>承認待ち（closing-status のみ）</td><td>一定間隔で再判定（待ち合わせ）</td></tr>
 *   <tr><td>4</td><td>照合不一致・差戻し（要修復）</td><td>警告終了。運用者が修復して再実行</td></tr>
 *   <tr><td>8</td><td>ステップの異常終了（再実行で続きから再開できる）</td><td>異常終了。原因を取り除いて再実行</td></tr>
 *   <tr><td>12</td><td>起動条件の誤り（不明なジョブ名・締めの記録なし）</td><td>異常終了。定義を見直す</td></tr>
 * </table>
 * 「ジョブの正常終了」と「勘定の正しさ」を分けるため、照合不一致は 0 ではなく 4 で返す。
 */
@Component
public class BatchJobs {

    private static final Logger log = LoggerFactory.getLogger(BatchJobs.class);

    public static final int OK = 0;
    public static final int AWAITING_APPROVAL = 1;
    public static final int NEEDS_REPAIR = 4;
    public static final int FAILED = 8;
    public static final int INVALID = 12;

    /** @param detail ジョブごとの結果（締めのレポート・照合結果など） */
    public record JobResult(String job, int exitCode, String status, String message, Object detail) {
    }

    private final DailyClosingService closing;
    private final ReconciliationService reconciliation;

    public BatchJobs(DailyClosingService closing, ReconciliationService reconciliation) {
        this.closing = closing;
        this.reconciliation = reconciliation;
    }

    /**
     * @param job      daily-closing（締めの実行）/ closing-status（直近の締めの状態の判定）/ reconciliation（照合だけ）
     * @param operator 実行者（監査ログと職務分離の判定に使う）
     */
    public JobResult run(String job, String operator) {
        try {
            return switch (job) {
                case "daily-closing" -> {
                    ClosingReport r = closing.run(operator);
                    yield new JobResult(job, r.exitCode(), r.status(),
                            r.reconciliationOk() ? "closing " + r.businessDate() + " " + r.status()
                                    : "reconciliation failed for " + r.businessDate(), r);
                }
                case "closing-status" -> status(job);
                case "reconciliation" -> {
                    ReconciliationService.Report r = reconciliation.run();
                    yield new JobResult(job, r.ok() ? OK : NEEDS_REPAIR, r.ok() ? "OK" : "MISMATCH",
                            r.ok() ? "books are consistent" : "reconciliation mismatch", r);
                }
                default -> new JobResult(job, INVALID, "UNKNOWN_JOB", "unknown job: " + job, null);
            };
        } catch (RuntimeException e) {
            log.error("batch job {} failed", job, e);
            return new JobResult(job, FAILED, "FAILED", String.valueOf(e.getMessage()), null);
        }
    }

    /** 直近の締めの状態を終了コードで返す（承認待ちを待ち合わせる判定ジョブ用）。 */
    private JobResult status(String job) {
        List<ClosingRecord> history = closing.history();
        if (history.isEmpty()) {
            return new JobResult(job, INVALID, "NONE", "no closing has run", null);
        }
        ClosingRecord c = history.getFirst();
        int code = switch (c.status()) {
            case "COMPLETED" -> OK;
            case "AWAITING_APPROVAL" -> AWAITING_APPROVAL;
            case "RECON_FAILED", "REJECTED" -> NEEDS_REPAIR;
            default -> FAILED;   // RUNNING（実行中または途中で失敗）
        };
        return new JobResult(job, code, c.status(), "closing " + c.businessDate() + " is " + c.status(), c);
    }
}
