# Zigner signer security posture

Status: review of the current source tree. This note is deliberately narrower
than the handoff: it records what the code does, what the handoff gets wrong,
and what must be true before a key is allowed to sign for a live validator.

The short version: the vote lane has Noise XX and a high-water mark, but this is
not yet safe for a live validator key. The KMS import path can send the
consensus secret to an unauthenticated BLE responder, the phone pins the first
KMS it meets, and the high-water mark fails open on a partial write. Keep the
first product cut on an isolated node with a throwaway key.

## 1. What the stack is

There are two keys, not one:

- The phone holds an Ed25519 consensus key. This is the key the KMS uses for
  CometBFT votes and proposals.
- The phone also holds a Terp secp256k1 account seed phrase. This is the key
  that signs the `cw-orch-granter:v1` challenge and the authz/feegrant QR.

The KMS can hold a validator key file, or an ephemeral drill key. The laptop
console is a local web page plus a BLE scanner. The phone is the GATT server.

### Three lanes

| Lane | Path | Key | State |
| --- | --- | --- | --- |
| Consensus | KMS `hrsPV` -> `Link.Sign` -> BLE `SIGN` (`0x54`, Noise) -> phone inner `0x10` -> Ed25519 -> `HighWater` -> `0x55` | Ed25519 consensus | Phone `HighWater` per key; KMS `FilePV` floor |
| Granter | Phone -> `GRANTER` characteristic `...0005` -> KMS `applyGranter` | Terp account | No signature; read gated by Noise session |
| Cosmos authz | KMS `takeoff` or cw-orch -> QR `[0x53,0x05,0x10]...` -> phone seed phrase -> secp256k1 -> `ur:cworch-sig` | Terp secp256k1 seed | Independent of the vote lane |

The lanes are not ordered and do not share a lock. A granter change does not
rotate the consensus key. A vote session does not stop a granter change. That
is acceptable only while each lane has its own authorization rule.

## 2. Corrections to the handoff

| Handoff claim | What the tree does | Why it matters |
| --- | --- | --- |
| The phone writes the consensus key as plaintext PKCS8 and does not use Android Keystore. | The normal key path seals `validator-keys/<id>/pkcs8` with AES-GCM under the AndroidKeyStore alias `zigner.validator.atrest`. The legacy import path still writes plaintext `filesDir/kms-ed25519.pkcs8`; `migrateLegacy` copies it into a key folder and does not delete the original. `readPkcs8` seals it only when the key is next read. | The primary path is better than the handoff says. The legacy path is a real plaintext leak, and the Keystore key has no user-authentication requirement. |
| There is no Noise, every central can sign, and there is no phone high-water mark. | `KmsGattServer.kt` runs Noise XX and `HighWater.kt`. `VoteGate.kt` rejects cleartext `0x10` and `0x30`; votes are sealed `0x54`. Status is a separate `STATUS` characteristic `...0006`, gated by `sessionReady`. | The central cannot sign just by connecting. The remaining failures are identity pinning, HWM durability, and import authentication. |
| Status is cleartext to anyone. | The status frame is not sealed, but the characteristic write is accepted only when that device has a completed Noise session. | It leaks to the paired laptop, not to every nearby device. It is still a metadata leak and has no rate limit. |
| Import is the stronger transport because it is Noise XX bound to both statics. | `SendKey` starts Noise XX with the KMS static, but the KMS never compares the responder static to the phone public key. `AcceptReply` accepts any responder. The 4-byte pairing code is only a selector. | An attacker-controlled BLE peripheral that advertises the chosen code can complete the handshake and receive the consensus secret. This is the most serious finding. |
| The export screen prints a sealed string and shows no fingerprint. | `BluetoothSignerScreen.kt` shows a QR only. It displays the expected KMS fingerprint, requires the user to type the page fingerprint, then `Check fingerprint`, then `Seal this key`. | The manual check exists, but it is not bound to the BLE peer and there is no second approver or vault unlock. A user copying the phone's displayed value defeats it. |
| Recovered 64-byte secrets and `ephemeral.bin` are plaintext at rest. | `internal/uiserver/server.go` seals recovered blobs with `SealBlob` under `AtRestKey`, checks `bytes.Contains(blob, secret)`, and writes mode `0600` under `/tmp/kms-ble/recovered`. `ephemeral.bin` is sealed too. `AtRestKey` is a local file under the user's application-support directory. | The handoff wording is too strong, but the design is still unsafe: a local-file key protects a secret sitting in `/tmp`, and the recovered file is never unlinked. |
| The live node is `/tmp/zcinti-live` on RPC `27657`. | `cmd/kms/console.go` defaults to chain `morocco-1`, node `http://127.0.0.1:26757`, dial `tcp://127.0.0.1:27159`, state `/tmp/kms-ble-console/state.json`. | The handoff's paths and port do not match the defaults. Verify the actual flags before relying on the runtime description. |
| The pairing code stays accepted until reject or scanner stop. | The phone stores the code in the `remote_signer` prefs. The KMS writes the chosen code to `/tmp/kms-ble/chosen` and `applySession` reloads it after a restart. | The code persists across restarts. It is not authentication, and it should rotate per pairing window. |
| A granter change clears prepared takeoff auth. | The file-driven `applyGranter` clears `prepared`. The HTTP `SetGranter` path used by `/api/takeoff` sets the granter but does not clear `prepared`. | Narrow, but do not rely on the statement for the HTTP path. |
| The historical create-validator transaction and validator address/height are established facts. | The fee constants and guards are in the tree. The live address, height, tx hash `4E2E...7270`, and installed debug build are not source facts. | Treat them as operator-reported until reproduced on an isolated node. |

## 3. P0: do not use a live validator key

### P0.1 KMS import sends the consensus secret to an unauthenticated responder

Evidence:

- `signing/bluetooth/link.go`: `SendKey` -> `BeginImport(static)` ->
  `Exchange` -> `AcceptReply` -> `SealKey`.
- `signing/bluetooth/import.go`: `AcceptReply` reads message 2 and returns
  message 3; it does not compare the responder static to a known phone key.
- `internal/uiserver/server.go`: `/api/import` loads `s.exportKey` and calls
  `SendKey`. `/api/pair` accepts any code currently seen by the scanner.
- `android/.../SignerRuntime.kt`: the advertised code is four bytes of
  manufacturer data `0xFFFF`; the code is not a secret.

Impact: a fake phone peripheral can advertise the same 4-byte code, answer the
Noise XX handshake with its own static key, and receive the full 64-byte
consensus secret. The KMS does not require a prior vote session for `SendKey`,
and the phone-side `Admit` gate does not help because the attacker is not
running the phone code.

Rule: the KMS must pin the phone's Noise static key from an out-of-band
fingerprint, compare the responder static after message 2, and refuse any
mismatch. The user must confirm the import on the KMS and on the phone. A
pairing code is a selector, never authentication. Do not send a key to a
code-only peer.

The phone side has a related weakness: `importFrame` starts a separate
`NoiseResponder` and does not itself compare the KMS static to the pin. It
relies on `admit` requiring a previous `sessionReady`. That is a P2 only while
the KMS pin is correct; it should still check explicitly.

### P0.2 First central wins the KMS pin (TOFU)

Evidence:

- `KmsGattServer.kt`: `finishSession` reads `filesDir/zcinti-kms.peer`. If the
  file does not exist, it writes the remote static with no user confirmation.
  If it exists and differs, the session is rejected.
- `onConnectionStateChange` accepts every central; the pin is the only
  identity check.

Impact: any nearby central that completes Noise first becomes "the KMS" for
that phone. The user never sees a fingerprint comparison. The pairing code does
not prevent this. The pin file is a public key, not a secret, but its
provisioning is silent.

Rule: do not write a pin silently. Show the KMS fingerprint on the phone and
the phone fingerprint on the KMS, require an explicit match, and refuse all
signing until the pin is confirmed. Provide a deliberate reset ceremony; a
missing or unreadable pin file must not mean "trust the next peer."

### P0.3 High-water mark fails open and is not durable

Evidence:

- `HighWater.kt`: `read` returns a fresh mark on a missing file, a short file,
  a non-`1` first line, or any parse failure. `write` uses
  `File.writeText(body)`; there is no temp file, fsync, or rename.
- `KmsGattServer.kt`: `signSealed` computes the signature, calls
  `mark.commit`/`mark.note`, calls `mark.write`, then notifies `0x55`.
- `HighWater.decide` treats a byte-pattern match in `isVoteExtension` as an
  extension and returns `Sign` without storing the message.

Impact: a partial write, crash, or power loss can make the next read return a
fresh mark and accept a lower height. The write happens before notify, but
without fsync the OS may lose it after the signature is released. The
extension exemption is a heuristic, not a validated vote-extension type/step,
so crafted bytes can avoid the stored-message replay check.

Rule: provision the HWM at key creation with a known floor (for a live
validator, the current chain height), and refuse to sign if the mark is
missing or corrupt after provisioning. Write atomically: temp file, fsync,
rename, fsync directory. Do not notify the signature until the durable write
succeeds. Validate vote extensions as extensions and bind them to the same
height/round/step; do not use a byte-pattern shortcut.

### P0.4 A live key can leave the phone, and legacy plaintext remains

Evidence:

- `KeyExport.kt`: `box` reads the 64-byte consensus secret and seals it to the
  scanned KMS static. `BluetoothSignerScreen.kt` allows exporting any listed
  key after the manual fingerprint check. There is no second approver and no
  vault unlock.
- `DeviceKey.kt`: `importFile` writes plaintext `filesDir/kms-ed25519.pkcs8`.
  `ValidatorKeys.migrateLegacy` copies it into the key folder and leaves the
  original in place. `readPkcs8` seals on read, so the plaintext can persist
  until the first use.
- `AtRest.kt` / `VaultKey`: AES-GCM under AndroidKeyStore, but the key has no
  `setUserAuthenticationRequired`; app unlock is the only barrier.

Impact: the export screen is an explicit exfiltration path for a live
consensus key. The legacy path leaves a plaintext copy on the device. An
unlocked phone can use the key without a fresh biometric or PIN.

Rule: do not export a live validator key. Restrict export to a drill key with a
separate, explicit "unsafe" flow. Require user authentication for export and
for any signing after a timeout. Migrate legacy plaintext, seal it, and delete
the original; fail closed if migration cannot complete. Never write a private
key to `filesDir` in plaintext.

### P0.5 KMS identity and local console are not protected enough

Evidence:

- `link.go`: `noiseStatic` stores the raw 32-byte KMS Noise static at
  `/tmp/kms-ble/noise.static` with mode `0600`.
- `atrest.go`: `AtRestKey` is a local file, not a hardware or OS keychain key.
- `internal/uiserver/server.go`: the console binds `127.0.0.1:8791`, has no
  authentication, no Origin/CSRF check, and no content-type enforcement.
  `/api/pair`, `/api/import`, `/api/recover`, and `/api/takeoff` are plain POST
  endpoints.

Impact: anyone who can read the KMS static can impersonate the KMS to a phone
that has pinned it and receive votes. Any local process, or a malicious web
page using a simple `text/plain` POST, can drive the console. Combined with
P0.1, `/api/pair` plus `/api/import` can send the configured export key to a
fake phone.

Rule: put the KMS identity key in the OS keychain or a hardware-backed store,
not `/tmp`. Authenticate every console request with a per-run token and check
`Origin`/`Host`; use a Unix socket or a non-HTTP local control channel for
key-moving operations. Disable import/recover endpoints in the drill build.
Loopback is not an authorization boundary.

## 4. P1: fix before a non-live demo

- **Pairing code persists and is on air.** The phone prefs and
  `/tmp/kms-ble/chosen` survive restarts. Rotate the code per pairing window
  and show it on both screens; never treat it as authentication.
- **Status and granter metadata leak.** The status frame is unsealed and
  carries height, lag, validator flag, chain, and consensus address. The
  granter characteristic exposes the Terp address after a session. Both are
  session-gated, but they are still cleartext metadata.
- **Vote frames are opaque.** `parse` checks the inner `0x10`, lengths, and
  fields, then signs `parsed.msg` as Ed25519. It does not validate chain ID,
  validator address, vote/proposal type, or canonical protobuf. The HWM is
  monotonicity, not semantic validation. A compromised KMS can ask for
  arbitrary consensus signatures.
- **Console has no request authentication.** See P0.5. Even after import
  pinning, add CSRF/Origin checks and a per-run token.
- **`--export-key` is guarded by substring only.** `CometSecret` rejects paths
  containing `.kms-terp` or `.kms-penumbra`, but the flag can point at another
  key file. Allowlist an explicit drill key, or require an `--unsafe` flag.
- **Recovery artifacts persist.** `recover` seals the blob but writes it under
  `/tmp/kms-ble/recovered` and never unlinks it. `AtRestKey` is local-file
  protected. Wipe after use, keep recovery offline, and do not leave a sealed
  live key on disk.
- **Feegrant budget is a fixed assumption.** `takeoff/session.go` uses
  `actionFee=20000`, `grantTxFee=40000`, `sessionSpendLimit=40000`, and a
  two-hour session. The code comment covers create and revoke, but the
  historical revoke failure is operator-reported. Test create and revoke on
  an isolated node; compute the budget from node params; fail closed if revoke
  cannot be paid; never test on mainnet.
- **Use a release build.** The manifest sets `allowBackup="false"`, removes
  `INTERNET` and `ACCESS_NETWORK_STATE`, leaves `ACCESS_WIFI_STATE`, and keeps
  `RemoteSignerService` `exported="false"`. Verify the merged manifest in the
  release variant. Do not ship a debug build; a debug build is not a security
  posture.
- **No cross-lane serialization.** A granter change can happen while a vote
  session is open. The file-driven granter change clears `prepared`; the HTTP
  `SetGranter` path does not. Add an explicit arming state and clear prepared
  auth on every granter change.

## 5. P2: correct, but not the first cut

- The phone import path does not compare the pinned KMS static itself; it
  relies on `sessionReady`. Add the explicit comparison.
- `HighWater` uses a line-oriented text file. If it is kept, add a version,
  atomic write, and a provisioned-floor sentinel. Do not treat "file missing"
  as "first sign" after provisioning.
- The console has no audit log and no operator-visible summary of which key and
  which lane a request used. Add a local audit trail and a clear "signing for
  chain X with key Y" display.
- `ApprovalFactor` is defined as `Keplr`, `Zafu`, `Passkey`, `Test`, but the
  default approver accepts `Test` only. No real wallet or passkey approval is
  in this cut.
- Bitchat patterns not used here: multi-hop mesh relay/TTL/gossip, source
  routing, Nostr fallback, and geohash channels. Used here: one foreground
  service, one advertiser, many centrals, one Noise session per central.
- Zafu patterns not used here: FROST `t-of-n` shielded multisig, three-round
  DKG, per-chain FROST(Pallas)/FROST(decaf377) vaults, and the 10-minute DKG
  deadline. Do not imply they protect this signer.
- The three lanes are a table, not a queue. If the product needs a single
  operator action, add an explicit lock and a visible state machine rather than
  relying on independent timers.

## 6. What is already sound

- Noise XX is used for votes, and cleartext `0x10`/`0x30` is rejected.
- The primary consensus key, the phone Noise static, and the normal imported
  key are sealed with AndroidKeyStore AES-GCM (`ZC01` header).
- `allowBackup="false"`, no `INTERNET`, no `ACCESS_NETWORK_STATE`, and the
  remote signer service is not exported.
- KMS `FilePV` is fail-closed on a missing or corrupt state file, and
  `ChainSigner` does not recover a persistence panic.
- `CometSecret` refuses the live Morocco/Penumbra paths (by substring) and
  checks the Ed25519 key type and lengths.
- Recovered secrets are sealed with `SealBlob`, checked against the plaintext
  with `bytes.Contains`, and written `0600`; `recover` does not write them as
  plaintext.
- `KeyExport.box` uses a fresh X25519 ephemeral key, SHA256 of the shared
  secret, ChaCha20-Poly1305, a zero nonce, and the `zcinti-export-v1` AAD. The
  Bluetooth session key is not reused.
- The QR parser rejects ambiguous trailing lengths and accepts either no
  trailing address index or exactly four zero bytes. The granter binding is
  `cw-orch-granter:v1\n<binding>`; the phone checks it.
- `sign_cosmos_transaction` re-derives the display and checks signers against
  the transaction.
- `HighWater` refuses lower height/round/step and replays the same bytes at the
  same HRS; the per-key file is the right granularity.
- The status characteristic is gated by `sessionReady`, and the granter
  characteristic is gated by the same session.
- Feegrant constants now cover a create and a revoke at the stated fees
  (`40000` total budget, `20000` per action), subject to the P1 test caveat.

## 7. First product cut

What an outsider may run:

- An isolated local `morocco-1` node, on loopback, with a fresh chain.
- The KMS console on loopback with a throwaway key created on the phone.
- A release-build Zigner install on a dedicated test phone.
- Pairing, a vote, a lower-height refusal, a same-bytes replay, a pin mismatch
  refusal, and a create/revoke cycle on the isolated chain.

What must stay off:

- The live Morocco file signer, Penumbra, or any key that has ever been a
  validator.
- Export or recovery of a live key.
- `/tmp/kms-ble/recovered`, `~/.kms-terp`, and `~/.kms-penumbra`.
- A debug build as the demo build.
- Any mainnet or production RPC.

The first cut is a drill, not a claim that the signer is live-safe. It should
demonstrate refusal behavior, not just a successful signature:

- A pin mismatch must stop the session before signing.
- A corrupt or missing provisioned HWM must refuse signing.
- An import must require the pinned phone key, not just a pairing code.
- A live-key export must be impossible without an explicit unsafe flow.
- No plaintext private key may exist in `filesDir` after migration.

Because P0.1-P0.5 are still open, the current code does not meet those
criteria. Do not put a live validator key on this build.

## 8. Runtime claims not verified

These are operator assertions, not source facts:

- The live validator address and height.
- The historical create-validator transaction hash `4E2E...7270`.
- The installed build being a debug build on the Galaxy Z Flip6.
- The live node path and RPC port (`/tmp/zcinti-live`, `27657`), which do not
  match the console defaults.
- The `app_setup` listener on 8741.

The source confirms the guards and constants above. Reproduce the runtime
claims on the isolated node before repeating them as facts.

## 9. Priority summary

| Priority | Work | Gate |
| --- | --- | --- |
| P0 | Pin the phone Noise static on KMS import; refuse unknown responders | Before any import, including drill |
| P0 | Replace silent TOFU with a two-sided fingerprint confirmation | Before any signing |
| P0 | Make HWM fail-closed and durable; validate extension type/step | Before any live key |
| P0 | Block live-key export; remove legacy plaintext; require user auth | Before any live key |
| P0 | Move KMS identity out of `/tmp`; authenticate the console | Before any import/recover |
| P1 | Rotate pairing codes; seal or minimize status metadata | Before a non-live demo |
| P1 | Validate vote semantics and chain/validator binding | Before a live key |
| P1 | Test feegrant create/revoke on an isolated node | Before claiming takeoff works |
| P1 | Verify release manifest and use a release build | Before sharing a build |
| P2 | Explicit import pin check; HWM format/audit; FROST/mesh patterns | Later |
