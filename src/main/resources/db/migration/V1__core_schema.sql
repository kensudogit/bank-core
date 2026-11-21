-- =====================================================================
-- bank-core スキーマ（教育用サンプル）
-- 金額はすべて BIGINT の最小通貨単位（JPY なら円、USD ならセント）で保持する。
-- =====================================================================

-- ---------------------------------------------------------------------
-- 預金口座（顧客から見た口座 = 銀行から見ると負債の補助元帳）
-- ---------------------------------------------------------------------
CREATE SEQUENCE account_number_seq START WITH 1000001;

CREATE TABLE accounts (
    id                    BIGSERIAL    PRIMARY KEY,
    account_number        VARCHAR(20)  NOT NULL UNIQUE,
    owner_name            VARCHAR(100) NOT NULL,
    currency              CHAR(3)      NOT NULL CHECK (currency ~ '^[A-Z]{3}$'),
    posted_balance_minor  BIGINT       NOT NULL DEFAULT 0,
    held_balance_minor    BIGINT       NOT NULL DEFAULT 0 CHECK (held_balance_minor >= 0),
    overdraft_limit_minor BIGINT       NOT NULL DEFAULT 0 CHECK (overdraft_limit_minor >= 0),
    status                VARCHAR(10)  NOT NULL DEFAULT 'ACTIVE'
                                       CHECK (status IN ('ACTIVE', 'FROZEN', 'CLOSED')),
    version               BIGINT       NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- 最後の砦：アプリのチェックをすり抜けても当座貸越枠を超える残高にはならない
    CONSTRAINT ck_accounts_overdraft CHECK (posted_balance_minor >= -overdraft_limit_minor)
);

-- ---------------------------------------------------------------------
-- 勘定科目（総勘定元帳）
-- ---------------------------------------------------------------------
CREATE TABLE gl_accounts (
    code     VARCHAR(10)  PRIMARY KEY,
    name     VARCHAR(100) NOT NULL,
    category VARCHAR(10)  NOT NULL CHECK (category IN ('ASSET', 'LIABILITY', 'EQUITY', 'INCOME', 'EXPENSE'))
);

INSERT INTO gl_accounts (code, name, category) VALUES
    ('1000', '現金', 'ASSET'),
    ('2000', '普通預金（顧客預り金）', 'LIABILITY');

-- ---------------------------------------------------------------------
-- 取引（振込・入金・出金）。冪等キーをここで一意にする。
-- ---------------------------------------------------------------------
CREATE TABLE transactions (
    id                 BIGSERIAL    PRIMARY KEY,
    idempotency_key    VARCHAR(100) NOT NULL UNIQUE,
    request_hash       CHAR(64)     NOT NULL,
    type               VARCHAR(20)  NOT NULL CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL')),
    source_account_id  BIGINT       REFERENCES accounts (id),
    target_account_id  BIGINT       REFERENCES accounts (id),
    amount_minor       BIGINT       NOT NULL CHECK (amount_minor > 0),
    currency           CHAR(3)      NOT NULL,
    memo               VARCHAR(200),
    status             VARCHAR(20)  NOT NULL CHECK (status IN ('PENDING', 'COMPLETED', 'REJECTED')),
    reject_reason      VARCHAR(50),
    correlation_id     VARCHAR(100),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at       TIMESTAMPTZ,
    CONSTRAINT ck_transactions_shape CHECK (
        (type = 'TRANSFER'   AND source_account_id IS NOT NULL AND target_account_id IS NOT NULL
                             AND source_account_id <> target_account_id)
     OR (type = 'DEPOSIT'    AND source_account_id IS NULL     AND target_account_id IS NOT NULL)
     OR (type = 'WITHDRAWAL' AND source_account_id IS NOT NULL AND target_account_id IS NULL)
    ),
    CONSTRAINT ck_transactions_reject CHECK ((status = 'REJECTED') = (reject_reason IS NOT NULL))
);

CREATE INDEX ix_transactions_source ON transactions (source_account_id);
CREATE INDEX ix_transactions_target ON transactions (target_account_id);

-- ---------------------------------------------------------------------
-- 仕訳（複式簿記）。1 取引につき最大 1 仕訳。
-- ---------------------------------------------------------------------
CREATE TABLE journal_entries (
    id             BIGSERIAL    PRIMARY KEY,
    transaction_id BIGINT       NOT NULL UNIQUE REFERENCES transactions (id),
    business_date  DATE         NOT NULL,
    description    VARCHAR(200) NOT NULL,
    posted_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE journal_lines (
    id               BIGSERIAL   PRIMARY KEY,
    journal_entry_id BIGINT      NOT NULL REFERENCES journal_entries (id),
    line_no          INT         NOT NULL,
    gl_code          VARCHAR(10) NOT NULL REFERENCES gl_accounts (code),
    account_id       BIGINT      REFERENCES accounts (id),
    debit_minor      BIGINT      NOT NULL DEFAULT 0 CHECK (debit_minor >= 0),
    credit_minor     BIGINT      NOT NULL DEFAULT 0 CHECK (credit_minor >= 0),
    currency         CHAR(3)     NOT NULL,
    -- 1 行は借方か貸方のどちらか一方だけ
    CONSTRAINT ck_journal_lines_one_side CHECK ((debit_minor > 0) <> (credit_minor > 0)),
    CONSTRAINT uq_journal_lines_no UNIQUE (journal_entry_id, line_no)
);

CREATE INDEX ix_journal_lines_account ON journal_lines (account_id);

-- 貸借一致をコミット時に DB でも検証する（遅延制約トリガー）。
-- アプリ側でも検証しているが、DB 制約は最後の安全網として機能する。
CREATE FUNCTION check_journal_balanced() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF EXISTS (
        SELECT 1
          FROM journal_lines
         WHERE journal_entry_id = NEW.journal_entry_id
         GROUP BY currency
        HAVING SUM(debit_minor) <> SUM(credit_minor)
    ) THEN
        RAISE EXCEPTION 'journal entry % is not balanced', NEW.journal_entry_id
              USING ERRCODE = '23514';
    END IF;
    RETURN NULL;
END;
$$;

CREATE CONSTRAINT TRIGGER trg_journal_lines_balanced
    AFTER INSERT ON journal_lines
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION check_journal_balanced();

-- 記帳済み仕訳は変更・削除禁止（訂正は反対仕訳で行う）
CREATE FUNCTION forbid_modification() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% is append-only; post a reversing entry instead', TG_TABLE_NAME
          USING ERRCODE = '42501';
END;
$$;

CREATE TRIGGER trg_journal_entries_immutable
    BEFORE UPDATE OR DELETE ON journal_entries
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

CREATE TRIGGER trg_journal_lines_immutable
    BEFORE UPDATE OR DELETE ON journal_lines
    FOR EACH ROW EXECUTE FUNCTION forbid_modification();

-- ---------------------------------------------------------------------
-- Transactional Outbox
-- ---------------------------------------------------------------------
CREATE TABLE outbox_events (
    id             BIGSERIAL    PRIMARY KEY,
    event_id       UUID         NOT NULL UNIQUE,
    aggregate_type VARCHAR(50)  NOT NULL,
    aggregate_id   VARCHAR(50)  NOT NULL,
    event_type     VARCHAR(50)  NOT NULL,
    payload        JSONB        NOT NULL,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at   TIMESTAMPTZ,
    attempts       INT          NOT NULL DEFAULT 0,
    last_error     TEXT
);

CREATE INDEX ix_outbox_unpublished ON outbox_events (id) WHERE published_at IS NULL;

-- 受信側の重複排除（Inbox）。at-least-once 配信を冪等に処理するため。
CREATE TABLE processed_events (
    consumer     VARCHAR(50) NOT NULL,
    event_id     UUID        NOT NULL,
    processed_at TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (consumer, event_id)
);
