# Handoff: zcinti signer security posture

You are the signer-figures pane. Read this file and write
`docs/security-posture.md` in this repo. Do not change the Zigner app, the
KMS, or cw-orch. Do not read or print private keys, seeds, PKCS8 files,
`/tmp/kms-ble/ephemeral.bin`, `/tmp/kms-ble/noise.static`,
`/tmp/kms-ble/recovered/`, or `~/.kms-terp`. Do not kill processes. Ports
8741 and 8791 are live. Do not open a second listener.

Cross-check the claims below against the files named here. Where a claim is
wrong, say so in the note. Then prioritize what has to be true before this
is a shareable first product, and name the bitchat and Zafu patterns we are
not using.

## What this stack is

zcinti is a phone-held Ed25519 consensus key. The laptop KMS forwards
CometBFT privval bytes over Bluetooth. The phone also holds the Terp
secp256k1 account that grants authz and a feegrant. An in-process ephemeral
key broadcasts create-validator and the revoke. The phone does not broadcast.

Two repos:

- Phone: the Zigner repo
- Laptop: the kms repo
- Auth page and session grants: `crates/cw-orchestrator/packages/cw-orch-kms`
- Patterns to borrow, not to merge: `crates/bitchat`, `crates/zafu`

## Locations

Phone

- GATT server: `android/src/main/java/net/rotko/zigner/screens/settings/bluetooth/KmsGattServer.kt`
- Service: `RemoteSignerService` (not exported). Foreground notification id 70.
- Consensus keys: `ValidatorKeys.kt`, `DeviceKey.kt`. Files are
  `filesDir/validator-keys/<id>/{pkcs8,spki,raw}` plus `index.json`.
  `DeviceKey.writeSeed` writes the PKCS8 prefix plus the 32-byte seed with
  `File.writeBytes`. No Android Keystore and no vault password on that path.
- Noise static seed: `filesDir/zcinti-noise.static`
- Export: `KeyExport.kt` and the Export signer key screen in
  `BluetoothSignerScreen.kt`
- Cosmos QR parse: `rust/transaction_signing/src/cosmos.rs`
  (`from_sign_bytes`, `granter_binding`) and `rust/signer/src/lib.rs`
  (`parse_cosmos_sign_request`, `sign_cosmos_transaction`)
- Return QR: `android/.../scan/ScanViewModel.kt`. Memos that start with
  `cw-orch-`, and a one-card Granter challenge, return `ur:cworch-sig`
  (33-byte pubkey || 64-byte signature).

Laptop

- Console UI and HTTP: `crates/kms/internal/uiserver/server.go` on
  `127.0.0.1:8791`
- Scanner: `crates/kms/scripts/ble-scan.swift`, launched by
  `internal/uiserver/radio.go` as `build/BleScan.app`
- Vote link: `crates/kms/signing/bluetooth/link.go`. Sign timeout is the
  context around `Link.Sign` (about 3.5–4s).
- High-water mark that the phone does not call: `signing/bluetooth/zafu.go`
  (`HWM.Allows`). The test is `noise_test.go` `TestColdSignerRefusesRegression`
  and it drives `softwareZafu`, not the phone.
- Import and export open: `signing/bluetooth/import.go`
- Takeoff: `internal/takeoff/{session,amino,tx,sig}.go`
- Runtime files under `/tmp/kms-ble/`: `chosen`, `pub.hex`, `granter`,
  `ephemeral.bin` (mode 600), `noise.static`, `recovered/<chain>.json`

Auth page

- `cw-orch-kms/src/terminal.rs`: cosmos frame `zigner_request` is
  `0x53 0x05 0x10`, account index, chain name, sign-doc length, sign-doc
  bytes. No trailing address index. The KMS takeoff QR appends 4 zero bytes.
  `from_qr_hex` accepts trailing length 0 or 4.
- Granter capture bytes are exactly `cw-orch-granter:v1\n` plus one binding
  line. The installed phone build signs those raw bytes. Amino JSON still
  goes through `from_json`.

## Progress that is already true

- Debug app `net.rotko.zigner.debug` is installed on Galaxy Z Flip6
  `R5CX62L5EVF`. Package has no INTERNET permission.
- One phone consensus key has already joined a chain as a validator. It is
  not the live file-backend signer. Do not publish that address or its
  transactions in these notes.
- Live `~/.kms-terp` and Penumbra `~/.kms-penumbra` are not this stack.
  `CometSecret` refuses paths containing `.kms-terp` or `.kms-penumbra`.
- Create-validator for a phone key has committed once.
  The feegrant that paid it was a BasicAllowance equal to that one fee, so
  the allowance was deleted in the same tx. The revoke then failed, key not
  found. A later budget of 40000 uthiol is aimed at create plus revoke.
  Gas is THIOL. Self-delegation is TERP. Do not trust older notes that still
  say the session fee is uterp.
- `app_setup` is still listening on `127.0.0.1:8741` for binding `app-setup`.
  The first document is the granter challenge. The second, after that
  signature, is an ordinary amino session grant. Leave that process alone.

## Security claims to verify

Key use and storage

- Phone consensus secrets are plaintext PKCS8 in app-private files. The
  Zigner password vault is a different store and does not wrap these files.
- Laptop does not hold the phone consensus key during voting. It does hold
  the takeoff ephemeral spend key on disk, and `OpenExport` writes a recovered
  64-byte secret to `/tmp/kms-ble/recovered/<chain>.json` mode 0600.
- `--export-key` is the only file `POST /api/import` may send. The drill file
  is an aside key, not the live Morocco signer.

Bluetooth

- Advertisements are manufacturer data, company id `0xFFFF`, 4 bytes, no
  device name. That 4-byte value is the pairing code. It is on the air.
- `KmsGattServer.onConnectionStateChange` accepts every central, binds it to
  `ValidatorKeys.selectedId`, and `onCharacteristicWriteRequest` signs a
  `0x10` frame for that central. There is no Noise session on the vote path
  and no check that the central knows a secret beyond having connected.
- Status `0x30` and vote `0x10` are writes on the same sign characteristic.
  Status must be write-with-response or the phone never sees height and lag.
  That shares the radio with the vote deadline.
- Noise XX (`0x20`–`0x25` in `import.go`, phone `NoiseImport.kt`) is only the
  key-import flight. Static identity stays out of message 1. Do not treat
  cosmos QR `0x53 0x05 0x10` as that import.

Transport and parallel auth

- Three lanes at once: privval votes over GATT, the granter bech32 on
  characteristic `…0005`, and camera QRs for the cosmos sign doc (KMS takeoff
  and the cw-orch page). They are not ordered against each other.
- The phone high-water mark is not on the GATT sign path. `HWM` lives in
  `zafu.go` and is enforced by `softwareZafu` in tests. `KmsGattServer.reply`
  signs the parsed message and does not compare height, round, and step with
  the last signature. `Link.SetHRS` comments that FilePV still enforces the
  floor. FilePV is not this phone.
- A granter change clears prepared takeoff auth. Vote signing does not wait
  for that auth, and auth does not wait for a quiet vote gap.

Export of the signer key

- Phone export is one scan of `zcinti-kms:1:` (32-byte static pub) and one
  button. `KeyExport.box` seals with a fresh X25519 ephemeral, SHA256 of the
  DH shared secret, ChaCha20-Poly1305, zero nonce, AAD `zcinti-export-v1`.
  The Bluetooth session key is not used. The screen also prints the sealed
  string.
- There is no second approver, no vault unlock on that button, and no
  displayed fingerprint of the KMS identity before sealing. A substituted
  identity QR seals the consensus key to whoever generated that public key.
- cw-orch already has `ApprovalFactor` (test, keplr, zafu, passkey) in
  `cw-orch-kms/src/session.rs`. Zafu has `txApproval`, `originApproval`,
  `signApproval`, and FROST (`crates/zafu/docs/MULTISIG.md`,
  `docs/src/architecture/state.md`). Neither gate sits on this export.
- Import is the stronger transport (Noise XX bound to both statics). Export
  is the weaker one (anonymous QR, one approval).

## What to borrow

bitchat, `crates/bitchat/WHITEPAPER.md` and the Noise XX session code:

- One radio, many sessions, each session Noise_XX_25519_ChaChaPoly_SHA256.
- Static identity not in the advertisement or the first handshake message.
- We copied the handshake for import only. Vote frames, status frames, and
  the granter characteristic are still cleartext GATT.

Zafu, `crates/zafu`:

- The high-water mark in `signing/bluetooth/zafu.go` is the rule the phone
  should enforce before this key is described as a cold signer.
- Approval windows and FROST are the N-approval pattern for moving a signer
  key. One phone button is not that pattern.

## Write this

`docs/security-posture.md` with:

1. Corrected findings, each with a file path.
2. A priority list. P0 is what is unsound while this phone key is already a
   morocco-1 validator. P1 is what a first shareable cut must include. P2 is
   the bitchat and Zafu work that can wait.
3. A proposed first-product cut: what an outsider may run, and what stays
   off the demo (live Morocco file signer, Penumbra, this phone's existing
   validator key, `/tmp` recovered secrets).
4. Do not propose code in the note beyond the rule each fix has to keep.
   Do not draw figures unless one diagram is required to show the three lanes.
