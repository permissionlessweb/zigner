# Zigner

The shielded air-gap signer for **Zcash** and **Penumbra**. Private keys never leave the device. The only I/O channel is
QR codes on the screen and camera.

The device MUST remain offline at all times. See
[Security and Privacy](./about/Security-And-Privacy.md).

## Features

- **PCZT signing** with mandatory inspection — anchor match, known-spend
  cross-reference, and value consistency are enforced at the Rust layer.
  No blind signing.
- **FROST multisig** for Orchard — distributed key generation and
  threshold signing run entirely between Zigner and a coordinator over
  animated QR codes. No relay server in the cryptographic path; a
  coordinator may *deliver* QR payloads but is never trusted to
  produce or aggregate signatures.
- **Anchor attestation** — once a device has held FROST keys it
  permanently requires every imported note bundle to carry a verifier
  signature, defending against a compromised hot wallet fabricating a
  note tree.
- **ZID auth** — site-scoped ed25519 challenge signing for OAuth-less
  login on web wallets and Zafu pro.
- **Hot wallet derivation** — deterministic 12-word BIP39 mnemonic
  derived from the master seed, exported as `ur:zafu-hot-wallet` for
  use in Zafu pro.
- **Encrypted backup** — `ur:zigner-backup` exports group metadata,
  contacts, labels, and FROST shares under XChaCha20-Poly1305.
- **Two chains** — Zcash (Orchard + transparent) and Penumbra (full
  action set).

## Hot wallet pairing

| Chain     | Hot wallet                                                   | Wire format         |
|-----------|--------------------------------------------------------------|---------------------|
| Zcash     | [Zafu](https://github.com/rotkonetworks/zafu), Zashi         | UR / PCZT / ZIP-316 |
| Penumbra  | [Prax](https://praxwallet.com)                               | UR / CBOR           |

## Guides

- [Getting Started](./tutorials/Start.md)
- [Key Derivation](./tutorials/Hierarchical-Deterministic-Key-Derivation.md)
- [FAQ](./about/FAQ.md)

## License

[GPL-3.0](LICENSE)
