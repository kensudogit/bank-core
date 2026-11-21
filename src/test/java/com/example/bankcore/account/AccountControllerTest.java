package com.example.bankcore.account;

import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 口座 API：開設・一覧・照会・状態変更・明細と入力検証。 */
class AccountControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void openWithOverdraftAndGet() throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"Dave\",\"currency\":\"JPY\",\"overdraftLimitMinor\":10000}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.overdraftLimitMinor").value(10000))
                .andExpect(jsonPath("$.availableBalanceMinor").value(10000));
        Account a = accountService.list().get(0);
        mvc.perform(get("/api/accounts/" + a.id()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ownerName").value("Dave"));
        mvc.perform(get("/api/accounts")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void openValidatesInput() throws Exception {
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"\",\"currency\":\"JPY\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"X\",\"currency\":\"jpy\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/accounts").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"ownerName\":\"X\",\"currency\":\"JPY\",\"overdraftLimitMinor\":-1}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void unknownAccountIs404() throws Exception {
        mvc.perform(get("/api/accounts/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/accounts/999999/statement")).andExpect(status().isNotFound());
    }

    @Test
    void changeStatusAndRejectClosingWithBalance() throws Exception {
        Account a = openAccount("A", 1_000);
        mvc.perform(post("/api/accounts/" + a.id() + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"FROZEN\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FROZEN"));
        mvc.perform(post("/api/accounts/" + a.id() + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":\"CLOSED\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/accounts/" + a.id() + "/status").contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void statementListsPostedLines() throws Exception {
        Account a = openAccount("A", 1_000);
        mvc.perform(get("/api/accounts/" + a.id() + "/statement"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1));
    }
}
