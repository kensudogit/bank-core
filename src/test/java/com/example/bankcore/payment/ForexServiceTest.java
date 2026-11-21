package com.example.bankcore.payment;

import com.example.bankcore.account.Account;
import com.example.bankcore.common.Exceptions.InvalidRequestException;
import com.example.bankcore.common.Exceptions.NotFoundException;
import com.example.bankcore.payment.ForexService.ForeignOrder;
import com.example.bankcore.payment.ForexService.ScreeningResult;
import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 外国為替：相場表・両替・海外送金の照合と照会。 */
class ForexServiceTest extends AbstractIntegrationTest {

    @Autowired ForexService forex;

    @Test
    void ratesHaveTtsAboveTtmAboveTtb() {
        assertThat(forex.rates()).isNotEmpty().allSatisfy(r -> {
            assertThat(r.tts()).isGreaterThan(r.ttm());
            assertThat(r.ttm()).isGreaterThan(r.ttb());
            assertThat(r.tts().subtract(r.ttm())).isEqualByComparingTo(r.spread());
        });
        assertThat(forex.rate("USD").currency()).isEqualTo("USD");
        assertThatThrownBy(() -> forex.rate("XXX")).isInstanceOf(NotFoundException.class);
    }

    @Test
    void screeningDetectsSanctionsAndHighRiskCountries() {
        ScreeningResult clean = forex.screen("John Smith", "US");
        assertThat(clean.hit()).isFalse();
        ScreeningResult sanctioned = forex.screen("Ivan Petrovich Demostein", "GB");
        assertThat(sanctioned.hit()).isTrue();
        assertThat(sanctioned.rule()).isEqualTo("SANCTIONS");
        assertThat(sanctioned.score()).isGreaterThanOrEqualTo(85);
        ScreeningResult country = forex.screen("Kim Lee", "KP");
        assertThat(country.hit()).isTrue();
        assertThat(country.rule()).isEqualTo("HIGH_RISK_COUNTRY");
    }

    @Test
    void exchangeIsListedAndValidated() {
        Account yen = openAccount("Taro", 1_000_000);
        Account usd = accountService.open("Taro", "USD", 0);
        forex.exchange(yen.id(), usd.id(), 10_000, key(), null);
        assertThat(forex.exchanges()).singleElement().satisfies(e -> {
            assertThat(e.side()).isEqualTo("BUY");
            assertThat(e.currency()).isEqualTo("USD");
            assertThat(e.foreignMinor()).isEqualTo(10_000);
        });
        assertThatThrownBy(() -> forex.exchange(yen.id(), usd.id(), 0, key(), null))
                .isInstanceOf(InvalidRequestException.class);
    }

    @Test
    void foreignRemittanceIsRecordedAndFilterable() {
        Account a = openAccount("Taro", 1_000_000);
        var sent = forex.sendForeign(new ForeignOrder(a.id(), null, "USD", 10_000, "John Smith", "US123", "BOFAUS3N",
                "US", "Gift"), key(), null).remittance();
        assertThat(sent.status()).isEqualTo("SENT");
        assertThat(sent.debitCurrency()).isEqualTo("JPY");
        assertThat(forex.foreignRemittances("SENT")).hasSize(1);
        assertThat(forex.foreignRemittances("HELD")).isEmpty();
        assertThat(forex.foreignRemittances(null)).hasSize(1);
        assertThat(forex.foreign(sent.id()).swiftBic()).isEqualTo("BOFAUS3N");
        assertThatThrownBy(() -> forex.foreign(999_999)).isInstanceOf(NotFoundException.class);
    }

    @Test
    void updateRateRecalculatesTtsAndTtb() {
        try {
            var r = forex.updateRate("USD", new BigDecimal("140.0000"), new BigDecimal("1.0000"), "dealer1");
            assertThat(r.tts()).isEqualByComparingTo("141");
            assertThat(r.ttb()).isEqualByComparingTo("139");
            assertThat(r.updatedBy()).isEqualTo("dealer1");
        } finally {
            forex.updateRate("USD", new BigDecimal("150.0000"), new BigDecimal("1.0000"), "seed");   // 他のテストのために戻す
        }
    }
}
