package com.example.bankcore.api;

import com.example.bankcore.account.Account;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** HTTP 層：ステータスコードと冪等ヘッダーの契約。 */
class TransferApiTest extends AbstractIntegrationTest {

    @Autowired
    MockMvc mvc;

    private String transferJson(long from, long to, long amount) {
        return """
                {"sourceAccountId": %d, "targetAccountId": %d, "amountMinor": %d, "currency": "JPY"}
                """.formatted(from, to, amount);
    }

    @Test
    void rootServesDemoPage() throws Exception {
        mvc.perform(get("/"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.forwardedUrl("index.html"));
        mvc.perform(get("/index.html"))
                .andExpect(status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content()
                        .string(org.hamcrest.Matchers.containsString("/api/transfers")));
    }

    @Test
    void openAccountViaApi() throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\": \"Carol\", \"currency\": \"JPY\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.accountNumber").exists())
                .andExpect(jsonPath("$.postedBalanceMinor").value(0))
                .andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    void transferRequiresIdempotencyKey() throws Exception {
        Account a = openAccount("A", 1_000);
        Account b = openAccount("B", 0);
        mvc.perform(post("/api/transfers").contentType(MediaType.APPLICATION_JSON)
                        .content(transferJson(a.id(), b.id(), 100)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void replayReturnsSameResponseWithReplayHeader() throws Exception {
        Account a = openAccount("A", 1_000);
        Account b = openAccount("B", 0);

        String body = mvc.perform(post("/api/transfers").header("Idempotency-Key", "api-key-1")
                        .header("X-Correlation-Id", "corr-api")
                        .contentType(MediaType.APPLICATION_JSON).content(transferJson(a.id(), b.id(), 100)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn().getResponse().getContentAsString();

        mvc.perform(post("/api/transfers").header("Idempotency-Key", "api-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(transferJson(a.id(), b.id(), 100)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().json(body));

        mvc.perform(post("/api/transfers").header("Idempotency-Key", "api-key-1")
                        .contentType(MediaType.APPLICATION_JSON).content(transferJson(a.id(), b.id(), 999)))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/accounts/{id}", b.id()))
                .andExpect(jsonPath("$.postedBalanceMinor").value(100));
        mvc.perform(get("/api/transactions").param("idempotencyKey", "api-key-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.correlationId").value("corr-api"));
    }

    @Test
    void businessRejectionIs422AndValidationErrorIs400() throws Exception {
        Account a = openAccount("A", 50);
        Account b = openAccount("B", 0);

        mvc.perform(post("/api/transfers").header("Idempotency-Key", "api-key-2")
                        .contentType(MediaType.APPLICATION_JSON).content(transferJson(a.id(), b.id(), 100)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectReason").value("INSUFFICIENT_FUNDS"));

        mvc.perform(post("/api/transfers").header("Idempotency-Key", "api-key-3")
                        .contentType(MediaType.APPLICATION_JSON).content(transferJson(a.id(), b.id(), 0)))
                .andExpect(status().isBadRequest());

        mvc.perform(post("/api/transfers").header("Idempotency-Key", "api-key-4")
                        .contentType(MediaType.APPLICATION_JSON).content(transferJson(a.id(), 99_999, 10)))
                .andExpect(status().isNotFound());
    }

    @Test
    void transactionDetailAndReconciliationEndpoints() throws Exception {
        Account a = openAccount("A", 1_000);

        mvc.perform(get("/api/accounts/{id}/statement", a.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].creditMinor").value(1000));
        mvc.perform(get("/api/transactions/{id}", 1))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.journalLines.length()").value(2));
        mvc.perform(get("/api/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true));
    }
}
