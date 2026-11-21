-- =====================================================================
-- V9: 資産運用・信託関連（投資信託の販売・解約、金銭信託）
-- =====================================================================

INSERT INTO gl_accounts (code, name, category) VALUES
    ('2420', '投資信託 払込未済金', 'LIABILITY'),
    ('2600', '金銭信託 受託金（信託勘定）', 'LIABILITY'),
    ('4400', '投資信託 販売手数料', 'INCOME'),
    ('5400', '金銭信託 配当金', 'EXPENSE');

-- 投資信託の銘柄。基準価額は 1 万口あたり（円）
CREATE TABLE funds (
    code             VARCHAR(12)   PRIMARY KEY,
    name             VARCHAR(100)  NOT NULL,
    -- 商品のリスク水準（1 低 〜 5 高）。顧客のリスク許容度を超える商品は販売しない（適合性の原則）
    risk_level       INT           NOT NULL CHECK (risk_level BETWEEN 1 AND 5),
    nav              NUMERIC(12,2) NOT NULL CHECK (nav > 0),
    nav_date         DATE          NOT NULL,
    purchase_fee_bp  INT           NOT NULL CHECK (purchase_fee_bp BETWEEN 0 AND 500),
    trust_fee_bp     INT           NOT NULL CHECK (trust_fee_bp BETWEEN 0 AND 300),
    status           VARCHAR(10)   NOT NULL DEFAULT 'OPEN' CHECK (status IN ('OPEN', 'CLOSED'))
);
INSERT INTO funds (code, name, risk_level, nav, nav_date, purchase_fee_bp, trust_fee_bp) VALUES
    ('F-BOND', '国内債券インデックス・ファンド', 2, 10250.00, CURRENT_DATE, 0, 15),
    ('F-BAL', 'バランス・ファンド（安定型）', 3, 11800.00, CURRENT_DATE, 110, 60),
    ('F-EQ', '世界株式アクティブ・ファンド', 5, 18500.00, CURRENT_DATE, 330, 160);

-- 口座ごとの保有口数（顧客の資産。銀行の資産ではないので勘定には載らない）
CREATE TABLE fund_holdings (
    account_id   BIGINT       NOT NULL REFERENCES accounts (id),
    fund_code    VARCHAR(12)  NOT NULL REFERENCES funds (code),
    units        BIGINT       NOT NULL CHECK (units >= 0),
    cost_minor   BIGINT       NOT NULL CHECK (cost_minor >= 0),
    updated_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, fund_code)
);

CREATE TABLE fund_orders (
    transaction_id  BIGINT        PRIMARY KEY REFERENCES transactions (id),
    account_id      BIGINT        NOT NULL REFERENCES accounts (id),
    customer_id     BIGINT        NOT NULL REFERENCES customers (id),
    fund_code       VARCHAR(12)   NOT NULL REFERENCES funds (code),
    side            VARCHAR(10)   NOT NULL CHECK (side IN ('SUBSCRIBE', 'REDEEM')),
    amount_minor    BIGINT        NOT NULL CHECK (amount_minor > 0),
    units           BIGINT        NOT NULL CHECK (units > 0),
    nav             NUMERIC(12,2) NOT NULL,
    fee_minor       BIGINT        NOT NULL DEFAULT 0,
    -- 購入代金を受託銀行へ払い込んだか（払込未済金の明細）
    settled         BOOLEAN       NOT NULL DEFAULT false,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_fund_orders_unsettled ON fund_orders (fund_code) WHERE side = 'SUBSCRIBE' AND NOT settled;
CREATE INDEX ix_fund_orders_account ON fund_orders (account_id);

-- 金銭信託（顧客から金銭を預かり、信託財産として分別管理して運用し、満期に元本と配当を払い出す）
CREATE TABLE money_trusts (
    id                  BIGSERIAL   PRIMARY KEY,
    account_id          BIGINT      NOT NULL REFERENCES accounts (id),
    customer_id         BIGINT      NOT NULL REFERENCES customers (id),
    principal_minor     BIGINT      NOT NULL CHECK (principal_minor > 0),
    expected_rate_bp    INT         NOT NULL CHECK (expected_rate_bp BETWEEN 0 AND 1000),
    start_date          DATE        NOT NULL,
    maturity_date       DATE        NOT NULL,
    status              VARCHAR(10) NOT NULL CHECK (status IN ('ACTIVE', 'MATURED')),
    entrust_transaction_id BIGINT   NOT NULL REFERENCES transactions (id),
    payout_transaction_id  BIGINT   REFERENCES transactions (id),
    dividend_minor      BIGINT,
    CONSTRAINT ck_trust_term CHECK (maturity_date > start_date)
);
CREATE INDEX ix_money_trusts_maturity ON money_trusts (maturity_date) WHERE status = 'ACTIVE';
