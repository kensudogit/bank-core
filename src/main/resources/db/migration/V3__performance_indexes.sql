-- =====================================================================
-- V3: 性能向上のためのインデックス
-- =====================================================================

-- 日次締めは「その業務日付の利息がどの口座に計上済みか」を冪等キーの前方一致
-- （idempotency_key LIKE 'interest-2030-04-01-%'）で調べる。
-- 既存の一意インデックスは照合順序（ロケール）依存のため LIKE の前方一致に使えないので、
-- text_pattern_ops の部分インデックスを用意して取引テーブル全体の走査を避ける。
CREATE INDEX ix_transactions_interest_key
    ON transactions (idempotency_key text_pattern_ops)
    WHERE type = 'INTEREST';
