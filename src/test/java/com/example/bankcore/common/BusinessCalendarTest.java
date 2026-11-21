package com.example.bankcore.common;

import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import static org.assertj.core.api.Assertions.assertThat;

/** 業務日付：照会と、期待する日付からの 1 日だけの前進（楽観的な条件付き更新）。 */
class BusinessCalendarTest extends AbstractIntegrationTest {

    @Autowired BusinessCalendar calendar;

    @Test
    void todayIsTheStoredBusinessDate() {
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE);
    }

    @Test
    void advanceMovesOneDayOnlyFromTheExpectedDate() {
        assertThat(calendar.advanceFrom(INITIAL_BUSINESS_DATE)).isTrue();
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE.plusDays(1));
        // 同じ日付から 2 回目に進めようとしても進まない（二重の日付更新を防ぐ）
        assertThat(calendar.advanceFrom(INITIAL_BUSINESS_DATE)).isFalse();
        assertThat(calendar.today()).isEqualTo(INITIAL_BUSINESS_DATE.plusDays(1));
    }
}
