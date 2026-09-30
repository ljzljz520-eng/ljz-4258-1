#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
docker compose up -d db
trap 'docker compose down' EXIT
for _ in $(seq 1 30); do
  if docker compose exec -T db pg_isready -U milk -d milk_receipt >/dev/null 2>&1; then break; fi
  sleep 1
done
docker compose exec -T db psql -U milk -d postgres -c "DROP DATABASE IF EXISTS milk_receipt_test;" -c "CREATE DATABASE milk_receipt_test;"
if [ ! -d .venv ]; then python3 -m venv .venv; fi
# shellcheck disable=SC1091
source .venv/bin/activate
pip install -r backend/requirements.txt
cd backend
DATABASE_URL="postgresql+psycopg://milk:milk@localhost:5432/milk_receipt_test" pytest -q
