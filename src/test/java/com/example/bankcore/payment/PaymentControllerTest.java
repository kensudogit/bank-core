package com.example.bankcore.payment;

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

/** 為替業務 API：振替・他行振込・全銀・口座振替・外国為替の HTTP 契約。 */
class PaymentControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    @Test
    void bookTransferAndDomesticRemittance() throws Exception {
        Account a = openAccount("Taro", 100_000);
        Account b = openAccount("Taro", 0);
        mvc.perform(post("/api/book-transfers").header("Idempotency-Key", "bt-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceAccountId\":%d,\"targetAccountId\":%d,\"amountMinor\":1000,\"currency\":\"JPY\"}"
                                .formatted(a.id(), b.id())))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"));

        mvc.perform(post("/api/domestic-remittances").header("Idempotency-Key", "dr-1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"accountId": %d, "bankCode": "0901", "branchCode": "123", "destAccountType": "1",
                                 "destAccountNo": "7654321", "destNameKana": "ﾔﾏﾀﾞ ﾊﾅｺ", "amountMinor": 5000}
                                """.formatted(a.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remittance.status").value("PENDING"));
        mvc.perform(get("/api/domestic-remittances").param("direction", "OUT"))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/zengin-batches")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.recordCount").value(1));
        mvc.perform(get("/api/zengin-batches")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/banks")).andExpect(status().isOk());
    }

    @Test
    void incomingRemittanceAndDirectDebits() throws Exception {
        Account hanako = openAccount("サトウ　ハナコ", 10_000);
        mvc.perform(post("/api/incoming-remittances").contentType(MediaType.APPLICATION_JSON).content("""
                        [{"bankCode": "0903", "externalRef": "R-1", "accountNumber": "%s", "nameKana": "ｻﾄｳ ﾊﾅｺ",
                          "remitterName": "X", "amountMinor": 1000}]""".formatted(hanako.accountNumber())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].remittance.status").value("CREDITED"));

        Account utility = openAccount("電力", 0);
        mvc.perform(post("/api/billers").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"code\":\"E1\",\"name\":\"電力\",\"accountId\":%d}".formatted(utility.id())))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/debit-mandates").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"accountId\":%d,\"billerCode\":\"E1\",\"payerNumber\":\"C1\"}".formatted(hanako.id())))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/debit-requests").contentType(MediaType.APPLICATION_JSON)
                        .content("[{\"billerCode\":\"E1\",\"payerNumber\":\"C1\",\"amountMinor\":500,\"dueDate\":\"2030-04-01\"}]"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].result").value("IMPORTED"));
        mvc.perform(post("/api/direct-debits/run")).andExpect(status().isOk()).andExpect(jsonPath("$.paid").value(1));
        mvc.perform(get("/api/debit-requests")).andExpect(jsonPath("$[0].status").value("PAID"));
        mvc.perform(get("/api/debit-mandates").param("accountId", String.valueOf(hanako.id())))
                .andExpect(jsonPath("$.length()").value(1));
        mvc.perform(get("/api/billers")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void forexEndpoints() throws Exception {
        Account yen = openAccount("Taro", 1_000_000);
        Account usd = accountService.open("Taro", "USD", 0);
        mvc.perform(get("/api/fx-rates")).andExpect(status().isOk());
        mvc.perform(post("/api/fx-exchanges").header("Idempotency-Key", "fx-1").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"foreignAmountMinor\":1000}"
                                .formatted(yen.id(), usd.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.exchange.side").value("BUY"));
        mvc.perform(get("/api/fx-exchanges")).andExpect(jsonPath("$.length()").value(1));
        mvc.perform(post("/api/foreign-remittances").header("Idempotency-Key", "fr-1")
                        .contentType(MediaType.APPLICATION_JSON).content("""
                                {"accountId": %d, "currency": "USD", "amountMinor": 1000, "beneficiaryName": "John Smith",
                                 "beneficiaryAccount": "US12345678901", "swiftBic": "BOFAUS3N", "country": "US", "purpose": "Gift"}
                                """.formatted(yen.id())))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.remittance.status").value("SENT"));
        mvc.perform(get("/api/foreign-remittances").param("status", "SENT")).andExpect(jsonPath("$.length()").value(1));
    }

    @Test
    void validationAndNotFound() throws Exception {
        mvc.perform(post("/api/book-transfers").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/domestic-remittances/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/zengin-batches/999999")).andExpect(status().isNotFound());
        mvc.perform(get("/api/foreign-remittances/999999")).andExpect(status().isNotFound());
        mvc.perform(post("/api/billers").contentType(MediaType.APPLICATION_JSON).content("{\"code\":\"\"}"))
                .andExpect(status().isBadRequest());
    }
}
