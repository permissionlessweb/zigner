//! zcash transaction signing
//!
//! implements transparent (secp256k1) and orchard (redpallas) signing for zcash.
//! based on pczt (partially constructed zcash transaction) format.

use crate::{Error, Result};

#[cfg(feature = "zcash")]
use zeroize::{Zeroize, ZeroizeOnDrop};

// ============================================================================
// constants
// ============================================================================

/// zcash bip44 coin type
pub const ZCASH_COIN_TYPE: u32 = 133;

/// zcash transparent bip44 path template: m/44'/133'/account'/change/index
pub fn transparent_path(account: u32, change: u32, index: u32) -> String {
    format!(
        "m/44'/{}'/{}'/{}/{}",
        ZCASH_COIN_TYPE, account, change, index
    )
}

/// zcash orchard zip32 path template: m/32'/133'/account'
pub fn orchard_path(account: u32) -> String {
    format!("m/32'/{}'/{}'", ZCASH_COIN_TYPE, account)
}

// ============================================================================
// transparent signing (secp256k1)
// ============================================================================

/// transparent spending key bytes - derived from bip44
#[derive(Clone, Zeroize, ZeroizeOnDrop)]
#[cfg(feature = "zcash")]
pub struct TransparentSpendingKey(pub [u8; 32]);

#[cfg(feature = "zcash")]
impl TransparentSpendingKey {
    /// create from raw bytes
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    /// derive from seed phrase using bip44 path
    pub fn from_seed_phrase(
        seed_phrase: &str,
        account: u32,
        change: u32,
        index: u32,
    ) -> Result<Self> {
        use bip32::{Mnemonic, XPrv};

        // parse mnemonic
        let mnemonic = Mnemonic::new(seed_phrase, bip32::Language::English)
            .map_err(|e| Error::ZcashKeyDerivation(format!("invalid mnemonic: {e}")))?;

        // derive seed
        let seed = mnemonic.to_seed("");

        // derive child key from bip44 path
        let path = transparent_path(account, change, index);
        let child_key = XPrv::derive_from_path(
            &seed,
            &path
                .parse()
                .map_err(|e| Error::ZcashKeyDerivation(format!("invalid derivation path: {e}")))?,
        )
        .map_err(|e| Error::ZcashKeyDerivation(format!("key derivation failed: {e}")))?;

        // extract 32-byte private key
        let mut key_bytes = [0u8; 32];
        key_bytes.copy_from_slice(&child_key.to_bytes()[..32]);

        Ok(Self(key_bytes))
    }

    /// get raw bytes
    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }
}

/// derive compressed public key (33 bytes) from spending key
#[cfg(feature = "zcash")]
pub fn derive_compressed_pubkey(secret_key: &TransparentSpendingKey) -> Result<Vec<u8>> {
    use secp256k1::{PublicKey, Secp256k1, SecretKey};
    let secp = Secp256k1::new();
    let sk = SecretKey::from_slice(&secret_key.0)
        .map_err(|e| Error::ZcashKeyDerivation(format!("invalid secret key: {e}")))?;
    let pk = PublicKey::from_secret_key(&secp, &sk);
    Ok(pk.serialize().to_vec())
}

/// derive transparent address from spending key
#[cfg(feature = "zcash")]
pub fn derive_transparent_address(
    secret_key: &TransparentSpendingKey,
    mainnet: bool,
) -> Result<String> {
    use ripemd::Ripemd160;
    use secp256k1::{PublicKey, Secp256k1, SecretKey};
    use sha2::{Digest, Sha256};

    let secp = Secp256k1::new();
    let sk = SecretKey::from_slice(&secret_key.0)
        .map_err(|e| Error::ZcashKeyDerivation(format!("invalid secret key: {e}")))?;
    let pk = PublicKey::from_secret_key(&secp, &sk);

    // P2PKH: RIPEMD160(SHA256(compressed_pubkey))
    let compressed = pk.serialize();
    let sha = Sha256::digest(compressed);
    let hash = Ripemd160::digest(sha);

    // prefix bytes
    let prefix = if mainnet {
        [0x1C, 0xB8] // mainnet t1
    } else {
        [0x1D, 0x25] // testnet tm
    };

    let mut payload = Vec::with_capacity(22);
    payload.extend_from_slice(&prefix);
    payload.extend_from_slice(&hash);

    Ok(bs58::encode(payload).with_check().into_string())
}

// ============================================================================
// orchard signing (redpallas on pallas curve)
// ============================================================================

/// orchard spending key bytes - derived from zip32
#[derive(Clone, Zeroize, ZeroizeOnDrop)]
#[cfg(feature = "zcash")]
pub struct OrchardSpendingKey(pub [u8; 32]);

#[cfg(feature = "zcash")]
impl OrchardSpendingKey {
    /// create from raw bytes
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    /// derive from seed phrase using zip32 path
    ///
    /// this implements the orchard key derivation from ZIP-32:
    /// m_orchard / purpose' / coin_type' / account'
    pub fn from_seed_phrase(seed_phrase: &str, account: u32) -> Result<Self> {
        use bip32::Mnemonic;
        use orchard::keys::SpendingKey;
        use zip32::AccountId;

        // parse mnemonic
        let mnemonic = Mnemonic::new(seed_phrase, bip32::Language::English)
            .map_err(|e| Error::ZcashKeyDerivation(format!("invalid mnemonic: {e}")))?;

        // derive seed
        let seed = mnemonic.to_seed("");
        let seed_bytes: &[u8] = seed.as_bytes();

        // convert account to AccountId
        let account_id = AccountId::try_from(account)
            .map_err(|_| Error::ZcashKeyDerivation("invalid account index".to_string()))?;

        // use orchard crate's zip32 derivation
        let sk =
            SpendingKey::from_zip32_seed(seed_bytes, ZCASH_COIN_TYPE, account_id).map_err(|e| {
                Error::ZcashKeyDerivation(format!("orchard key derivation failed: {e:?}"))
            })?;

        Ok(Self(*sk.to_bytes()))
    }

    /// Compute the ZIP-32 seed fingerprint (32 bytes) per ZIP 32 "Seed
    /// Fingerprints", over the same 64-byte BIP39 seed used for key derivation
    /// (`mnemonic.to_seed("")`). This is what Keystone/Zashi/vizor compute via
    /// `zip32::fingerprint::SeedFingerprint::from_seed`, so an account exported
    /// from zigner is recognized as belonging to the same seed.
    ///
    /// Note: this is NOT `SHA256(mnemonic)[..16]`. That older form was
    /// non-conformant on both counts - 16 bytes instead of 32, and the wrong
    /// preimage - which is why Keystone-compatible wallets rejected the import
    /// ("seed fingerprint must be 32 bytes"). `from_seed` prepends the seed
    /// length byte before hashing (ZIP 32), which is why we defer to the crate
    /// rather than hand-rolling BLAKE2b.
    pub fn seed_fingerprint(seed_phrase: &str) -> Result<[u8; 32]> {
        use bip32::Mnemonic;
        use zip32::fingerprint::SeedFingerprint;

        let mnemonic = Mnemonic::new(seed_phrase, bip32::Language::English)
            .map_err(|e| Error::ZcashKeyDerivation(format!("invalid mnemonic: {e}")))?;
        let seed = mnemonic.to_seed("");
        SeedFingerprint::from_seed(seed.as_bytes())
            .map(|fp| fp.to_bytes())
            .ok_or_else(|| {
                Error::ZcashKeyDerivation(
                    "seed length invalid for ZIP-32 seed fingerprint".to_string(),
                )
            })
    }

    /// get raw bytes
    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }

    /// convert to orchard SpendingKey
    #[cfg(feature = "zcash")]
    pub fn to_spending_key(&self) -> Result<orchard::keys::SpendingKey> {
        orchard::keys::SpendingKey::from_bytes(self.0)
            .into_option()
            .ok_or_else(|| Error::ZcashKeyDerivation("invalid orchard spending key".to_string()))
    }

    /// get full viewing key bytes (96 bytes)
    #[cfg(feature = "zcash")]
    pub fn fvk_bytes(&self) -> [u8; 96] {
        use orchard::keys::FullViewingKey;
        let sk = self.to_spending_key().expect("valid spending key");
        let fvk = FullViewingKey::from(&sk);
        fvk.to_bytes()
    }

    /// get receiving address as unified address string (default diversifier index 0)
    #[cfg(feature = "zcash")]
    #[allow(deprecated)]
    pub fn get_address(&self, mainnet: bool) -> String {
        self.get_address_at(0, mainnet)
    }

    /// get receiving address at a specific diversifier index
    ///
    /// orchard supports unlimited diversified addresses from the same FVK.
    /// each diversifier index produces a unique address that maps to the same wallet.
    #[cfg(feature = "zcash")]
    #[allow(deprecated)]
    pub fn get_address_at(&self, diversifier_index: u32, mainnet: bool) -> String {
        use orchard::keys::FullViewingKey;
        use zcash_address::unified::{Address as UnifiedAddress, Encoding, Receiver};
        use zcash_protocol::consensus::NetworkType as Network;

        let sk = self.to_spending_key().expect("valid spending key");
        let fvk = FullViewingKey::from(&sk);

        let address = fvk.address_at(diversifier_index, orchard::keys::Scope::External);

        // Build unified address with just Orchard receiver
        let orchard_receiver = Receiver::Orchard(address.to_raw_address_bytes());
        let network = if mainnet {
            Network::Main
        } else {
            Network::Test
        };

        UnifiedAddress::try_from_items(vec![orchard_receiver])
            .expect("valid receivers")
            .encode(&network)
    }

    /// get unified full viewing key (UFVK) string for wallet import (orchard only)
    /// prefer get_ufvk_with_transparent when seed phrase is available
    #[cfg(feature = "zcash")]
    #[allow(deprecated)]
    pub fn get_ufvk(&self, mainnet: bool) -> String {
        use orchard::keys::FullViewingKey;
        use zcash_address::unified::{Encoding, Fvk, Ufvk};
        use zcash_protocol::consensus::NetworkType as Network;

        let sk = self.to_spending_key().expect("valid spending key");
        let fvk = FullViewingKey::from(&sk);

        let orchard_fvk = Fvk::Orchard(fvk.to_bytes());
        let network = if mainnet {
            Network::Main
        } else {
            Network::Test
        };

        Ufvk::try_from_items(vec![orchard_fvk])
            .expect("valid fvk items")
            .encode(&network)
    }

    /// get UFVK with both orchard and transparent components
    /// the transparent component allows watch-only wallets to derive t-addresses
    #[cfg(feature = "zcash")]
    #[allow(deprecated)]
    pub fn get_ufvk_with_transparent(
        seed_phrase: &str,
        account: u32,
        mainnet: bool,
    ) -> Result<String> {
        use orchard::keys::FullViewingKey;
        use zcash_address::unified::{Encoding, Fvk, Ufvk};
        use zcash_protocol::consensus::NetworkType as Network;

        let mnemonic = bip32::Mnemonic::new(seed_phrase, bip32::Language::English)
            .map_err(|e| Error::ZcashKeyDerivation(format!("invalid mnemonic: {e}")))?;
        let seed = mnemonic.to_seed("");
        let seed_bytes: &[u8] = seed.as_bytes();

        // orchard FVK
        let account_id = zip32::AccountId::try_from(account)
            .map_err(|_| Error::ZcashKeyDerivation("invalid account index".to_string()))?;
        let orchard_sk =
            orchard::keys::SpendingKey::from_zip32_seed(seed_bytes, ZCASH_COIN_TYPE, account_id)
                .map_err(|e| {
                    Error::ZcashKeyDerivation(format!("orchard key derivation failed: {e:?}"))
                })?;
        let fvk = FullViewingKey::from(&orchard_sk);
        let orchard_fvk = Fvk::Orchard(fvk.to_bytes());

        // transparent account pubkey: derive m/44'/133'/account'
        let root_xprv = bip32::XPrv::new(seed_bytes)
            .map_err(|e| Error::ZcashKeyDerivation(format!("bip32 root key failed: {e}")))?;
        let purpose = bip32::ChildNumber::new(44, true)
            .map_err(|e| Error::ZcashKeyDerivation(format!("bip32 purpose: {e}")))?;
        let coin_type = bip32::ChildNumber::new(ZCASH_COIN_TYPE, true)
            .map_err(|e| Error::ZcashKeyDerivation(format!("bip32 coin_type: {e}")))?;
        let account_child = bip32::ChildNumber::new(account, true)
            .map_err(|e| Error::ZcashKeyDerivation(format!("bip32 account: {e}")))?;

        let account_xprv = root_xprv
            .derive_child(purpose)
            .and_then(|k| k.derive_child(coin_type))
            .and_then(|k| k.derive_child(account_child))
            .map_err(|e| Error::ZcashKeyDerivation(format!("bip32 derivation failed: {e}")))?;

        let account_xpub = bip32::XPub::from(&account_xprv);

        // Fvk::P2pkh is 65 bytes: chain_code (32) + compressed_pubkey (33)
        let mut p2pkh_data = [0u8; 65];
        p2pkh_data[..32].copy_from_slice(&account_xpub.attrs().chain_code);
        use bip32::PublicKey as _;
        let pubkey_bytes = account_xpub.public_key().to_bytes();
        p2pkh_data[32..65].copy_from_slice(&pubkey_bytes);
        let transparent_fvk = Fvk::P2pkh(p2pkh_data);

        let network = if mainnet {
            Network::Main
        } else {
            Network::Test
        };

        let ufvk = Ufvk::try_from_items(vec![orchard_fvk, transparent_fvk])
            .map_err(|e| Error::ZcashKeyDerivation(format!("failed to build UFVK: {e}")))?;

        Ok(ufvk.encode(&network))
    }
}

/// derive orchard full viewing key from spending key
#[cfg(feature = "zcash")]
pub fn derive_orchard_fvk(spending_key: &OrchardSpendingKey) -> Result<OrchardFullViewingKey> {
    use orchard::keys::FullViewingKey;

    let sk = spending_key.to_spending_key()?;
    let fvk = FullViewingKey::from(&sk);

    Ok(OrchardFullViewingKey(fvk.to_bytes()))
}

/// orchard full viewing key (96 bytes)
#[derive(Clone, Debug)]
pub struct OrchardFullViewingKey(pub [u8; 96]);

impl OrchardFullViewingKey {
    /// get raw bytes
    pub fn as_bytes(&self) -> &[u8; 96] {
        &self.0
    }

    /// encode as bech32m with "zviewo" prefix (orchard FVK)
    #[cfg(feature = "zcash")]
    pub fn to_bech32m(&self, mainnet: bool) -> Result<String> {
        use bech32::{Bech32m, Hrp};

        let hrp_str = if mainnet {
            "zviewo"
        } else {
            "zviewtestorchard"
        };
        let hrp = Hrp::parse(hrp_str)
            .map_err(|e| Error::ZcashKeyDerivation(format!("invalid hrp: {e}")))?;

        let encoded = bech32::encode::<Bech32m>(hrp, &self.0)
            .map_err(|e| Error::ZcashKeyDerivation(format!("bech32 encode error: {e}")))?;

        Ok(encoded)
    }
}

// ============================================================================
// QR code type constants (must match zafu-wasm)
// ============================================================================

/// QR code type for Zcash FVK export
pub const QR_TYPE_ZCASH_FVK_EXPORT: u8 = 0x01;
/// QR code type for Zcash notes sync (ur:zcash-notes)
pub const QR_TYPE_ZCASH_NOTES: u8 = 0x04;

// ============================================================================
// zcash note sync types
// ============================================================================

/// A verified Zcash Orchard note (merkle path checked against anchor)
#[derive(Debug, Clone)]
pub struct ZcashVerifiedNote {
    /// Note value in zatoshis
    pub value: u64,
    /// Nullifier (32 bytes, unique identifier for spend tracking)
    pub nullifier: [u8; 32],
    /// Note commitment x-coordinate (cmx)
    pub cmx: [u8; 32],
    /// Global position in the orchard commitment tree
    pub position: u32,
    /// Block height where this note was mined
    pub block_height: u32,
}

/// A note with its merkle authentication path (pre-verification)
#[derive(Debug, Clone)]
pub struct ZcashNoteWithPath {
    /// Note value in zatoshis
    pub value: u64,
    /// Nullifier
    pub nullifier: [u8; 32],
    /// Note commitment x-coordinate
    pub cmx: [u8; 32],
    /// Position in commitment tree
    pub position: u32,
    /// Block height
    pub block_height: u32,
    /// Merkle authentication path (32 sibling hashes, from leaf to root)
    pub merkle_path: [[u8; 32]; 32],
}

/// Bundle of notes with anchor, received via QR
#[derive(Debug, Clone)]
pub struct ZcashNotesBundle {
    /// Orchard tree root that all notes must verify against
    pub anchor: [u8; 32],
    /// Block height of the anchor
    pub anchor_height: u32,
    /// Network: true = mainnet
    pub mainnet: bool,
    /// Notes with merkle paths
    pub notes: Vec<ZcashNoteWithPath>,
    /// Optional anchor attestation signature (variable length).
    /// 64 bytes = ed25519 (rotko verifier), 96 bytes = FROST (threshold group).
    pub anchor_attestation: Option<Vec<u8>>,
    /// Optional Unix timestamp (seconds) of the anchor block's header. Chain
    /// truth for "as of block N" — unlike the device clock we stamp at import.
    /// Absent from bundles produced before this field existed.
    pub anchor_time: Option<u32>,
}

/// Result of note sync verification
#[derive(Debug, Clone)]
pub struct ZcashNoteSyncResult {
    /// Number of notes that passed merkle verification
    pub notes_verified: u32,
    /// Total balance of verified notes (zatoshis)
    pub total_balance: u64,
    /// Anchor hex
    pub anchor_hex: String,
    /// Anchor height
    pub anchor_height: u32,
    /// Network
    pub mainnet: bool,
    /// Whether the anchor was verified via FROST group attestation
    pub anchor_verified: bool,
}

// ============================================================================
// merkle path verification
// ============================================================================

/// Verify a note's merkle path against the anchor
///
/// Uses orchard's MerklePath::root() which implements MerkleCRH^Orchard
/// (Sinsemilla hash) per ZIP-244.
#[cfg(feature = "zcash")]
pub fn verify_merkle_path(
    cmx_bytes: &[u8; 32],
    position: u32,
    path: &[[u8; 32]; 32],
    anchor_bytes: &[u8; 32],
) -> Result<bool> {
    use orchard::note::ExtractedNoteCommitment;
    use orchard::tree::{Anchor, MerkleHashOrchard, MerklePath};

    // Parse cmx
    let cmx = Option::from(ExtractedNoteCommitment::from_bytes(cmx_bytes))
        .ok_or_else(|| Error::ZcashParsing("invalid cmx bytes".to_string()))?;

    // Parse anchor
    let anchor = Option::from(Anchor::from_bytes(*anchor_bytes))
        .ok_or_else(|| Error::ZcashParsing("invalid anchor bytes".to_string()))?;

    // Parse merkle path siblings
    let empty = Option::from(MerkleHashOrchard::from_bytes(&[0u8; 32])).unwrap();
    let mut auth_path = [empty; 32];
    for (i, sibling) in path.iter().enumerate() {
        auth_path[i] = Option::from(MerkleHashOrchard::from_bytes(sibling))
            .ok_or_else(|| Error::ZcashParsing(format!("invalid merkle sibling at level {i}")))?;
    }

    let merkle_path = MerklePath::from_parts(position, auth_path);
    let computed_root = merkle_path.root(cmx);

    Ok(computed_root == anchor)
}

// ============================================================================
// FROST anchor attestation — verify anchor via threshold group signature
// ============================================================================

/// Re-export the attestation digest builder from frost-spend.
#[cfg(feature = "zcash")]
pub use frost_spend::attestation::attestation_digest;

// ============================================================================
// CBOR codec for zcash-notes bundle
// ============================================================================

/// Encode a ZcashNotesBundle to CBOR bytes
///
/// CBOR structure:
/// ```text
/// map(4) {
///   1: bstr(32)     -- anchor
///   2: uint          -- anchor_height
///   3: bool          -- mainnet
///   4: array [       -- notes
///     map(6) {
///       1: uint      -- value
///       2: bstr(32)  -- nullifier
///       3: bstr(32)  -- cmx
///       4: uint      -- position
///       5: uint      -- block_height
///       6: array(32) [bstr(32)]  -- merkle_path
///     }, ...
///   ]
/// }
/// ```
pub fn encode_notes_bundle_to_cbor(bundle: &ZcashNotesBundle) -> Vec<u8> {
    let mut cbor = Vec::new();

    // map(5) — version + anchor + height + mainnet + notes
    // (attestation adds a 6th key if present)
    let map_len = 5 + if bundle.anchor_attestation.is_some() {
        1
    } else {
        0
    };
    cbor.push(0xa0 | map_len as u8);

    // key 0: version (uint)
    cbor.push(0x00);
    cbor.push(0x01); // version 1

    // key 1: anchor (bstr 32)
    cbor.push(0x01);
    cbor.push(0x58);
    cbor.push(0x20); // bytes(32)
    cbor.extend_from_slice(&bundle.anchor);

    // key 2: anchor_height (uint)
    cbor.push(0x02);
    cbor_encode_uint(&mut cbor, bundle.anchor_height as u64);

    // key 3: mainnet (bool)
    cbor.push(0x03);
    cbor.push(if bundle.mainnet { 0xf5 } else { 0xf4 }); // true / false

    // key 4: notes array
    cbor.push(0x04);
    cbor_encode_array_len(&mut cbor, bundle.notes.len());

    for note in &bundle.notes {
        // map(6) per note
        cbor.push(0xa6);

        // 1: value
        cbor.push(0x01);
        cbor_encode_uint(&mut cbor, note.value);

        // 2: nullifier
        cbor.push(0x02);
        cbor.push(0x58);
        cbor.push(0x20);
        cbor.extend_from_slice(&note.nullifier);

        // 3: cmx
        cbor.push(0x03);
        cbor.push(0x58);
        cbor.push(0x20);
        cbor.extend_from_slice(&note.cmx);

        // 4: position
        cbor.push(0x04);
        cbor_encode_uint(&mut cbor, note.position as u64);

        // 5: block_height
        cbor.push(0x05);
        cbor_encode_uint(&mut cbor, note.block_height as u64);

        // 6: merkle_path (array of 32 bstr(32))
        cbor.push(0x06);
        cbor.push(0x98);
        cbor.push(0x20); // array(32)
        for sibling in &note.merkle_path {
            cbor.push(0x58);
            cbor.push(0x20);
            cbor.extend_from_slice(sibling);
        }
    }

    // key 5: anchor_attestation (bstr, variable length) — optional
    if let Some(ref att) = bundle.anchor_attestation {
        cbor.push(0x05);
        cbor.push(0x58);
        cbor.push(att.len() as u8);
        cbor.extend_from_slice(att);
    }

    cbor
}

/// Decode a ZcashNotesBundle from CBOR bytes
pub fn decode_notes_bundle_from_cbor(data: &[u8]) -> Result<ZcashNotesBundle> {
    let mut offset = 0;

    // map(4)
    if data.is_empty() {
        return Err(Error::ZcashParsing("empty CBOR data".to_string()));
    }
    let (map_len, consumed) = cbor_decode_map_len(data, offset)?;
    offset = consumed;
    if map_len < 4 {
        return Err(Error::ZcashParsing(format!(
            "expected map(4+), got map({map_len})"
        )));
    }

    let mut anchor = [0u8; 32];
    let mut anchor_height = 0u32;
    let mut mainnet = true;
    let mut notes = Vec::new();
    let mut anchor_attestation: Option<Vec<u8>> = None;
    let mut anchor_time: Option<u32> = None;

    for _ in 0..map_len {
        let (key, consumed) = cbor_decode_uint(data, offset)?;
        offset = consumed;

        match key {
            1 => {
                // anchor: bstr(32)
                let (bytes, consumed) = cbor_decode_bstr(data, offset)?;
                offset = consumed;
                if bytes.len() != 32 {
                    return Err(Error::ZcashParsing(format!(
                        "anchor must be 32 bytes, got {}",
                        bytes.len()
                    )));
                }
                anchor.copy_from_slice(&bytes);
            }
            2 => {
                // anchor_height: uint
                let (val, consumed) = cbor_decode_uint(data, offset)?;
                offset = consumed;
                anchor_height = u32::try_from(val)
                    .map_err(|_| Error::ZcashParsing("anchor_height out of range".to_string()))?;
            }
            3 => {
                // mainnet: bool
                if offset >= data.len() {
                    return Err(Error::ZcashParsing("truncated bool".to_string()));
                }
                mainnet = match data[offset] {
                    0xf5 => true,
                    0xf4 => false,
                    b => return Err(Error::ZcashParsing(format!("expected bool, got 0x{b:02x}"))),
                };
                offset += 1;
            }
            4 => {
                // notes: array
                let (arr_len, consumed) = cbor_decode_array_len(data, offset)?;
                offset = consumed;

                for _ in 0..arr_len {
                    let (note, consumed) = cbor_decode_note_with_path(data, offset)?;
                    offset = consumed;
                    notes.push(note);
                }
            }
            5 => {
                // anchor_attestation: bstr (64 = ed25519, 96 = FROST)
                let (bytes, consumed) = cbor_decode_bstr(data, offset)?;
                offset = consumed;
                if bytes.len() != 64 && bytes.len() != 96 {
                    return Err(Error::ZcashParsing(format!(
                        "attestation must be 64 bytes (ed25519) or 96 bytes (FROST), got {}",
                        bytes.len()
                    )));
                }
                anchor_attestation = Some(bytes);
            }
            7 => {
                // anchor_time: uint (unix seconds of the anchor block header)
                let (val, consumed) = cbor_decode_uint(data, offset)?;
                offset = consumed;
                anchor_time =
                    Some(u32::try_from(val).map_err(|_| {
                        Error::ZcashParsing("anchor_time out of range".to_string())
                    })?);
            }
            _ => {
                // skip unknown keys
                let consumed = cbor_skip_value(data, offset)?;
                offset = consumed;
            }
        }
    }

    if offset != data.len() {
        return Err(Error::ZcashParsing(format!(
            "{} trailing bytes after the notes bundle",
            data.len() - offset
        )));
    }

    Ok(ZcashNotesBundle {
        anchor,
        anchor_height,
        mainnet,
        notes,
        anchor_attestation,
        anchor_time,
    })
}

// ============================================================================
// CBOR helpers
// ============================================================================

fn cbor_encode_uint(buf: &mut Vec<u8>, val: u64) {
    if val <= 23 {
        buf.push(val as u8);
    } else if val <= 0xff {
        buf.push(0x18);
        buf.push(val as u8);
    } else if val <= 0xffff {
        buf.push(0x19);
        buf.extend_from_slice(&(val as u16).to_be_bytes());
    } else if val <= 0xffff_ffff {
        buf.push(0x1a);
        buf.extend_from_slice(&(val as u32).to_be_bytes());
    } else {
        buf.push(0x1b);
        buf.extend_from_slice(&val.to_be_bytes());
    }
}

fn cbor_encode_array_len(buf: &mut Vec<u8>, len: usize) {
    if len <= 23 {
        buf.push(0x80 | len as u8);
    } else if len <= 0xff {
        buf.push(0x98);
        buf.push(len as u8);
    } else if len <= 0xffff {
        buf.push(0x99);
        buf.extend_from_slice(&(len as u16).to_be_bytes());
    } else {
        buf.push(0x9a);
        buf.extend_from_slice(&(len as u32).to_be_bytes());
    }
}

fn cbor_decode_uint(data: &[u8], offset: usize) -> Result<(u64, usize)> {
    if offset >= data.len() {
        return Err(Error::ZcashParsing("truncated uint".to_string()));
    }
    let first = data[offset];
    let major = first >> 5;
    let additional = first & 0x1f;

    if major != 0 {
        return Err(Error::ZcashParsing(format!(
            "expected uint (major 0), got major {major}"
        )));
    }

    match additional {
        0..=23 => Ok((additional as u64, offset + 1)),
        24 => {
            if offset + 2 > data.len() {
                return Err(Error::ZcashParsing("truncated uint".to_string()));
            }
            Ok((data[offset + 1] as u64, offset + 2))
        }
        25 => {
            if offset + 3 > data.len() {
                return Err(Error::ZcashParsing("truncated uint".to_string()));
            }
            Ok((
                u16::from_be_bytes([data[offset + 1], data[offset + 2]]) as u64,
                offset + 3,
            ))
        }
        26 => {
            if offset + 5 > data.len() {
                return Err(Error::ZcashParsing("truncated uint".to_string()));
            }
            Ok((
                u32::from_be_bytes([
                    data[offset + 1],
                    data[offset + 2],
                    data[offset + 3],
                    data[offset + 4],
                ]) as u64,
                offset + 5,
            ))
        }
        27 => {
            if offset + 9 > data.len() {
                return Err(Error::ZcashParsing("truncated uint".to_string()));
            }
            Ok((
                u64::from_be_bytes([
                    data[offset + 1],
                    data[offset + 2],
                    data[offset + 3],
                    data[offset + 4],
                    data[offset + 5],
                    data[offset + 6],
                    data[offset + 7],
                    data[offset + 8],
                ]),
                offset + 9,
            ))
        }
        _ => Err(Error::ZcashParsing(format!(
            "unsupported uint additional {additional}"
        ))),
    }
}

fn cbor_decode_bstr(data: &[u8], offset: usize) -> Result<(Vec<u8>, usize)> {
    if offset >= data.len() {
        return Err(Error::ZcashParsing("truncated bstr".to_string()));
    }
    let first = data[offset];
    let major = first >> 5;
    let additional = first & 0x1f;

    if major != 2 {
        return Err(Error::ZcashParsing(format!(
            "expected bstr (major 2), got major {major}"
        )));
    }

    let (len, header_end) = match additional {
        0..=23 => (additional as usize, offset + 1),
        24 => {
            if offset + 2 > data.len() {
                return Err(Error::ZcashParsing("truncated bstr len".to_string()));
            }
            (data[offset + 1] as usize, offset + 2)
        }
        25 => {
            if offset + 3 > data.len() {
                return Err(Error::ZcashParsing("truncated bstr len".to_string()));
            }
            (
                u16::from_be_bytes([data[offset + 1], data[offset + 2]]) as usize,
                offset + 3,
            )
        }
        _ => {
            return Err(Error::ZcashParsing(format!(
                "unsupported bstr additional {additional}"
            )))
        }
    };

    if header_end + len > data.len() {
        return Err(Error::ZcashParsing("truncated bstr data".to_string()));
    }
    Ok((
        data[header_end..header_end + len].to_vec(),
        header_end + len,
    ))
}

fn cbor_decode_map_len(data: &[u8], offset: usize) -> Result<(usize, usize)> {
    if offset >= data.len() {
        return Err(Error::ZcashParsing("truncated map".to_string()));
    }
    let first = data[offset];
    let major = first >> 5;
    let additional = first & 0x1f;

    if major != 5 {
        return Err(Error::ZcashParsing(format!(
            "expected map (major 5), got major {major}"
        )));
    }

    match additional {
        0..=23 => Ok((additional as usize, offset + 1)),
        24 => {
            if offset + 2 > data.len() {
                return Err(Error::ZcashParsing("truncated map len".to_string()));
            }
            Ok((data[offset + 1] as usize, offset + 2))
        }
        _ => Err(Error::ZcashParsing(format!(
            "unsupported map additional {additional}"
        ))),
    }
}

fn cbor_decode_array_len(data: &[u8], offset: usize) -> Result<(usize, usize)> {
    if offset >= data.len() {
        return Err(Error::ZcashParsing("truncated array".to_string()));
    }
    let first = data[offset];
    let major = first >> 5;
    let additional = first & 0x1f;

    if major != 4 {
        return Err(Error::ZcashParsing(format!(
            "expected array (major 4), got major {major}"
        )));
    }

    match additional {
        0..=23 => Ok((additional as usize, offset + 1)),
        24 => {
            if offset + 2 > data.len() {
                return Err(Error::ZcashParsing("truncated array len".to_string()));
            }
            Ok((data[offset + 1] as usize, offset + 2))
        }
        25 => {
            if offset + 3 > data.len() {
                return Err(Error::ZcashParsing("truncated array len".to_string()));
            }
            Ok((
                u16::from_be_bytes([data[offset + 1], data[offset + 2]]) as usize,
                offset + 3,
            ))
        }
        _ => Err(Error::ZcashParsing(format!(
            "unsupported array additional {additional}"
        ))),
    }
}

fn cbor_decode_note_with_path(data: &[u8], offset: usize) -> Result<(ZcashNoteWithPath, usize)> {
    let (map_len, mut offset) = cbor_decode_map_len(data, offset)?;
    if map_len < 6 {
        return Err(Error::ZcashParsing(format!(
            "expected note map(6+), got map({map_len})"
        )));
    }

    let mut value = 0u64;
    let mut nullifier = [0u8; 32];
    let mut cmx = [0u8; 32];
    let mut position = 0u32;
    let mut block_height = 0u32;
    let mut merkle_path = [[0u8; 32]; 32];

    for _ in 0..map_len {
        let (key, consumed) = cbor_decode_uint(data, offset)?;
        offset = consumed;

        match key {
            1 => {
                let (val, consumed) = cbor_decode_uint(data, offset)?;
                offset = consumed;
                value = val;
            }
            2 => {
                let (bytes, consumed) = cbor_decode_bstr(data, offset)?;
                offset = consumed;
                if bytes.len() != 32 {
                    return Err(Error::ZcashParsing(
                        "nullifier must be 32 bytes".to_string(),
                    ));
                }
                nullifier.copy_from_slice(&bytes);
            }
            3 => {
                let (bytes, consumed) = cbor_decode_bstr(data, offset)?;
                offset = consumed;
                if bytes.len() != 32 {
                    return Err(Error::ZcashParsing("cmx must be 32 bytes".to_string()));
                }
                cmx.copy_from_slice(&bytes);
            }
            4 => {
                let (val, consumed) = cbor_decode_uint(data, offset)?;
                offset = consumed;
                position = u32::try_from(val)
                    .map_err(|_| Error::ZcashParsing("position out of range".to_string()))?;
            }
            5 => {
                let (val, consumed) = cbor_decode_uint(data, offset)?;
                offset = consumed;
                block_height = u32::try_from(val)
                    .map_err(|_| Error::ZcashParsing("block_height out of range".to_string()))?;
            }
            6 => {
                let (arr_len, consumed) = cbor_decode_array_len(data, offset)?;
                offset = consumed;
                if arr_len != 32 {
                    return Err(Error::ZcashParsing(format!(
                        "merkle path must have 32 siblings, got {arr_len}"
                    )));
                }
                for sibling in &mut merkle_path {
                    let (bytes, consumed) = cbor_decode_bstr(data, offset)?;
                    offset = consumed;
                    if bytes.len() != 32 {
                        return Err(Error::ZcashParsing(
                            "merkle sibling must be 32 bytes".to_string(),
                        ));
                    }
                    sibling.copy_from_slice(&bytes);
                }
            }
            _ => {
                offset = cbor_skip_value(data, offset)?;
            }
        }
    }

    Ok((
        ZcashNoteWithPath {
            value,
            nullifier,
            cmx,
            position,
            block_height,
            merkle_path,
        },
        offset,
    ))
}

/// Skip the value of an unknown map key (key 0, the version, today).
///
/// Only a number, a byte or text string, or a simple value is skipped. A
/// nested array or map under an unknown key is refused rather than walked,
/// so no input can drive recursion, and a string must fit in the data.
fn cbor_skip_value(data: &[u8], offset: usize) -> Result<usize> {
    let truncated = || Error::ZcashParsing("truncated CBOR value".to_string());
    let first = *data.get(offset).ok_or_else(truncated)?;
    let major = first >> 5;
    let additional = first & 0x1f;
    if major == 7 {
        // true / false / null / undefined; floats are not expected here
        return match additional {
            20..=23 => Ok(offset + 1),
            _ => Err(Error::ZcashParsing(format!(
                "unsupported CBOR simple value {additional}"
            ))),
        };
    }

    let width = match additional {
        0..=23 => 0,
        24 => 1,
        25 => 2,
        26 => 4,
        27 => 8,
        _ => {
            return Err(Error::ZcashParsing(format!(
                "unsupported CBOR additional {additional}"
            )))
        }
    };
    let header_end = offset + 1 + width;
    let header = data.get(offset + 1..header_end).ok_or_else(truncated)?;
    let arg = if width == 0 {
        additional as u64
    } else {
        header.iter().fold(0u64, |v, b| (v << 8) | *b as u64)
    };

    match major {
        0 | 1 => Ok(header_end),
        2 | 3 => usize::try_from(arg)
            .ok()
            .and_then(|len| header_end.checked_add(len))
            .filter(|end| *end <= data.len())
            .ok_or_else(truncated),
        _ => Err(Error::ZcashParsing(format!(
            "unknown key holds CBOR major type {major}, refusing to walk it"
        ))),
    }
}

// ============================================================================
// fvk export
// ============================================================================

/// zcash FVK export data for QR code
#[derive(Debug, Clone)]
pub struct ZcashFvkExportData {
    /// account index
    pub account_index: u32,
    /// optional label
    pub label: Option<String>,
    /// orchard FVK bytes (96 bytes)
    pub orchard_fvk: Option<[u8; 96]>,
    /// transparent xpub (for watch-only)
    pub transparent_xpub: Option<Vec<u8>>,
    /// network: true = mainnet, false = testnet
    pub mainnet: bool,
}

impl ZcashFvkExportData {
    /// encode for QR code
    ///
    /// format:
    /// ```text
    /// [0x53][0x04][0x01]           - prelude (substrate compat, zcash, fvk export)
    /// [flags: 1 byte]              - bit 0: mainnet, bit 1: has orchard, bit 2: has transparent
    /// [account_index: 4 bytes LE]
    /// [label_len: 1 byte]
    /// [label: label_len bytes]
    /// [orchard_fvk: 96 bytes]      - if has orchard
    /// [transparent_xpub_len: 1]    - if has transparent
    /// [transparent_xpub: n bytes]  - if has transparent
    /// ```
    pub fn encode_qr(&self) -> Vec<u8> {
        let mut output = Vec::new();

        // prelude
        output.push(0x53); // substrate compat
        output.push(0x04); // zcash
        output.push(QR_TYPE_ZCASH_FVK_EXPORT);

        // flags
        let mut flags = 0u8;
        if self.mainnet {
            flags |= 0x01;
        }
        if self.orchard_fvk.is_some() {
            flags |= 0x02;
        }
        if self.transparent_xpub.is_some() {
            flags |= 0x04;
        }
        output.push(flags);

        // account index
        output.extend_from_slice(&self.account_index.to_le_bytes());

        // label
        match &self.label {
            Some(label) => {
                let label_bytes = label.as_bytes();
                output.push(label_bytes.len() as u8);
                output.extend_from_slice(label_bytes);
            }
            None => {
                output.push(0);
            }
        }

        // orchard fvk
        if let Some(fvk) = &self.orchard_fvk {
            output.extend_from_slice(fvk);
        }

        // transparent xpub
        if let Some(xpub) = &self.transparent_xpub {
            output.push(xpub.len() as u8);
            output.extend_from_slice(xpub);
        }

        output
    }

    /// decode from QR bytes
    pub fn decode_qr(data: &[u8]) -> Result<Self> {
        if data.len() < 9 {
            return Err(Error::ZcashParsing("QR data too short".to_string()));
        }

        // validate prelude
        if data[0] != 0x53 || data[1] != 0x04 || data[2] != QR_TYPE_ZCASH_FVK_EXPORT {
            return Err(Error::ZcashParsing(
                "invalid QR prelude for Zcash FVK export".to_string(),
            ));
        }

        let mut offset = 3;

        // flags
        let flags = data[offset];
        offset += 1;
        let mainnet = flags & 0x01 != 0;
        let has_orchard = flags & 0x02 != 0;
        let has_transparent = flags & 0x04 != 0;

        // account index
        let account_index = u32::from_le_bytes(data[offset..offset + 4].try_into().unwrap());
        offset += 4;

        // label
        let label_len = data[offset] as usize;
        offset += 1;
        let label = if label_len > 0 {
            if offset + label_len > data.len() {
                return Err(Error::ZcashParsing("label extends beyond data".to_string()));
            }
            let label_bytes = &data[offset..offset + label_len];
            offset += label_len;
            Some(String::from_utf8_lossy(label_bytes).to_string())
        } else {
            None
        };

        // orchard fvk
        let orchard_fvk = if has_orchard {
            if offset + 96 > data.len() {
                return Err(Error::ZcashParsing("orchard FVK truncated".to_string()));
            }
            let fvk: [u8; 96] = data[offset..offset + 96].try_into().unwrap();
            offset += 96;
            Some(fvk)
        } else {
            None
        };

        // transparent xpub
        let transparent_xpub = if has_transparent {
            if offset >= data.len() {
                return Err(Error::ZcashParsing(
                    "transparent xpub length missing".to_string(),
                ));
            }
            let xpub_len = data[offset] as usize;
            offset += 1;
            if offset + xpub_len > data.len() {
                return Err(Error::ZcashParsing(
                    "transparent xpub truncated".to_string(),
                ));
            }
            let xpub = data[offset..offset + xpub_len].to_vec();
            Some(xpub)
        } else {
            None
        };

        Ok(Self {
            account_index,
            label,
            orchard_fvk,
            transparent_xpub,
            mainnet,
        })
    }

    /// encode as hex string
    pub fn encode_qr_hex(&self) -> String {
        hex::encode(self.encode_qr())
    }

    /// decode from hex string
    pub fn decode_qr_hex(hex_str: &str) -> Result<Self> {
        let data = hex::decode(hex_str)
            .map_err(|e| Error::ZcashParsing(format!("hex decode error: {e}")))?;
        Self::decode_qr(&data)
    }
}

#[cfg(all(test, feature = "zcash"))]
#[allow(clippy::vec_init_then_push)]
mod tests {
    use super::*;

    #[test]
    fn test_transparent_path() {
        assert_eq!(transparent_path(0, 0, 0), "m/44'/133'/0'/0/0");
        assert_eq!(transparent_path(1, 1, 5), "m/44'/133'/1'/1/5");
    }

    #[test]
    fn test_orchard_path() {
        assert_eq!(orchard_path(0), "m/32'/133'/0'");
        assert_eq!(orchard_path(5), "m/32'/133'/5'");
    }

    #[test]
    fn test_fvk_export_encode_decode() {
        let export = ZcashFvkExportData {
            account_index: 0,
            label: Some("Test Wallet".to_string()),
            orchard_fvk: Some([42u8; 96]),
            transparent_xpub: None,
            mainnet: true,
        };

        let encoded = export.encode_qr();

        // verify prelude
        assert_eq!(encoded[0], 0x53);
        assert_eq!(encoded[1], 0x04);
        assert_eq!(encoded[2], QR_TYPE_ZCASH_FVK_EXPORT);

        let decoded = ZcashFvkExportData::decode_qr(&encoded).unwrap();
        assert_eq!(decoded.account_index, 0);
        assert_eq!(decoded.label, Some("Test Wallet".to_string()));
        assert_eq!(decoded.orchard_fvk, Some([42u8; 96]));
        assert!(decoded.mainnet);
    }

    #[test]
    fn test_fvk_export_no_label() {
        let export = ZcashFvkExportData {
            account_index: 5,
            label: None,
            orchard_fvk: Some([0u8; 96]),
            transparent_xpub: Some(vec![1, 2, 3, 4]),
            mainnet: false,
        };

        let encoded = export.encode_qr();
        let decoded = ZcashFvkExportData::decode_qr(&encoded).unwrap();

        assert_eq!(decoded.account_index, 5);
        assert_eq!(decoded.label, None);
        assert!(decoded.orchard_fvk.is_some());
        assert_eq!(decoded.transparent_xpub, Some(vec![1, 2, 3, 4]));
        assert!(!decoded.mainnet);
    }

    #[test]
    fn test_fvk_export_with_transparent_xpub() {
        let xpub = vec![0x04; 78]; // typical xpub length
        let export = ZcashFvkExportData {
            account_index: 1,
            label: Some("Mixed Wallet".to_string()),
            orchard_fvk: Some([0x55; 96]),
            transparent_xpub: Some(xpub.clone()),
            mainnet: true,
        };

        let encoded = export.encode_qr();
        let decoded = ZcashFvkExportData::decode_qr(&encoded).unwrap();

        assert_eq!(decoded.account_index, 1);
        assert_eq!(decoded.label, Some("Mixed Wallet".to_string()));
        assert!(decoded.orchard_fvk.is_some());
        assert_eq!(decoded.transparent_xpub, Some(xpub));
        assert!(decoded.mainnet);
    }

    #[test]
    fn test_fvk_export_orchard_only() {
        let export = ZcashFvkExportData {
            account_index: 0,
            label: None,
            orchard_fvk: Some([0xAA; 96]),
            transparent_xpub: None,
            mainnet: false,
        };

        let encoded = export.encode_qr();

        // Check flags: bit 1 set (orchard), bits 0 and 2 not set
        assert_eq!(encoded[3] & 0x01, 0); // not mainnet
        assert_eq!(encoded[3] & 0x02, 0x02); // has orchard
        assert_eq!(encoded[3] & 0x04, 0); // no transparent

        let decoded = ZcashFvkExportData::decode_qr(&encoded).unwrap();
        assert!(!decoded.mainnet);
        assert!(decoded.orchard_fvk.is_some());
        assert!(decoded.transparent_xpub.is_none());
    }

    #[test]
    fn test_notes_cbor_roundtrip() {
        let bundle = ZcashNotesBundle {
            anchor: [0xAA; 32],
            anchor_height: 2_500_000,
            mainnet: true,
            notes: vec![
                ZcashNoteWithPath {
                    value: 100_000_000, // 1 ZEC
                    nullifier: [0x11; 32],
                    cmx: [0x22; 32],
                    position: 42,
                    block_height: 2_499_999,
                    merkle_path: {
                        let mut path = [[0u8; 32]; 32];
                        for (i, p) in path.iter_mut().enumerate() {
                            p[0] = i as u8;
                        }
                        path
                    },
                },
                ZcashNoteWithPath {
                    value: 50_000_000, // 0.5 ZEC
                    nullifier: [0x33; 32],
                    cmx: [0x44; 32],
                    position: 100,
                    block_height: 2_499_990,
                    merkle_path: [[0x55; 32]; 32],
                },
            ],
            anchor_attestation: None,
            anchor_time: None,
        };

        let cbor = encode_notes_bundle_to_cbor(&bundle);
        let decoded = decode_notes_bundle_from_cbor(&cbor).unwrap();

        assert_eq!(decoded.anchor, bundle.anchor);
        assert_eq!(decoded.anchor_height, bundle.anchor_height);
        assert_eq!(decoded.mainnet, bundle.mainnet);
        assert_eq!(decoded.notes.len(), 2);
        assert_eq!(decoded.notes[0].value, 100_000_000);
        assert_eq!(decoded.notes[0].nullifier, [0x11; 32]);
        assert_eq!(decoded.notes[0].cmx, [0x22; 32]);
        assert_eq!(decoded.notes[0].position, 42);
        assert_eq!(decoded.notes[0].block_height, 2_499_999);
        assert_eq!(decoded.notes[0].merkle_path[0][0], 0);
        assert_eq!(decoded.notes[0].merkle_path[31][0], 31);
        assert_eq!(decoded.notes[1].value, 50_000_000);
        assert_eq!(decoded.notes[1].nullifier, [0x33; 32]);
        assert_eq!(decoded.notes[1].merkle_path, [[0x55; 32]; 32]);
    }

    #[test]
    fn test_notes_cbor_empty_bundle() {
        let bundle = ZcashNotesBundle {
            anchor: [0xFF; 32],
            anchor_height: 0,
            mainnet: false,
            notes: vec![],
            anchor_attestation: None,
            anchor_time: None,
        };

        let cbor = encode_notes_bundle_to_cbor(&bundle);
        let decoded = decode_notes_bundle_from_cbor(&cbor).unwrap();

        assert_eq!(decoded.anchor, [0xFF; 32]);
        assert_eq!(decoded.anchor_height, 0);
        assert!(!decoded.mainnet);
        assert!(decoded.notes.is_empty());
    }

    /// The empty bundle with one more map entry, `key: value`, appended.
    fn bundle_with_extra(extra: &[u8]) -> Vec<u8> {
        let mut cbor = encode_notes_bundle_to_cbor(&ZcashNotesBundle {
            anchor: [0xFF; 32],
            anchor_height: 7,
            mainnet: true,
            notes: vec![],
            anchor_attestation: None,
            anchor_time: None,
        });
        assert!((0xa1..0xb7).contains(&cbor[0]), "small map header");
        cbor[0] += 1;
        cbor.extend_from_slice(extra);
        cbor
    }

    #[test]
    fn notes_cbor_skips_a_scalar_under_an_unknown_key() {
        // key 0 = version 1, as zcli sends it
        let decoded = decode_notes_bundle_from_cbor(&bundle_with_extra(&[0x00, 0x01])).unwrap();
        assert_eq!(decoded.anchor_height, 7);
        // a short text value under key 9
        assert!(
            decode_notes_bundle_from_cbor(&bundle_with_extra(&[0x09, 0x62, b'h', b'i'])).is_ok()
        );
    }

    /// Deep nesting under an unknown key used to recurse once per level, so
    /// a long run of 0x81 overflowed the native stack.
    #[test]
    fn notes_cbor_refuses_nesting_under_an_unknown_key() {
        let mut extra = vec![0x09];
        extra.extend(std::iter::repeat_n(0x81, 200_000));
        extra.push(0x00);
        assert!(decode_notes_bundle_from_cbor(&bundle_with_extra(&extra)).is_err());
        assert!(decode_notes_bundle_from_cbor(&bundle_with_extra(&[0x09, 0xa0])).is_err());
    }

    #[test]
    fn notes_cbor_refuses_a_string_past_the_end() {
        let extra = [0x09, 0x5b, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff, 0xff];
        assert!(decode_notes_bundle_from_cbor(&bundle_with_extra(&extra)).is_err());
    }

    #[test]
    fn notes_cbor_refuses_trailing_bytes_and_wide_heights() {
        let mut cbor = bundle_with_extra(&[0x00, 0x01]);
        cbor.push(0x00);
        assert!(decode_notes_bundle_from_cbor(&cbor).is_err());

        let mut wide = encode_notes_bundle_to_cbor(&ZcashNotesBundle {
            anchor: [0; 32],
            anchor_height: 0,
            mainnet: true,
            notes: vec![],
            anchor_attestation: None,
            anchor_time: None,
        });
        // anchor_height is key 2: replace its uint 0 with 2^32
        let at = wide.windows(2).position(|w| w == [0x02, 0x00]).unwrap();
        wide.splice(at + 1..at + 2, [0x1b, 0, 0, 0, 1, 0, 0, 0, 0]);
        assert!(decode_notes_bundle_from_cbor(&wide).is_err());
    }

    #[test]
    fn zcash_seed_fingerprint_is_zip32_conformant() {
        // 24-word BIP39 all-zero-entropy vector (the bip32 crate only accepts
        // 24-word phrases - same mnemonic the other zcash tests use).
        let m = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon art";
        let fp = super::OrchardSpendingKey::seed_fingerprint(m).unwrap();
        // ZIP-32 seed fingerprints are exactly 32 bytes (the bug we fixed:
        // Keystone/vizor reject anything else with "must be 32 bytes").
        assert_eq!(fp.len(), 32);
        // Deterministic for the same seed.
        assert_eq!(super::OrchardSpendingKey::seed_fingerprint(m).unwrap(), fp);
        // Regression guard: this is the ZIP-32 SeedFingerprint of the 24-word
        // all-zero-entropy seed (cross-checkable against vizor/Keystone/Zashi).
        assert_eq!(
            hex::encode(fp),
            "e62855dc1419972e2d8fd349b740de6a67d8cf6feaafe90cf2f37a2344566ccc",
            "unexpected fingerprint - see printed value to update if algo changed",
        );
        println!("seed_fingerprint(abandon..art/24w) = {}", hex::encode(fp));
    }
}
