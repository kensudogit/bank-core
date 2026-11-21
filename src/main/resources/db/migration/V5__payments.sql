-- =====================================================================
-- V5: 為替業務（振替・口座振替・国内為替（全銀）・外国為替（両替・海外送金））
-- =====================================================================

-- ---------------------------------------------------------------------
-- 勘定科目
-- ---------------------------------------------------------------------
INSERT INTO gl_accounts (code, name, category) VALUES
    ('1100', '日銀預け金（為替決済）', 'ASSET'),
    ('1900', '外国為替ポジション', 'ASSET'),
    ('2400', '未決済為替借（仕向）', 'LIABILITY'),
    ('2410', '外国送金未決済', 'LIABILITY'),
    ('2500', '仮受金（被仕向の保留）', 'LIABILITY'),
    ('4200', '為替手数料', 'INCOME');

-- ---------------------------------------------------------------------
-- 取引種別の追加
-- ---------------------------------------------------------------------
ALTER TABLE transactions DROP CONSTRAINT transactions_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'REVERSAL', 'INTEREST',
                    'LOAN_DISBURSEMENT', 'LOAN_REPAYMENT', 'LOAN_PROVISION', 'LOAN_WRITE_OFF',
                    'DIRECT_DEBIT', 'REMITTANCE_OUT', 'REMITTANCE_IN', 'REMITTANCE_RETURN',
                    'CLEARING_SETTLEMENT', 'FX_EXCHANGE', 'FOREIGN_REMITTANCE', 'FOREIGN_REFUND'));

ALTER TABLE transactions DROP CONSTRAINT ck_transactions_shape;
ALTER TABLE transactions ADD CONSTRAINT ck_transactions_shape CHECK (
    (type = 'TRANSFER'   AND source_account_id IS NOT NULL AND target_account_id IS NOT NULL
                         AND source_account_id <> target_account_id)
 OR (type = 'DEPOSIT'    AND source_account_id IS NULL     AND target_account_id IS NOT NULL)
 OR (type = 'WITHDRAWAL' AND source_account_id IS NOT NULL AND target_account_id IS NULL)
 OR (type = 'INTEREST'   AND source_account_id IS NULL     AND target_account_id IS NOT NULL)
 OR (type = 'REVERSAL'   AND (source_account_id IS NOT NULL OR target_account_id IS NOT NULL)
                         AND source_account_id IS DISTINCT FROM target_account_id)
 OR (type = 'LOAN_DISBURSEMENT' AND source_account_id IS NULL     AND target_account_id IS NOT NULL)
 OR (type = 'LOAN_REPAYMENT'    AND source_account_id IS NOT NULL AND target_account_id IS NULL)
 OR (type IN ('LOAN_PROVISION', 'LOAN_WRITE_OFF') AND source_account_id IS NULL AND target_account_id IS NULL)
 -- 口座振替・両替：払う口座と受け取る口座（別の口座）
 OR (type IN ('DIRECT_DEBIT', 'FX_EXCHANGE') AND source_account_id IS NOT NULL AND target_account_id IS NOT NULL
                         AND source_account_id <> target_account_id)
 -- 仕向送金（国内・海外）：口座から引落し
 OR (type IN ('REMITTANCE_OUT', 'FOREIGN_REMITTANCE') AND source_account_id IS NOT NULL AND target_account_id IS NULL)
 -- 被仕向・返金：口座へ入金（保留中の被仕向や相手行への返却では口座なし）
 OR (type IN ('REMITTANCE_IN', 'REMITTANCE_RETURN', 'FOREIGN_REFUND') AND source_account_id IS NULL)
 -- 為替決済：預金は動かない
 OR (type = 'CLEARING_SETTLEMENT' AND source_account_id IS NULL AND target_account_id IS NULL)
);

-- ---------------------------------------------------------------------
-- 金融機関マスター（全銀の金融機関コード。デモ用の架空の銀行）
-- ---------------------------------------------------------------------
CREATE TABLE banks (
    bank_code  CHAR(4)     PRIMARY KEY CHECK (bank_code ~ '^[0-9]{4}$'),
    name       VARCHAR(50) NOT NULL,
    name_kana  VARCHAR(15) NOT NULL
);
INSERT INTO banks (bank_code, name, name_kana) VALUES
    ('0901', 'さくら台銀行', 'ｻｸﾗﾀﾞｲ'),
    ('0902', 'みなと信用金庫', 'ﾐﾅﾄｼﾝｷﾝ'),
    ('0903', 'あおば銀行', 'ｱｵﾊﾞ');

-- ---------------------------------------------------------------------
-- 国内為替（仕向・被仕向）と全銀データ
-- ---------------------------------------------------------------------
CREATE TABLE zengin_batches (
    id            BIGSERIAL   PRIMARY KEY,
    value_date    DATE        NOT NULL,
    record_count  INT         NOT NULL CHECK (record_count > 0),
    total_minor   BIGINT      NOT NULL CHECK (total_minor > 0),
    content       TEXT        NOT NULL,
    status        VARCHAR(10) NOT NULL CHECK (status IN ('SENT', 'SETTLED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    settled_at    TIMESTAMPTZ
);

CREATE TABLE domestic_remittances (
    id                 BIGSERIAL    PRIMARY KEY,
    direction          VARCHAR(3)   NOT NULL CHECK (direction IN ('OUT', 'IN')),
    account_id         BIGINT       REFERENCES accounts (id),
    bank_code          CHAR(4)      NOT NULL REFERENCES banks (bank_code),
    branch_code        CHAR(3)      NOT NULL,
    dest_account_type  CHAR(1)      NOT NULL CHECK (dest_account_type IN ('1', '2', '4')),
    dest_account_no    CHAR(7)      NOT NULL,
    dest_name_kana     VARCHAR(30)  NOT NULL,
    remitter_name      VARCHAR(40),
    amount_minor       BIGINT       NOT NULL CHECK (amount_minor > 0),
    fee_minor          BIGINT       NOT NULL DEFAULT 0 CHECK (fee_minor >= 0),
    status             VARCHAR(10)  NOT NULL
                       CHECK (status IN ('PENDING', 'SENT', 'SETTLED', 'CANCELLED', 'RETURNED', 'CREDITED', 'HELD')),
    value_date         DATE         NOT NULL,
    batch_id           BIGINT       REFERENCES zengin_batches (id),
    external_ref       VARCHAR(40),
    transaction_id     BIGINT       NOT NULL REFERENCES transactions (id),
    note               VARCHAR(200),
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT ck_remittance_direction CHECK (
        (direction = 'OUT' AND account_id IS NOT NULL AND status IN ('PENDING', 'SENT', 'SETTLED', 'CANCELLED', 'RETURNED'))
     OR (direction = 'IN' AND external_ref IS NOT NULL AND status IN ('CREDITED', 'HELD', 'RETURNED')))
);
CREATE INDEX ix_remittances_pending ON domestic_remittances (status) WHERE direction = 'OUT' AND status = 'PENDING';
CREATE INDEX ix_remittances_account ON domestic_remittances (account_id);
CREATE INDEX ix_remittances_batch ON domestic_remittances (batch_id);
-- 同じ仕向銀行・同じ参照番号の被仕向は 1 回だけ受け付ける
CREATE UNIQUE INDEX uq_remittances_incoming ON domestic_remittances (bank_code, external_ref) WHERE direction = 'IN';

-- ---------------------------------------------------------------------
-- 口座振替（収納企業・契約・請求）
-- ---------------------------------------------------------------------
CREATE TABLE billers (
    code        VARCHAR(8)   PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    -- 収納代金の入金先（当行にある収納企業の口座）
    account_id  BIGINT       NOT NULL REFERENCES accounts (id)
);

CREATE TABLE debit_mandates (
    id            BIGSERIAL   PRIMARY KEY,
    account_id    BIGINT      NOT NULL REFERENCES accounts (id),
    biller_code   VARCHAR(8)  NOT NULL REFERENCES billers (code),
    payer_number  VARCHAR(20) NOT NULL,
    status        VARCHAR(10) NOT NULL DEFAULT 'ACTIVE' CHECK (status IN ('ACTIVE', 'CANCELLED')),
    created_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT uq_mandates_payer UNIQUE (biller_code, payer_number)
);
CREATE INDEX ix_mandates_account ON debit_mandates (account_id);

CREATE TABLE debit_requests (
    id              BIGSERIAL   PRIMARY KEY,
    mandate_id      BIGINT      NOT NULL REFERENCES debit_mandates (id),
    amount_minor    BIGINT      NOT NULL CHECK (amount_minor > 0),
    due_date        DATE        NOT NULL,
    status          VARCHAR(10) NOT NULL DEFAULT 'PENDING' CHECK (status IN ('PENDING', 'PAID', 'FAILED')),
    -- 振替結果コード（全銀の口座振替結果に倣う）: 0 振替済 / 1 資金不足 / 2 取引なし（口座・契約が無効）
    result_code     CHAR(1)     CHECK (result_code IN ('0', '1', '2')),
    transaction_id  BIGINT      REFERENCES transactions (id),
    processed_at    TIMESTAMPTZ,
    -- 同じ契約・同じ引落日の請求は 1 回だけ（同じ請求データを 2 回取り込んでも二重に引き落とさない）
    CONSTRAINT uq_debit_requests UNIQUE (mandate_id, due_date)
);
CREATE INDEX ix_debit_requests_pending ON debit_requests (due_date) WHERE status = 'PENDING';

-- ---------------------------------------------------------------------
-- 外国為替：為替相場（仲値 TTM と片側スプレッド。TTS = TTM + スプレッド、TTB = TTM − スプレッド）
-- ---------------------------------------------------------------------
CREATE TABLE fx_rates (
    currency    CHAR(3)       PRIMARY KEY CHECK (currency ~ '^[A-Z]{3}$' AND currency <> 'JPY'),
    ttm         NUMERIC(12,4) NOT NULL CHECK (ttm > 0),
    spread      NUMERIC(12,4) NOT NULL CHECK (spread >= 0),
    updated_by  VARCHAR(50),
    updated_at  TIMESTAMPTZ   NOT NULL DEFAULT now(),
    CONSTRAINT ck_fx_spread CHECK (spread < ttm)
);
INSERT INTO fx_rates (currency, ttm, spread, updated_by) VALUES
    ('USD', 150.0000, 1.0000, 'seed'),
    ('EUR', 162.0000, 1.5000, 'seed');

-- 両替の約定（どの相場で何をいくら交換したか。外貨ポジションの集計にも使う）
CREATE TABLE fx_exchanges (
    transaction_id    BIGINT        PRIMARY KEY REFERENCES transactions (id),
    side              VARCHAR(4)    NOT NULL CHECK (side IN ('BUY', 'SELL')),   -- 顧客から見た外貨の売買
    from_account_id   BIGINT        NOT NULL REFERENCES accounts (id),
    to_account_id     BIGINT        NOT NULL REFERENCES accounts (id),
    currency          CHAR(3)       NOT NULL,
    foreign_minor     BIGINT        NOT NULL CHECK (foreign_minor > 0),
    yen_minor         BIGINT        NOT NULL CHECK (yen_minor > 0),
    rate              NUMERIC(12,4) NOT NULL,
    created_at        TIMESTAMPTZ   NOT NULL DEFAULT now()
);

-- 海外送金
CREATE TABLE foreign_remittances (
    id                  BIGSERIAL    PRIMARY KEY,
    account_id          BIGINT       NOT NULL REFERENCES accounts (id),
    fee_account_id      BIGINT       NOT NULL REFERENCES accounts (id),
    currency            CHAR(3)      NOT NULL,
    amount_minor        BIGINT       NOT NULL CHECK (amount_minor > 0),
    -- 送金元口座から引き落とした額（円口座なら TTS で換算した円、外貨口座なら同じ通貨の額）
    debit_minor         BIGINT       NOT NULL CHECK (debit_minor > 0),
    debit_currency      CHAR(3)      NOT NULL,
    applied_rate        NUMERIC(12,4),
    fee_minor           BIGINT       NOT NULL CHECK (fee_minor >= 0),
    beneficiary_name    VARCHAR(70)  NOT NULL,
    beneficiary_account VARCHAR(34)  NOT NULL,
    swift_bic           VARCHAR(11)  NOT NULL,
    country             CHAR(2)      NOT NULL,
    purpose             VARCHAR(70)  NOT NULL,
    status              VARCHAR(10)  NOT NULL CHECK (status IN ('SENT', 'HELD', 'REJECTED')),
    screening_result    VARCHAR(400),
    message             TEXT,
    transaction_id      BIGINT       NOT NULL REFERENCES transactions (id),
    decided_by          VARCHAR(50),
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
CREATE INDEX ix_foreign_remittances_account ON foreign_remittances (account_id);

-- 制裁リスト（デモ用の架空の名前）と高リスク国
CREATE TABLE sanctions_list (
    id         SERIAL       PRIMARY KEY,
    name       VARCHAR(100) NOT NULL,
    list_name  VARCHAR(40)  NOT NULL
);
INSERT INTO sanctions_list (name, list_name) VALUES
    ('IVAN PETROVICH DEMOSTEIN', 'DEMO-SDN'),
    ('GOLDEN HARBOR TRADING LLC', 'DEMO-SDN'),
    ('NORTHWIND ARMS EXPORT CO', 'DEMO-EU');

CREATE TABLE high_risk_countries (
    country  CHAR(2)     PRIMARY KEY,
    name     VARCHAR(40) NOT NULL
);
INSERT INTO high_risk_countries (country, name) VALUES ('KP', '北朝鮮'), ('IR', 'イラン'), ('MM', 'ミャンマー');
