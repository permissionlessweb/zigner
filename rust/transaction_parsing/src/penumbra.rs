//! penumbra transaction parsing
//!
//! parses penumbra transaction plans from QR codes and prepares them for signing.
//!
//! QR format (from prax wallet):
//! ```text
//! [0x53][0x03][0x10][metadata][transaction_plan_bytes]
//!
//! metadata format:
//!   asset_count: u8
//!   for each asset:
//!     name_len: u8
//!     name: [u8; name_len]
//! ```

use definitions::helpers::unhex;

use crate::{Error, Result};

// re-export signing types for convenience
pub use transaction_signing::penumbra::{
    sign_spend,
    sign_transaction,
    EffectHash,
    // FVK types
    FullViewingKey,
    FvkExportData,
    NullifierKey,
    PenumbraAuthorizationData,
    SpendKeyBytes,
    WalletId,
    PENUMBRA_COIN_TYPE,
    QR_TYPE_FVK_EXPORT,
};

/// penumbra chain identifier in QR prelude (0x03)
pub const PENUMBRA_CRYPTO_TYPE: u8 = 0x03;

/// penumbra transaction type (0x10)
pub const PENUMBRA_TX_TYPE: u8 = 0x10;

/// parsed penumbra transaction plan
#[derive(Debug, Clone)]
pub struct PenumbraTransactionPlan {
    /// raw transaction plan bytes (protobuf encoded)
    pub plan_bytes: Vec<u8>,
    /// asset metadata from QR (name mappings)
    pub asset_metadata: Vec<String>,
    /// extracted spend randomizers for signing
    pub spend_randomizers: Vec<[u8; 32]>,
    /// extracted delegator vote randomizers
    pub delegator_vote_randomizers: Vec<[u8; 32]>,
    /// extracted lqt vote randomizers
    pub lqt_vote_randomizers: Vec<[u8; 32]>,
    /// chain id if extracted
    pub chain_id: Option<String>,
    /// expiry height if present
    pub expiry_height: Option<u64>,
    /// pre-computed effect hash from hot wallet (64 bytes)
    /// this is what gets signed - Zigner verifies it against the plan + FVK before signing
    pub effect_hash: Option<[u8; 64]>,
}

impl PenumbraTransactionPlan {
    /// get the number of actions requiring signatures
    pub fn signature_count(&self) -> usize {
        self.spend_randomizers.len()
            + self.delegator_vote_randomizers.len()
            + self.lqt_vote_randomizers.len()
    }
}

/// parse asset metadata from QR payload
fn parse_asset_metadata(data: &[u8]) -> Result<(Vec<String>, usize)> {
    if data.is_empty() {
        return Ok((Vec::new(), 0));
    }

    let asset_count = data[0] as usize;
    let mut offset = 1;
    let mut assets = Vec::with_capacity(asset_count);

    for _ in 0..asset_count {
        if offset >= data.len() {
            return Err(Error::PenumbraParseError(
                "unexpected end of asset metadata".to_string(),
            ));
        }
        let name_len = data[offset] as usize;
        offset += 1;

        if offset + name_len > data.len() {
            return Err(Error::PenumbraParseError(
                "asset name extends beyond data".to_string(),
            ));
        }
        let name = String::from_utf8(data[offset..offset + name_len].to_vec())
            .map_err(|e| Error::PenumbraParseError(format!("invalid UTF-8 in asset name: {e}")))?;
        assets.push(name);
        offset += name_len;
    }

    Ok((assets, offset))
}

// Note: randomizers are now passed from the hot wallet via QR,
// so we don't need to extract them from the protobuf plan anymore

/// parse a penumbra transaction from QR payload
///
/// payload format v2 (extended with chain_id for security):
/// `[0x53][0x03][0x10]`             - prelude (3 bytes)
/// `[chain_id_len:1]`               - length of chain_id (0 = not provided)
/// `[chain_id: chain_id_len bytes]` - chain_id string (e.g., "penumbra-1")
/// `[metadata]`                     - asset names
/// `[plan_bytes_len:4 LE]`          - length of plan bytes
/// `[plan_bytes]`                   - raw protobuf plan
/// `[effect_hash:64]`               - computed effect hash
/// `[spend_count:2 LE]`             - number of spend randomizers
/// `[spend_randomizers:32 each]`    - randomizer for each spend
/// `[vote_count:2 LE]`              - number of vote randomizers
/// `[vote_randomizers:32 each]`     - randomizer for each vote
///
/// SECURITY: chain_id is required for safe signing. If not provided,
/// a warning will be displayed to the user.
pub fn parse_penumbra_transaction(data_hex: &str) -> Result<PenumbraTransactionPlan> {
    let data = unhex(data_hex)?;

    // verify prelude
    if data.len() < 3 {
        return Err(Error::TooShort);
    }
    if data[0] != 0x53 {
        return Err(Error::NotSubstrate(format!("{:02x}", data[0])));
    }
    if data[1] != PENUMBRA_CRYPTO_TYPE {
        return Err(Error::PenumbraParseError(format!(
            "expected crypto type 0x03, got 0x{:02x}",
            data[1]
        )));
    }
    if data[2] != PENUMBRA_TX_TYPE {
        return Err(Error::PenumbraParseError(format!(
            "expected tx type 0x10, got 0x{:02x}",
            data[2]
        )));
    }

    // parse chain_id (v2 format)
    // the first byte after prelude indicates chain_id length
    // if it looks like a small number (0-50), it's chain_id length
    // if it looks like asset count (0-10 typical), we need to check context
    let mut offset = 3;
    let mut chain_id: Option<String> = None;

    // try to detect v2 format: check if first byte could be chain_id length
    // "penumbra-1" = 10 chars, "penumbra-testnet" = 16 chars
    // asset count is typically 0-5
    if data.len() > offset {
        let first_byte = data[offset];
        // v2 format detection: chain_id length between 8-50 is clearly v2
        // (asset names are typically short like "um" = 2 chars)
        if (8..=50).contains(&first_byte) {
            let chain_id_len = first_byte as usize;
            offset += 1;

            if offset + chain_id_len <= data.len() {
                chain_id = Some(
                    String::from_utf8(data[offset..offset + chain_id_len].to_vec()).map_err(
                        |e| Error::PenumbraParseError(format!("invalid UTF-8 in chain_id: {e}")),
                    )?,
                );
                offset += chain_id_len;
            }
        }
    }

    // parse asset metadata
    let (asset_metadata, metadata_len) = parse_asset_metadata(&data[offset..])?;
    offset += metadata_len;

    // check if this is the new format (has plan length prefix) or old format
    // new format: [plan_len:4][plan][effect_hash:64][randomizers...]
    // old format: [plan_bytes_to_end]

    if data.len() < offset + 4 {
        return Err(Error::PenumbraParseError(
            "no transaction plan data".to_string(),
        ));
    }

    // read plan length (4 bytes LE)
    let plan_len = u32::from_le_bytes([
        data[offset],
        data[offset + 1],
        data[offset + 2],
        data[offset + 3],
    ]) as usize;
    offset += 4;

    // validate plan length
    if offset + plan_len > data.len() {
        return Err(Error::PenumbraParseError(format!(
            "plan length {} exceeds data (offset={}, len={})",
            plan_len,
            offset,
            data.len()
        )));
    }

    let plan_bytes = data[offset..offset + plan_len].to_vec();
    offset += plan_len;

    // parse effect hash (64 bytes) if present
    let effect_hash = if offset + 64 <= data.len() {
        let mut hash = [0u8; 64];
        hash.copy_from_slice(&data[offset..offset + 64]);
        offset += 64;
        Some(hash)
    } else {
        None
    };

    // parse spend randomizers
    let mut spend_randomizers = Vec::new();
    if offset + 2 <= data.len() {
        let spend_count = u16::from_le_bytes([data[offset], data[offset + 1]]) as usize;
        offset += 2;

        for _ in 0..spend_count {
            if offset + 32 > data.len() {
                return Err(Error::PenumbraParseError(
                    "truncated spend randomizer".to_string(),
                ));
            }
            let mut r = [0u8; 32];
            r.copy_from_slice(&data[offset..offset + 32]);
            spend_randomizers.push(r);
            offset += 32;
        }
    }

    // parse vote randomizers
    let mut delegator_vote_randomizers = Vec::new();
    if offset + 2 <= data.len() {
        let vote_count = u16::from_le_bytes([data[offset], data[offset + 1]]) as usize;
        offset += 2;

        for _ in 0..vote_count {
            if offset + 32 > data.len() {
                return Err(Error::PenumbraParseError(
                    "truncated vote randomizer".to_string(),
                ));
            }
            let mut r = [0u8; 32];
            r.copy_from_slice(&data[offset..offset + 32]);
            delegator_vote_randomizers.push(r);
            offset += 32;
        }
    }

    // parse LQT vote randomizers
    let mut lqt_vote_randomizers = Vec::new();
    if offset + 2 <= data.len() {
        let lqt_count = u16::from_le_bytes([data[offset], data[offset + 1]]) as usize;
        offset += 2;

        for _ in 0..lqt_count {
            if offset + 32 > data.len() {
                return Err(Error::PenumbraParseError(
                    "truncated LQT vote randomizer".to_string(),
                ));
            }
            let mut r = [0u8; 32];
            r.copy_from_slice(&data[offset..offset + 32]);
            lqt_vote_randomizers.push(r);
            offset += 32;
        }
    }

    let _ = offset; // may have trailing data

    Ok(PenumbraTransactionPlan {
        plan_bytes,
        asset_metadata,
        spend_randomizers,
        delegator_vote_randomizers,
        lqt_vote_randomizers,
        chain_id,
        expiry_height: None,
        effect_hash,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_asset_metadata() {
        // empty metadata
        let (assets, len) = parse_asset_metadata(&[0]).unwrap();
        assert!(assets.is_empty());
        assert_eq!(len, 1);

        // single asset
        let data = [1, 3, b'u', b'm', b'p']; // 1 asset, len 3, "ump"
        let (assets, len) = parse_asset_metadata(&data).unwrap();
        assert_eq!(assets.len(), 1);
        assert_eq!(assets[0], "ump");
        assert_eq!(len, 5);

        // two assets
        let data = [2, 3, b'u', b'm', b'p', 4, b't', b'e', b's', b't'];
        let (assets, len) = parse_asset_metadata(&data).unwrap();
        assert_eq!(assets.len(), 2);
        assert_eq!(assets[0], "ump");
        assert_eq!(assets[1], "test");
        assert_eq!(len, 10);
    }

    #[test]
    fn test_parse_penumbra_transaction_new_format() {
        // new format: prelude + metadata + plan_len + plan + effect_hash + randomizers
        let mut hex = String::new();

        // prelude
        hex.push_str("530310");

        // metadata: 1 asset "um"
        hex.push_str("01"); // 1 asset
        hex.push_str("02"); // len 2
        hex.push_str("756d"); // "um"

        // plan length (4 bytes LE) - 4 bytes of plan
        hex.push_str("04000000");

        // plan bytes
        hex.push_str("deadbeef");

        // effect hash (64 bytes)
        for _ in 0..64 {
            hex.push_str("ab");
        }

        // spend count (2 bytes LE) - 1 spend
        hex.push_str("0100");

        // spend randomizer (32 bytes)
        for _ in 0..32 {
            hex.push_str("cd");
        }

        // vote count (2 bytes LE) - 0 votes
        hex.push_str("0000");

        let result = parse_penumbra_transaction(&hex);
        assert!(result.is_ok(), "Parse failed: {:?}", result);

        let plan = result.unwrap();
        assert_eq!(plan.asset_metadata.len(), 1);
        assert_eq!(plan.asset_metadata[0], "um");
        assert_eq!(plan.plan_bytes, vec![0xde, 0xad, 0xbe, 0xef]);
        assert!(plan.effect_hash.is_some());
        assert_eq!(plan.effect_hash.unwrap(), [0xab; 64]);
        assert_eq!(plan.spend_randomizers.len(), 1);
        assert_eq!(plan.spend_randomizers[0], [0xcd; 32]);
        assert_eq!(plan.delegator_vote_randomizers.len(), 0);
    }

    #[test]
    fn test_parse_penumbra_transaction_minimal() {
        // minimal new format: prelude + empty metadata + tiny plan + no signing data
        let mut hex = String::new();

        // prelude
        hex.push_str("530310");

        // metadata: 0 assets
        hex.push_str("00");

        // plan length (4 bytes LE) - 1 byte of plan
        hex.push_str("01000000");

        // plan bytes
        hex.push_str("ff");

        let result = parse_penumbra_transaction(&hex);
        assert!(result.is_ok(), "Parse failed: {:?}", result);

        let plan = result.unwrap();
        assert!(plan.asset_metadata.is_empty());
        assert_eq!(plan.plan_bytes, vec![0xff]);
        assert!(plan.effect_hash.is_none()); // no effect hash in minimal
        assert!(plan.spend_randomizers.is_empty());
    }

    #[test]
    fn test_invalid_prelude() {
        // wrong first byte
        let result = parse_penumbra_transaction("5403100001000000ff");
        assert!(matches!(result, Err(Error::NotSubstrate(_))));

        // wrong crypto type
        let result = parse_penumbra_transaction("5301100001000000ff");
        assert!(matches!(result, Err(Error::PenumbraParseError(_))));

        // wrong tx type
        let result = parse_penumbra_transaction("5303110001000000ff");
        assert!(matches!(result, Err(Error::PenumbraParseError(_))));
    }
}
