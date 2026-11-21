package com.example.bankcore.account;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 口座状態：DB の CHECK 制約と同じ 3 値であること（名前で保存するので綴りが変わると読めなくなる）。 */
class AccountStatusTest {

    @Test
    void hasExactlyTheStatusesStoredInTheDatabase() {
        assertThat(AccountStatus.values()).extracting(Enum::name).containsExactly("ACTIVE", "FROZEN", "CLOSED");
    }

    @Test
    void parsesStoredNames() {
        assertThat(AccountStatus.valueOf("FROZEN")).isEqualTo(AccountStatus.FROZEN);
        assertThatThrownBy(() -> AccountStatus.valueOf("active")).isInstanceOf(IllegalArgumentException.class);
    }
}
