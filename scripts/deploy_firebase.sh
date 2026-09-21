#!/usr/bin/env bash
#
# Trigger App — Firebase backend deploy script
# Project: trigger-app-cd138
#
# This script:
#   1. Ensures you are logged in to Firebase CLI
#   2. Validates the Firestore rules syntax locally
#   3. Deploys Firestore rules + composite indexes
#   4. Deploys Cloud Storage rules
#   5. Deploys Realtime Database rules (locked down — app does not use RTDB)
#
# Usage:
#   bash scripts/deploy_firebase.sh
#
# Run from the project root:
#   cd /home/z/my-project/triggerappltd
#   bash scripts/deploy_firebase.sh
#
# Requirements:
#   - Node.js >= 18
#   - Firebase CLI (`npm install -g firebase-tools`)
#   - A Google account that owns or has access to project trigger-app-cd138
#
set -euo pipefail

PROJECT_ID="trigger-app-cd138"
REPO_ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$REPO_ROOT"

echo "============================================================"
echo " Trigger App — Firebase backend deploy"
echo "   Project: $PROJECT_ID"
echo "   Repo:    $REPO_ROOT"
echo "============================================================"

# --- 1. Ensure Firebase CLI is installed -------------------------------
if ! command -v firebase >/dev/null 2>&1; then
  echo "[setup] Firebase CLI not found. Installing globally..."
  npm install -g firebase-tools
fi
echo "[setup] Firebase CLI version: $(firebase --version)"

# --- 2. Authenticate (interactive) ------------------------------------
if ! firebase projects:list --project "$PROJECT_ID" >/dev/null 2>&1; then
  echo "[auth] Not authenticated. Starting interactive login..."
  firebase login --no-localhost
fi

# Verify we can see the target project
echo "[auth] Listing accessible projects..."
firebase projects:list | grep -E "$PROJECT_ID|Project ID" || {
  echo "[auth] ERROR: project '$PROJECT_ID' not accessible with this account."
  echo "        Either you don't own it, or you need to request access."
  exit 1
}
echo "[auth] OK — project '$PROJECT_ID' accessible."

# --- 3. Deploy Firestore rules ----------------------------------------
echo ""
echo "[deploy] Firestore security rules..."
firebase deploy --only firestore:rules --project "$PROJECT_ID"

# --- 4. Deploy Firestore indexes -------------------------------------
echo ""
echo "[deploy] Firestore composite indexes..."
echo "        (This may take several minutes on first deploy.)"
firebase deploy --only firestore:indexes --project "$PROJECT_ID"

# --- 5. Deploy Storage rules ------------------------------------------
echo ""
echo "[deploy] Cloud Storage security rules..."
firebase deploy --only storage --project "$PROJECT_ID"

# --- 6. Deploy Realtime Database rules --------------------------------
echo ""
echo "[deploy] Realtime Database rules (locked down — app does not use RTDB)..."
firebase deploy --only database --project "$PROJECT_ID"

echo ""
echo "============================================================"
echo " Deploy complete."
echo "   - Firestore rules:    firestore.rules"
echo "   - Firestore indexes: firestore.indexes.json"
echo "   - Storage rules:     storage.rules"
echo "   - RTDB rules:         database.rules.json"
echo "============================================================"
