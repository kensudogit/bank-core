package com.example.bankcore.batch;

import com.example.bankcore.batch.DailyClosingService.ClosingReport;
import com.example.bankcore.batch.DailyClosingService.ClosingStatus;
import com.example.bankcore.batch.DailyClosingService.Step;
import com.example.bankcore.common.BusinessCalendar;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 業務日付と日次締め（実行・状態・承認・差戻し・修復・ステップの再実行指示）の API。 */
@RestController
@RequestMapping("/api")
public class BatchController {

    public record ApprovalRequest(@NotBlank @Size(max = 50) String approver) {
    }

    public record RejectionRequest(@NotBlank @Size(max = 50) String approver, @NotBlank @Size(max = 1000) String reason) {
    }

    public record RepairRequest(@NotBlank @Size(max = 50) String actor, @NotBlank @Size(max = 1000) String description) {
    }

    public record ActorRequest(@NotBlank @Size(max = 50) String actor) {
    }

    private final DailyClosingService closing;
    private final BatchJobs jobs;
    private final BusinessCalendar calendar;

    public BatchController(DailyClosingService closing, BatchJobs jobs, BusinessCalendar calendar) {
        this.closing = closing;
        this.jobs = jobs;
        this.calendar = calendar;
    }

    /** 業務日付（勘定日付）とシステム日付。両者は別物で、業務日付は締めの確定でだけ進む。 */
    @GetMapping("/business-date")
    public Map<String, Object> businessDate() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("businessDate", calendar.today());
        m.put("systemDate", LocalDate.now(ZoneId.systemDefault()));
        m.put("approvalRequired", closing.approvalRequired());
        return m;
    }

    /** 当日の業務日付で日次締めを実行する。途中で失敗しても、もう一度呼べば続きから処理される。 */
    @PostMapping("/batch/daily-closing")
    public ClosingReport runDailyClosing(@RequestParam(defaultValue = "SYSTEM") String operator) {
        return closing.run(operator);
    }

    @GetMapping("/batch/daily-closings")
    public List<DailyClosingService.ClosingRecord> history() {
        return closing.history();
    }

    @GetMapping("/batch/daily-closings/{date}")
    public ClosingStatus status(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date) {
        return closing.status(date);
    }

    @PostMapping("/batch/daily-closings/{date}/approval")
    public ClosingStatus approve(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                 @Valid @RequestBody ApprovalRequest r) {
        return closing.approve(date, r.approver());
    }

    @PostMapping("/batch/daily-closings/{date}/rejection")
    public ClosingStatus reject(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                @Valid @RequestBody RejectionRequest r) {
        return closing.reject(date, r.approver(), r.reason());
    }

    @PostMapping("/batch/daily-closings/{date}/repairs")
    public ClosingStatus repair(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                @Valid @RequestBody RepairRequest r) {
        return closing.recordRepair(date, r.actor(), r.description());
    }

    @PostMapping("/batch/daily-closings/{date}/steps/{step}/reset")
    public ClosingStatus resetStep(@PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
                                   @PathVariable Step step, @Valid @RequestBody ActorRequest r) {
        return closing.resetStep(date, step, r.actor());
    }

    /**
     * ジョブスケジューラ（JP1/AJS など）向けの実行口。応答の exitCode をラッパースクリプトがそのまま終了コードにする
     * （scripts/jp1 を参照）。
     */
    @PostMapping("/batch/jobs/{job}")
    public BatchJobs.JobResult runJob(@PathVariable String job,
                                      @RequestParam(defaultValue = "JP1") String operator) {
        return jobs.run(job, operator);
    }
}
