package com.example.bankcore.common;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import java.time.LocalDate;

/**
 * 業務日付。暦日（システム時計）ではなく DB の business_calendar が正であり、
 * 日次締め（{@code DailyClosingService}）が成功したときだけ 1 日進む。
 */
@Component
public class BusinessCalendar {

    private final JdbcClient jdbc;

    public BusinessCalendar(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    public LocalDate today() {
        return jdbc.sql("SELECT current_business_date FROM business_calendar WHERE id = 1")
                .query(LocalDate.class)
                .single();
    }

    /**
     * 業務日付を from → from+1 に進める（比較して一致したときだけ更新する CAS）。
     * 同時に複数の締め処理が走っても日付は 1 日しか進まない。
     *
     * @return 進めた場合 true
     */
    public boolean advanceFrom(LocalDate from) {
        return jdbc.sql("""
                        UPDATE business_calendar
                           SET current_business_date = current_business_date + 1, updated_at = now()
                         WHERE id = 1 AND current_business_date = :from""")
                .param("from", from)
                .update() == 1;
    }
}
