-- =====================================================================
-- V8: 有価証券業務（市場系：国債・社債・株式の売買、利金・償還、時価評価）
--     あわせて、以降の業務（投資信託・信託・カード・電子決済）の取引種別もここで追加する
-- =====================================================================

ALTER TABLE transactions DROP CONSTRAINT transactions_type_check;
ALTER TABLE transactions ADD CONSTRAINT transactions_type_check
    CHECK (type IN ('TRANSFER', 'DEPOSIT', 'WITHDRAWAL', 'REVERSAL', 'INTEREST',
                    'LOAN_DISBURSEMENT', 'LOAN_REPAYMENT', 'LOAN_PROVISION', 'LOAN_WRITE_OFF',
                    'DIRECT_DEBIT', 'REMITTANCE_OUT', 'REMITTANCE_IN', 'REMITTANCE_RETURN',
                    'CLEARING_SETTLEMENT', 'FX_EXCHANGE', 'FOREIGN_REMITTANCE', 'FOREIGN_REFUND',
                    'SECURITIES_TRADE', 'SECURITIES_INCOME', 'SECURITIES_VALUATION',
                    'FUND_SUBSCRIPTION', 'FUND_REDEMPTION', 'FUND_SETTLEMENT', 'TRUST_ENTRUST', 'TRUST_PAYOUT',
                    'CARD_CAPTURE', 'MERCHANT_SETTLEMENT', 'WALLET_TOPUP', 'WALLET_PAYMENT'));

-- 新しい種別は業務サービスが口座の向きを決める（同じ口座同士の移動だけは禁止）
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
 OR (type IN ('DIRECT_DEBIT', 'FX_EXCHANGE') AND source_account_id IS NOT NULL AND target_account_id IS NOT NULL
                         AND source_account_id <> target_account_id)
 OR (type IN ('REMITTANCE_OUT', 'FOREIGN_REMITTANCE') AND source_account_id IS NOT NULL AND target_account_id IS NULL)
 OR (type IN ('REMITTANCE_IN', 'REMITTANCE_RETURN', 'FOREIGN_REFUND') AND source_account_id IS NULL)
 OR (type = 'CLEARING_SETTLEMENT' AND source_account_id IS NULL AND target_account_id IS NULL)
 OR (type IN ('SECURITIES_TRADE', 'SECURITIES_INCOME', 'SECURITIES_VALUATION', 'FUND_SUBSCRIPTION',
              'FUND_REDEMPTION', 'FUND_SETTLEMENT', 'TRUST_ENTRUST', 'TRUST_PAYOUT', 'CARD_CAPTURE',
              'MERCHANT_SETTLEMENT', 'WALLET_TOPUP', 'WALLET_PAYMENT')
     AND (source_account_id IS NULL OR target_account_id IS NULL OR source_account_id <> target_account_id))
);

INSERT INTO gl_accounts (code, name, category) VALUES
    ('1400', '有価証券', 'ASSET'),
    ('3100', 'その他有価証券評価差額金', 'EQUITY'),
    ('4300', '有価証券利息配当金', 'INCOME'),
    ('4310', '有価証券売却益', 'INCOME'),
    ('5300', '有価証券売却損', 'EXPENSE');

-- 銘柄マスター。債券の数量は額面（円）、株式の数量は株数。価格は債券が額面 100 円あたり、株式が 1 株あたり
CREATE TABLE securities (
    code             VARCHAR(12)  PRIMARY KEY,
    name             VARCHAR(100) NOT NULL,
    type             VARCHAR(15)  NOT NULL CHECK (type IN ('JGB', 'CORPORATE_BOND', 'STOCK')),
    currency         CHAR(3)      NOT NULL DEFAULT 'JPY' CHECK (currency = 'JPY'),
    coupon_rate_bp   INT          CHECK (coupon_rate_bp >= 0),
    maturity_date    DATE,
    -- 修正デュレーション（年。金利リスクの計測に使う）
    modified_duration NUMERIC(8,4),
    CONSTRAINT ck_securities_bond CHECK (
        (type = 'STOCK' AND coupon_rate_bp IS NULL AND maturity_date IS NULL)
     OR (type <> 'STOCK' AND coupon_rate_bp IS NOT NULL AND maturity_date IS NOT NULL))
);

CREATE TABLE security_prices (
    code        VARCHAR(12)   NOT NULL REFERENCES securities (code),
    price_date  DATE          NOT NULL,
    price       NUMERIC(14,4) NOT NULL CHECK (price > 0),
    PRIMARY KEY (code, price_date)
);

-- 保有残高（銘柄ごと）。簿価は移動平均法、時価評価差額は直近の評価額との差（洗替）
CREATE TABLE security_holdings (
    code                  VARCHAR(12) PRIMARY KEY REFERENCES securities (code),
    quantity              BIGINT      NOT NULL CHECK (quantity >= 0),
    book_value_minor      BIGINT      NOT NULL CHECK (book_value_minor >= 0),
    valuation_adjustment_minor BIGINT NOT NULL DEFAULT 0,
    last_valued_on        DATE,
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE security_trades (
    transaction_id      BIGINT        PRIMARY KEY REFERENCES transactions (id),
    code                VARCHAR(12)   NOT NULL REFERENCES securities (code),
    side                VARCHAR(6)    NOT NULL CHECK (side IN ('BUY', 'SELL', 'REDEEM')),
    quantity            BIGINT        NOT NULL CHECK (quantity > 0),
    price               NUMERIC(14,4) NOT NULL,
    principal_minor     BIGINT        NOT NULL,
    accrued_interest_minor BIGINT     NOT NULL DEFAULT 0,
    realized_pnl_minor  BIGINT        NOT NULL DEFAULT 0,
    trade_date          DATE          NOT NULL,
    trader              VARCHAR(50)   NOT NULL,
    created_at          TIMESTAMPTZ   NOT NULL DEFAULT now()
);
CREATE INDEX ix_security_trades_code ON security_trades (code, trade_date);

-- デモ用の銘柄と価格（架空）
INSERT INTO securities (code, name, type, coupon_rate_bp, maturity_date, modified_duration) VALUES
    ('JGB10-370', '利付国債（10年）第370回', 'JGB', 80, DATE '2033-03-20', 8.2000),
    ('JGB5-160', '利付国債（5年）第160回', 'JGB', 30, DATE '2028-09-20', 4.1000),
    ('CB-SAKURA', 'さくら台工業 第5回社債', 'CORPORATE_BOND', 120, DATE '2030-06-20', 4.6000),
    ('STK-1001', '架空ホールディングス', 'STOCK', NULL, NULL, NULL);
