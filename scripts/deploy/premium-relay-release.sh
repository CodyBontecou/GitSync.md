#!/usr/bin/env bash
# premium-relay-release.sh — verified release tooling for the Background Sync relay.
#
# Default mode is VERIFY ONLY: read-only wrangler queries plus a local deploy
# dry run. Nothing is deployed, migrated, or mutated.
#
#   ./premium-relay-release.sh            # verify only
#   ./premium-relay-release.sh --execute  # apply remote D1 migrations + deploy
#
# --execute refuses to run while any required secret is missing. Secret values
# are never read or printed; only names are checked.
#
# Companion steps that stay manual (see docs/premium-v1-release-runbook.md):
#   - GitHub App web configuration and installation
#   - App Store Connect products and privacy answers
#   - flipping FeatureFlags.gitSyncAssistEnabled
set -euo pipefail

WORKER_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/../../worker/premium-relay" && pwd)"
cd "$WORKER_DIR"

MODE=verify
if [ "${1:-}" = "--execute" ]; then
  MODE=execute
elif [ -n "${1:-}" ]; then
  echo "usage: $0 [--execute]" >&2
  exit 64
fi

REQUIRED_SECRETS=(
  GITHUB_APP_PRIVATE_KEY
  GITHUB_WEBHOOK_SECRET
  GITHUB_CLIENT_ID
  GITHUB_CLIENT_SECRET
  APNS_PRIVATE_KEY
)
RELAY_ORIGIN="https://gitsync-premium-relay.costream.workers.dev"

say() { printf '\n== %s ==\n' "$*"; }

if [ ! -x node_modules/.bin/wrangler ]; then
  say "Installing dependencies"
  npm ci
fi

say "Wrangler auth"
npx wrangler whoami >/dev/null
echo "authenticated"

say "Local deploy dry run"
npm run dry-run

say "Remote secrets (names only)"
wrangler secret list >/tmp/premium-relay-secrets.json
secret_names=$(python3 -c 'import json;print("\n".join(s["name"] for s in json.load(open("/tmp/premium-relay-secrets.json"))))')
missing=0
for s in "${REQUIRED_SECRETS[@]}"; do
  if grep -qx "$s" <<<"$secret_names"; then
    echo "  present  $s"
  else
    echo "  MISSING  $s"
    missing=$((missing + 1))
  fi
done
if [ "$missing" -ne 0 ] && [ "$MODE" = execute ]; then
  echo "refusing --execute: set missing secrets first, e.g. npx wrangler secret put GITHUB_CLIENT_ID" >&2
  exit 1
fi

say "Remote D1 migrations pending"
npx wrangler d1 migrations list premium-relay --remote

say "Current deployment (most recent)"
npx wrangler deployments list >/tmp/premium-relay-deployments.txt
sed -n '1,10p' /tmp/premium-relay-deployments.txt

say "Live worker liveness probe"
code=$(curl -s -o /dev/null -w '%{http_code}' "$RELAY_ORIGIN/")
echo "GET $RELAY_ORIGIN/ -> HTTP $code (expected 404 with JSON not-found)"

if [ "$MODE" = verify ]; then
  printf '\nVERIFY ONLY complete. Re-run with --execute to apply migrations and deploy.\n'
  exit 0
fi

say "Applying remote D1 migrations"
npm run migrate:remote

say "Deploying worker"
npx wrangler deploy

say "Post-deploy verification"
npx wrangler d1 migrations list premium-relay --remote
npx wrangler deployments list >/tmp/premium-relay-deployments-after.txt
sed -n '1,10p' /tmp/premium-relay-deployments-after.txt
code=$(curl -s -o /dev/null -w '%{http_code}' "$RELAY_ORIGIN/")
echo "GET $RELAY_ORIGIN/ -> HTTP $code"

printf '\nDeploy finished. Remaining gates are manual: GitHub App config/install,\nApp Store Connect products/privacy, physical-device matrix, then feature flag.\n'
