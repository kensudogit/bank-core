-- =====================================================================
-- V2: 取消（反対仕訳）・利息計上・業務日付・日次締め
-- =====================================================================

-- ---------------------------------------------------------------------
-- 取引種別の追加（REVERSAL = 取消、INTEREST = 利息計上）
-- ---------------------------------------------------------------------
ALTER TABLE transactions DROP CONSTRAINT transactions_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'REVERSAL', 'INTEREST'));

-- 取消対象の元取引
ALTER TABLE transactions ADD COLUMN reverses_transaction_id BIGINT REFERENCES transactions (id);

ALTER TABLE transactions DROP CONSTRAINT ck_transactions_shape;
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_shape CHECK (
    (type = 'TRANSFER'   AND source_account_id IS NOT NULL AND target_account_id IS NOT NULL
                         AND source_account_id <> target_account_id)
 OR (type = 'DEPOSIT'    AND source_account_id IS NULL     AND target_account_id IS NOT NULL)
 OR (type = 'WITHDRAWAL' AND source_account_id IS NOT NULL AND target_account_id IS NULL)
 OR (type = 'INTEREST'   AND source_account_id IS NULL     AND target_account_id IS NOT NULL)
 OR (type = 'REVERSAL'   AND (source_account_id IS NOT NULL OR target_account_id IS NOT NULL)
                         AND source_account_id IS DISTINCT FROM target_account_id)
);
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_reversal_ref
    CHECK ((type = 'REVERSAL') = (reverses_transaction_id IS NOT NULL));

-- 1 つの元取引に対して有効な（COMPLETED の）取消は 1 件だけ。
-- アプリは元取引の行ロックで直列化しているが、これは最後の安全網。
-- 資金不足で REJECTED になった取消は対象外なので、入金後に別キーで取消し直せる。
CREATE UNIQUE INDEX uq_transactions_one_reversal
    ON transactions (reverses_transaction_id) WHERE status = 'COMPLETED';

-- ---------------------------------------------------------------------
-- 勘定科目の追加
-- ---------------------------------------------------------------------
INSERT INTO gl_accounts (code, name, category) VALUES ('5000', '支払利息', 'EXPENSE');

-- ---------------------------------------------------------------------
-- 業務日付（1 行だけのテーブル）。日次締めで 1 日ずつ進める。
-- ---------------------------------------------------------------------
CREATE TABLE business_calendar (
    id                    SMALLINT    PRIMARY KEY DEFAULT 1 CHECK (id = 1),
    current_business_date DATE        NOT NULL,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);
INSERT INTO business_calendar (current_business_date) VALUES (CURRENT_DATE);

-- ---------------------------------------------------------------------
-- 日次締めの実行記録
-- ---------------------------------------------------------------------
CREATE TABLE daily_closings (
    business_date     DATE        PRIMARY KEY,
    status            VARCHAR(10) NOT NULL CHECK (status IN ('RUNNING', 'COMPLETED')),
    started_at        TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at      TIMESTAMPTZ,
    run_count         INT         NOT NULL DEFAULT 1,
    interest_postings INT         NOT NULL DEFAULT 0,
    last_error        TEXT
);
