package com.example.bankcore.transaction;

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

/** 取引 API：入金・出金・照会・取消・冪等キーでの結果確認。 */
class TransactionControllerTest extends AbstractIntegrationTest {

    @Autowired MockMvc mvc;

    private static String cash(long account, long amount) {
        return "{\"accountId\":%d,\"amountMinor\":%d,\"currency\":\"JPY\"}".formatted(account, amount);
    }

    @Test
    void depositAndWithdrawal() throws Exception {
        Account a = openAccount("A", 0);
        mvc.perform(post("/api/deposits").header("Idempotency-Key", "dep-1")
                        .contentType(MediaType.APPLICATION_JSON).content(cash(a.id(), 1_000)))
                .andExpect(status().isCreated())
                .andExpect(header().string("Idempotent-Replayed", "false"))
                .andExpect(jsonPath("$.type").value("DEPOSIT"))
                .andExpect(jsonPath("$.requestHash").doesNotExist());
        mvc.perform(post("/api/withdrawals").header("Idempotency-Key", "wd-1")
                        .contentType(MediaType.APPLICATION_JSON).content(cash(a.id(), 5_000)))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.rejectReason").value("INSUFFICIENT_FUNDS"));
        mvc.perform(post("/api/withdrawals").header("Idempotency-Key", "wd-2")
                        .contentType(MediaType.APPLICATION_JSON).content(cash(a.id(), 400)))
                .andExpect(status().isCreated());
    }

    @Test
    void validationAndMissingKey() throws Exception {
        Account a = openAccount("A", 0);
        mvc.perform(post("/api/deposits").contentType(MediaType.APPLICATION_JSON).content(cash(a.id(), 1)))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/deposits").header("Idempotency-Key", "k")
                        .contentType(MediaType.APPLICATION_JSON).content(cash(a.id(), -1)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void detailReversalAndLookupByKey() throws Exception {
        Account a = openAccount("A", 1_000);
        Account b = openAccount("B", 0);
        long id = movements.execute(MovementCommand.transfer("tx-key", a.id(), b.id(), 100, "JPY", null, null))
                .transaction().id();

        mvc.perform(get("/api/transactions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.id").value(id))
                .andExpect(jsonPath("$.reversedByTransactionId").doesNotExist())
                .andExpect(jsonPath("$.journalLines.length()").value(2));

        mvc.perform(post("/api/transactions/" + id + "/reversal").header("Idempotency-Key", "rev-1"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reversesTransactionId").value(id));
        mvc.perform(get("/api/transactions/" + id))
                .andExpect(jsonPath("$.reversedByTransactionId").isNumber());

        mvc.perform(get("/api/transactions").param("idempotencyKey", "tx-key"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
        mvc.perform(get("/api/transactions").param("idempotencyKey", "missing")).andExpect(status().isNotFound());
        mvc.perform(get("/api/transactions/999999")).andExpect(status().isNotFound());
    }
}
