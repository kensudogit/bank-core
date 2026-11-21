# bank-core — 勘定系コアバンキング 教育用サンプル

Java 21 / Spring Boot 3.5 / PostgreSQL 16 / Docker Compose で作った、**学習用**の勘定系サンプルです。
預金・振込を中心に、融資・為替・有価証券・投資信託と信託・カードと電子決済・顧客管理・リスク管理・AML と、
照合と承認で統制する日次締め（JP1 などのジョブスケジューラ連携）を備えています。

> ⚠️ これは教育目的のリファレンス実装です。本番利用可能な銀行システムではなく、FISC 安全対策基準などの
> 規制・監査要件への適合を主張するものでもありません。認証・認可、監査ログ、暗号化、可用性設計などは対象外です。

## 実装している機能

| 機能 | 概要 |
|---|---|
| 預金口座管理 | 口座開設・照会・一覧・状態変更（ACTIVE / FROZEN / CLOSED）・入出金明細 |
| 入金・出金 | 現金入金（現金→口座）、現金出金（口座→現金） |
| 行内振込 | 口座 A → 口座 B の資金移動 |
| 複式簿記 | すべての資金移動で貸借一致した仕訳を記帳。DB トリガーでも貸借一致と変更禁止を強制 |
| 冪等性 | `Idempotency-Key` ヘッダーによる二重実行防止。再送時は最初の結果を返す |
| 同時実行制御 | 口座行の悲観ロックを **ID 昇順** で取得してデッドロックを回避 |
| Transactional Outbox | 業務データと同じトランザクションでイベントを記録し、別プロセスで配信 |
| 照合（リコンサイル） | 仕訳の貸借一致・口座残高と元帳の一致・試算表を検査 |
| 取消（反対仕訳） | 元の仕訳は変更せず、貸借を入れ替えた仕訳を新しい取引として記帳。二重取消は不可 |
| 日次締めバッチ | 利息計上・口座振替・融資の自動引落し・延滞管理・有価証券・信託・加盟店精算・AML 監視をステップとして実行し、**照合に合格し承認されたときだけ**業務日付を進める。途中で落ちても続きから再開し、二重計上しない |
| 運用統制 | ステップの実行記録・照合結果の保存・承認（実行者とは別の人）・差戻し・修復の記録・ステップの再実行指示・監査ログ。JP1 等向けの終了コード |
| 与信審査・融資 | 申込 → 担保・保証の登録 → スコアリング（返済比率・保全率・格付・推奨・提示金利）→ 職務分離の決裁 → 融資実行 |
| 貸付元帳・返済 | 元利均等・元金均等の返済予定表、遅延損害金 → 利息 → 元金の充当、繰上返済（返済額軽減型）、約定返済の自動引落し |
| 延滞・不良債権 | 延滞日数による債務者区分（正常先〜実質破綻先）、貸倒引当金の繰入・戻入、償却 |
| 信用リスク | EAD・PD・LGD による期待損失、不良債権比率、集中度（HHI）、ストレステスト |
| 為替業務 | 振替、他行宛振込（全銀フォーマット作成・決済・取消・組戻し）、被仕向（名義照合・保留）、口座振替、両替（TTS/TTB）、海外送金（制裁リスト照合・MT103） |
| 有価証券 | 国債・社債・株式の売買（経過利子・移動平均簿価・売却損益）、利金・償還、時価評価（評価差額金） |
| 投資信託・信託 | 投資信託の販売・解約（適合性の原則・KYC）、受託銀行への払込、金銭信託の受託と満期払出し |
| カード・電子決済 | デビットカードのオーソリ（拘束額）・売上確定・取消・日次限度額、電子マネーのチャージと支払、加盟店精算 |
| 顧客管理（CRM） | 顧客台帳・KYC・適合性情報・口座の紐づけ・接触履歴・営業案件・顧客 360° |
| 市場・流動性リスク | 有価証券と外貨ポジションの VaR・BPV・金利／為替ショック、LCR に倣った流動性指標とマチュリティ・ラダー |
| コンプライアンス（AML） | 取引・カード・海外送金の監視ルール、アラートの案件管理、疑わしい取引の届出（顧客リスクの引上げ・口座凍結） |

## 動かし方

### 必要なもの

- Docker Desktop（Compose v2）
- テストを実行する場合：JDK 21 以上（Maven はラッパー同梱のため不要）

### 起動（Docker Compose）

```powershell
docker compose up -d --build
```

- デモ画面: http://localhost:18080/ （口座開設・入出金・振込・明細・照合をブラウザから試せます）
- API: http://localhost:18080/api/...
- PostgreSQL: `localhost:15432`（DB / ユーザー / パスワード：すべて `bankcore`）

ホスト側ポートは他プロジェクトとの衝突を避けるため 18080 / 15432 にしています。変更する場合は
`docker-compose.yml` の `ports` を編集してください。

> Windows の Docker Desktop で `docker compose build` がビルダー関連のエラーになる場合は、
> 環境変数 `DOCKER_CONTEXT=desktop-linux` と `COMPOSE_BAKE=false` を設定してから再実行してください。

停止・データ削除：

```powershell
docker compose down -v
```

### テスト（JUnit 5 + Testcontainers）

Docker Desktop が起動している状態で：

```powershell
.\mvnw.cmd verify
```

（macOS / Linux / Git Bash では `./mvnw verify`）。テストは Testcontainers で `postgres:16-alpine` を起動し、
**実際の PostgreSQL** に対して実行します。インメモリ DB やモックは使いません。

## API の使い方（PowerShell の例）

```powershell
$api = "http://localhost:18080/api"
$json = @{ "Content-Type" = "application/json" }

# 口座開設
$alice = Invoke-RestMethod -Method Post "$api/accounts" -Headers $json -Body '{"ownerName":"Alice","currency":"JPY"}'
$bob   = Invoke-RestMethod -Method Post "$api/accounts" -Headers $json -Body '{"ownerName":"Bob","currency":"JPY"}'

# 現金入金（Idempotency-Key 必須）
Invoke-RestMethod -Method Post "$api/deposits" `
  -Headers @{ "Content-Type" = "application/json"; "Idempotency-Key" = "dep-001" } `
  -Body (@{ accountId = $alice.id; amountMinor = 10000; currency = "JPY" } | ConvertTo-Json)

# 振込
Invoke-RestMethod -Method Post "$api/transfers" `
  -Headers @{ "Content-Type" = "application/json"; "Idempotency-Key" = "trf-001" } `
  -Body (@{ sourceAccountId = $alice.id; targetAccountId = $bob.id; amountMinor = 3000; currency = "JPY" } | ConvertTo-Json)

# 照会・明細・照合
Invoke-RestMethod "$api/accounts/$($bob.id)"
Invoke-RestMethod "$api/accounts/$($alice.id)/statement"
Invoke-RestMethod "$api/reconciliation"
```

### エンドポイント一覧

| メソッド | パス | 説明 |
|---|---|---|
| POST | `/api/accounts` | 口座開設 `{ownerName, currency, overdraftLimitMinor?}` |
| GET | `/api/accounts` / `/api/accounts/{id}` | 口座一覧 / 照会（`availableBalanceMinor` を含む） |
| POST | `/api/accounts/{id}/status` | 状態変更 `{status: ACTIVE\|FROZEN\|CLOSED}` |
| GET | `/api/accounts/{id}/statement` | 入出金明細（仕訳から導出） |
| POST | `/api/deposits` | 現金入金 `{accountId, amountMinor, currency, memo?}` |
| POST | `/api/withdrawals` | 現金出金 `{accountId, amountMinor, currency, memo?}` |
| POST | `/api/transfers` | 行内振込 `{sourceAccountId, targetAccountId, amountMinor, currency, memo?}` |
| GET | `/api/transactions/{id}` | 取引と仕訳明細 |
| GET | `/api/transactions?idempotencyKey=...` | 冪等キーで結果を照会（応答を受け取れなかったとき用） |
| POST | `/api/transactions/{id}/reversal` | 取消（反対仕訳）。`Idempotency-Key` 必須 |
| GET | `/api/business-date` | 現在の業務日付 |
| POST | `/api/batch/daily-closing` | 日次締め（利息計上 → 業務日付 +1） |
| GET | `/api/batch/daily-closings` | 日次締めの実行履歴 |
| GET | `/api/reconciliation` | 照合レポート |
| GET | `/actuator/health` | ヘルスチェック |

資金移動 API のレスポンス：

| 状況 | HTTP | 備考 |
|---|---|---|
| 成功 | 201 | `status: COMPLETED` |
| 業務ルールで拒否（残高不足・凍結・通貨不一致） | 422 | `status: REJECTED` と `rejectReason`。**結果は記録され、同じキーの再送でも 422 が返る** |
| 入力不正（金額 ≤ 0、同一口座、ヘッダー欠落など） | 400 | 冪等レコードは作らない |
| 口座が存在しない | 404 | 冪等レコードは作らない |
| 同じキーで異なる内容 | 409 | |
| 取消済み・未完了・取消取引の取消 | 409 | 冪等キーは消費しない |
| ロックを取れなかった（リトライ後） | 503 | 同じキーで再送してよい |

再送で過去の結果を返した場合、レスポンスヘッダー `Idempotent-Replayed: true` が付きます。
`X-Correlation-Id` ヘッダーを付けると取引レコードとイベントに記録されます。

## 設計

### 構成

```
src/main/java/com/example/bankcore
├── account/      口座（Account, AccountRepository, AccountService, AccountController）
├── transaction/  資金移動（MoneyMovementService が中核、TransactionRules, MovementCommand）
├── ledger/       複式簿記（LedgerService, JournalRepository）と照合（ReconciliationService）
├── outbox/       Transactional Outbox（OutboxRepository, OutboxDispatcher, EventPublisher）
├── batch/        日次締め（DailyClosingService, BatchController）
└── common/       設定・例外・業務日付
src/main/resources/db/migration/V1__core_schema.sql   スキーマ（Flyway）
src/main/resources/db/migration/V2__reversal_and_daily_closing.sql   取消・利息・業務日付・日次締め
src/main/resources/db/migration/V3__performance_indexes.sql          性能向上のためのインデックス
src/main/resources/static/index.html                  デモ画面（API を呼ぶだけの静的ページ）
```

永続化は JPA ではなく Spring の `JdbcClient` で SQL を明示的に書いています。
ロックの種類や発行順序が勘定系では重要なので、それをコード上で読めるようにするためです。

### 金額の表現

金額はすべて `BIGINT` の**最小通貨単位**（JPY なら円、USD ならセント）で保持します。`float` / `double` は使いません。
Java 側の加減算は `Math.addExact` などでオーバーフローを検出します。

### テーブル

| テーブル | 役割 |
|---|---|
| `accounts` | 預金口座。記帳残高・拘束額・当座貸越枠・状態。`CHECK (posted_balance >= -overdraft_limit)` |
| `gl_accounts` | 勘定科目。`1000 現金（資産）`、`2000 普通預金（負債）` |
| `transactions` | 取引（振込・入金・出金）。`idempotency_key UNIQUE` と `request_hash` を持つ |
| `journal_entries` / `journal_lines` | 仕訳ヘッダー / 明細。1 取引につき最大 1 仕訳（`transaction_id UNIQUE`） |
| `outbox_events` | 未配信イベント。`published_at IS NULL` が配信待ち |
| `processed_events` | 受信側の重複排除（Inbox）。`(consumer, event_id)` が主キー |
| `business_calendar` | 現在の業務日付（1 行だけ）。日次締めでのみ進む |
| `daily_closings` | 日次締めの実行記録（RUNNING / COMPLETED、実行回数、最後のエラー） |

### 複式簿記

顧客の預金は銀行にとって**負債**です。負債は貸方で増え、借方で減ります。

| 取引 | 借方 | 貸方 |
|---|---|---|
| 振込 A→B | 普通預金（A） | 普通預金（B） |
| 現金入金 | 現金 | 普通預金（口座） |
| 現金出金 | 普通預金（口座） | 現金 |
| 利息計上 | 支払利息（費用） | 普通預金（口座） |
| 取消 | 元仕訳の貸方側 | 元仕訳の借方側 |

`journal_lines.account_id` が普通預金科目の補助元帳（どの口座か）を表します。

貸借一致は 3 段階で守ります。

1. `LedgerService.assertBalanced` がアプリ側で通貨ごとの借方合計＝貸方合計を検証
2. DB の**遅延制約トリガー**（`DEFERRABLE INITIALLY DEFERRED`）がコミット時に同じ検証を行う
3. `ReconciliationService` が事後に全仕訳と全口座を照合

記帳済み仕訳は `UPDATE` / `DELETE` をトリガーで禁止しています（訂正は反対仕訳で行う想定）。

### 資金移動の 1 トランザクション

`MoneyMovementService.execute` は 1 つの DB トランザクションで以下を行います。
途中で失敗するとすべてロールバックされ、残高・取引・仕訳・Outbox のどれも部分的に残りません。

```
0. 入力検証（400）                                  ← DB に触れない
1. 関係口座の存在確認（404）                         ← 1 文。冪等レコードは作らない
2. INSERT INTO transactions (... 'PENDING')
     ON CONFLICT (idempotency_key) DO NOTHING      ← 冪等キーの予約
   └ 既存あり → request_hash を比較し、同じなら既存結果を返す / 違えば 409
3. SELECT ... WHERE id IN (...) ORDER BY id
     FOR NO KEY UPDATE                             ← 関係口座を 1 文で ID 昇順にロック
4. ロック後の最新状態で業務ルール検証（状態・通貨・支払可能額）
   └ 違反 → UPDATE ... 'REJECTED' RETURNING ... でコミット（422）
5. 残高更新（複数口座を 1 文）→ 仕訳記帳（ヘッダ＋明細を 1 文）
   → UPDATE ... 'COMPLETED' RETURNING ... → outbox_events に追記
6. COMMIT
```

ロック待ちの上限 `lock_timeout` は、取引ごとに `SET` を送らず、接続プールの接続初期化 SQL
（`spring.datasource.hikari.connection-init-sql`）で接続単位に設定しています。

#### 性能上の工夫

同じ口座への取引は行ロックで直列化されるため、**ロックを持っている時間**がそのまま混雑時のスループットの上限になります。
そこでロック取得後の DB 往復をできるだけ減らしています。

| 改善 | 内容 |
|---|---|
| 口座ロックを 1 文に | `ORDER BY id ... FOR NO KEY UPDATE` はソート済みの行を上から順にロックするので、1 件ずつ昇順にロックするのと同じ順序（デッドロック回避の性質は同じ） |
| 残高更新を 1 文に | `UPDATE accounts ... FROM (VALUES (id, delta), ...)` |
| 仕訳を 1 文に | データ変更 CTE（`WITH entry AS (INSERT ... RETURNING id) INSERT INTO journal_lines ...`）。業務日付も同じ文の中で `business_calendar` から読む |
| 再読込をなくす | ステータス更新を `UPDATE ... RETURNING` にして結果をそのまま返す |
| `lock_timeout` を接続単位に | 取引ごとの `SET` を省く |
| Outbox の配信済み更新を 1 文に | `UPDATE ... WHERE id IN (...)`（行ロックはコミットまで保持しているので安全） |
| 日次締めの並行化 | 口座ごとに独立した取引なので `bank.closing.parallelism`（既定 8、接続プール 20 未満）本で並行計上。計上済み口座は 1 クエリでまとめて取得し、冪等キーの前方一致検索用に `text_pattern_ops` の部分インデックスを追加（V3） |
| 融資の自動引落しの絞り込みと並行化 | 期日到来・未引落しの融資を 1 本の SQL で絞ってから、**返済口座ごと**に並行実行（同じ口座の融資は 1 タスクで順に処理し、支払可能額を取り合わない） |
| AML 監視を集合指向に | ルールに該当し得ない取引（50 万円未満・外貨）は SQL で除外し、顧客リスクは JOIN で、カードの直近 10 分の件数・否決数は `LATERAL` で 1 本にまとめて取得。同じ（ルール, 参照）への INSERT は検査中に 1 回だけ |

振込 1 件あたりの DB 往復は約 17 回から 7 回（＋コミット）に、ロック保持中の往復は約 10 回から 4 回になりました。
`ThroughputBenchmarkTest`（Testcontainers 上の PostgreSQL、16 スレッド、各 8,000 件）での計測結果は次のとおりです。

| シナリオ | 改善前 | 改善後 |
|---|---|---|
| 振込（200 口座にばらける） | 861 件/秒（p50 16.3ms / p99 37.9ms） | 1,463 件/秒（p50 9.7ms / p99 24.6ms） |
| 振込（全件が同じ 2 口座＝ロック競合） | 114 件/秒（p50 48.6ms） | 200 件/秒（p50 14.2ms） |
| 日次締め（2,000 口座） | 14.0 秒（143 口座/秒） | 2.3 秒（884 口座/秒） |

開発 PC 上の相対比較であり、本番性能の見積もりではありません。次のコマンドで再計測できます（通常の `verify` では実行されません）。

```powershell
.\mvnw.cmd test "-Dtest=ThroughputBenchmarkTest" "-Dbench=true" "-Dsurefire.failIfNoSpecifiedTests=false"
```

業務機能を実用的な件数（口座 2,000・融資 300・振込 30,000・カードのオーソリ 4,000）で計測する
`OperationsBenchmarkTest` の結果は次のとおりです。

| 処理 | 改善前 | 改善後 |
|---|---|---|
| AML 監視（取引 32,000 件・オーソリ 4,000 件） | 25.6 秒 | **1.7 秒** |
| 日次締め 合計（利息 2,000 口座・融資 300 件の約定返済日） | 10.5 秒 | **5.0 秒** |
| └ 利息計上 | 3.2 秒 | 1.9 秒 |
| └ 融資の自動引落し | 4.4 秒 | 2.1 秒 |
| 振込 / カードのオーソリ / 融資の申込〜実行 | 1,392 / 727 / 168 件/秒 | 1,554 / 761 / 216 件/秒（変更なし、誤差の範囲） |
| 照合 / リスク管理ダッシュボード / 顧客 360°×50 | 104 / 74 / 398 ms | 88 / 82 / 515 ms（変更なし、誤差の範囲） |

ステップごとの時間は DB の `now()` で記録するため、Docker Desktop のコンテナ時計の揺れ（1 秒前後）の影響を受けます。
合計はアプリ側で計測しています。

```powershell
.\mvnw.cmd test "-Dtest=OperationsBenchmarkTest" "-Dbench=true" "-Dsurefire.failIfNoSpecifiedTests=false"
```

コミット時の WAL 同期（`synchronous_commit`）を緩めればさらに速くなりますが、コミット済み取引が障害時に失われうるため勘定系では変更していません。

#### 冪等性

- 冪等キーは `transactions.idempotency_key` の一意制約で守ります。
- `INSERT ... ON CONFLICT DO NOTHING` は、同じキーを挿入した**未コミット**のトランザクションがあると、その結果が確定するまで待ちます。
  そのため同じキーの同時リクエストはここで直列化され、後続は確定済みの結果を受け取ります。
  先行がロールバックした場合は後続が予約に成功し、通常どおり処理します。
- キーと一緒にリクエスト内容の SHA-256（`request_hash`）を保存し、同じキーで内容が異なれば 409 にします。
- 業務ルール違反（残高不足など）も「拒否という結果」として記録するため、同じキーで再送すると、その間に入金があっても同じ 422 を返します。
  新しい振込をしたい場合は新しいキーを使います。
- タイムアウトなどで結果が分からなくなったクライアントは、**新しいキーで再送せず**、`GET /api/transactions?idempotencyKey=...` で結果を確認するか、同じキーで再送します。

#### 同時実行制御とデッドロック回避

- 口座行は**常に ID の昇順**でロックします。A→B と B→A の振込が同時に来ても、どちらも小さい ID から順にロックを取るため、待ちが循環しません。
- 残高チェックはロック取得**後**の最新値で行うため、同時に出金しても残高を超えて引き出せません（いわゆる lost update / 二重引き落としの防止）。
- ロック種別は `FOR UPDATE` ではなく **`FOR NO KEY UPDATE`** です。
  `transactions` や `journal_lines` から `accounts` への外部キー検査は、参照先の口座行に `FOR KEY SHARE` ロックを取ります。
  `FOR UPDATE` はこれと競合するため、「2 つのトランザクションがどちらも FK 検査で KEY SHARE を持ったまま、互いの FOR UPDATE を待つ」デッドロックが
  ID 昇順ロックでも起きます（本サンプルの開発中、同時実行テストで実際に `deadlock detected` が発生しました）。
  キー列を変えない残高更新には `FOR NO KEY UPDATE` で十分で、口座更新同士は排他されつつ FK 検査とは競合しません。
- ロック待ちは `lock_timeout`（既定 5 秒）で打ち切り、ジッター付きの指数バックオフで最大 3 回リトライします。
  冪等キーがあるため、再実行しても二重計上にはなりません。
- ロックを保持したまま外部サービスを呼びません（イベント送信は Outbox 経由でコミット後に行う）。

### 取消（反対仕訳）

記帳済みの仕訳は変更・削除できないため、誤りの訂正は**反対仕訳**で行います。
`POST /api/transactions/{id}/reversal` は元取引の仕訳明細の借方と貸方を入れ替えた仕訳を、`REVERSAL` 型の新しい取引として記帳します。
口座残高は仕訳の顧客預金行から更新するので、仕訳と残高が食い違うことはありません。

- 取消も通常の資金移動と同じ流れ（冪等キー → 行ロック → ルール検証 → 記帳 → Outbox）を通ります。
- 取消対象の元取引行を `FOR NO KEY UPDATE` でロックしてから「既に取り消されていないか」を確認するため、別々のキーで同時に取り消しても成功するのは 1 件だけです。
  さらに `reverses_transaction_id` に `status = 'COMPLETED'` の部分一意インデックスを張り、DB でも二重取消を防いでいます。
- 振込の取消で、振込先がすでにお金を使っていて残高が足りない場合は `REJECTED / INSUFFICIENT_FUNDS`（422）になります。
  入金後に**新しいキー**で取り消し直せます（部分一意インデックスは REJECTED を対象外にしているため）。
- 取消できないケース（未完了・拒否済み・取消済み・取消取引そのもの）は 409 で、冪等キーは消費しません。

### 業務日付と日次締め（運用統制）

業務日付（勘定日付）は暦日ではなく `business_calendar` テーブルの値が正で、仕訳の `business_date` にはこの値が入ります
（`GET /api/business-date` はシステム日付と業務日付を両方返します）。**「バッチが正常終了したこと」と「勘定が正しいこと」は別の条件**として扱い、
日次締めは次の順で確定します（`DailyClosingService`）。

| # | ステップ | 内容（すべて決定的な冪等キーで 1 件 = 1 コミット） |
|---|---|---|
| 1 | `INTEREST_POSTING` | 普通預金利息（`interest-<日付>-<口座>`） |
| 2 | `DIRECT_DEBIT` | 引落日の来た口座振替（`debit-<請求>`） |
| 3 | `LOAN_AUTO_DEBIT` | 融資の約定返済の自動引落し（`loan-autodebit-<日付>-<融資>`） |
| 4 | `LOAN_MONITORING` | 延滞判定・債務者区分・貸倒引当金（`provision-<日付>-<融資>`） |
| 5 | `SECURITIES_INCOME` | 有価証券の利金・償還（`coupon-<銘柄>-<日付>`・`redeem-<銘柄>`） |
| 6 | `SECURITIES_VALUATION` | 有価証券の時価評価（`valuation-<銘柄>-<日付>`） |
| 7 | `TRUST_PAYOUT` | 金銭信託の満期払出し（`trust-payout-<信託>`） |
| 8 | `MERCHANT_SETTLEMENT` | 加盟店への売上精算 |
| 9 | `AML_SCAN` | AML・不正取引の監視（カーソル行のロックで二重検査しない） |
| 10 | `RECONCILIATION` | 照合：仕訳の貸借・預金残高と元帳・試算表・融資の補助元帳・経過勘定（為替・有価証券・投信・信託・加盟店・電子マネー・カードの拘束額）・**取引件数**（完了取引と仕訳が 1 対 1、未完了取引なし） |

- 照合が不一致なら `RECON_FAILED` で止め、業務日付は進めません（終了コード 4）。取消（反対仕訳）などの通常の手続きで修復し、
  `POST /api/batch/daily-closings/{date}/repairs` で記録してから再実行すると、**完了済みのステップは飛ばし**て照合からやり直します。
- 照合に合格すると `AWAITING_APPROVAL`。**締めの実行者とは別の人**が `POST /api/batch/daily-closings/{date}/approval` で承認すると、
  直前にもう一度照合したうえで、締めの確定と業務日付の更新（比較更新）を 1 トランザクションで行います。差戻しは `/rejection`。
- ステップが例外で止まった場合は `RUNNING` のまま `last_error` とステップの `FAILED` を残します（終了コード 8）。再実行で失敗したステップから再開します。
  完了済みのステップを明示的にやり直す場合は `/steps/{step}/reset`（各ステップは冪等なので二重計上しません）。
- 実行・承認・差戻し・修復・ステップの再実行指示は `closing_audit`（追記のみ）に記録します。状態は `GET /api/batch/daily-closings/{date}`。
- 承認の要否は `bank.closing.approval-required`（Docker 起動時の既定は true、環境変数 `CLOSING_APPROVAL_REQUIRED`）。

#### JP1 などのジョブスケジューラとの連携

| 終了コード | 意味 |
|---|---|
| 0 | 正常（照合合格。締めは承認待ちまたは完了） |
| 1 | 承認待ち（`closing-status` のみ。一定間隔で再判定して待ち合わせる） |
| 4 | 照合不一致・差戻し（要修復） |
| 8 | ステップの異常終了（再実行で続きから再開）／API に接続できない |
| 12 | 起動条件の誤り（不明なジョブ名・締めの記録なし） |

ジョブは `daily-closing`・`closing-status`・`reconciliation` の 3 つです。起動方法は 2 通りあります。

```powershell
# 1) 稼働中のアプリの API を呼ぶ（応答の exitCode をそのまま終了コードにする）
powershell -NoProfile -ExecutionPolicy Bypass -File scripts\jp1\run-job.ps1 -Job daily-closing -Operator JP1
```

```powershell
# 2) アプリをジョブとして起動し、終了コードを返して終わる
java -jar target\bank-core-0.1.0-SNAPSHOT.jar --spring.main.web-application-type=none --bank.outbox.scheduler-enabled=false --bank.job.name=daily-closing --bank.job.operator=JP1
```

Linux では `scripts/jp1/run-job.sh daily-closing JP1`。

教育用の簡略化として、利息は締め開始時点の残高で計算します。実システムでは残高確定（最終残高のスナップショット）、
利息の支払サイクル、源泉税などを扱い、大量件数では Spring Batch 等でチャンク処理とジョブ管理を行うのが一般的です。

### 融資（与信審査・貸付元帳・延滞管理・信用リスク）

`/api/loan-applications`（申込・担保・保証・審査・決裁・実行）、`/api/loans`（照会・返済・償却・貸付元帳）、`/api/loans/monitoring`、
`/api/credit-risk`、`/api/loan-simulations`（返済シミュレーション）。

- 仕訳：実行 借方 貸出金（融資を補助科目）／貸方 顧客預金、返済 借方 顧客預金／貸方 貸出金・貸出金利息・遅延損害金、
  引当 借方 貸倒引当金繰入額／貸方 貸倒引当金、償却 借方 貸倒引当金・貸倒損失／貸方 貸出金。
- ロック順は「申込・融資の行 → 預金口座の行」。振込は口座だけをロックするのでデッドロックしません。
- 照合で、融資ごとの貸出金残高・貸倒引当金が仕訳と一致することを確かめます。融資の取引は資金移動 API の取消の対象外です。

### 為替業務

`/api/book-transfers`（振替）、`/api/domestic-remittances`（他行宛振込・取消・組戻し）、`/api/zengin-batches`（全銀データ作成・決済）、
`/api/incoming-remittances`（被仕向の取込・保留の解消）、`/api/billers`・`/api/debit-mandates`・`/api/debit-requests`・`/api/direct-debits/run`（口座振替）、
`/api/fx-rates`・`/api/fx-exchanges`（両替）、`/api/foreign-remittances`（海外送金・保留の判断）。

- 他行宛振込は 未決済為替借 → 全銀決済で 日銀預け金、被仕向の名義不一致は 仮受金 で保留します。
- 両替は 外国為替ポジション 科目を介して通貨ごとに貸借を一致させます。外貨は小数 2 桁（セント）、円換算は顧客に払う側を切り捨て・受け取る側を切り上げ。

### 有価証券・投資信託と信託・カードと電子決済・CRM・リスク管理・AML

| 業務 | API | 主な仕訳・ルール |
|---|---|---|
| 有価証券 | `/api/securities`・`/trades`・`/portfolio`・`/{code}/price` | 購入 借方 有価証券・有価証券利息（経過利子）／貸方 日銀預け金。簿価は移動平均、評価差額は その他有価証券評価差額金 |
| 投資信託・信託 | `/api/funds`・`/api/fund-orders/*`・`/api/money-trusts` | 購入 借方 顧客預金／貸方 投信払込未済金・販売手数料。商品のリスク水準 ≦ 顧客のリスク許容度、KYC 済みのみ |
| カード・電子決済 | `/api/cards`・`/api/card-authorizations`・`/api/wallets`・`/api/merchant-settlements` | オーソリは口座の拘束額で押さえる。売上確定 借方 顧客預金／貸方 加盟店未払金、精算で手数料控除後を加盟店口座へ |
| CRM | `/api/customers`（`/{id}` は顧客 360°）・`/api/opportunities` | 顧客区分は年収・法人区分から自動設定。口座は 1 顧客にだけ紐づけ可能 |
| リスク管理 | `/api/risk/dashboard`・`/market`・`/liquidity` | VaR = 2.326 × 日次ボラティリティ × 時価、BPV、外貨の未カバー持ち高、LCR に倣った指標 |
| AML | `/api/aml/scan`・`/api/aml/alerts`・`/{id}/investigation`・`/closure`・`/report` | 大口現金・分割入金・通過口座・高リスク顧客・制裁照合の保留・カードの短時間多発。届出で顧客リスク HIGH、口座凍結も可 |

### Transactional Outbox

- 資金移動トランザクションの中で `outbox_events` にイベント（`TransferCompleted` など）を INSERT します。
  業務データとイベントが同じトランザクションでコミットされるため、「残高は動いたのにイベントが出ない」「イベントは出たのに残高が戻った」は起きません。
- `OutboxScheduler` が 1 秒ごとに `OutboxDispatcher.dispatchBatch()` を呼び、
  `SELECT ... FOR UPDATE SKIP LOCKED` で未配信イベントを取って `EventPublisher` に渡し、`published_at` を記録します。
  `SKIP LOCKED` により、アプリを複数台動かしても同じイベントを同時に配信しません。
- 送信に失敗したイベントは `attempts` と `last_error` を更新して残り、次回再送されます。
- 配信は **at-least-once** です。送信成功後・コミット前に落ちると同じイベントが再送されるため、
  受信側は `event_id` で重複排除します（`ProcessedEventRepository` が Inbox パターンの例）。
- 既定の `EventPublisher` はログ出力のみ（`LoggingEventPublisher`）。Kafka や MQ に送る場合はこのインターフェースを実装して差し替えます。
- Kafka 等への配信成功を「記帳成功の証拠」として扱ってはいけません。正は常に DB の仕訳です。

## テスト

`.\mvnw.cmd verify` で 94 件のテストが定義されています（92 件を実行。性能計測用の `ThroughputBenchmarkTest` と
`OperationsBenchmarkTest` は `-Dbench=true` を付けたときだけ実行）。DB を使うテストはすべて Testcontainers の実 PostgreSQL に対して実行します。

| テストクラス | 内容 |
|---|---|
| `TransactionRulesTest` | 業務ルール（残高不足・拘束額・貸越枠・凍結・通貨不一致）、入力検証、リクエストハッシュ |
| `LedgerServiceTest` | 仕訳の貸借一致検証（通貨別）、片側記入の検証 |
| `TransferIntegrationTest` | 振込成功時に残高・取引・仕訳・Outbox が 1 件ずつ揃う／拒否時は何も変わらない／障害注入で全ロールバック／入出金の仕訳 |
| `IdempotencyIntegrationTest` | 同一キー同一内容→1 回だけ実行／異なる内容→409／拒否結果も再送で同じ／キーで結果照会／**同一キー 20 並列でも 1 回だけ実行** |
| `ConcurrencyIntegrationTest` | **残高 10,000 に 1,000 × 30 並列 → ちょうど 10 件成功・残高 0**／**逆方向・循環振込 60 並列でデッドロックなし**／他トランザクションのロック解放待ち／全接続に `lock_timeout` が設定されている |
| `LedgerIntegrationTest` | DB トリガーが貸借不一致の仕訳をコミット時に拒否／記帳済み仕訳の変更・削除を拒否／照合が残高改ざんを検出 |
| `OutboxIntegrationTest` | 配信と `published_at` 記録／ブローカー障害時の再送／クラッシュ後の重複配信を受信側で排除／**4 並列ディスパッチャで重複配信なし** |
| `ReversalIntegrationTest` | 振込・入金・出金の取消で残高が戻り、仕訳が貸借反転／同一キー再送は再生・別キーの二重取消は 409／拒否・取消取引は取消不可／残高不足で 422 → 入金後に再取消可／**別キー 10 並列の取消で成功は 1 件だけ** |
| `DailyClosingIntegrationTest` | 利息の切り捨て計算／利息計上・業務日付での記帳・日付更新／翌日分だけ処理／**並行計上中に障害注入で途中失敗 → 再実行で残りだけ計上（二重計上なし）**／**締め 4 並列でも計上 1 回・日付 +1 のみ**／取引は現在の業務日付で記帳 |
| `TransferApiTest` | HTTP ステータス（201 / 400 / 404 / 409 / 422）、`Idempotent-Replayed` ヘッダー、明細・照合 API、ルートのデモ画面 |
| `ClosingControlIntegrationTest` | **承認は実行者以外**／**照合不一致で日付を進めない → 修復・再実行で照合だけやり直す**／差戻し → 再実行／**ステップ障害から完了済みステップを飛ばして再開**／融資の自動引落しが 1 回だけ／ジョブ API の終了コード |
| `LoanMathTest`・`CreditScoringTest` | 元利均等（年金現価式・端数調整）・元金均等・遅延損害金・債務者区分、スコアリングと形式要件、返済の充当順、期待損失 |
| `LendingIntegrationTest` | 審査 → 職務分離の決裁 → 実行の仕訳・再送／繰上返済と再計算・完済／**同時返済で残高超過しない**／延滞で区分と引当が 1 日 1 回／償却の仕訳／日次締めとの連携／信用リスク |
| `PaymentIntegrationTest` | 振替の名義確認／他行振込・全銀ファイル（120 桁固定長）・決済・取消・組戻し／被仕向の二重入金防止と保留／口座振替／両替の TTS・TTB／海外送金の制裁照合・謝絶返金 |
| `SecuritiesIntegrationTest` | 経過利子・時価評価・売却損益・利金（1 回だけ）・償還 |
| `WealthIntegrationTest` | 適合性の原則・KYC、手数料と口数、解約・払込、金銭信託の満期払出し（1 回だけ） |
| `CardsIntegrationTest` | オーソリの拘束・再送・日次限度額・否決、売上確定・取消、電子マネー、加盟店精算（1 回だけ） |
| `CrmIntegrationTest` | 顧客登録・KYC・口座の紐づけ・案件の進捗・顧客 360° |
| `AmlAndRiskIntegrationTest` | 監視ルールが 1 回ずつ発報・届出で顧客リスク HIGH と口座凍結、市場リスク（VaR・外貨持ち高）と流動性 |

## このサンプルで扱っていないこと

- 認証・認可（操作者・承認者は API の入力値で受け取るだけで、本人確認はしていません）、個人情報のマスキング
- 全銀システム・SWIFT・カードブランド・受託銀行との実際の接続（ファイル・電文の作成と状態遷移まで）。外国送金未決済のコルレス決済
- 利息の支払サイクル・源泉税、休日カレンダー、融資の未収利息の日次計上（利息は約定返済日に収益化）
- 規制上の計算（自己資本比率・LCR・自己査定の正式な基準）や、統計モデルの検証を伴う与信・AML のモデル
- 振替・両替の「同一名義」は口座名義の文字列で判定しています（CRM の顧客と紐づけた判定には未対応）
- 高可用性・DR、本格的な性能試験（負荷試験ツールによる長時間・大量データの計測）

## 主な技術要素

Java 21 / Spring Boot 3.5.7（Web, Validation, JDBC, Actuator）/ Flyway / PostgreSQL 16 / JUnit 5 / AssertJ / Testcontainers / Docker Compose
