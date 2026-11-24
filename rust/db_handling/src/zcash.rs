//! Zcash key derivation and address management
//! Uses ZIP-32 derivation for Orchard keys

use crate::error::{Error, Result};
use constants::{ZCASH_ADDRESS_TREE, ZCASH_NOTES_TREE};
use zeroize::Zeroize;

/// Derive the standard BIP39 seed (empty passphrase) for a phrase.
///
/// tiny-bip39 accepts 12/15/18/21/24-word phrases, while bip32's `Mnemonic`
/// only accepts 24, which rejected valid 12-word Zcash recovery phrases.
/// Both the mnemonic and the returned seed zeroize on drop.
pub fn bip39_seed(seed_phrase: &str) -> Result<bip39::Seed> {
    let mnemonic = bip39::Mnemonic::from_phrase(seed_phrase, bip39::Language::English)
        .map_err(|e| Error::Other(anyhow::anyhow!("Invalid mnemonic: {e}")))?;
    Ok(bip39::Seed::new(&mnemonic, ""))
}

/// Store Zcash address in database (keyed by public key hex)
pub fn store_zcash_address(database: &sled::Db, pubkey_hex: &str, address: &str) -> Result<()> {
    let addresses = database.open_tree(ZCASH_ADDRESS_TREE)?;
    addresses.insert(pubkey_hex.as_bytes(), address.as_bytes())?;
    Ok(())
}

/// Get Zcash address from database by public key hex
pub fn get_zcash_address(database: &sled::Db, pubkey_hex: &str) -> Result<Option<String>> {
    let addresses = database.open_tree(ZCASH_ADDRESS_TREE)?;
    match addresses.get(pubkey_hex.as_bytes())? {
        Some(bytes) => Ok(Some(String::from_utf8(bytes.to_vec()).map_err(|e| {
            Error::Other(anyhow::anyhow!("Invalid UTF-8 in stored address: {}", e))
        })?)),
        None => Ok(None),
    }
}

/// Derive Zcash keys from seed phrase
/// Path format: m/32'/133'/account' (ZIP-32 for Orchard)
/// Returns (32-byte public key for MultiSigner, unified address string)
pub fn derive_zcash_keys(
    seed_phrase: &str,
    path: &str,
    mainnet: bool,
) -> Result<([u8; 32], String)> {
    // Parse account from path (format: m/32'/133'/account' or just account number)
    let account = parse_account_from_path(path)?;

    // Use the orchard crate for ZIP-32 key derivation
    use orchard::keys::SpendingKey;
    use zip32::AccountId;

    // Derive seed (BIP39 allows 12/15/18/21/24 words)
    let seed = bip39_seed(seed_phrase)?;
    let seed_bytes: &[u8] = seed.as_bytes();

    // Convert account to AccountId
    let account_id = AccountId::try_from(account)
        .map_err(|_| Error::Other(anyhow::anyhow!("Invalid account index")))?;

    // Derive orchard spending key using ZIP-32
    let sk = SpendingKey::from_zip32_seed(seed_bytes, 133, account_id)  // 133 = Zcash coin type
        .map_err(|e| Error::Other(anyhow::anyhow!("Orchard key derivation failed: {e:?}")))?;

    // Get full viewing key
    use orchard::keys::FullViewingKey;
    let fvk = FullViewingKey::from(&sk);

    // Get default address (diversifier index 0)
    let address = fvk.address_at(0u32, orchard::keys::Scope::External);

    // Build unified address with Orchard receiver
    use zcash_address::unified::{Address as UnifiedAddress, Encoding, Receiver};
    use zcash_address::Network;

    let orchard_receiver = Receiver::Orchard(address.to_raw_address_bytes());
    let network = if mainnet {
        Network::Main
    } else {
        Network::Test
    };

    let unified_address = UnifiedAddress::try_from_items(vec![orchard_receiver])
        .map_err(|e| Error::Other(anyhow::anyhow!("Failed to create unified address: {e}")))?
        .encode(&network);

    // Use first 32 bytes of FVK as the "public key" for MultiSigner storage
    let fvk_bytes = fvk.to_bytes();
    let mut pubkey = [0u8; 32];
    pubkey.copy_from_slice(&fvk_bytes[0..32]);

    // Zeroize sensitive data
    let mut sk_bytes = *sk.to_bytes();
    sk_bytes.zeroize();

    Ok((pubkey, unified_address))
}

/// Parse account number from derivation path
/// Supports formats: "m/32'/133'/0'", "0", empty string (defaults to 0)
fn parse_account_from_path(path: &str) -> Result<u32> {
    if path.is_empty() {
        return Ok(0);
    }

    // If it's just a number
    if let Ok(account) = path.parse::<u32>() {
        return Ok(account);
    }

    // Parse ZIP-32 style path: m/32'/133'/account'
    let parts: Vec<&str> = path.split('/').collect();

    // Find the account component (should be after 133')
    for (i, part) in parts.iter().enumerate() {
        if *part == "133'" && i + 1 < parts.len() {
            let account_str = parts[i + 1].trim_end_matches('\'');
            return account_str
                .parse::<u32>()
                .map_err(|_| Error::Other(anyhow::anyhow!("Invalid account in path: {}", path)));
        }
    }

    // Default to account 0 if we can't parse
    Ok(0)
}

// ============================================================================
// Verified note storage (synced via animated QR)
// ============================================================================

// Stored note value in sled: CBOR-encoded { value, cmx, position, block_height }
// Key: nullifier (32 bytes)

/// Verified note as synced: (value, nullifier, cmx, position, block_height)
pub type VerifiedNoteInput = (u64, [u8; 32], [u8; 32], u32, u32);

/// Verified note as stored: (value, nullifier_hex, cmx, position, block_height)
pub type VerifiedNote = (u64, String, [u8; 32], u32, u32);

/// Anchor metadata: (anchor, height, mainnet, synced_at, anchor_time)
///
/// `synced_at` is this device's clock when the notes were stored; `anchor_time`
/// is the anchor block header's own Unix time (chain truth), 0 when the source
/// bundle predates the field.
pub type VerifiedAnchor = ([u8; 32], u32, bool, u64, u32);

/// Store verified notes in the database (clear-and-replace model)
///
/// Also stores anchor and height in special keys prefixed with 0xff.
pub fn store_verified_notes(
    database: &sled::Db,
    notes: &[VerifiedNoteInput],
    anchor: &[u8; 32],
    anchor_height: u32,
    mainnet: bool,
    anchor_time: u32,
) -> Result<()> {
    let tree = database.open_tree(ZCASH_NOTES_TREE)?;

    // Clear existing data
    tree.clear()?;

    // Store anchor metadata under special key
    // Format: anchor(32) + height(4) + mainnet(1) + synced_at(8) + anchor_time(4)
    let synced_at = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_secs();
    let mut meta = Vec::with_capacity(49);
    meta.extend_from_slice(anchor);
    meta.extend_from_slice(&anchor_height.to_le_bytes());
    meta.push(if mainnet { 1 } else { 0 });
    meta.extend_from_slice(&synced_at.to_le_bytes());
    meta.extend_from_slice(&anchor_time.to_le_bytes());
    tree.insert(b"__anchor__", meta.as_slice())?;

    // Store each note keyed by nullifier
    for (value, nullifier, cmx, position, block_height) in notes {
        let mut note_data = Vec::with_capacity(72);
        note_data.extend_from_slice(&value.to_le_bytes()); // 8 bytes
        note_data.extend_from_slice(cmx); // 32 bytes
        note_data.extend_from_slice(&position.to_le_bytes()); // 4 bytes
        note_data.extend_from_slice(&block_height.to_le_bytes()); // 4 bytes
        tree.insert(&nullifier[..], note_data.as_slice())?;
    }

    tree.flush()?;
    Ok(())
}

/// Retrieve all verified notes from the database
/// Returns: Vec<(value, nullifier_hex, cmx, position, block_height)>
pub fn get_verified_notes(database: &sled::Db) -> Result<Vec<VerifiedNote>> {
    let tree = database.open_tree(ZCASH_NOTES_TREE)?;
    let mut notes = Vec::new();

    for entry in tree.iter() {
        let (key, value) = entry?;
        // Skip metadata keys
        if key.as_ref() == b"__anchor__" {
            continue;
        }
        if value.len() < 48 {
            continue;
        }

        let val = u64::from_le_bytes(value[0..8].try_into().unwrap());
        let mut cmx = [0u8; 32];
        cmx.copy_from_slice(&value[8..40]);
        let position = u32::from_le_bytes(value[40..44].try_into().unwrap());
        let block_height = u32::from_le_bytes(value[44..48].try_into().unwrap());
        let nullifier_hex = hex::encode(key.as_ref());

        notes.push((val, nullifier_hex, cmx, position, block_height));
    }

    Ok(notes)
}

/// Get the sum of all verified note values
pub fn get_verified_balance(database: &sled::Db) -> Result<u64> {
    let notes = get_verified_notes(database)?;
    Ok(notes.iter().map(|(value, _, _, _, _)| value).sum())
}

/// Get the stored anchor, height, mainnet flag, and sync timestamp
pub fn get_verified_anchor(database: &sled::Db) -> Result<Option<VerifiedAnchor>> {
    let tree = database.open_tree(ZCASH_NOTES_TREE)?;
    match tree.get(b"__anchor__")? {
        Some(data) => {
            if data.len() < 37 {
                return Ok(None);
            }
            let mut anchor = [0u8; 32];
            anchor.copy_from_slice(&data[0..32]);
            let height = u32::from_le_bytes(data[32..36].try_into().unwrap());
            let mainnet = data[36] == 1;
            // synced_at was added later; old entries won't have it
            let synced_at = if data.len() >= 45 {
                u64::from_le_bytes(data[37..45].try_into().unwrap())
            } else {
                0
            };
            // anchor_time (block header time) added later still
            let anchor_time = if data.len() >= 49 {
                u32::from_le_bytes(data[45..49].try_into().unwrap())
            } else {
                0
            };
            Ok(Some((anchor, height, mainnet, synced_at, anchor_time)))
        }
        None => Ok(None),
    }
}

/// Clear all zcash notes from the database
pub fn clear_zcash_notes(database: &sled::Db) -> Result<()> {
    let tree = database.open_tree(ZCASH_NOTES_TREE)?;
    tree.clear()?;
    tree.flush()?;
    Ok(())
}

/// Sticky flag: once set, attestation is required for all future note syncs.
const ATTESTATION_REQUIRED_KEY: &[u8] = b"__attestation_required__";

/// Mark that FROST attestation is permanently required for this device.
pub fn set_attestation_required(database: &sled::Db) -> Result<()> {
    let tree = database.open_tree(ZCASH_NOTES_TREE)?;
    tree.insert(ATTESTATION_REQUIRED_KEY, &[1u8])?;
    tree.flush()?;
    Ok(())
}

/// Check if FROST attestation is required (sticky flag was set).
pub fn is_attestation_required(database: &sled::Db) -> Result<bool> {
    let tree = database.open_tree(ZCASH_NOTES_TREE)?;
    Ok(tree.get(ATTESTATION_REQUIRED_KEY)?.is_some())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_account_from_path() {
        assert_eq!(parse_account_from_path("").unwrap(), 0);
        assert_eq!(parse_account_from_path("0").unwrap(), 0);
        assert_eq!(parse_account_from_path("5").unwrap(), 5);
        assert_eq!(parse_account_from_path("m/32'/133'/0'").unwrap(), 0);
        assert_eq!(parse_account_from_path("m/32'/133'/1'").unwrap(), 1);
        assert_eq!(parse_account_from_path("m/32'/133'/42'").unwrap(), 42);
    }

    #[test]
    fn test_key_derivation() {
        // Standard 24-word test mnemonic
        let test_mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art";

        let (pubkey, address) = derive_zcash_keys(test_mnemonic, "m/32'/133'/0'", true).unwrap();

        assert_eq!(pubkey.len(), 32);
        assert!(address.starts_with("u1")); // Unified address prefix

        // 24-word behaviour must stay byte-for-byte unchanged.
        assert_eq!(
            hex::encode(pubkey),
            "83b0e991c4f124b311108e30ea961d74bb97bd10d195f2085fd3ae4af8a6b01b"
        );
        assert_eq!(
            address,
            "u1gsqvqxx6lmmqg05uvx57gjdg5j3a54nxw09z4vq4z0yp7dfdcjrqk5wq64quwzrufmujd5e8xu5jn7cyewjaptxc8lsqwa2lk559u4cd"
        );
    }

    #[test]
    fn test_twelve_word_key_derivation() {
        // Standard 12-word BIP39 test vector (abandon x11 about).
        let test_mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

        let (pubkey, address) = derive_zcash_keys(test_mnemonic, "m/32'/133'/0'", true).unwrap();

        assert_eq!(pubkey.len(), 32);
        assert!(address.starts_with("u1"));

        // Deterministic for the same phrase.
        let (pubkey2, address2) = derive_zcash_keys(test_mnemonic, "m/32'/133'/0'", true).unwrap();
        assert_eq!((pubkey, address), (pubkey2, address2));
    }

    #[test]
    fn test_invalid_mnemonic_is_rejected() {
        // 12 valid words but a bad BIP39 checksum.
        let invalid = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon";
        assert!(derive_zcash_keys(invalid, "m/32'/133'/0'", true).is_err());
    }

    #[test]
    fn bip39_seed_matches_standard_vectors() {
        // Standard BIP39 seeds (empty passphrase) for the all-zero entropy
        // vectors, cross-checkable with any BIP39 implementation.
        let vectors = [
            (
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about",
                "5eb00bbddcf069084889a8ab9155568165f5c453ccb85e70811aaed6f6da5fc19a5ac40b389cd370d086206dec8aa6c43daea6690f20ad3d8d48b2d2ce9e38e4",
            ),
            (
                "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art",
                "408b285c123836004f4b8842c89324c1f01382450c0d439af345ba7fc49acf705489c6fc77dbd4e3dc1dd8cc6bc9f043db8ada1e243c4a0eafb290d399480840",
            ),
        ];
        for (phrase, expected) in vectors {
            let seed = bip39_seed(phrase).unwrap();
            assert_eq!(hex::encode(seed.as_bytes()), expected);
        }
    }

    #[test]
    fn test_different_accounts() {
        // Standard 24-word test mnemonic
        let test_mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art";

        let (pubkey0, addr0) = derive_zcash_keys(test_mnemonic, "m/32'/133'/0'", true).unwrap();
        let (pubkey1, addr1) = derive_zcash_keys(test_mnemonic, "m/32'/133'/1'", true).unwrap();

        // Different accounts should have different keys and addresses
        assert_ne!(pubkey0, pubkey1);
        assert_ne!(addr0, addr1);
    }
}
