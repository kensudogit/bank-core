package com.example.bankcore.api;

import com.example.bankcore.account.Account;
import com.example.bankcore.support.AbstractIntegrationTest;
import com.example.bankcore.transaction.MovementCommand;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 業務 API（CRM・有価証券・投信と信託・カードと電子決済・AML・リスク管理）の HTTP 契約。 */
class BankingOperationsControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;
    @Autowired ObjectMapper json;

    private JsonNode body(ResultActions r) throws Exception {
        return json.readTree(r.andReturn().getResponse().getContentAsString());
    }

    private static final MediaType JSON = MediaType.APPLICATION_JSON;

    @Test
    void customerManagement() throws Exception {
        long id = body(mvc.perform(post("/api/customers").contentType(JSON).content("""
                        {"customerType": "INDIVIDUAL", "name": "山田 太郎", "nameKana": "ヤマダ タロウ",
                         "annualIncomeMinor": 12000000, "riskTolerance": 3, "relationshipManager": "rm1"}"""))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.segment").value("AFFLUENT"))).get("id").asLong();
        mvc.perform(get("/api/customers").param("q", "ヤマダ")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/customers/" + id)).andExpect(status().isOk());
        mvc.perform(post("/api/customers/" + id + "/kyc").param("verified", "true"))
                .andExpect(jsonPath("$.kycStatus").value("VERIFIED"));
        mvc.perform(put("/api/customers/" + id + "/suitability").contentType(JSON)
                        .content("{\"riskTolerance\":4,\"experienceYears\":2}"))
                .andExpect(jsonPath("$.riskTolerance").value(4));
        Account a = openAccount("山田 太郎", 1_000);
        mvc.perform(post("/api/customers/" + id + "/accounts/" + a.id())).andExpect(status().isOk());
        mvc.perform(post("/api/customers/" + id + "/interactions").contentType(JSON)
                        .content("{\"channel\":\"BRANCH\",\"kind\":\"PROPOSAL\",\"note\":\"提案\",\"staff\":\"rm1\"}"))
                .andExpect(status().isOk());
        long opp = body(mvc.perform(post("/api/customers/" + id + "/opportunities").contentType(JSON)
                        .content("{\"product\":\"FUND\",\"description\":\"提案\",\"owner\":\"rm1\"}"))
                .andExpect(status().isOk())).get("id").asLong();
        mvc.perform(post("/api/opportunities/" + opp + "/stage").param("stage", "PROPOSED"))
                .andExpect(jsonPath("$.stage").value("PROPOSED"));
        mvc.perform(get("/api/opportunities").param("owner", "rm1")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/customers").contentType(JSON).content("{\"customerType\":\"INDIVIDUAL\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/customers/999999")).andExpect(status().isNotFound());
    }

    @Test
    void securitiesTrading() throws Exception {
        mvc.perform(get("/api/securities")).andExpect(status().isOk());
        mvc.perform(post("/api/securities/trades").header("Idempotency-Key", "sec-1").contentType(JSON)
                        .content("{\"code\":\"STK-1001\",\"side\":\"BUY\",\"quantity\":10,\"price\":1000,\"trader\":\"d1\"}"))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));
        mvc.perform(put("/api/securities/STK-1001/price").contentType(JSON).content("{\"price\":1100}"))
                .andExpect(status().is2xxSuccessful());
        mvc.perform(get("/api/securities/trades").param("code", "STK-1001")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/securities/portfolio")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void cardsAndWallets() throws Exception {
        Account holder = openAccount("H", 50_000);
        Account shop = openAccount("Shop", 0);
        long merchant = body(mvc.perform(post("/api/merchants").contentType(JSON)
                        .content("{\"name\":\"店\",\"mcc\":\"5411\",\"accountId\":%d,\"feeRateBp\":300}".formatted(shop.id())))
                .andExpect(status().isCreated())).get("id").asLong();
        JsonNode card = body(mvc.perform(post("/api/cards").contentType(JSON)
                        .content("{\"accountId\":%d,\"dailyLimitMinor\":100000}".formatted(holder.id())))
                .andExpect(status().isCreated()));
        long auth = body(mvc.perform(post("/api/card-authorizations").contentType(JSON)
                        .content("{\"cardToken\":\"%s\",\"merchantId\":%d,\"amountMinor\":1000,\"externalRef\":\"P-1\"}"
                                .formatted(card.get("token").asText(), merchant)))
                .andExpect(jsonPath("$.status").value("AUTHORIZED"))).get("id").asLong();
        mvc.perform(post("/api/card-authorizations/" + auth + "/capture").header("Idempotency-Key", "cap-1")
                        .contentType(JSON).content("{\"amountMinor\":1000}"))
                .andExpect(status().isCreated());
        long wallet = body(mvc.perform(post("/api/wallets").param("accountId", String.valueOf(holder.id())))
                .andExpect(status().isCreated())).get("id").asLong();
        mvc.perform(post("/api/wallets/" + wallet + "/top-ups").header("Idempotency-Key", "top-1").contentType(JSON)
                        .content("{\"amountMinor\":2000}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/wallets/" + wallet + "/payments").header("Idempotency-Key", "pay-1").contentType(JSON)
                        .content("{\"merchantId\":%d,\"amountMinor\":500}".formatted(merchant)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.wallet.balanceMinor").value(1500));
        mvc.perform(post("/api/merchant-settlements")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/cards/" + card.get("id").asLong() + "/status").param("active", "false"))
                .andExpect(status().isOk());
    }

    @Test
    void amlAndRisk() throws Exception {
        Account a = openAccount("A", 0);
        movements.execute(MovementCommand.deposit(key(), a.id(), 3_000_000, "JPY", null, null));
        mvc.perform(post("/api/aml/scan")).andExpect(jsonPath("$.alertsCreated").value(1));
        long alert = body(mvc.perform(get("/api/aml/alerts").param("status", "OPEN"))
                .andExpect(jsonPath("$.length()").value(1))).get(0).get("id").asLong();
        mvc.perform(post("/api/aml/alerts/" + alert + "/investigation").contentType(JSON).content("{\"by\":\"c1\"}"))
                .andExpect(jsonPath("$.status").value("INVESTIGATING"));
        mvc.perform(post("/api/aml/alerts/" + alert + "/report").contentType(JSON)
                        .content("{\"by\":\"c1\",\"note\":\"届出\",\"freezeAccount\":true}"))
                .andExpect(jsonPath("$.status").value("REPORTED"));
        mvc.perform(post("/api/aml/alerts/" + alert + "/closure").contentType(JSON)
                        .content("{\"by\":\"c1\",\"note\":\"x\"}"))
                .andExpect(status().isConflict());

        mvc.perform(get("/api/risk/dashboard")).andExpect(status().isOk()).andExpect(jsonPath("$.credit").exists());
        mvc.perform(get("/api/risk/market")).andExpect(status().isOk());
        mvc.perform(get("/api/risk/liquidity")).andExpect(status().isOk()).andExpect(jsonPath("$.hqlaMinor").exists());
    }

    @Test
    void fundsAndTrusts() throws Exception {
        mvc.perform(get("/api/funds")).andExpect(status().isOk());
        Account a = openAccount("A", 100_000);
        mvc.perform(get("/api/accounts/" + a.id() + "/funds")).andExpect(jsonPath("$.length()").value(0));
        // 顧客に紐づいていない口座は投資商品を買えない
        mvc.perform(post("/api/fund-orders/subscriptions").header("Idempotency-Key", "f-1").contentType(JSON)
                        .content("{\"accountId\":%d,\"fundCode\":\"F-BOND\",\"amountMinor\":10000}".formatted(a.id())))
                .andExpect(status().isConflict());
        mvc.perform(get("/api/money-trusts/999999")).andExpect(status().isNotFound());
    }
}
