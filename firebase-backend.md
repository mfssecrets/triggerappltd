# Trigger App — Firebase Backend

This directory configures the entire Firebase backend for the Trigger App
(`com.trigger.app`, Firebase project `trigger-app-cd138`).

All files are at the **repo root** (standard Firebase CLI convention):

```
/home/z/my-project/triggerappltd/
├── .firebaserc              # Project alias mapping (default = trigger-app-cd138)
├── firebase.json            # CLI configuration (rules/indexes/emulators)
├── firestore.rules          # Firestore security rules (covers all 9 paths)
├── firestore.indexes.json   # Composite indexes for chat_details + messages
├── storage.rules            # Cloud Storage security rules
├── database.rules.json      # RTDB rules — locked down (app does not use RTDB)
└── scripts/
    └── deploy_firebase.sh   # One-shot deploy script
```

## What was wired

### Firestore security rules (`firestore.rules`)

| Path | Read | Write | Notes |
|---|---|---|---|
| `users/{uid}` | signed-in | owner only (create/update/delete) | `uid`, `number`, `username` are immutable after create; username must match `^[a-z0-9_]{5,24}$` |
| `users/{uid}/blockedUsers/{blockedUid}` | owner | owner | Stores full `User` object of blocked user |
| `usernames/{username}` | signed-in | create-if-not-exists, delete-if-owner | `uid` field must equal caller; pattern `^[a-z0-9_]{5,24}$`. Atomic reservation now safe even outside a transaction |
| `chat_details/{chatID}` | participant only | participant only | Participants are `firstMiniUser.uid` or `secondMiniUser.uid` |
| `chats/{chatID}/messages/{messageID}` | participant only | participant only; delete sender-only | `senderID == uid()` enforced on create |
| `personalized_chats/FILLER/{uid}/{chatID}` | owner | owner, must be participant of chatID | The hard-coded `FILLER` doc is locked down |
| `story_details/{uid}` | signed-in | author only | — |
| `story/content/{uid}/{storyID}` | signed-in | author only | — |
| `story/viewers/{uid}/{viewerID}` | **denied** | **denied** | Currently unused (`watchStory` is TODO); loosen when implemented |
| `reports/{reportID}` | **admin only** | signed-in, `reporterID == uid()`, reason non-empty | Read via Cloud Functions / console |
| Anything else | **denied** | **denied** | Catch-all |

### Cloud Storage rules (`storage.rules`)

| Path | Read | Write | Size cap | Content type |
|---|---|---|---|---|
| `USERS/{uid}/profilePic` | signed-in | owner only | 5 MB | `image/*` |
| `CHATS/{chatID}/IMAGES/{file}` | chat participant | chat participant | 10 MB | `image/*` |
| `CHATS/{chatID}/AUDIO/{file}` | chat participant | chat participant | 5 MB | `audio/*` or `application/ogg` |
| `story/{uid}/{storyID}` | signed-in | author only | 10 MB | `image/*` |
| Anything else | **denied** | **denied** | — | — |

### Firestore composite indexes (`firestore.indexes.json`)

Indexes created (10 total) — derived from the actual queries in the Kotlin source:

- `chat_details` (6): `firstMiniUser.uid` + `unreadMessagesCount`, `secondMiniUser.uid` + `unreadMessagesCount`, both with `timeOfLastMessage DESC` for chat list ordering, and the (first, second) pair in both directions for the existing-chat lookup in `ContactsRepoImpl`.
- `messages` (4, collection-group): `messageStatus` + `timeSent DESC`, `senderID` + `timeSent DESC`, the triple `(messageStatus, senderID, timeSent DESC)`, and `(senderID, messageStatus)` for the unread / mark-as-opened queries in `UnreadMessagesRepoImpl` and `MessagesRepoImpl`.

### Realtime Database rules (`database.rules.json`)

```json
{ "rules": { ".read": false, ".write": false } }
```

The app does not link the `firebase-database` dependency and never reads or
writes RTDB. Locked down to deny all access. If you later wire up presence /
typing indicators via RTDB, replace this file with proper rules and deploy.

## Deploy

### Option A — one-shot script

```bash
cd /home/z/my-project/triggerappltd
bash scripts/deploy_firebase.sh
```

The script will:
1. Install Firebase CLI if missing
2. Trigger interactive login (`firebase login --no-localhost`) — opens a URL in
   your browser; paste back the auth code
3. Verify you can see project `trigger-app-cd138`
4. Deploy in order: Firestore rules → Firestore indexes → Storage rules → RTDB rules

### Option B — manual commands

```bash
# One-time auth (interactive)
firebase login

# Deploy each piece individually
firebase deploy --only firestore:rules   --project trigger-app-cd138
firebase deploy --only firestore:indexes  --project trigger-app-cd138
firebase deploy --only storage             --project trigger-app-cd138
firebase deploy --only database           --project trigger-app-cd138

# Or deploy everything at once
firebase deploy --project trigger-app-cd138
```

### Option C — CI / non-interactive

Generate a CI token from a machine that has `firebase login` working:

```bash
firebase login:ci   # prints a FIREBASE_TOKEN
```

Then in CI:

```bash
firebase deploy --project trigger-app-cd138 --token "$FIREBASE_TOKEN"
```

## Verify locally with the Emulator Suite

```bash
cd /home/z/my-project/triggerappltd
firebase emulators:start --only firestore,storage,auth
```

UI at http://localhost:4000. The emulator will load `firestore.rules` and
`storage.rules` so you can test access patterns without touching production.

## What this does NOT include

The following were intentionally **not** added in this pass:

1. **Cloud Functions** — none defined yet. Recommended follow-ups:
   - A `onDeleteUser` function that fully cleans up account data (Auth user,
     `usernames/{username}` reservation, `personalized_chats` entries on
     other users, `users/{uid}/blockedUsers` subcollection, Storage
     `USERS/{uid}/profilePic`, stories). The client-side
     `SettingsViewModel.deleteAccount` only deletes the Firestore user doc
     and disables chats.
   - A scheduled job to prune stories older than 24 h.
   - A trigger to write `ServerValue.timestamp()` instead of
     `System.currentTimeMillis()` for `Message.timeSent`,
     `Chat.timeOfLastMessage`, `Story.timeUploaded`, `reports.createdAt`.
     (Audit confirmed all timestamps today are client-side.)
2. **App Check** — not enabled. Recommended for production to lock down
   Storage and Firestore from non-app clients.
3. **Firestore rules unit tests** (`firestore.rules.test.js`) — can be added
   using `@firebase/rules-unit-testing-mjs` and the emulator. The current
   `firestore.rules` is comprehensive but not yet under test.
4. **Backup / restore** — no scheduled exports configured in this repo.

## Mapping from audit → rules

Each rule clause traces back to a concrete Kotlin source location. See the
header comments in `firestore.rules` and `storage.rules` for the per-path
file:line references. The full source audit is available in
`/home/z/my-project/worklog.md` (Task ID `firebase-audit`).
