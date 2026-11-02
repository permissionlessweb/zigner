// frost_multisig.rs — FROST threshold multisig for Zigner cold signer
//
// Zigner participates as a signer in a t-of-n FROST multisig.
// It stores a KeyPackage (from DKG) and produces signature shares
// when presented with a sighash + alphas.
//
// All orchestration logic lives in frost_spend::orchestrate.
// This module wraps it for uniffi FFI.

use std::collections::HashSet;
use std::convert::TryInto;
use std::sync::Mutex;

use frost_spend::orchestrate;
use zeroize::Zeroize;

lazy_static::lazy_static! {
    /// Track nonce hashes that have been consumed by signing.
    /// A nonce that appears here has been used and MUST NOT be reused.
    /// This prevents catastrophic key recovery from nonce reuse.
    /// Fingerprint is SHA-256(nonces_hex) — collision-resistant; the
    /// full 32 bytes are stored so a malicious peer cannot engineer a
    /// fingerprint collision to bypass reuse detection.
    static ref USED_NONCES: Mutex<HashSet<[u8; 32]>> = Mutex::new(HashSet::new());
}

/// Cryptographic fingerprint of a nonce for reuse tracking.
/// Uses SHA-256 — engineered collisions are infeasible.
fn nonce_fingerprint(nonces_hex: &str) -> [u8; 32] {
    use sha2::{Digest, Sha256};
    Sha256::digest(nonces_hex.as_bytes()).into()
}

/// Mark a nonce as consumed. Returns Err if already used.
///
/// Reuse is checked against BOTH the in-memory HashSet and the persistent
/// sled tree. The persistent layer defeats a force-quit attack where an
/// adversary could otherwise restart the app, drop the in-memory state,
/// and replay an already-used nonce against a different message —
/// which would catastrophically leak the FROST private key.
fn consume_nonce(nonces_hex: &str) -> Result<(), String> {
    let fp = nonce_fingerprint(nonces_hex);

    // Persistent check first — survives app restart.
    let db_guard = crate::DB
        .read()
        .map_err(|_| "DB lock poisoned".to_string())?;
    if let Some(database) = db_guard.as_ref() {
        db_handling::frost::mark_nonce_used(database, &fp).map_err(|e| {
            format!(
                "CRITICAL: {}. Reusing a FROST nonce with a different message would \
                 leak the private key. Generate fresh nonces with sign_round1.",
                e
            )
        })?;
    }
    drop(db_guard);

    // In-memory write-through cache (fast-path for repeated reuse within
    // the same session — sled would catch it too but at a sled round trip).
    let mut used = USED_NONCES
        .lock()
        .map_err(|_| "nonce tracker poisoned".to_string())?;
    if !used.insert(fp) {
        return Err(
            "CRITICAL: Nonce reuse detected. This nonce was already used for signing. \
             Reusing a FROST nonce with a different message would leak the private key. \
             Generate fresh nonces with sign_round1."
                .to_string(),
        );
    }
    // Bound the in-memory cache; the persistent tracker remains authoritative.
    if used.len() > 1000 {
        used.clear();
        used.insert(fp);
    }
    Ok(())
}

/// DKG round 1: generate ephemeral identity + signed commitment.
/// Returns JSON: { "secret": hex, "broadcast": hex }
///
/// The returned JSON contains secret material in the "secret" field. The
/// FFI caller is responsible for clearing the returned String once it
/// has been persisted (encrypted) by the native layer. We zero our own
/// in-memory copies of the secret hex before returning.
pub fn frost_dkg_part1(max_signers: u16, min_signers: u16) -> Result<String, String> {
    let mut result = orchestrate::dkg_part1(max_signers, min_signers).map_err(|e| e.to_string())?;
    let json = serde_json::to_string(&serde_json::json!({
        "secret": result.secret_hex,
        "broadcast": result.broadcast_hex,
    }))
    .map_err(|e| e.to_string())?;
    result.secret_hex.zeroize();
    Ok(json)
}

/// DKG round 2: process signed round1 broadcasts.
/// peer_broadcasts_json: JSON array of hex strings.
/// Returns JSON: { "secret": hex, "peer_packages": [hex, ...] }
///
/// As with round 1, the returned JSON contains secret material; the FFI
/// caller must clear it after persistence. Round 1's `secret_hex` was
/// already zeroized when its caller dropped the JSON string; this round
/// zeroes its own intermediate copy.
pub fn frost_dkg_part2(secret_hex: &str, peer_broadcasts_json: &str) -> Result<String, String> {
    let broadcasts: Vec<String> = serde_json::from_str(peer_broadcasts_json)
        .map_err(|e| format!("bad broadcasts JSON: {}", e))?;
    let mut result = orchestrate::dkg_part2(secret_hex, &broadcasts).map_err(|e| e.to_string())?;
    let json = serde_json::to_string(&serde_json::json!({
        "secret": result.secret_hex,
        "peer_packages": result.peer_packages,
    }))
    .map_err(|e| e.to_string())?;
    result.secret_hex.zeroize();
    Ok(json)
}

/// DKG round 3: finalize — returns key package + public key package.
/// Returns JSON: { "key_package": hex, "public_key_package": hex, "ephemeral_seed": hex }
///
/// `key_package` and `ephemeral_seed` are long-lived secrets — the FFI
/// caller persists them encrypted as the FROST wallet record and zeroes
/// the JSON after that. We zero our local copies of those fields once
/// they have been serialized into the JSON return value.
pub fn frost_dkg_part3(
    secret_hex: &str,
    round1_broadcasts_json: &str,
    round2_packages_json: &str,
) -> Result<String, String> {
    let r1: Vec<String> = serde_json::from_str(round1_broadcasts_json)
        .map_err(|e| format!("bad round1 JSON: {}", e))?;
    let r2: Vec<String> = serde_json::from_str(round2_packages_json)
        .map_err(|e| format!("bad round2 JSON: {}", e))?;
    let mut result = orchestrate::dkg_part3(secret_hex, &r1, &r2).map_err(|e| e.to_string())?;
    let json = serde_json::to_string(&serde_json::json!({
        "key_package": result.key_package_hex,
        "public_key_package": result.public_key_package_hex,
        "ephemeral_seed": result.ephemeral_seed_hex,
    }))
    .map_err(|e| e.to_string())?;
    result.key_package_hex.zeroize();
    result.ephemeral_seed_hex.zeroize();
    Ok(json)
}

/// signing round 1: generate nonces + signed commitments.
/// Returns JSON: { "nonces": hex, "commitments": hex }
pub fn frost_sign_round1(
    ephemeral_seed_hex: &str,
    key_package_hex: &str,
) -> Result<String, String> {
    let seed = parse_seed(ephemeral_seed_hex)?;
    let (nonces, commitments) =
        orchestrate::sign_round1(&seed, key_package_hex).map_err(|e| e.to_string())?;
    serde_json::to_string(&serde_json::json!({
        "nonces": nonces,
        "commitments": commitments,
    }))
    .map_err(|e| e.to_string())
}

/// spend-authorize round 2: produce FROST share bound to sighash + alpha.
/// This is what Zigner does when participating in a multisig spend.
/// commitments_json: JSON array of hex-encoded signed commitments from all signers.
/// Returns: hex-encoded signature share.
pub fn frost_spend_sign_round2(
    key_package_hex: &str,
    nonces_hex: &str,
    sighash_hex: &str,
    alpha_hex: &str,
    commitments_json: &str,
) -> Result<String, String> {
    // Consume nonce BEFORE signing — prevents reuse even if signing fails
    consume_nonce(nonces_hex)?;

    let sighash = parse_32(sighash_hex, "sighash")?;
    let alpha = parse_32(alpha_hex, "alpha")?;
    let commitments: Vec<String> = serde_json::from_str(commitments_json)
        .map_err(|e| format!("bad commitments JSON: {}", e))?;
    orchestrate::spend_sign_round2(key_package_hex, nonces_hex, &sighash, &alpha, &commitments)
        .map_err(|e| e.to_string())
}

/// Authenticated variant: wraps the share in a SignedMessage so the coordinator
/// can map it to the correct FROST identifier by VK, regardless of BTreeMap order.
/// Use this instead of frost_spend_sign_round2 whenever sharing over the relay.
/// Returns: hex-encoded SignedMessage containing the signature share.
pub fn frost_spend_sign_round2_signed(
    ephemeral_seed_hex: &str,
    key_package_hex: &str,
    nonces_hex: &str,
    sighash_hex: &str,
    alpha_hex: &str,
    commitments_json: &str,
) -> Result<String, String> {
    consume_nonce(nonces_hex)?;

    let seed = parse_seed(ephemeral_seed_hex)?;
    let sighash = parse_32(sighash_hex, "sighash")?;
    let alpha = parse_32(alpha_hex, "alpha")?;
    let commitments: Vec<String> = serde_json::from_str(commitments_json)
        .map_err(|e| format!("bad commitments JSON: {}", e))?;
    orchestrate::spend_sign_round2_signed(
        &seed,
        key_package_hex,
        nonces_hex,
        &sighash,
        &alpha,
        &commitments,
    )
    .map_err(|e| e.to_string())
}

/// derive raw Orchard address bytes (43 bytes, hex-encoded) from public key package.
/// Caller encodes to unified address string for the appropriate network.
pub fn frost_derive_address_raw(
    public_key_package_hex: &str,
    diversifier_index: u32,
) -> Result<String, String> {
    let raw = orchestrate::derive_address_raw(public_key_package_hex, diversifier_index)
        .map_err(|e| e.to_string())?;
    Ok(hex::encode(raw))
}

/// Derive both the Orchard-only UFVK (`uview1…`) and unified address (`u1…`)
/// from a FROST public_key_package + the host-broadcast `sk`. Every participant
/// who calls this with identical inputs lands on byte-identical outputs.
/// Returns JSON `{ "orchard_fvk_uview": "...", "address": "..." }`.
pub fn frost_derive_metadata(
    public_key_package_hex: &str,
    sk_hex: &str,
    mainnet: bool,
    diversifier_index: u32,
) -> Result<String, String> {
    use frost_spend::frost_keys::PublicKeyPackage;
    use frost_spend::keys::{derive_address as fs_derive_address, derive_fvk_from_sk};
    use frost_spend::orchestrate::from_hex;
    use zcash_address::unified::{Address as UnifiedAddress, Encoding, Fvk, Receiver, Ufvk};
    use zcash_protocol::consensus::NetworkType as Network;

    let sk = parse_32(sk_hex, "fvk sk")?;
    let pubkeys: PublicKeyPackage = from_hex(public_key_package_hex).map_err(|e| e.to_string())?;
    let fvk = derive_fvk_from_sk(sk, &pubkeys)
        .ok_or_else(|| "failed to derive FVK from group key + sk".to_string())?;
    let addr = fs_derive_address(&fvk, diversifier_index);
    let raw = addr.to_raw_address_bytes();

    let network = if mainnet {
        Network::Main
    } else {
        Network::Test
    };

    let ufvk_str = Ufvk::try_from_items(vec![Fvk::Orchard(fvk.to_bytes())])
        .map_err(|e| format!("build UFVK: {e}"))?
        .encode(&network);

    let addr_str = UnifiedAddress::try_from_items(vec![Receiver::Orchard(raw)])
        .map_err(|e| format!("build address: {e}"))?
        .encode(&network);

    serde_json::to_string(&serde_json::json!({
        "orchard_fvk_uview": ufvk_str,
        "address": addr_str,
    }))
    .map_err(|e| e.to_string())
}

/// Verify a FROST payout PCZT on-device before signing (gh #17).
///
/// Recomputes the ZIP-244 sighash from the PCZT and lists what it pays, from
/// the PCZT's own output fields, each proven against its note commitment
/// with the bundle's value balance (`verify_displayed_summary`). Every output
/// is accounted for: a withheld field, a transparent output or a Sapling part
/// is refused, as on the single-signer path. An output is change only when
/// its address belongs to the group's viewing key, so a coordinator cannot
/// label a payment to itself as change.
///
/// Returns JSON:
/// `{ recomputed_sighash, sighash_match, outputs:[{recipient,amount_zat,is_change}],
///    total_send_zat, total_change_zat, fee_zat }`
pub fn frost_verify_pczt(
    pczt_hex: &str,
    claimed_sighash_hex: &str,
    orchard_fvk_uview: &str,
) -> Result<String, String> {
    use zcash_address::unified::{Container, Encoding, Fvk, Ufvk};

    let bytes = hex::decode(pczt_hex.trim()).map_err(|e| format!("bad pczt hex: {e}"))?;
    let pczt = pczt::Pczt::parse(&bytes).map_err(|e| format!("pczt parse: {e:?}"))?;

    // Same refusals as the single-signer review: anything the list below
    // could not show is refused before it is built.
    crate::zcash_consensus_gate::check_supported(&pczt).map_err(|e| format!("{e:?}"))?;
    crate::verify_displayed_summary(&pczt).map_err(|e| format!("{e:?}"))?;

    // The sighash the FROST share will commit to, from pczt's own Signer: it
    // dispatches on the transaction version (V5, or V6 for an ironwood spend)
    // and the PCZT's branch id (NU6.3 or NU7). zcli's builder and joiner take
    // it from the same place, so all three agree by construction.
    let shielded_sighash = pczt::roles::signer::Signer::new(pczt.clone())
        .map_err(|e| format!("pczt signer init: {e:?}"))?
        .shielded_sighash();
    let recomputed = hex::encode(shielded_sighash);
    let sighash_match = recomputed.eq_ignore_ascii_case(claimed_sighash_hex.trim());

    // group orchard FVK from the stored UFVK string (uview1…)
    let (network, ufvk) =
        Ufvk::decode(orchard_fvk_uview.trim()).map_err(|e| format!("ufvk decode: {e}"))?;
    let fvk_bytes = ufvk
        .items()
        .into_iter()
        .find_map(|item| match item {
            Fvk::Orchard(b) => Some(b),
            _ => None,
        })
        .ok_or_else(|| "UFVK has no orchard component".to_string())?;
    let fvk = orchard::keys::FullViewingKey::from_bytes(&fvk_bytes)
        .ok_or_else(|| "invalid orchard FVK in UFVK".to_string())?;

    let mut out = Recovered::default();
    // Both pools: since NU6.3 a spend's outputs land in the ironwood bundle.
    for bundle in [pczt.orchard(), pczt.ironwood()] {
        list_outputs(bundle, &fvk, network, &mut out)?;
        let &(magnitude, negative) = bundle.value_sum();
        let net = <i64 as std::convert::TryFrom<u64>>::try_from(magnitude)
            .map_err(|_| "value sum out of range".to_string())?;
        out.fee = out
            .fee
            .checked_add(if negative { -net } else { net })
            .ok_or_else(|| "fee out of range".to_string())?;
    }

    serde_json::to_string(&serde_json::json!({
        "recomputed_sighash": recomputed,
        "sighash_match": sighash_match,
        "outputs": out.outputs,
        "total_send_zat": out.total_send,
        "total_change_zat": out.total_change,
        "fee_zat": out.fee,
    }))
    .map_err(|e| e.to_string())
}

#[derive(Default)]
struct Recovered {
    outputs: Vec<serde_json::Value>,
    total_send: u64,
    total_change: u64,
    /// Shielded net value: with no transparent or Sapling part, the fee.
    fee: i64,
}

/// List one bundle's outputs from the PCZT's own fields. Zero-value outputs
/// are padding and move nothing, so they are not listed.
fn list_outputs(
    bundle: &pczt::orchard::Bundle,
    fvk: &orchard::keys::FullViewingKey,
    network: zcash_protocol::consensus::NetworkType,
    out: &mut Recovered,
) -> Result<(), String> {
    use zcash_address::unified::{Address as UnifiedAddress, Encoding, Receiver};

    for (index, action) in bundle.actions().iter().enumerate() {
        let output = action.output();
        let (Some(amount), Some(raw)) = (*output.value(), output.recipient()) else {
            return Err(format!("output {index} withholds its value or recipient"));
        };
        if amount == 0 {
            continue;
        }
        let addr = Option::<orchard::Address>::from(orchard::Address::from_raw_address_bytes(raw))
            .ok_or_else(|| format!("output {index} has an invalid recipient"))?;
        let is_change = fvk.scope_for_address(&addr).is_some();
        if is_change {
            out.total_change = out.total_change.saturating_add(amount);
        } else {
            out.total_send = out.total_send.saturating_add(amount);
        }
        let ua = UnifiedAddress::try_from_items(vec![Receiver::Orchard(*raw)])
            .map(|a| a.encode(&network))
            .map_err(|e| format!("output {index}: {e}"))?;
        out.outputs.push(serde_json::json!({
            "recipient": ua,
            "amount_zat": amount,
            "is_change": is_change,
        }));
    }
    Ok(())
}

// ── helpers ──

fn parse_seed(hex_str: &str) -> Result<[u8; 32], String> {
    let bytes = hex::decode(hex_str).map_err(|e| format!("bad seed hex: {}", e))?;
    bytes
        .try_into()
        .map_err(|_| "seed must be 32 bytes".to_string())
}

fn parse_32(hex_str: &str, name: &str) -> Result<[u8; 32], String> {
    let bytes = hex::decode(hex_str).map_err(|e| format!("bad {} hex: {}", name, e))?;
    bytes
        .try_into()
        .map_err(|_| format!("{} must be 32 bytes", name))
}
