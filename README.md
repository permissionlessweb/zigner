<div align="center">

# Zafu Zigner

**Air-gapped hardware wallet for Zcash &amp; Penumbra — turn a spare Android or iOS phone into a cold signer.**

Sign transactions offline via QR codes; private keys never touch the network.
Also signs Polkadot, Kusama, and Cosmos. Part of the [Zafu](https://github.com/rotkonetworks/zafu) ecosystem.

[![Release](https://img.shields.io/github/v/release/rotkonetworks/zigner?color=f4a31e)](https://github.com/rotkonetworks/zigner/releases/latest)
[![License](https://img.shields.io/badge/license-GPL--3.0-blue)](LICENSE)
[![Platform](https://img.shields.io/badge/platform-Android%20%7C%20iOS-informational)](#building)
[![Downloads](https://img.shields.io/github/downloads/rotkonetworks/zigner/total)](https://github.com/rotkonetworks/zigner/releases)

</div>

> Zafu Zigner is an open-source, air-gapped cold wallet / hardware signer. It keeps your
> Zcash and Penumbra keys on an offline device and signs transactions through QR codes —
> a DIY hardware wallet with FROST threshold multisig, built on a shared Rust core.

## Threat model

Zigner assumes the signing device is never connected to any network after
initial setup. All key material is generated and stored on-device. Transaction
payloads arrive as QR codes, get parsed and displayed for review, then signed
and returned as QR codes. The only output channel is the screen.

The device MUST have WiFi, Bluetooth, NFC, and cellular disabled. Airplane
mode is the minimum. Physically removing wireless hardware is better.

On devices with a secure element (Pixel 8+ Titan M2, Samsung Knox), seed
encryption keys are generated and stored inside the hardware module via
Android StrongBox. The key never exists in main memory. On iOS, seeds are
Keychain-backed by the Secure Enclave. Zigner reports the detected security
level in Settings.

Recommended: Pixel 8+ running GrapheneOS. See
[Security and Privacy](docs/src/about/Security-And-Privacy.md) for details.

## Bluetooth consensus signer

zcinti is the optional mode where this phone holds an Ed25519 consensus key and signs blocks for a laptop KMS. It is not the QR air-gap path above. The user starts it from the Signer tab. Stop keeps the phone off the radio until Start is pressed again.

The flows are in [docs/bluetooth-remote-signer.md](docs/bluetooth-remote-signer.md).

![Four parts and one signature](docs/figures/fig1-four-parts-signature-path.svg)

![Phone session](docs/figures/fig2-phone-session-life.svg)

![One block](docs/figures/fig3-one-block-signing.svg)

![Signer tab](docs/figures/fig4-signer-tab.svg)

Open work is in [docs/TODO.md](docs/TODO.md).

What Zigner does NOT protect against:

- A compromised build toolchain (use reproducible builds and verify checksums)
- Physical access to an unlocked device with no secure element
- A user who approves a transaction without reading it
- Side-channel attacks on the device hardware (partially mitigated by StrongBox)

## Penumbra

Signs all transaction actions: shielded spends/outputs, DEX swaps, liquidity
positions, delegate/undelegate/claim, delegator votes, Dutch auctions
(schedule/end/withdraw), ICS20 withdrawals.

Chain ID is validated on every signing request to prevent cross-chain replay.

Full Viewing Key export (bech32m, UR) for import into [Prax](https://praxwallet.com).

## Zcash

- Orchard: RedPallas signatures over shielded actions
- Transparent: secp256k1 ECDSA for t-address inputs
- PCZT: Partially Created Zcash Transactions, inspectable signing only
- Key derivation: ZIP-32 (`m/32'/133'/account'`), BIP-44 transparent
- UFVK export per ZIP-316 for Zashi/Zafu import

PCZT signing enforces, at the Rust layer, that the signer's verified-notes
state matches the bundle's anchor, that every spend nullifier is known, and
that the implied spend value is consistent with the verified balance. There
is no blind-signing path. UR-encoded animated QR codes (Keystone wire format).

## FROST multisig

Threshold spend authorization for Zcash Orchard via [`frost-spend`][frost-spend]
(ZF FROST RedPallas). DKG (rounds 1–3) and signing (rounds 1–2) run entirely
between Zigner and a coordinator over QR codes. No relay server is in the
cryptographic path; a coordinator may deliver QR payloads but is never trusted
to produce or aggregate signatures. Per-action randomizer α is bound to the
sighash; nonces are tracked by a collision-resistant SHA-256 fingerprint and
signing refuses any reuse.

**Anchor attestation** — once a device has held FROST keys, it permanently
requires every imported Zcash note bundle to carry an ed25519 attestation
signed by a pinned verifier key. Defends against a compromised hot wallet
fabricating a note tree on a previously-multisig device.

**Encrypted backup** — `ur:zigner-backup` exports group metadata, contacts,
labels, and FROST shares under XChaCha20-Poly1305 with the seed phrase as the
KDF input. Format version is bound into the AEAD's associated data so a v2
ciphertext cannot be presented as v1 (or vice-versa). Wrong-passphrase
attempts return a generic error; tampering invalidates the auth tag.

[frost-spend]: https://github.com/rotkonetworks/zcli/tree/master/crates/frost-spend

## ZID auth

Site-scoped ed25519 identity, derived per-origin from the master seed. Used
for OAuth-less login on web wallets and the Zafu pro tier. The Rust layer
rejects challenges older than 5 minutes or more than 60 seconds in the
future, so a malicious frontend cannot force the device to sign a stale
challenge replay.

Cross-site correlation is prevented by domain-separated derivation
(`HMAC-SHA512(zid_root, "site:{origin}")`).

## Substrate

Polkadot, Kusama, Westend built-in. Sr25519 and Ed25519 signing.
Substrate is a secondary use case; for chain-agility tooling use a
dedicated Polkadot signer.

## Hot wallet pairing

| Chain     | Hot wallet                                                         | Wire format        |
|-----------|--------------------------------------------------------------------|--------------------|
| Penumbra  | [Prax](https://praxwallet.com)                                     | UR / CBOR          |
| Zcash     | [Zafu](https://github.com/rotkonetworks/zafu), Zashi               | UR / PCZT / ZIP-316|
| Substrate | Polkadot.js                                                        | UOS                |

The hot wallet holds only viewing keys. It constructs unsigned transactions,
encodes them as QR, and scans back the signed response.

Zigner can also derive a 12-word BIP39 *hot wallet mnemonic* from the master
seed (HMAC chain, deterministic, recoverable from backup) and export it as
`ur:zafu-hot-wallet` for use in Zafu pro. The hot wallet shares the seed
family with the cold device — convenient for daily spending, not a security
boundary.

## Architecture

Native iOS (Swift) and Android (Kotlin/Compose) over a shared Rust core. All
cryptography, key derivation, transaction parsing, and signing lives in Rust.
The native layers handle UI, camera, and QR rendering.

```
rust/
  signer/                UniFFI bridge to native (auth, backup, FROST, hot wallet)
  transaction_signing/   Penumbra, Zcash, Substrate signers
  transaction_parsing/   QR / UR / CBOR / PCZT decoder
  db_handling/           Encrypted sled storage (seeds, FROST keys, contacts, anchors)
  navigator/             Screen state machine
  qrcode_rtx/            Animated UR encoder (raptorq fountain codes)
  qrcode_static/         Static QR generation
  qr_reader_phone/       Camera frame UR decoder
  qr_reader_pc/          Desktop dev QR reader (uses opencv, not in mobile build)
  zcash-wasm/            Orchard derivation for browser wallets
  constants/             Pinned verifier keys, tree names, network defaults
  definitions/           Shared error / model types
ios/                     Swift + SwiftUI
android/                 Kotlin + Jetpack Compose
```

## Building

Requires [Rust](https://www.rust-lang.org/tools/install) and uniffi-bindgen
matching the project version:

```
cargo install uniffi_bindgen --version 0.22.0
```

The mobile builds do **not** require opencv. Only the desktop dev tool
`qr_reader_pc` pulls in [opencv](https://crates.io/crates/opencv) and is
gated behind its own crate; cargo build for the app targets skips it.

### Android

```
rustup target add aarch64-linux-android armv7-linux-androideabi x86_64-linux-android
```

Install [Android Studio](https://developer.android.com/studio) with NDK
`24.0.8215888`. Open the project root and build.

### iOS

Open `ios/PolkadotVault.xcodeproj` in Xcode. Build and run.

## Releasing (Android)

1. Bump `versionName` in `android/build.gradle`
2. Merge to master
3. Tag `v*` (e.g. `v0.4.1`) and push the tag

`.github/workflows/android-release.yml` then runs tests, builds + signs the
APK (v2 + v3 + v4), computes `SHA256SUMS`, signs the checksum file with the
release ssh ed25519 key (`SHA256SUMS.sig`), and publishes a GitHub release
with all three artifacts. Tags containing `-` (e.g. `v1.0.0-rc1`) publish
as pre-releases.

To verify a downloaded APK:

```
sha256sum -c SHA256SUMS
ssh-keygen -Y verify -f allowed_signers -I release@rotko.net -n file -s SHA256SUMS.sig < SHA256SUMS
```

## Tests

```
cd rust && cargo test --locked
```

## License

[GPL-3.0](LICENSE)
