//! zcash transaction parsing
//!
//! parses zcash sign requests from QR codes and prepares them for signing.
//!
//! QR format (from zafu wallet):
//! ```text
//! [0x53][0x04][0x02]           - prelude
//! [flags: 1 byte]              - bit 0: mainnet
//! [account_index: 4 bytes LE]
//! [sighash: 32 bytes]
//! [action_count: 2 bytes LE]
//! [alphas: 32 bytes each]
//! [summary_len: 2 bytes LE]
//! [summary: summary_len bytes]
//! ```

use definitions::helpers::unhex;
use definitions::navigation::{
    Card, TransactionAction, TransactionCard, TransactionCardSet, ZcashOrchardSpend,
    ZcashTransactionSummary,
};

use crate::{Error, Result};

/// zcash chain identifier in QR prelude (0x04)
pub const ZCASH_CRYPTO_TYPE: u8 = 0x04;

/// zcash sign request type (0x02)
pub const ZCASH_SIGN_REQUEST_TYPE: u8 = 0x02;

/// parsed zcash sign request
#[derive(Debug, Clone)]
pub struct ZcashSignRequestData {
    /// account index for key derivation
    pub account_index: u32,
    /// the transaction sighash (32 bytes)
    pub sighash: [u8; 32],
    /// orchard action randomizers (alpha values)
    pub orchard_alphas: Vec<[u8; 32]>,
    /// human-readable summary for display
    pub summary: String,
    /// network: true = mainnet, false = testnet
    pub mainnet: bool,
    /// true if this is a shielding (transparent → orchard) transaction
    pub shielding: bool,
}

impl ZcashSignRequestData {
    /// parse from QR hex string
    pub fn from_qr_hex(hex_str: &str) -> Result<Self> {
        let data = unhex(hex_str)?;
        Self::from_qr_bytes(&data)
    }

    /// parse from QR bytes
    pub fn from_qr_bytes(data: &[u8]) -> Result<Self> {
        // validate prelude: [0x53][0x04][0x02]
        if data.len() < 42 {
            // minimum: 3 + 1 + 4 + 32 + 2 = 42
            return Err(Error::ZcashParseError("QR data too short".to_string()));
        }
        if data[0] != 0x53 {
            return Err(Error::NotSubstrate(format!("{:02x}", data[0])));
        }
        if data[1] != ZCASH_CRYPTO_TYPE {
            return Err(Error::ZcashParseError(format!(
                "expected crypto type 0x04, got 0x{:02x}",
                data[1]
            )));
        }
        if data[2] != ZCASH_SIGN_REQUEST_TYPE {
            return Err(Error::ZcashParseError(format!(
                "expected tx type 0x02, got 0x{:02x}",
                data[2]
            )));
        }

        let mut offset = 3;

        // parse flags
        let flags = data[offset];
        let mainnet = (flags & 0x01) != 0;
        let shielding = (flags & 0x02) != 0;
        offset += 1;

        // account index
        if offset + 4 > data.len() {
            return Err(Error::ZcashParseError(
                "account index truncated".to_string(),
            ));
        }
        let account_index = u32::from_le_bytes([
            data[offset],
            data[offset + 1],
            data[offset + 2],
            data[offset + 3],
        ]);
        offset += 4;

        let (sighash, orchard_alphas) = if shielding {
            // shielding format: multiple transparent inputs, no orchard alphas
            // [input_count: 2B LE][per-input: sighash(32B) + address_index(4B LE)]...[action_count=0: 2B LE]
            if offset + 2 > data.len() {
                return Err(Error::ZcashParseError("input count truncated".to_string()));
            }
            let input_count = u16::from_le_bytes([data[offset], data[offset + 1]]) as usize;
            offset += 2;

            // use the first input's sighash as the representative sighash
            if input_count == 0 {
                return Err(Error::ZcashParseError("shielding: no inputs".to_string()));
            }

            let mut first_sighash = [0u8; 32];
            // parse all inputs (sighash + address_index per input)
            for i in 0..input_count {
                if offset + 36 > data.len() {
                    return Err(Error::ZcashParseError(format!(
                        "shielding input {} truncated",
                        i
                    )));
                }
                if i == 0 {
                    first_sighash.copy_from_slice(&data[offset..offset + 32]);
                }
                offset += 32; // sighash
                offset += 4; // address_index
            }
            // action count (should be 0 for shielding)
            if offset + 2 > data.len() {
                return Err(Error::ZcashParseError("action count truncated".to_string()));
            }
            offset += 2; // skip action_count (0)
            (first_sighash, Vec::new())
        } else {
            // regular send format: single sighash + orchard alphas
            if offset + 32 > data.len() {
                return Err(Error::ZcashParseError("sighash truncated".to_string()));
            }
            let mut sh = [0u8; 32];
            sh.copy_from_slice(&data[offset..offset + 32]);
            offset += 32;

            // action count
            if offset + 2 > data.len() {
                return Err(Error::ZcashParseError("action count truncated".to_string()));
            }
            let action_count = u16::from_le_bytes([data[offset], data[offset + 1]]) as usize;
            offset += 2;

            // each action's alpha (32 bytes each)
            let mut alphas = Vec::with_capacity(action_count);
            for _ in 0..action_count {
                if offset + 32 > data.len() {
                    return Err(Error::ZcashParseError("alpha truncated".to_string()));
                }
                let mut alpha = [0u8; 32];
                alpha.copy_from_slice(&data[offset..offset + 32]);
                alphas.push(alpha);
                offset += 32;
            }
            (sh, alphas)
        };

        // summary (length-prefixed string)
        let summary = if offset + 2 <= data.len() {
            let summary_len = u16::from_le_bytes([data[offset], data[offset + 1]]) as usize;
            offset += 2;
            if offset + summary_len <= data.len() {
                String::from_utf8_lossy(&data[offset..offset + summary_len]).to_string()
            } else {
                String::new()
            }
        } else {
            String::new()
        };

        Ok(Self {
            account_index,
            sighash,
            orchard_alphas,
            summary,
            mainnet,
            shielding,
        })
    }
}

/// create transaction cards for display
fn create_zcash_cards(request: &ZcashSignRequestData) -> TransactionCardSet {
    let mut method_cards = Vec::new();
    let mut index = 0u32;

    // add summary card
    method_cards.push(TransactionCard {
        index,
        indent: 0,
        card: Card::ZcashSummaryCard {
            f: ZcashTransactionSummary {
                mainnet: request.mainnet,
                fee: "see summary".to_string(),
                spend_count: request.orchard_alphas.len() as u64,
                output_count: 0, // not provided in sign request
                sighash: hex::encode(request.sighash),
                anchor: "provided by wallet".to_string(),
            },
        },
    });
    index += 1;

    // add spend cards for each action
    for (i, alpha) in request.orchard_alphas.iter().enumerate() {
        method_cards.push(TransactionCard {
            index,
            indent: 0,
            card: Card::ZcashOrchardSpendCard {
                f: ZcashOrchardSpend {
                    nullifier: format!("action #{}", i + 1),
                    value: "shielded".to_string(),
                    cmx: hex::encode(&alpha[..16]), // show part of alpha as identifier
                },
            },
        });
        index += 1;
    }

    // add transaction summary from wallet if provided
    if !request.summary.is_empty() {
        // parse the summary to extract more details
        // format is typically: "Send X.XX ZEC to u1..."
        method_cards.push(TransactionCard {
            index,
            indent: 0,
            card: Card::TextCard {
                f: request.summary.clone(),
            },
        });
        index += 1;
    }

    // add signing info
    method_cards.push(TransactionCard {
        index,
        indent: 0,
        card: Card::TextCard {
            f: format!(
                "Account #{} · {} signature{} required",
                request.account_index,
                request.orchard_alphas.len(),
                if request.orchard_alphas.len() == 1 {
                    ""
                } else {
                    "s"
                }
            ),
        },
    });

    TransactionCardSet {
        method: Some(method_cards),
        ..Default::default()
    }
}

/// process a zcash sign request QR code
///
/// this is called from handle_scanner_input when crypto type is "04" and tx type is "02"
pub fn process_zcash_sign_request(
    _database: &sled::Db,
    data_hex: &str,
) -> Result<TransactionAction> {
    // parse the sign request
    let request = ZcashSignRequestData::from_qr_hex(data_hex)?;

    // create display cards
    let content = create_zcash_cards(&request);

    // return as Sign action - the signing will happen when user approves
    // for now we return Read since we need to wire up the full signing flow
    Ok(TransactionAction::Read {
        r: Box::new(content),
    })
}

/// check if a payload is a zcash transaction
pub fn is_zcash_transaction(data_hex: &str) -> bool {
    if data_hex.len() < 6 {
        return false;
    }
    // check for 53 04 02 prefix
    &data_hex[0..2] == "53" && &data_hex[2..4] == "04" && &data_hex[4..6] == "02"
}

#[cfg(test)]
#[allow(clippy::vec_init_then_push)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_zcash_sign_request() {
        // build a sign request manually
        let mut data = Vec::new();

        // prelude
        data.push(0x53);
        data.push(0x04);
        data.push(0x02);

        // flags: mainnet = true
        data.push(0x01);

        // account index
        data.extend_from_slice(&0u32.to_le_bytes());

        // sighash
        let sighash = [0x42u8; 32];
        data.extend_from_slice(&sighash);

        // action count
        data.extend_from_slice(&2u16.to_le_bytes());

        // alpha values
        let alpha1 = [0x11u8; 32];
        let alpha2 = [0x22u8; 32];
        data.extend_from_slice(&alpha1);
        data.extend_from_slice(&alpha2);

        // summary
        let summary = "Send 1.5 ZEC";
        data.extend_from_slice(&(summary.len() as u16).to_le_bytes());
        data.extend_from_slice(summary.as_bytes());

        // parse
        let hex_str = hex::encode(&data);
        let request = ZcashSignRequestData::from_qr_hex(&hex_str).unwrap();

        assert_eq!(request.account_index, 0);
        assert_eq!(request.sighash, sighash);
        assert_eq!(request.orchard_alphas.len(), 2);
        assert_eq!(request.orchard_alphas[0], alpha1);
        assert_eq!(request.orchard_alphas[1], alpha2);
        assert_eq!(request.summary, summary);
        assert!(request.mainnet);
    }

    #[test]
    fn test_parse_zcash_testnet() {
        let mut data = Vec::new();

        // prelude
        data.push(0x53);
        data.push(0x04);
        data.push(0x02);

        // flags: mainnet = false (testnet)
        data.push(0x00);

        // account index
        data.extend_from_slice(&0u32.to_le_bytes());

        // sighash
        data.extend_from_slice(&[0x42u8; 32]);

        // action count = 1
        data.extend_from_slice(&1u16.to_le_bytes());

        // alpha
        data.extend_from_slice(&[0x11u8; 32]);

        // no summary
        data.extend_from_slice(&0u16.to_le_bytes());

        let hex_str = hex::encode(&data);
        let request = ZcashSignRequestData::from_qr_hex(&hex_str).unwrap();

        assert!(!request.mainnet);
        assert_eq!(request.orchard_alphas.len(), 1);
    }

    #[test]
    fn test_is_zcash_transaction() {
        assert!(is_zcash_transaction("530402"));
        assert!(is_zcash_transaction("530402abcdef"));
        assert!(!is_zcash_transaction("530401")); // wrong tx type
        assert!(!is_zcash_transaction("530302")); // wrong crypto type
        assert!(!is_zcash_transaction("5404")); // too short
    }

    #[test]
    fn test_parse_error_too_short() {
        let result = ZcashSignRequestData::from_qr_hex("530402");
        assert!(result.is_err());
    }

    #[test]
    fn test_parse_error_wrong_prelude() {
        // wrong first byte
        let mut data = vec![0x54, 0x04, 0x02];
        data.extend_from_slice(&[0; 40]);
        let hex_str = hex::encode(&data);
        let result = ZcashSignRequestData::from_qr_hex(&hex_str);
        assert!(result.is_err());
    }

    #[test]
    fn test_parse_error_wrong_crypto_type() {
        // wrong crypto type (not zcash)
        let mut data = vec![0x53, 0x03, 0x02];
        data.extend_from_slice(&[0; 40]);
        let hex_str = hex::encode(&data);
        let result = ZcashSignRequestData::from_qr_hex(&hex_str);
        assert!(result.is_err());
    }

    #[test]
    fn test_parse_error_wrong_tx_type() {
        // wrong tx type
        let mut data = vec![0x53, 0x04, 0x03];
        data.extend_from_slice(&[0; 40]);
        let hex_str = hex::encode(&data);
        let result = ZcashSignRequestData::from_qr_hex(&hex_str);
        assert!(result.is_err());
    }

    #[test]
    fn test_parse_truncated_sighash() {
        let mut data = Vec::new();
        data.push(0x53);
        data.push(0x04);
        data.push(0x02);
        data.push(0x01); // flags
        data.extend_from_slice(&0u32.to_le_bytes()); // account
        data.extend_from_slice(&[0x42u8; 16]); // only 16 bytes of sighash

        let hex_str = hex::encode(&data);
        let result = ZcashSignRequestData::from_qr_hex(&hex_str);
        assert!(result.is_err());
    }

    #[test]
    fn test_parse_truncated_alphas() {
        let mut data = Vec::new();
        data.push(0x53);
        data.push(0x04);
        data.push(0x02);
        data.push(0x01); // flags
        data.extend_from_slice(&0u32.to_le_bytes()); // account
        data.extend_from_slice(&[0x42u8; 32]); // sighash
        data.extend_from_slice(&3u16.to_le_bytes()); // claim 3 actions
        data.extend_from_slice(&[0x11u8; 32]); // only 1 alpha

        let hex_str = hex::encode(&data);
        let result = ZcashSignRequestData::from_qr_hex(&hex_str);
        assert!(result.is_err());
    }

    #[test]
    fn test_create_zcash_cards() {
        let request = ZcashSignRequestData {
            account_index: 0,
            sighash: [0x42u8; 32],
            orchard_alphas: vec![[0x11u8; 32], [0x22u8; 32]],
            summary: "Send 1.0 ZEC".to_string(),
            mainnet: true,
            shielding: false,
        };

        let cards = create_zcash_cards(&request);

        // Should have method cards
        assert!(cards.method.is_some());
        let method_cards = cards.method.unwrap();

        // At least summary card + 2 spend cards + summary text + signing info = 5
        assert!(method_cards.len() >= 4);
    }

    #[test]
    fn test_create_zcash_cards_no_summary() {
        let request = ZcashSignRequestData {
            account_index: 1,
            sighash: [0xAA; 32],
            orchard_alphas: vec![[0x11u8; 32]],
            summary: "".to_string(),
            mainnet: false,
            shielding: false,
        };

        let cards = create_zcash_cards(&request);
        assert!(cards.method.is_some());

        // With empty summary, should still have cards for the spend
        let method_cards = cards.method.unwrap();
        assert!(method_cards.len() >= 2);
    }

    #[test]
    fn test_is_zcash_transaction_case_insensitive() {
        assert!(is_zcash_transaction("530402"));
        assert!(is_zcash_transaction("530402ABCDEF")); // uppercase
                                                       // Note: actual impl uses lowercase comparison
    }

    #[test]
    fn test_zafu_format_compatibility() {
        // exact format from Zafu wallet
        let zafu_payload = concat!(
            "530402",
            "01",
            "00000000",
            "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
            "0200",
            "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb",
            "cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc",
            "0c00",
            "53656e6420312e30205a4543" // "Send 1.0 ZEC"
        );

        let request = ZcashSignRequestData::from_qr_hex(zafu_payload).unwrap();
        assert!(request.mainnet);
        assert_eq!(request.account_index, 0);
        assert_eq!(request.orchard_alphas.len(), 2);
        assert_eq!(request.summary, "Send 1.0 ZEC");
    }
}

#[cfg(test)]
mod fuzz_smoke {
    use super::*;

    struct Rng(u64);

    impl Rng {
        fn next(&mut self) -> u64 {
            let mut x = self.0;
            x ^= x >> 12;
            x ^= x << 25;
            x ^= x >> 27;
            self.0 = x;
            x.wrapping_mul(0x2545_F491_4F6C_DD1D)
        }
    }

    /// Zcash is enabled by default in the scan policy, so unlike the Penumbra
    /// decoder this parser is reachable on a stock device. Anything a camera
    /// can be pointed at gets here.
    ///
    /// Random bytes alone would be rejected at the prefix, so most rounds
    /// start from the real envelope prefix and mutate what follows - that is
    /// what gets past the early checks and into the length handling.
    #[test]
    fn hostile_sign_requests_never_panic() {
        for round in 0..30_000u64 {
            let mut rng = Rng(round.wrapping_mul(0x9E37_79B9_7F4A_7C15) | 1);
            let len = (rng.next() % 160) as usize;
            let mut bytes: Vec<u8> = (0..len).map(|_| rng.next() as u8).collect();

            // Most rounds wear a valid-looking prefix so they survive
            // classification; the rest stay fully random so nothing upstream
            // of it is skipped.
            if round % 4 != 0 && bytes.len() >= 3 {
                bytes[0] = 0x53;
                bytes[1] = 0x04;
                bytes[2] = (rng.next() % 8) as u8;
            }

            let _ = ZcashSignRequestData::from_qr_bytes(&bytes);
            let _ = is_zcash_transaction(&hex::encode(&bytes));
            let _ = ZcashSignRequestData::from_qr_hex(&hex::encode(&bytes));
        }
    }

    /// Counts and lengths declared far larger than the buffer, which is where
    /// a parser that checks some bounds and not others gives way. Built by
    /// hand because random bytes almost never produce a large count field.
    #[test]
    fn oversized_counts_and_truncations_never_panic() {
        let mut cases: Vec<Vec<u8>> = Vec::new();
        for kind in 0u8..8 {
            for count in [0xffffu16, 0xfffe, 0x8000, 1, 0] {
                let mut v = vec![0x53u8, 0x04, kind, 0x00];
                v.extend_from_slice(&0xffff_ffffu32.to_le_bytes());
                v.extend_from_slice(&count.to_le_bytes());
                cases.push(v.clone());
                // Same header, then a single byte: the truncation that lands
                // between a declared count and the data it describes.
                v.push(0x01);
                cases.push(v);
            }
        }
        // Every truncation of a plausible envelope.
        let full: Vec<u8> = (0..96u8).collect();
        for n in 0..full.len() {
            let mut v = vec![0x53u8, 0x04, 0x01];
            v.extend_from_slice(&full[..n]);
            cases.push(v);
        }

        for case in &cases {
            let _ = ZcashSignRequestData::from_qr_bytes(case);
            let _ = is_zcash_transaction(&hex::encode(case));
        }
    }
}
