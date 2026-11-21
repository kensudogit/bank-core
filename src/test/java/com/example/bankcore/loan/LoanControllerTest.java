package com.example.bankcore.loan;

import com.example.bankcore.account.Account;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 融資 API：申込から実行・返済までの HTTP の流れと入力検証。 */
class LoanControllerTest extends LoanTestSupport {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode body(org.springframework.test.web.servlet.ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    @Test
    void applicationToRepaymentOverHttp() throws Exception {
        Account a = openAccount("A", 0);
        JsonNode app = body(mvc.perform(post("/api/loan-applications").contentType(MediaType.APPLICATION_JSON).content("""
                        {"accountId": %d, "product": "BUSINESS", "principalMinor": 1200000, "termMonths": 12,
                         "repaymentMethod": "EQUAL_PAYMENT", "annualIncomeMinor": 8000000, "submittedBy": "teller1"}
                        """.formatted(a.id())))
                .andExpect(status().isCreated()));
        long appId = app.at("/application/id").asLong();

        JsonNode col = body(mvc.perform(post("/api/loan-applications/" + appId + "/collaterals")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"DEPOSIT\",\"description\":\"定期\",\"appraisalMinor\":500000}"))
                .andExpect(status().isOk()));
        long colId = col.at("/collaterals/0/id").asLong();
        mvc.perform(put("/api/collaterals/" + colId + "/appraisal").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"appraisalMinor\":600000}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.securedMinor").value(600000));
        mvc.perform(post("/api/loan-applications/" + appId + "/guarantees").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"type\":\"INDIVIDUAL\",\"guarantorName\":\"代表者\",\"coverageMinor\":200000}"))
                .andExpect(status().isOk());
        mvc.perform(post("/api/loan-applications/" + appId + "/screening"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("SCREENED"));
        mvc.perform(post("/api/loan-applications/" + appId + "/decision").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"approve\":true,\"decidedBy\":\"manager1\",\"note\":\"ok\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.application.status").value("APPROVED"));
        mvc.perform(get("/api/loan-applications/" + appId)).andExpect(status().isOk());
        mvc.perform(get("/api/loan-applications")).andExpect(jsonPath("$.length()").value(1));

        JsonNode disb = body(mvc.perform(post("/api/loan-applications/" + appId + "/disbursement")
                        .header("Idempotency-Key", "http-disb"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false")));
        long loanId = disb.at("/loan/id").asLong();

        mvc.perform(post("/api/loans/" + loanId + "/repayments").header("Idempotency-Key", "http-rep")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"amountMinor\":100000}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/loans/" + loanId)).andExpect(status().isOk())
                .andExpect(jsonPath("$.installments.length()").value(12));
        mvc.perform(get("/api/loans/" + loanId + "/ledger")).andExpect(jsonPath("$.length()").value(2));
        mvc.perform(get("/api/loans").param("status", "ACTIVE")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/loans/monitoring")).andExpect(status().isOk())
                .andExpect(jsonPath("$.loansChecked").value(1));
        mvc.perform(get("/api/credit-risk")).andExpect(status().isOk()).andExpect(jsonPath("$.loans").value(1));
    }

    @Test
    void validationAndErrors() throws Exception {
        mvc.perform(post("/api/loan-applications").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":1,\"product\":\"BUSINESS\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/loan-applications/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/loans/999999")).andExpect(status().isNotFound());
        Account a = openAccount("A", 0);
        long appId = submitted(a, 1_200_000).application().id();
        mvc.perform(post("/api/loan-applications/" + appId + "/disbursement").header("Idempotency-Key", "k"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/loan-applications/" + appId + "/disbursement")).andExpect(status().isBadRequest());
    }

    @Test
    void simulation() throws Exception {
        mvc.perform(get("/api/loan-simulations").param("principalMinor", "1000000").param("annualRateBp", "200")
                        .param("termMonths", "6").param("method", "EQUAL_PRINCIPAL"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(6));
        mvc.perform(get("/api/loan-simulations").param("principalMinor", "0").param("annualRateBp", "200")
                        .param("termMonths", "6").param("method", "EQUAL_PRINCIPAL"))
                .andExpect(status().isBadRequest());
    }
}
