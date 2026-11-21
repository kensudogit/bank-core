package com.example.bankcore.ledger;

import com.example.bankcore.account.Account;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 照合 API：結果をそのまま JSON で返す。 */
class ReconciliationControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void reportsOkAndMismatches() throws Exception {
        Account a = openAccount("A", 1_000);
        mvc.perform(get("/api/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(true))
                .andExpect(jsonPath("$.trialBalance").isArray());

        jdbc.sql("UPDATE accounts SET posted_balance_minor = 0 WHERE id = :id").param("id", a.id()).update();
        mvc.perform(get("/api/reconciliation"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ok").value(false))
                .andExpect(jsonPath("$.accountMismatches[0].accountId").value(a.id()));
    }
}
