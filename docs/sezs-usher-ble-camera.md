# SEZS usher BLE camera

Design for a Zigner variant in which an usher's phone is a Bluetooth
camera extension for a desktop [Zafu](https://github.com/permissionlessweb)
session running the Speak-Ez Sessions (SEZS) check-in flow.

**Status.** Design only. No runtime code in this commit.

**Branch.** `sezs/usher-ble-camera`, cut from `permissionlessweb/master`
(`2a60e1db`, *fix merge: drop removed zcash deps…*). That tip already
carries the zcinti Bluetooth bridge (phone GATT server, Noise XX sessions,
transfer / handoff flow). It does **not** yet contain the 2026-11-19
attempt to merge `novasamatech/parity-signer` `master`. That merge was
stopped uncommitted on `merge/upstream-20261119` because of 27 non-trivial
conflicts; see [Upstream merge status](#upstream-merge-status). This
branch should be rebased or merged onto the resolved main once that work
lands.

**Date.** 19 November 2026 (Africa/Dakar, UTC+0).

## Goal

Event ushers carry a phone running this Zigner fork. The phone scans the
guest ticket QR and sends the payload over a Noise-secured Bluetooth link
to a desktop that already has Zafu (browser extension) and the SEZS web
app. The desktop runs the existing check-in path: wasm proof, Zafu
authorization, backend relay.

This removes a second QR hop (phone result shown back into the desktop
camera / Zafu). It also sets up a multiplexed session manifold so several
ushers can attach to one desk, and so the radio under that manifold can
later be Bluetooth, LAN, LoRa, or a local mesh without breaking the
SEZS consume API.

Zafu is a browser extension, not a mobile app. The phone is only the
camera and the radio. Secrets that belong to Zafu or to the consensus
signer never leave the phone for this feature; the only new thing that
crosses the air is the scanned ticket opening, inside a Noise session.

## Roles

- **Usher phone (Zigner variant).** Android GATT *server* already used
  by the zcinti remote signer. Adds a camera path that accepts a
  `sezs3` guest QR and writes a sealed ticket frame to the desk. The
  phone does not build the consume proof and does not hold the event
  operator's Zafu keys.
- **Desktop helper.** A small native BLE *client* next to the browser
  (the existing KMS `BleScan.app` pattern). It holds one Noise session
  per paired usher, decrypts ticket frames, and posts them to a
  loopback hook that the SEZS page already treats as a scan.
- **Desktop SEZS web app.** Unchanged consume pipeline:
  `prepareConsumeV3` then Zafu authorization then `submitConsumeV3`.
  The camera stage in `UsherDoor` gains a second input (the helper
  hook) alongside the on-page camera.
- **Desktop Zafu extension.** Authorizes the prepared consume in the
  same browser as today. It is not the GATT client in v1 (see
  [Zafu-side work](#zafu-side-work)).

The existing "bridge" is this phone-server plus laptop-client plus a
loopback web console, not a Web Bluetooth page inside Zafu. Ticket
camera should follow that shape.

## Existing bridge (what we reuse)

The Bluetooth-capable line lives on `permissionlessweb/master` and its
descendants (`penumbra-receive-fix-merged` at the main checkout, this
branch). It is **not** on `origin` (`rotkonetworks/zigner`) `master`
(`28d0ab94`, tag `v0.15.0`, zafu theme).

Phone (this repo):

- `android/src/main/java/net/rotko/zigner/screens/settings/bluetooth/KmsGattServer.kt`
  — GATT server, per-device `NoiseResponder` map, notify path.
- `NoiseImport.kt` — phone side of `Noise_XX_25519_ChaChaPoly_SHA256`.
- `RemoteSignerService.kt`, `SignerBoot.kt`, `SignerRuntime.kt`,
  `BluetoothSignerScreen.kt` — foreground radio, UI.
- `Handoff.kt`, `PhoneGate.kt`, `MoveValidatorPanel.kt`,
  `ZcintiQrScan.kt` — validator-key transfer and move gates (QR
  prefixes `zcinti-handoff` / `zcinti-recover`). Reuse pairing and
  freeze/floor discipline as *patterns*, not ticket encodings.
- `SignRoute.kt`, `SignHistory.kt`, `HighWater.kt`, `VoteGate.kt` —
  consensus vote path; keep it off the ticket characteristic.
- `docs/bluetooth-remote-signer.md`, `docs/handoff-signer-security.md`.
- `FrostSendToZafuScreen.kt` is the current *animated QR* path into
  Zafu for FROST wallet metadata. That is the class of second scan
  this design eliminates for tickets; it is not a BLE transport.

Desktop (terp-core KMS, cite only; not modified here):

- `crates/kms/signing/bluetooth/noise.go` — `Noise_XX_25519_ChaChaPoly_SHA256`.
- `crates/kms/signing/bluetooth/link.go` — `wireHello` / `wireFinish`,
  session start before any sealed payload.
- `crates/kms/signing/bluetooth/mesh.go` — one sealed link per
  authorized phone, `MaxLinks = 7`, independent redial, pin by
  fingerprint; unpinned phones are stopped after handshake message 2.
- `crates/kms/scripts/ble-scan.swift`, `build-ble-scan.sh` — CoreBluetooth
  scanner helper.
- `crates/kms/internal/uiserver/server.go` — loopback console
  (historically `http://127.0.0.1:8791`).
- `crates/kms/signing/bluetooth/{peer,zafu,handoff,gate,registry}.go`.

`crates/bitchat` and `crates/zafu` are pattern sources (foreground
radio, extension session), not merge sources.

### GATT service already on the phone

Defined in `KmsGattServer.kt`:

| Role | UUID |
| --- | --- |
| Service | `e7c8a1b0-4f3e-4c2a-9b6d-6b6d73000001` |
| `PUB` (read, legacy public key) | `…000002` |
| `SIGN` (write, block sign request) | `…000003` |
| `SIG` (notify/read, signature) | `…000004` |
| `GRANTER` (read/notify) | `…000005` |
| `STATUS` (write) | `…000006` |
| `KEYS` (read/notify) | `…000007` |
| CCCD | `00002902-0000-1000-8000-00805f9b34fb` |

Sessions are already multiplexed: `mutableMapOf<String, NoiseResponder>`
keyed by device address. Several desktops can be connected at once at
the GATT layer; the KMS mesh then pins which phone a given desk will
talk to.

### Recommendation: reuse the session, add a ticket characteristic

Do not overload `SIGN` / `SIG`. A vote frame and a ticket frame must
not share a characteristic or a parser.

Add a sibling characteristic on the same service, for example:

- `TICKET` `e7c8a1b0-4f3e-4c2a-9b6d-6b6d73000008` — write (phone →
  desk, sealed ticket frame) and notify (desk → phone, admit / reject
  ack).

The Noise XX session that `KmsGattServer` already maintains for that
device wraps the frame. First version can also run as a *camera-only*
mode that still does XX and advertises the same service, but never
enables `SIGN`. That lets a door phone exist without loading validator
keys.

## Noise handshake

The stack already implements **Noise XX** with
`Noise_XX_25519_ChaChaPoly_SHA256` (`NoiseImport.kt`, `noise.go`,
`link.go`). XX authenticates both static keys by the end of the
handshake and does not require either peer to know the other's static
key ahead of time. Pairing today is a short advertised code plus
operator comparison of fingerprint tiles; a phone that is not pinned
never holds a session (`mesh.go`).

**Recommendation for v1: keep XX.** The usher camera should sit on the
same session type so we do not fork `KmsGattServer` / `link.go` or
break zcinti. A 42-byte ticket plus AEAD and a short header is well
inside one post-handshake transport message once ATT MTU is raised
(the existing link already does a multi-frame handshake; ticket I/O
must request a large MTU the same way, because a 20-byte default ATT
payload would fragment even this small QR).

**IK is the right upgrade, not the first handshake.** Noise IK is a
better fit once the phone has learned the desk's static key from a
pairing QR printed at the door (or shown in the SEZS / helper UI).
IK is 1-RTT and binds the usher to *that* desk's identity, which is
exactly the "scan once to pair, then just scan tickets" UX. Do not
switch the consensus-signer path to IK in the same change. After both
statics are pinned, KK is available for reconnects, but that can wait.

A desk pairing QR should carry a new, ticket-specific prefix (not
`zcinti-handoff:…` and not a raw `sezs3` string), plus the desk Noise
static, an event tag, and a short-lived pairing nonce. The ticket
opening itself never goes into a pairing QR.

## Multiplexed session manifold

`mesh.go` is already a manifold: one sealed Noise XX link per
authorized phone, a hard cap of seven concurrent LE links (practical
CoreBluetooth / Android GATT limit), per-link redial with jitter, and
pinning so a payload is delivered only to the intended peer.

Map that onto the door:

- **Several ushers, one desk.** The helper's mesh has one link per
  paired usher phone. Each scan is stamped with the phone's static-key
  fingerprint and a monotonic frame seq so the desk can attribute and
  dedupe.
- **Several desks.** Each helper has its own Noise static and pairing
  QR. A phone pins one desk for an event (or is re-paired). GATT can
  show multiple centrals; the phone should still refuse to emit a
  ticket frame to a peer that is not pinned for this event.
- **Egress.** The manifold is radio plus Noise only. Egress toward the
  chain is a plug on the desk: live HTTP to the SEZS backend, a local
  queue, or a later mesh relay to a desk that *does* have a route.
  Changing egress must not change the phone protocol.

Frame sketch (plaintext inside Noise, not a wire commitment yet):

```
type    u8     0x01 ticket, 0x02 ack, 0x03 reject, 0x04 health
seq     u32    per-link monotonic
event   8 B    sezs3 event tag (sha256(eventId)[0:8])
body    rest   UTF-8 `sezs3…` bech32m, or an ack code
```

## Payload

Guest ticket v3 is defined in the SEZS app
(`apps/sezs/src/ticket/v2/guest-qr.mjs`):

- HRP `sezs3`, bech32m.
- Typical body: 42 raw bytes (version, flags, 32-byte opening, 8-byte
  event tag; SKU optional), about 80 characters encoded. Budget is
  raw ≤ 64 bytes, encoded ≤ 90 characters.

The phone must parse and reject anything that is not `sezs3` (older
`sezs1://ticket?…` URIs can be accepted later if the desk still does).
It should also refuse a ticket whose event tag does not match the
desk it is paired to.

The opening (32-byte door secret) is a capability. It may cross the
radio inside Noise; it must not be logged, written to app storage, or
shown on screen after the scan reticle. Wipe after the desk acks, or
on a short timeout.

## Transport abstraction

Keep Noise, framing, pairing, and the SEZS hook independent of the
radio. A trait along these lines (Rust sketch; a TypeScript or Kotlin
mirror is fine for the first helper):

```rust
/// Byte pipe under a Noise session. BLE is the first implementation;
/// LAN, LoRa, or a mesh hop should plug in without changing the
/// handshake or `prepareConsumeV3` / `submitConsumeV3`.
#[async_trait]
pub trait Transport: Send + Sync {
    async fn advertise(&self, local: &Identity) -> anyhow::Result<()>;
    async fn connect(&self, hint: &PeerHint) -> anyhow::Result<Box<dyn RawLink>>;
}

#[async_trait]
pub trait RawLink: Send {
    async fn write(&mut self, ciphertext: &[u8]) -> anyhow::Result<()>;
    async fn read(&mut self) -> anyhow::Result<Vec<u8>>;
}

/// One multiplexed, authenticated session (already Noise-wrapped).
#[async_trait]
pub trait Session: Send {
    async fn send(&mut self, frame: &[u8]) -> anyhow::Result<()>;
    async fn recv(&mut self) -> anyhow::Result<Vec<u8>>;
    fn peer_static(&self) -> &[u8; 32];
}
```

v1 `Transport` is the existing GATT service (plus `TICKET`). A later
`LanTransport` can be TCP or HTTPS on a local link, still running XX
or IK over the pipe so a radio swap is not a new trust model. LoRa
and other constrained radios use the same session object and may
need fragmentation; the 42-byte payload is chosen so even those
radios can carry one ticket without a fountain code.

## Offline and mesh ticketing

The product horizon is events with little or no internet: a temporary
network of Zafu desktops that still admit guests and keep verifiable
state, then flush to the chain when any desk has egress.

Sketch, grounded in `mesh.go` and the door client:

1. Each desk runs the helper + SEZS + Zafu as today, with circuit
   params cached so the wasm proof does not need the network.
2. `prepareConsumeV3` / `submitConsumeV3` today `fetch` the splash
   backend (`/sezs/v2/door/prepare`, `/sezs/v2/door/consume`). Offline
   mode needs a local stand-in that records the consume, maintains a
   spent-set of nullifiers, and queues signed submits.
3. Desks can link to each other with the same `Transport` (LAN first,
   BLE if needed) and replicate the spent-set so two doors cannot
   admit the same ticket. Conflict rule: first seen nullifier wins;
   a later submit is refused locally and again on chain.
4. When one desk has a route, it is the egress node and drains the
   queue. The phone protocol does not change.

This is deliberately later than the single-desk BLE camera. The
manifold and the transport trait are what make it additive.

## Security model

- **Least material on the wire.** No seed, spending key, consensus
  key, or Zafu authorization key leaves the phone for this feature.
  The only secret in a ticket frame is the scanned opening, plus the
  public event tag and SKU.
- **Pairing.** XX (v1) plus operator-compared fingerprints, as in
  `mesh.go`. Unpinned peers never receive a ticket frame and never
  complete a session. Optional later: desk QR → IK.
- **Replay.** (1) Noise session keys and a per-link `seq` drop
  replayed radio frames. (2) The desk remembers `H(opening)` / the
  derived nullifier for the event and refuses a second admit locally.
  (3) The chain consume is already one-shot; `UsherDoor` tells the
  usher "A second scan will be refused."
- **Event binding.** A phone paired to event A must not forward a
  ticket tagged for event B.
- **Validator isolation.** Camera-only phones should not have
  consensus keys loaded. The `TICKET` parser must ignore `SIGN`
  writes; the sign path must ignore `TICKET` writes.
- **UI.** Reuse `DisableScreenshots` (already used on
  `FrostSendToZafuScreen`) on the usher scan surface.
- **Do not reuse handoff prefixes.** `zcinti-handoff` / `zcinti-recover`
  move validator keys. Ticket and pairing QR encodings stay in the
  `sezs3` / a new `sezs-desk` namespace.

## Plug-in to the SEZS usher flow

In `apps/sezs/src/event/UsherDoor.tsx` the stage machine is
`scan → proving → authorize → done`. A payload from the on-page
camera or a file picker is passed to `onPayload`, which:

1. Builds the consume witness on the usher device (the desk, in this
   design).
2. Calls `prepareConsumeV3` in `apps/sezs/src/event/door-v3.ts`
   (`POST /sezs/v2/door/prepare`).
3. Asks Zafu (or a configured stand-in) to authorize.
4. Calls `submitConsumeV3` (`POST /sezs/v2/door/consume`).

The BLE helper should deliver the same `sezs3` string the camera
would have decoded, into `onPayload` — for example by a loopback
`EventSource` or `POST` on `127.0.0.1`, in the same spirit as the
KMS console. `prepareConsumeV3` and `submitConsumeV3` stay as they
are. The phone never calls those endpoints.

## Zafu-side work

Web Bluetooth inside a Chrome extension is not a solid v1 host:

- `chrome.bluetooth` / `bluetoothSocket` is not a general desktop
  API (historically ChromeOS).
- Web Bluetooth, where it exists, needs a user gesture and a
  privileged document; it is not available from an extension service
  worker. Windows often needs extra flags or settings. Safari and
  Firefox extensions do not give us this path.
- Background reconnect and a seven-phone mesh are what the native
  helper already does.

**v1: keep the GATT client in a native helper**
(`ble-scan.swift` or a thinner sibling) and a loopback page. Zafu
stays the authorizer in the desktop browser. SEZS reads ticket
payloads from the helper, not from `navigator.bluetooth`.

A later experiment can try Web Bluetooth from an extension *tab* or
a desktop PWA on Chromium, with the same Noise framing, as an
optional transport behind the trait. It must not be the only path.

Other caveats: macOS Bluetooth TCC for the helper; Android is the
only production GATT server today; iOS background advertising and
GATT-server limits are an open question for usher phones.

## Phased plan

1. **This document** (this commit).
2. **Phone camera surface** on this branch: parse `sezs3`, event-tag
   check, write a sealed `TICKET` frame on the existing Noise XX
   session. Android first. No validator keys required in camera-only
   mode.
3. **Desktop helper hook**: decrypt, dedupe, `POST` the bech32 string
   to the SEZS page on loopback. Wire `UsherDoor` `onPayload`.
4. **Ack path** so the phone can show admitted / refused without a
   second QR.
5. **Multi-usher pinning** using the existing mesh (`MaxLinks = 7`).
6. **Local spent-set and submit queue** for a single offline desk.
7. **Extract `Transport` / `Session`**; add a LAN implementation.
8. **Desk mesh** and egress election, then optional IK + desk pairing
   QR, then an optional Web Bluetooth transport.
9. **Integrate `merge/upstream-20261119`** once the conflicts are
   resolved, so this line also receives upstream's parser hardening
   (`#2543`, already auto-merged in that worktree) and the other
   seven upstream commits.

No Android or iOS release build is required to start; the first
vertical slice can be exercised against the existing GATT server
tests and the KMS link tests.

## Upstream merge status

Performed on 19 November 2026 on branch `merge/upstream-20261119`, starting from
`permissionlessweb/master` (`2a60e1db`).

- Remotes: `upstream` = `https://github.com/novasamatech/parity-signer.git`
  (default branch `master`); `permissionlessweb` =
  `https://github.com/permissionlessweb/zigner.git` (our fork);
  `origin` = `https://github.com/rotkonetworks/zigner.git` (parent of
  our fork; itself a fork of Novasama). The checkout was unshallowed
  so a merge-base could be computed: `ac54f59d`
  (*fix: tap detections on derived keys (#2532)*, 26 November 2025).
- Eight commits on `upstream/master` were not in
  `permissionlessweb/master`: `#2533` soft-derivation export, `#2534`
  apps 7.2.0, `#2535` SwiftFormat stubs, `#2536` Android target SDK
  35, `#2541` CI tools, `#2543` malicious extrinsic parsing,
  `#2550` iOS CI on Xcode 26, `#2552` button actions / db / backend
  (30 September 2026).
- Auto-merged (not committed): `rust/db_handling` identities and
  tests, `rust/parser` (including `#2543` fixtures
  `deeply_nested_extrinsic` and `malicios_extrinsic`), several iOS
  onboarding/camera files, `KeySetDetailsScreenSubgraph.kt`,
  `BottomSheet.kt`.
- **27 unresolved conflicts.** The merge was stopped there, as
  requested, rather than guessed. Conflicts: CI policy and deleted
  iOS workflows (`.github/actions/install-android-dep/action.yaml`,
  `install-ios-dep/action.yaml`, `docs.yml`, `ios-build.yml`,
  `ios-manual-firebase.yml`, `ios-manual-testflight-prod.yml`,
  `ios-manual-testflight-qa.yml`, `ios-tests.yml`,
  `reusable-android-build.yml`, `reusable-android-tests.yml`,
  `rust-cargo-deny.yml`, `rust-clippy.yml`, `rust-test-android.yml`,
  `rust-test-ios.yml`), `.spellcheck/config.toml`,
  `.spellcheck/signer.dic`, `Gemfile.lock`, `README.md`,
  `android/build.gradle`, `android/src/main/java/io/parity/signer/MainActivity.kt`
  (package rename to `net.rotko.zigner`), `build.gradle`,
  `docs/src/tutorials/Start.md`,
  `gradle/wrapper/gradle-wrapper.properties`,
  `ios/PolkadotVault.xcodeproj/project.pbxproj`, `rust/Cargo.lock`,
  `rust/deny.toml`, and deleted-renamed
  `android/src/main/java/io/parity/signer/screens/settings/general/SettingsGeneralViewModel.kt`.
- `permissionlessweb/master` was **not** fast-forwarded. There is no
  new main HEAD from this merge.

Cheap checks on *this* (clean) worktree, not the conflicted merge:
`cargo check -p parser -p definitions --offline --locked` succeeded
on 19 November 2026. No Android or iOS build was run.

## Open questions

1. Confirm `TICKET` as a new characteristic (`…000008`) versus a
   framed message type on a new service UUID. Preference here is the
   sibling characteristic, so sign and camera share advertising and
   the Noise map but not a parser.
2. Is the first usher phone Android-only, or is an iOS GATT-server
   path in scope despite background advertising limits?
3. Who owns the desktop Noise static and pairing UI — a SEZS-local
   helper, the existing KMS console, or a future Zafu surface? v1
   preference: a thin helper next to SEZS, not the validator KMS
   home directory.
4. Should camera-only firmware refuse to boot the sign path even if
   validator keys are present?
5. Desk mesh: how is the egress desk elected, and how is the
   spent-set signed so a hostile desk cannot inject admits?
6. When should this line take `origin/master` `v0.15.0` (zafu theme)
   and the resolved upstream merge?
7. Does the phone need a live admit/reject notify for v1, or is a
   desk-only UI enough for the first slice?
8. Web Bluetooth in a Zafu tab: worth a time-boxed probe after the
   helper works, or wait until the transport trait exists?

## What this branch does not do

- It does not resolve or commit `merge/upstream-20261119`.
- It does not push, retag, or move `permissionlessweb/master`.
- It does not change validator handoff, KMS, or Zafu code in this
  commit.
- It does not print or move secrets, pairing seeds, or key paths.
