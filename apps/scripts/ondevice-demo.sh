#!/usr/bin/env bash
# One-command on-device demo: a local API + seeded family/hubs + the app on a
# physical Android device, so you can SEE the real Compose UI render with data.
#
# Distilled from the first hub-render on-device run (2026-06-24). The five things
# that bit us, now handled here so they don't bite next time:
#   1. PORT COLLISION — 8799 is squatted by other dev servers (workerd/wrangler).
#      → auto-pick a free port; the device still talks to :8799 via adb reverse.
#   2. LAN IP UNREACHABLE — wifi client-isolation / the mac firewall blocks
#      inbound. → use `adb reverse` over USB (device localhost:8799 → laptop),
#      never the LAN IP.
#   3. STALE SESSION — a prior install's saved session points at a dead API and
#      wedges on "Couldn't reach Dayfold". → `pm clear` before installing.
#   4. SIGN-IN PATH — "Continue with Google" needs real Firebase config; the
#      reliable dev path is "Continue with Apple" → AndroidFirebaseSignIn returns
#      null for non-google → the app falls back to /auth/dev-token. Seed the
#      matching dev identity (provider=dev, provider_uid=dev-user) so it lands on
#      a family that already has hubs.
#   5. RUNNING THE API — Node 24 runs the TypeScript entry directly (type
#      stripping); no build step. JDK 17 for the Gradle build.
#
# Usage:  apps/scripts/ondevice-demo.sh           # uses the first adb device
#         DEVICE=57091FDCH01331 apps/scripts/ondevice-demo.sh
# Teardown: apps/scripts/ondevice-demo.sh --down
set -euo pipefail
cd "$(dirname "$0")/.."                      # apps/
ROOT="$(cd .. && pwd)"
ADB="${ADB:-$HOME/Library/Android/sdk/platform-tools/adb}"
JAVA17="${JAVA17:-/opt/homebrew/Cellar/openjdk@17/17.0.18/libexec/openjdk.jdk/Contents/Home}"
DB=dayfold_dev
SECRET=devsecret
DEVICE="${DEVICE:-$($ADB devices | awk 'NR>1 && $2=="device"{print $1; exit}')}"
KEY=/tmp/dayfold-signing.json
LOG=/tmp/dayfold-api.log
PIDFILE=/tmp/dayfold-api.pid

down() {
  [ -f "$PIDFILE" ] && kill "$(cat "$PIDFILE")" 2>/dev/null && rm -f "$PIDFILE" && echo "stopped api"
  pkill -f "node api/src/server.ts" 2>/dev/null || true
  [ -n "${DEVICE:-}" ] && "$ADB" -s "$DEVICE" reverse --remove tcp:8799 2>/dev/null || true
  echo "demo torn down (dropdb $DB yourself if you want the data gone)"
}
[ "${1:-}" = "--down" ] && { down; exit 0; }

[ -n "$DEVICE" ] || { echo "no adb device — plug in the phone + enable USB debugging"; exit 1; }
echo "device: $DEVICE"

# 1. free port (avoid the 8799 squatters)
PORT=8788
while lsof -i :"$PORT" >/dev/null 2>&1; do PORT=$((PORT+1)); done
echo "api port: $PORT (device reaches it as :8799 via adb reverse)"

# 2. signing key (reuse if present)
[ -f "$KEY" ] || node -e "import('jose').then(async j=>{const k=await j.generateKeyPair('EdDSA',{crv:'Ed25519',extractable:true});const w=await j.exportJWK(k.privateKey);w.kid='k1';w.alg='EdDSA';require('fs').writeFileSync('$KEY',JSON.stringify(w))})"

# 3. fresh DB + migrations + seed (idempotent)
dropdb "$DB" 2>/dev/null || true; createdb "$DB"
for m in api/migrations/000[1-9]_*.sql; do psql -q -d "$DB" -f "$m"; done
psql -q -d "$DB" -f apps/scripts/ondevice-seed.sql 2>/dev/null || psql -q -d "$DB" -f scripts/ondevice-seed.sql
echo "seeded: $(psql -t -d "$DB" -c "select count(*) from hubs" | tr -d ' ') hubs"

# 4. start the API (Node 24 runs the .ts directly)
PORT=$PORT DATABASE_URL="postgres:///$DB" ENABLE_DEV_AUTH=1 DEV_AUTH_SECRET=$SECRET \
  AUTH_ISS="https://dayfold.dev/auth" AUTH_AUD="dayfold-api" AUTH_SIGNING_KEY="$(cat "$KEY")" \
  node api/src/server.ts > "$LOG" 2>&1 &
echo $! > "$PIDFILE"; sleep 2
curl -sf -m3 "http://localhost:$PORT/health" >/dev/null && echo "api up on :$PORT" || { echo "api failed — see $LOG"; exit 1; }

# 5. tunnel device:8799 → laptop:$PORT
"$ADB" -s "$DEVICE" reverse --remove tcp:8799 2>/dev/null || true
"$ADB" -s "$DEVICE" reverse tcp:8799 tcp:"$PORT"

# 6. build (LAN-free: app points at localhost:8799) + clean install + launch
JAVA_HOME="$JAVA17" DAYFOLD_API="http://localhost:8799" DEV_AUTH_SECRET=$SECRET \
  ./gradlew :androidApp:assembleDebug -q
"$ADB" -s "$DEVICE" shell pm clear com.sloopworks.dayfold >/dev/null   # drop any stale session
"$ADB" -s "$DEVICE" reverse tcp:8799 tcp:"$PORT"                        # pm clear can drop the reverse
"$ADB" -s "$DEVICE" install -r androidApp/build/outputs/apk/debug/dayfold-android-debug.apk >/dev/null
"$ADB" -s "$DEVICE" shell monkey -p com.sloopworks.dayfold -c android.intent.category.LAUNCHER 1 >/dev/null 2>&1

cat <<EOF

✅ ready on $DEVICE. On the phone:
   1. tap "Continue with Apple"   (the reliable dev-token sign-in — NOT Google)
   2. you'll land on Today; tap "Hubs" in the bottom nav
   → the seeded hubs render: "Maya's birthday party" (family) + "Dad's knee
     surgery" (restricted, with the 🔒 lock + the "Who can see" treatment).

api log: tail -f $LOG      teardown: apps/scripts/ondevice-demo.sh --down
EOF
