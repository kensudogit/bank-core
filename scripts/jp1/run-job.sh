#!/bin/sh
# JP1/AJS などのジョブから bank-core のバッチジョブを起動し、その結果を終了コードで返す（Linux 用）。
#   usage: run-job.sh <daily-closing|closing-status|reconciliation> [operator]
# 終了コード: 0 正常 / 1 承認待ち / 4 照合不一致・差戻し / 8 異常終了・接続不可 / 12 起動条件の誤り
set -u
JOB="${1:?job name is required}"
OPERATOR="${2:-JP1}"
BASE_URL="${BANKCORE_URL:-http://localhost:18080}"

BODY=$(curl -sS -f -X POST --max-time 3600 "$BASE_URL/api/batch/jobs/$JOB?operator=$OPERATOR") || {
  echo "bank-core job '$JOB' could not be started" >&2
  exit 8
}
echo "$BODY"
CODE=$(printf '%s' "$BODY" | sed -n 's/.*"exitCode":\([0-9][0-9]*\).*/\1/p')
exit "${CODE:-8}"
