package com.example.bankcore.ledger;

import com.example.bankcore.support.AbstractIntegrationTest;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 勘定科目コード：定数がすべて勘定科目マスターに存在し、区分が科目の性質と合っていること。 */
class GlCodeTest extends AbstractIntegrationTest {

    private static List<String> codes() throws IllegalAccessException {
        List<String> codes = new ArrayList<>();
        for (Field f : GlCode.class.getDeclaredFields()) {
            if (Modifier.isStatic(f.getModifiers()) && f.getType() == String.class) {
                codes.add((String) f.get(null));
            }
        }
        return codes;
    }

    @Test
    void everyConstantExistsInMaster() throws Exception {
        List<String> codes = codes();
        assertThat(codes).hasSizeGreaterThanOrEqualTo(9).doesNotHaveDuplicates();
        for (String code : codes) {
            assertThat(jdbc.sql("SELECT count(*) FROM gl_accounts WHERE code = :c").param("c", code)
                    .query(Integer.class).single()).as(code).isEqualTo(1);
        }
    }

    @Test
    void categoriesMatchTheChartOfAccounts() {
        assertThat(category(GlCode.CASH)).isEqualTo("ASSET");
        assertThat(category(GlCode.LOANS_RECEIVABLE)).isEqualTo("ASSET");
        assertThat(category(GlCode.CUSTOMER_DEPOSITS)).isEqualTo("LIABILITY");
        assertThat(category(GlCode.LOAN_INTEREST_INCOME)).isEqualTo("INCOME");
        assertThat(category(GlCode.LATE_FEE_INCOME)).isEqualTo("INCOME");
        assertThat(category(GlCode.INTEREST_EXPENSE)).isEqualTo("EXPENSE");
        assertThat(category(GlCode.PROVISION_EXPENSE)).isEqualTo("EXPENSE");
        assertThat(category(GlCode.LOAN_LOSS_EXPENSE)).isEqualTo("EXPENSE");
    }

    private String category(String code) {
        return jdbc.sql("SELECT category FROM gl_accounts WHERE code = :c").param("c", code).query(String.class).single();
    }
}
