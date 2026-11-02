//! FROST multisig key package storage
//!
//! Stores key shares from DKG so zigner can participate in threshold signing.
//! Key: wallet_id (8 bytes, truncated blake3 of public_key_package hex).
//! Value: JSON-encoded FrostWalletData.

use crate::error::{Error, Result};
use constants::{FROST_KEYS_TREE, FROST_NONCES_TREE};

/// Data stored per FROST wallet.
///
/// `orchard_fvk_uview`, `address` and `relay_url` are public-derived data
/// computed by zafu during DKG (FVK echo step) and handed to zigner in the
/// r3 ack so zigner can carry them in the airgap-import re-add QR.
/// All three are optional for backward compat with wallets created before
/// this schema bump — older wallets simply emit a re-add QR with these
/// fields absent and zafu will refuse to import (clear error to user).
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct FrostWalletData {
    pub key_package_hex: String,
    pub public_key_package_hex: String,
    pub ephemeral_seed_hex: String,
    pub label: String,
    pub min_signers: u16,
    pub max_signers: u16,
    pub mainnet: bool,
    pub created_at: u64,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub orchard_fvk_uview: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub address: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub relay_url: Option<String>,
    /// `key_package_hex` and `ephemeral_seed_hex`, sealed under a key derived
    /// from one of the device's seeds (signer `frost_storage`). When set, the
    /// two plaintext fields are empty. Absent on wallets stored before
    /// sealing; those are sealed the next time their secrets are read.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub sealed: Option<String>,
    /// Fingerprint of the seed `sealed` is under, to pick it among several.
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub seed_id: Option<String>,
}

/// Summary for listing wallets (no secrets)
#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
pub struct FrostWalletSummary {
    pub wallet_id: String,
    pub label: String,
    pub min_signers: u16,
    pub max_signers: u16,
    pub mainnet: bool,
    pub created_at: u64,
}

/// Compute wallet_id from public_key_package hex (first 8 bytes of simple hash)
fn wallet_id_bytes(public_key_package_hex: &str) -> [u8; 8] {
    // Simple FNV-1a inspired hash, producing 8 stable bytes from the hex string.
    // Not cryptographic — just a deterministic key for sled lookup.
    let mut h: u64 = 0xcbf29ce484222325;
    for &b in public_key_package_hex.as_bytes() {
        h ^= b as u64;
        h = h.wrapping_mul(0x100000001b3);
    }
    h.to_le_bytes()
}

pub fn wallet_id_hex(public_key_package_hex: &str) -> String {
    hex::encode(wallet_id_bytes(public_key_package_hex))
}

/// Store a FROST wallet after DKG completion.
/// Also sets the sticky attestation-required flag — once a FROST wallet exists,
/// anchor attestation is required for all future note syncs, even if the wallet
/// is later deleted.
pub fn store_frost_wallet(database: &sled::Db, data: &FrostWalletData) -> Result<String> {
    let tree = database.open_tree(FROST_KEYS_TREE)?;
    let id = wallet_id_bytes(&data.public_key_package_hex);
    // The id is a 64-bit non-cryptographic hash of a package a co-signer can
    // influence, so a different group could land on an existing id. Storing
    // it would overwrite that wallet's key share; refuse instead.
    if let Some(existing) = tree.get(id)? {
        let existing: FrostWalletData = serde_json::from_slice(&existing)
            .map_err(|e| Error::Other(anyhow::anyhow!("FROST deserialize: {e}")))?;
        if existing.public_key_package_hex != data.public_key_package_hex {
            return Err(Error::Other(anyhow::anyhow!(
                "another FROST wallet already uses this id; refusing to overwrite it"
            )));
        }
    }
    let json = serde_json::to_vec(data)
        .map_err(|e| Error::Other(anyhow::anyhow!("FROST serialize: {e}")))?;
    tree.insert(id, json.as_slice())?;
    tree.flush()?;

    // Set sticky flag: attestation now required permanently
    crate::zcash::set_attestation_required(database)?;

    Ok(hex::encode(id))
}

/// Get a FROST wallet by id (hex string)
pub fn get_frost_wallet(
    database: &sled::Db,
    wallet_id_hex: &str,
) -> Result<Option<FrostWalletData>> {
    let tree = database.open_tree(FROST_KEYS_TREE)?;
    let id = hex::decode(wallet_id_hex)
        .map_err(|e| Error::Other(anyhow::anyhow!("bad wallet id hex: {e}")))?;
    match tree.get(&id)? {
        Some(bytes) => {
            let data: FrostWalletData = serde_json::from_slice(&bytes)
                .map_err(|e| Error::Other(anyhow::anyhow!("FROST deserialize: {e}")))?;
            Ok(Some(data))
        }
        None => Ok(None),
    }
}

/// List all stored FROST wallets (summaries only, no secrets)
pub fn list_frost_wallets(database: &sled::Db) -> Result<Vec<FrostWalletSummary>> {
    let tree = database.open_tree(FROST_KEYS_TREE)?;
    let mut wallets = Vec::new();
    for entry in tree.iter() {
        let (key, value) = entry?;
        let data: FrostWalletData = serde_json::from_slice(&value)
            .map_err(|e| Error::Other(anyhow::anyhow!("FROST deserialize: {e}")))?;
        wallets.push(FrostWalletSummary {
            wallet_id: hex::encode(key.as_ref()),
            label: data.label,
            min_signers: data.min_signers,
            max_signers: data.max_signers,
            mainnet: data.mainnet,
            created_at: data.created_at,
        });
    }
    Ok(wallets)
}

/// Delete a FROST wallet by id
pub fn delete_frost_wallet(database: &sled::Db, wallet_id_hex: &str) -> Result<()> {
    let tree = database.open_tree(FROST_KEYS_TREE)?;
    let id = hex::decode(wallet_id_hex)
        .map_err(|e| Error::Other(anyhow::anyhow!("bad wallet id hex: {e}")))?;
    tree.remove(&id)?;
    tree.flush()?;
    Ok(())
}

/// Rename a FROST wallet (changes only the user-facing label, no secret
/// material affected). Empty `new_label` is rejected so the row stays
/// renderable in the wallet list.
pub fn rename_frost_wallet(
    database: &sled::Db,
    wallet_id_hex: &str,
    new_label: &str,
) -> Result<()> {
    if new_label.trim().is_empty() {
        return Err(Error::Other(anyhow::anyhow!(
            "FROST rename: label cannot be empty"
        )));
    }
    let tree = database.open_tree(FROST_KEYS_TREE)?;
    let id = hex::decode(wallet_id_hex)
        .map_err(|e| Error::Other(anyhow::anyhow!("bad wallet id hex: {e}")))?;
    let bytes = tree
        .get(&id)?
        .ok_or_else(|| Error::Other(anyhow::anyhow!("FROST wallet not found: {wallet_id_hex}")))?;
    let mut data: FrostWalletData = serde_json::from_slice(&bytes)
        .map_err(|e| Error::Other(anyhow::anyhow!("FROST deserialize: {e}")))?;
    data.label = new_label.to_string();
    let json = serde_json::to_vec(&data)
        .map_err(|e| Error::Other(anyhow::anyhow!("FROST serialize: {e}")))?;
    tree.insert(&id, json.as_slice())?;
    tree.flush()?;
    Ok(())
}

/// Update the public-derived metadata fields on an existing FROST wallet.
/// Used by the DKG metadata round-trip — zafu computes `orchardFvk + address + relayUrl`
/// after FVK echo and hands them to zigner so the airgap re-add QR can carry them later.
/// Empty strings are ignored (treated as "no change for this field").
pub fn update_wallet_metadata(
    database: &sled::Db,
    wallet_id_hex: &str,
    orchard_fvk_uview: &str,
    address: &str,
    relay_url: &str,
) -> Result<()> {
    let tree = database.open_tree(FROST_KEYS_TREE)?;
    let id = hex::decode(wallet_id_hex)
        .map_err(|e| Error::Other(anyhow::anyhow!("bad wallet id hex: {e}")))?;
    let bytes = tree
        .get(&id)?
        .ok_or_else(|| Error::Other(anyhow::anyhow!("FROST wallet not found: {wallet_id_hex}")))?;
    let mut data: FrostWalletData = serde_json::from_slice(&bytes)
        .map_err(|e| Error::Other(anyhow::anyhow!("FROST deserialize: {e}")))?;
    if !orchard_fvk_uview.is_empty() {
        data.orchard_fvk_uview = Some(orchard_fvk_uview.to_string());
    }
    if !address.is_empty() {
        data.address = Some(address.to_string());
    }
    if !relay_url.is_empty() {
        data.relay_url = Some(relay_url.to_string());
    }
    let json = serde_json::to_vec(&data)
        .map_err(|e| Error::Other(anyhow::anyhow!("FROST serialize: {e}")))?;
    tree.insert(&id, json.as_slice())?;
    tree.flush()?;
    Ok(())
}

// ── FROST nonce reuse persistence ──
//
// Tracks fingerprints of consumed signing nonces in sled so a force-quit
// of the app cannot drop the in-memory tracker and let an attacker replay
// a previously-used nonce against a different message (which would leak
// the FROST private key). Persistence is on the hot path of every signing
// operation; tree size is bounded by signing frequency and pruned in
// `prune_used_nonces` (caller's discretion).

/// Returns true if the nonce fingerprint has already been consumed.
pub fn is_nonce_used(database: &sled::Db, fingerprint: &[u8; 32]) -> Result<bool> {
    let tree = database.open_tree(FROST_NONCES_TREE)?;
    Ok(tree.get(fingerprint)?.is_some())
}

/// Mark a nonce fingerprint as consumed. Returns Err if it was already
/// present — the caller MUST treat this as "do not sign" because
/// proceeding would reuse a nonce.
pub fn mark_nonce_used(database: &sled::Db, fingerprint: &[u8; 32]) -> Result<()> {
    let tree = database.open_tree(FROST_NONCES_TREE)?;
    let now_secs = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|d| d.as_secs())
        .unwrap_or(0)
        .to_le_bytes();
    // compare_and_swap with expected = None ensures atomic insert-only.
    let prev = tree.compare_and_swap(fingerprint, None as Option<&[u8]>, Some(&now_secs[..]))?;
    if prev.is_err() {
        return Err(Error::Other(anyhow::anyhow!(
            "FROST nonce reuse detected (already in persistent tracker)"
        )));
    }
    tree.flush()?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_wallet_id_deterministic() {
        let id1 = wallet_id_hex("abcdef1234");
        let id2 = wallet_id_hex("abcdef1234");
        assert_eq!(id1, id2);
        assert_eq!(id1.len(), 16); // 8 bytes = 16 hex chars
    }

    /// Two different groups whose packages hash to the same id must not
    /// overwrite each other; re-storing the same group still updates it.
    #[test]
    fn store_refuses_to_overwrite_another_group_on_an_id_collision() {
        let db = sled::Config::new().temporary(true).open().unwrap();
        let wallet = |pkg: &str, label: &str| FrostWalletData {
            key_package_hex: "aabb".to_string(),
            public_key_package_hex: pkg.to_string(),
            ephemeral_seed_hex: "eeff".to_string(),
            label: label.to_string(),
            min_signers: 2,
            max_signers: 3,
            mainnet: true,
            created_at: 0,
            orchard_fvk_uview: None,
            address: None,
            relay_url: None,
            sealed: None,
            seed_id: None,
        };
        let id = store_frost_wallet(&db, &wallet("ccdd", "first")).unwrap();
        // same group again: allowed (an update)
        store_frost_wallet(&db, &wallet("ccdd", "renamed")).unwrap();

        // plant a different package under the same id, as a collision would
        let tree = db.open_tree(FROST_KEYS_TREE).unwrap();
        let mut other = wallet("ccdd", "victim");
        other.public_key_package_hex = "different".to_string();
        tree.insert(
            hex::decode(&id).unwrap(),
            serde_json::to_vec(&other).unwrap(),
        )
        .unwrap();
        assert!(store_frost_wallet(&db, &wallet("ccdd", "attacker")).is_err());
        assert_eq!(get_frost_wallet(&db, &id).unwrap().unwrap().label, "victim");
    }

    #[test]
    fn test_store_and_retrieve() {
        let db = sled::Config::new().temporary(true).open().unwrap();
        let data = FrostWalletData {
            key_package_hex: "aabb".to_string(),
            public_key_package_hex: "ccdd".to_string(),
            ephemeral_seed_hex: "eeff".to_string(),
            label: "test 2-of-3".to_string(),
            min_signers: 2,
            max_signers: 3,
            mainnet: true,
            created_at: 1234567890,
            orchard_fvk_uview: None,
            address: None,
            relay_url: None,
            sealed: None,
            seed_id: None,
        };
        let id = store_frost_wallet(&db, &data).unwrap();
        let retrieved = get_frost_wallet(&db, &id).unwrap().unwrap();
        assert_eq!(retrieved.label, "test 2-of-3");
        assert_eq!(retrieved.min_signers, 2);

        let list = list_frost_wallets(&db).unwrap();
        assert_eq!(list.len(), 1);
        assert_eq!(list[0].wallet_id, id);

        delete_frost_wallet(&db, &id).unwrap();
        assert!(get_frost_wallet(&db, &id).unwrap().is_none());
    }
}
