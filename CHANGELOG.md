# Changelog

All notable changes to zigner are documented in this file.

## 0.14.0 - 2026-10-31

Minor. Security release from a full review of the signer. **Penumbra users:
liquidity positions, Dutch auctions and votes are refused until each has a
review screen.** iOS FROST signing is turned off.

### Security

- **FROST key shares are encrypted at rest.** A share has no seed phrase
  behind it and used to sit in the database as plaintext JSON, readable from
  a forensic image without any unlock. It is now sealed with
  XChaCha20-Poly1305 under a key derived from one of the device's seeds,
  with the wallet id bound in, so the seed you already back up is the only
  thing that opens it.
  - Storing, signing with, exporting or importing a share asks for the
    unlock at that moment; the wallet list and details stay readable
    without it.
  - Existing wallets are sealed the first time they are used with a seed
    unlocked, and only after the sealed copy reads back correctly.
  - **Creating a FROST wallet now needs a key set on the device**, since
    the share is sealed under that seed.
  - iOS FROST wallet creation is turned off with signing until iOS has the
    unlock flow.
- **Zigner no longer signs a hash handed to it by the wallet.** The legacy
  Zcash request (`530402`) carried a bare sighash plus a free-text summary,
  and the review screen showed the amount and recipient parsed from that
  summary - text the wallet wrote, not what was signed. The request is now
  refused as an unknown type. Zcash is signed only from a PCZT, whose
  sighash Zigner computes itself. Zafu has not sent this request since
  29.4.0. The unused code that signed supplied sighashes (`sign_pczt`,
  `ZcashAuthorizationData`) and an unused FROST attestation verifier are
  deleted with it.
- **A hostile QR code can no longer crash or exhaust the phone through the
  camera.**
  - Every multi-frame transport now has a frame cap. Before, only one of
    them did, and declared totals were trusted, so a crafted stream could
    grow memory without bound or wrap a frame count negative.
  - Animated UR frames are checked against the app's limits before the
    fountain decoder sees them. One frame declaring hundreds of millions of
    fragments made that decoder ask for gigabytes, on any device, since
    module updates are always readable. A lone `1-1` frame now has its
    checksum checked too.
  - The note-import CBOR decoder no longer recurses into an unknown key, so
    deep nesting cannot overflow the native stack. It also refuses trailing
    bytes, strings that run past the end, and heights over 32 bits.
  - FROST `P…` frames are parsed with bounds: a malformed index used to
    crash the app, and a stream with gaps was assembled anyway. JSON
    nested deeper than any real message is refused before parsing.
  - The binary transport keeps its own frame total, so another transport's
    frame can no longer make a single-frame request wait for more frames.
  - The fountain and note-import decoders are retried at growing intervals
    instead of every few frames, which kept a long hostile stream quadratic
    on the main thread.
- **Smaller leaks closed.**
  - Rust logs go to logcat only in debug builds; release builds have no
    Rust logger at all.
  - A debug build no longer logs a FROST wallet's key package when its
    detail screen fails to parse it.
  - A FROST wallet whose 64-bit id collides with an existing one is
    refused instead of overwriting that wallet's key share.
  - Every signing path asks for an unlock at the moment of signing.
- **Penumbra signing shows what it signs.** The approve screen showed only
  the chain, a spend count and part of a hash - never a recipient, amount
  or fee - so a hostile wallet could pay any address. It now decodes the
  transaction plan with the Penumbra SDK, from the same bytes Zigner
  checks against the effect hash, and shows each action's amount and full
  address, the fee, expiry and memo, and the key that signs. The chain is
  the plan's own, not the QR header's (the header defaulted to
  `penumbra-1` when missing).
  - Sends, swaps, swap claims, delegation, undelegation and claims, and IBC
    withdrawals are shown and signed. Liquidity positions, Dutch auctions,
    votes and governance actions are refused until each has a screen.
  - An output is marked as this device's own only when it pays a receive
    address stored on the device.
  - The QR's spend randomizers must match the plan's, and the review runs
    again at signing time, so nothing it cannot show is ever signed.
  - The old card display, its hand-written protobuf decoder, and the
    unauthenticated schema-update QR that rewrote it are deleted.
- **FROST review can no longer hide a payment or call it change.** The
  on-device check listed only the outputs it could decrypt with the group's
  keys and silently dropped the rest, and it labelled an output "change"
  by which key decrypted it - a coordinator could encrypt a payment to
  itself that way. It now lists every output from the transaction's own
  fields, each proven against its note commitment, refuses any output it
  cannot prove and any transparent or Sapling part, calls an output change
  only when its address belongs to the group, and shows the fee.
- **iOS FROST signing is turned off.** Round 2 signed every action of a
  transaction with one nonce, which lets anyone who sees the signature
  shares recover this device's key share. The function that did it is
  deleted; Android takes one nonce per action and is unaffected.
- **The camera parses a network's codes only when the device holds a key
  for it.** A Zcash-only device refuses Penumbra codes, and a Penumbra-only
  device refuses Zcash codes, before their content parsers run. Zcash also opens
  with a FROST wallet, FROST signing needs a FROST wallet, and identity
  challenges need a key. Recovery, FROST wallet creation and module updates
  stay open on an empty device, because that is how it stops being empty.
  Polkadot and Cosmos codes are always refused. A refused code says what
  the device is missing; a failed key lookup says that instead.
- Not yet covered: the multi-frame QR transport decoder still runs on
  byte-mode codes before they are classified.
- The set comes from the Rust core and is derived from the database each
  time the camera opens. The stored scan setting, which nothing could
  change, is gone.

## 0.13.0 - 2026-10-29

Minor. **Penumbra users: read the first entry.**

### Penumbra

- **The Penumbra receive address was wrong (zigner#35).** Zigner derived the
  address it showed with its own placeholder code instead of penumbra-sdk, so
  the address did not belong to the viewing key Zigner exports or the key it
  signs with. Funds sent to an address an older Zigner showed cannot be found
  or spent, by Zigner or any other wallet. The FVK export and signing were
  always correct: funds received at the address Zafu (or any wallet) shows
  for the imported FVK are safe.
  - The address now comes from penumbra-sdk: index 0 of the
    `m/44'/6532'/account'` key, the same address Zafu shows for that FVK.
  - Addresses stored by older versions are never shown again. A key added
    before this release shows no address until you open it; opening it
    derives the address from the seed and stores that one.
  - The Receive QR is built only from that freshly derived address.
- **Exported Penumbra keys are tagged `penumbra:`**, not `substrate:`. Zafu
  29.5.3 accepts only `penumbra:` and refuses the old form, which marks an
  address from an affected Zigner.
- **12-word seeds** work for Penumbra: export and signing used a BIP39 parser
  that accepts only 24 words.
- Penumbra keys come from penumbra-sdk alone; Zigner's own derivation code is
  deleted, with its `decaf377`, `poseidon377`, `f4jumble` and `aes` deps.

### Dependencies

- Removed Substrate's wasm executor and the `unwasm` command of
  `generate_message` (host tool), which pulled zstd 0.11 into the build.
- `tokio` 1.53.3, `zstd-sys` 2.1.2 and `zstd` 0.14.3 for new RustSec advisories.

## 0.12.0 - 2026-10-20

Minor. NU7-ready signing on the zakura stack, a reproducible protocol
module, and device-security screens that only state what they measured.
`BAKED_MODULE_VERSION` 2 → 3.

### Penumbra

- **Penumbra signing works again.** The camera gate added in 0.11.3 left
  Penumbra off with no switch to turn it on, so every Penumbra request was
  refused with "enable it in settings" - a setting that did not exist.
  Penumbra is now on by default; Substrate and Cosmos stay off, and their
  refusal no longer points at a missing setting.

### Zcash

- **NU7 (ZIP 259, branch `0x77190ad9`)** on the protocol-module path
  (`ur:zigner-module`), built on the zakura crate family (`zakura-pczt`
  0.1.0-rc4, `zakura-*` 2.2.0) - the same stack zcli, zafu and vizor build
  with. **NU7 activates on its own, on every network, with no further Zigner
  release**: the consensus branch id inside each PCZT is the signal - the
  online wallet picks it from the chain height and the network rejects a
  wrong-branch transaction - so this offline device needs no height table and
  no update for activation day.
- **Fail-closed PCZT review.** Both signer paths refuse, before anything is
  displayed, any PCZT they cannot verify: unknown consensus branch, tx
  format other than v5/v6, V6 on a pre-NU6.3 branch, any Sapling component.
  An unrecognised branch used to render outputs and amounts nobody had
  checked.
- The native `ur:zcash-pczt` path now refuses **transparent outputs**: its
  review screen cannot show them, so a payment to a t-address was hidden
  behind a "change + fee" display.
- **NU7 on the native path and for FROST.** The native `ur:zcash-pczt`
  signer and the FROST payout verifier moved to the same zakura 2.2.0 stack
  as the module, so both sign and verify NU7 PCZTs too.
- **FROST verify works for Ironwood spends.** The verifier took a V5 sighash
  and read only the Orchard pool, so every V6 (NU6.3) team-wallet spend
  failed the sighash check and was blocked. It now takes the sighash from
  the PCZT signer, which picks V5 or V6 by the transaction, and shows
  outputs from both pools - the same route zcli's builder and joiner use.
- **THORChain deposits on the module path.** A transparent output's
  OP_RETURN is shown as its memo, and a THORChain swap, add or withdraw is
  spelled out in words beside the raw memo. Change back to the sending
  address is labelled as such (it is also where a THORChain refund lands).
  A data output that carries value is shown as burned.
- The **fee of a transaction with transparent inputs** is shown instead of
  "unknown": it comes from the inputs' own values, which the ZIP 244 sighash
  this device signs commits to.
- **Transparent keys are found by script**, across the external and internal
  branches up to index 5,000, so a swap address past the first 20 signs. An
  input no key of this account owns is now refused instead of returned
  unsigned.
- **FROST round 2 is bound to round 1.** Round 2 used to sign whatever
  sighash and randomizers its own QR carried, so a hostile coordinator could
  show one transaction in round 1 and collect a signature share for another
  in round 2. The device now records exactly what you approved in round 1
  and refuses round 2 unless it matches.
- **FROST:** Approve & Sign is offered only when the device verified the
  request's sighash itself. A missing PCZT, a missing viewing key or a
  verifier error now blocks signing instead of showing the coordinator's
  claim with the button enabled.
- Verified balances state "as of block N", and the anchor can be shown as
  that block's own header date - chain time, not this device's clock.

### Scope

- **Polkadot/Substrate removed from the Android app.** Zigner is a Zcash
  and Penumbra signer; the Substrate key, network and metadata screens are
  gone (181 files, ~6.3k lines), along with 47 unused strings and 13 unused
  images. The Substrate parsers in the shared Rust core remain for now (the
  iOS app still uses them).

### Security & honesty

- **Screenshots are blocked on every screen that shows a secret**, through
  one reference-counted mechanism; two competing ones could leave a freshly
  generated recovery phrase unprotected. Includes the hot-wallet mnemonic QR.
- Factory reset now says exactly what is erased and what recovery depends
  on, instead of "Reset the Vault app".

- The root check (`su` paths - invisible to Magisk, so a pass meant
  nothing) is replaced by a device-integrity notice: verified-boot state,
  bootloader lock, test-keys and concrete root findings, labelled as
  self-reported by the OS. A locked GrapheneOS phone (yellow boot) is not
  flagged. The notice informs; it no longer locks you out.
- The single-CVE OS check is replaced by **security-patch age**, checked on
  every start. Fixes a patch-date parser that read "2022-11-05" as January.
- **MTE (memory tagging) enabled** (`memtagMode="sync"`) on supporting
  hardware. MTE status is now read from the kernel; it previously read a
  `/proc` field that does not exist and assumed "on" on error.
- Settings reports where the seed key actually lives (StrongBox / TEE /
  software), read back from the keystore - not what was requested.

### Build

- `module0.wasm` is now **reproducible**: built in a digest-pinned
  container with pinned rustc and clang, and CI plus tag releases refuse an
  asset that is not a build of the published source
  (`scripts/verify-baked-module.sh`).
- `ruzstd` 0.7 → 0.9.1 for RUSTSEC-2026-0344 (decompression bomb). It
  decodes module-update deltas; in this release that path is unreachable
  (packages fail the 2-of-3 signature check first - the release keys are
  still placeholders), but it would be live after the key ceremony.
- `cargo deny` advisories are clean again, each remaining ignore with its
  reason in `rust/deny.toml`. Note the CI job is `continue-on-error`, so a
  green tick there has never meant "no advisories".

### Fixes

- Seed-phrase backspace no longer freezes after one delete during recovery.
- The 512px store icon was still Parity's logo (and so was the F-Droid repo
  icon); replaced with the Zafu Zigner mark.

## 0.11.3 - 2026-08-19

### Fixes

- Zcash (`m/32'/133'/0'`) and Penumbra (`m/44'/6532'/0'`) derivation paths
  were rejected as invalid - they were checked against the Substrate path
  grammar. This also fixes "can't have both Penumbra and Zcash on one key
  set".

### Changes

- Banana Split export pages one shard QR at a time (Shard i of N) instead of
  animating every shard into one stream - each shard is a separate secret.
- Signing asks for the PIN once, not twice.
- Removed "Export to New Device" (a metadata-only QR that confused users);
  Banana Split is the backup and transfer path.

## 0.11.2 - 2026-08-17

Patch. Makes the release-key QR scannable on poor cameras.

### Fixes

- The Release-key screen QR encoded the 64-char hex *string* (64 bytes) for a
  32-byte key - twice the payload it needed, in dense byte-mode. It now carries
  the 32 raw bytes as base64 (44 chars), so the QR is a couple of versions
  sparser and a low-res desktop webcam can lock it. The zafu.pro ceremony page
  decodes base64 or 64-hex; the printed hex for manual entry is unchanged.

## 0.11.1 - 2026-08-17

Patch over 0.11.0. Fixes a startup deadlock that froze every launch, and
rebrands to the zafu enso mark.

### Fixes

- **Unfreeze startup.** `init_navigation` held the DB `RwLock` write guard in a
  bound variable (from the poisoned-lock recovery refactor) and then called
  `read()` on the same lock — a self-deadlock that hung every launch at
  "Verifying with secure hardware" until Android killed it (universal, all
  devices, both build types). Each guard is now scoped so it drops before the
  next lock, restoring 0.10.0's temporary-lock behaviour.

### Branding

- Launch/unlock screen and settings footer now use the zafu enso logo (was the
  Rotko wordmark).

## 0.11.0 - 2026-08-17

Minor, not patch. The FROST DKG wire format changed and three key
modules left the shipped binary, so this is not a drop-in patch for
0.10.0. Adds backup-to-a-public-key as an alternative to passphrase
backups, and hardens the module update lifecycle.

### Features

- age-encrypted share backup, to one or more public keys: an
  alternative to passphrase-encrypted backups, where forgetting the
  passphrase is a common way a backup turns out to be worthless -
  discovered at restore time. Recipients are ordinary `ssh-ed25519`
  lines or native `age1` recipients; this device's own recipient is
  always included in an export, derived under a dedicated backup
  domain rather than reusing the signing key. Both transports share
  one plaintext format, so the threshold cross-check on import cannot
  be skipped by arriving over the new one.
- Export/restore screens for the above, with the recipient list shown
  before export ("who can restore this" is the only question on that
  screen a user can get wrong), and restore dispatching on the age
  armor header so the user need not remember which way they backed up.

### Fixes

- FROST DKG round 2 is now sealed (frost-spend bd9c63e). Round-2
  packages were shipped in the clear on a QR, which for any n > t lets
  an observer interpolate the dealers' polynomials and recover the
  group signing key. The wire format change is version-gated and fails
  loudly on mismatch - every participant must be on 0.11.0 or later.
  Wallets from earlier ceremonies are NOT retroactively protected;
  where a ceremony could have been observed, the remedy is a fresh DKG
  and moving funds.
- Activation installs what was confirmed: staging recorded only which
  slot it wrote, so activation re-verified the slot without proving
  the bytes were the same package the user was shown. The staged
  version and module hash are now recorded and re-checked at
  activation.
- Module update state is written atomically (write-then-rename). A
  torn write left unparseable JSON, dropping `lastInstalled` to the
  baked floor and reopening the anti-rollback window.
- The `zid-sign` scan route reached the identity surface without a
  capability check; frame accumulation is now bounded, as is the
  envelope's `read_contributions` reservation, which previously sized
  itself from a declared u16 count rather than what the buffer could
  hold.
- Poisoned DB locks recover instead of panicking, so one transient
  failure no longer disables the device until restart.

### Removed

- Bitcoin, Nostr and atproto key derivation modules: 1,952 lines for
  three chains this device does not sign for, built into the shipped
  binary via the `active` feature and carrying a `bitcoin` 0.32
  dependency. Deliberate narrowing of scope, recoverable from history.
  The `Encryption::Bitcoin` FFI variant stays.
- Android screens and objects with no reachable callers, including the
  `SecurityStatusViewModel` chain and the standalone FVK export
  screens superseded by the inline path in `KeyDetailsPublicKeyScreen`.

## 0.10.0 - 2026-08-10

Minor, not patch. Picks up on-device delta module updates, the modpack
release tooling, the camera route that finally reaches the update confirm
screen, a voting-delegation display, a feature-gated ML-DSA hybrid
verifier, and developer-gated online mode - plus security-relevant fixes:
hedged signing nonces, package length malleability, and an SSH key
comparison that matched a substring instead of the parsed field.

### Features

- ed25519 general verifier + ZIP-32 seed fingerprint: standardizes the
  update/publisher verifier on ed25519 (zid-style identity), replacing the
  sr25519 default, and emits a proper 32-byte ZIP-32 `SeedFingerprint`
  (BLAKE2b, `mnemonic.to_seed`) at the export site so vizor/Keystone import
  no longer rejects it as not-32-bytes.
- Delegation-aware display for voting-delegation PCZTs: recognizes a Zcash
  voting-delegation PCZT by its shape (zero transparent inputs, one
  unsigned Ironwood governance action with a provably-zero output value)
  and shows a truthful "voting delegation -> hotkey, zero value"
  confirmation instead of a payment screen or an outright rejection. The
  send/payment path and its anti-tamper checks are untouched and still run
  first. Round id and amount stay in the encrypted memo and are not
  displayed.
- ed25519+ML-DSA hybrid verifier scaffold (feature-gated): adds
  `rust/pq_hybrid` with `HybridPublicKey`/`HybridSignature` and dual-verify
  AND semantics (both ed25519 and ML-DSA-65 must pass), behind the
  `pq-hybrid` feature (off by default). Kept out of the main workspace for
  now due to a `digest` pre-release pin clash with the zcash signer stack;
  builds and tests against its own lockfile.
- Hide online mode behind a 5-tap developer unlock: online mode (which
  breaks the air-gap) was always visible in Advanced Settings. It is now
  gated behind an Android developer-mode style gesture - 5 taps on the
  verifier certificate value within a short window reveal the toggle.
  Revealing only surfaces the option; enabling online mode still requires
  the existing explicit confirmation and authentication flow, and stays
  off by default.
- Release tooling for signed module packages: `prepare` / `[scan]` /
  `assemble` / `verify` commands split release production across three
  machines so no single step ever holds two release keys, with delta
  packaging (bsdiff + zstd) reporting a 9x size reduction on a real module
  pair.
- Apply delta module updates on-device: a module update carrying a delta
  payload is reconstructed from the installed base plus the delta, turning
  a module update into tens of KB over QR instead of 2 MB. Decompression
  is bounded by `MAX_MODULE_BYTES` so a swapped payload costs bounded work
  before failing the hash check.

### Fixes

- Package length malleability: a signed delta package accepted arbitrary
  appended bytes without invalidating the signature. The manifest now
  commits `payload_len` inside the signed region, and verification checks
  it before resolving the payload, so a wrong-sized payload is rejected
  before decompression ever runs.
- SSH key-ownership guard compared a substring of the blob instead of the
  parsed key field: the guard could be satisfied by embedding our key
  bytes in the attacker-controlled session identifier while the actual
  public-key field named a different identity. `classify_userauth` now
  returns the parsed key field and `sign_request` compares that field
  exactly.
- Checked length arithmetic in the SSH and manifest parsers: an
  attacker-declared length near `u32::MAX` could overflow on 32-bit
  targets (armeabi-v7a is a shipped ABI), passing bounds checks and then
  building a slice with `end < start` - a panic on hostile input on a real
  device. Now uses `checked_add` throughout.

### Security

- Hedged signing nonces (module OTA path), so host entropy is no longer
  load-bearing for key safety.

### Tests

- End-to-end release ceremony tests against the real modpack binary,
  covering the full package path, delta packaging, 2-of-3 threshold
  enforcement, and the changelog-required guard on `prepare`.
- cargo-fuzz targets for the untrusted-input parsers (`parse_prefix`,
  `verify_package`, `apply_patch`), plus a deterministic seeded-mutation
  smoke test that runs on every commit.
- Pinned the baked module version so bumping the shipped asset is a
  deliberate decision rather than something that silently regresses
  already-updated devices.

### Notes

- Module OTA ships inert in this release: `RELEASE_KEY_BYTES` is still
  all-zero, so `release_keys()` yields no trust anchor and
  `module_verify_package` fails closed with "release keys not
  provisioned." Scanning a module package will be refused. That is
  correct behavior, not a regression - the pipeline is complete but
  cannot be armed until the key ceremony happens.
