package com.example.bankcore.batch;

import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** バッチ API：業務日付・締めの実行と照会・修復・ジョブの終了コード（承認なしの設定）。 */
class BatchControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void businessDate() throws Exception {
        mvc.perform(get("/api/business-date"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.businessDate").value("2030-04-01"))
                .andExpect(jsonPath("$.approvalRequired").value(false))
                .andExpect(jsonPath("$.systemDate").exists());
    }

    @Test
    void runClosingAndInspect() throws Exception {
        openAccount("A", 3_650_000);
        mvc.perform(post("/api/batch/daily-closing").param("operator", "op1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.dateAdvanced").value(true));
        mvc.perform(get("/api/batch/daily-closings")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/batch/daily-closings/2030-04-01"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.closing.status").value("COMPLETED"))
                .andExpect(jsonPath("$.steps.length()").value(DailyClosingService.Step.values().length));
        mvc.perform(post("/api/batch/daily-closings/2030-04-01/repairs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"actor\":\"op2\",\"description\":\"確認のみ\"}"))
                .andExpect(status().isConflict());   // 完了した締めには修復を記録できない
        mvc.perform(get("/api/business-date")).andExpect(jsonPath("$.businessDate").value("2030-04-02"));
    }

    @Test
    void jobsEndpointReturnsExitCode() throws Exception {
        mvc.perform(post("/api/batch/jobs/reconciliation")).andExpect(status().isOk())
                .andExpect(jsonPath("$.exitCode").value(0));
        mvc.perform(post("/api/batch/jobs/unknown")).andExpect(jsonPath("$.exitCode").value(12));
    }

    @Test
    void validationAndNotFound() throws Exception {
        mvc.perform(get("/api/batch/daily-closings/2029-01-01")).andExpect(status().isNotFound());
        mvc.perform(post("/api/batch/daily-closings/2030-04-01/approval").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/batch/daily-closings/2030-04-01/rejection").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approver\":\"m\"}"))
                .andExpect(status().isBadRequest());
    }
}
