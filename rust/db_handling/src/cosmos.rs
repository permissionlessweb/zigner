//! Cosmos SDK key derivation using BIP44 secp256k1
//!
//! This implements key derivation for Cosmos SDK chains (Osmosis, Noble, Celestia, etc.)
//!
//! Key derivation path: m/44'/coin_type'/account'/0/address_index
//! For Cosmos Hub (ATOM): m/44'/118'/0'/0/0
//!
//! Address derivation:
//! 1. secp256k1 public key (33 bytes compressed)
//! 2. SHA256(pubkey) → 32 bytes
//! 3. RIPEMD160(sha256_hash) → 20 bytes
//! 4. bech32 encode with chain-specific prefix (cosmos, osmo, noble, celestia, etc.)

use crate::error::{Error, Result};

/// Sled tree name for storing Cosmos bech32 addresses
const COSMOS_ADDRS: &str = "cosmos_addresses";

/// Store a Cosmos bech32 address for a given public key hex and genesis hash
pub fn store_cosmos_address(
    database: &sled::Db,
    pubkey_hex: &str,
    genesis_hash: &str,
    bech32_address: &str,
) -> Result<()> {
    let tree = database.open_tree(COSMOS_ADDRS)?;
    let key = format!("{}_{}", pubkey_hex, genesis_hash);
    tree.insert(key.as_bytes(), bech32_address.as_bytes())?;
    Ok(())
}

/// Retrieve a Cosmos bech32 address for a given public key hex and genesis hash
pub fn get_cosmos_address(
    database: &sled::Db,
    pubkey_hex: &str,
    genesis_hash: &str,
) -> Result<Option<String>> {
    let tree = database.open_tree(COSMOS_ADDRS)?;
    let key = format!("{}_{}", pubkey_hex, genesis_hash);
    match tree.get(key.as_bytes())? {
        Some(bytes) => {
            let address = String::from_utf8(bytes.to_vec())
                .map_err(|e| Error::Other(anyhow::anyhow!("Invalid UTF-8: {}", e)))?;
            Ok(Some(address))
        }
        None => Ok(None),
    }
}
use bip32::{DerivationPath, XPrv};
use ripemd::Ripemd160;
use sha2::{Digest, Sha256};
use zeroize::{Zeroize, ZeroizeOnDrop};

/// Compute bech32 cosmos address from compressed pubkey bytes and network name.
/// Same key for all cosmos chains (coin type 118), just different bech32 prefix.
/// This follows how zafu differentiates addresses: `from_bech32` → `to_bech32` with chain prefix.
pub fn pubkey_to_bech32_address(pubkey_bytes: &[u8], network_name: &str) -> Result<String> {
    use bech32::{Bech32, Hrp};

    if pubkey_bytes.len() != 33 {
        return Err(Error::Other(anyhow::anyhow!(
            "expected 33-byte compressed secp256k1 pubkey, got {} bytes",
            pubkey_bytes.len()
        )));
    }

    // Map network name to bech32 prefix (fixed table, unknown names -> cosmos).
    // Callers that know the real prefix should use `pubkey_to_bech32_with_prefix`.
    let prefix = prefix_for_network(network_name);

    // RIPEMD160(SHA256(pubkey)) → 20 bytes address
    let sha256_hash = Sha256::digest(pubkey_bytes);
    let ripemd160_hash = Ripemd160::digest(sha256_hash);

    let hrp = Hrp::parse(prefix)
        .map_err(|e| Error::Other(anyhow::anyhow!("Invalid bech32 prefix: {}", e)))?;
    let address = bech32::encode::<Bech32>(hrp, &ripemd160_hash)
        .map_err(|e| Error::Other(anyhow::anyhow!("Bech32 encoding error: {}", e)))?;

    Ok(address)
}

/// SLIP-0044 coin types for popular Cosmos chains
pub const SLIP0044_COSMOS: u32 = 118; // Cosmos Hub (ATOM)
pub const SLIP0044_OSMOSIS: u32 = 118; // Osmosis uses Cosmos coin type
pub const SLIP0044_NOBLE: u32 = 118; // Noble uses Cosmos coin type
pub const SLIP0044_CELESTIA: u32 = 118; // Celestia uses Cosmos coin type
pub const SLIP0044_TERRA: u32 = 330; // Terra
pub const SLIP0044_KAVA: u32 = 459; // Kava
pub const SLIP0044_SECRET: u32 = 529; // Secret Network
pub const SLIP0044_INJECTIVE: u32 = 60; // Injective uses Ethereum coin type

/// Common bech32 address prefixes for Cosmos chains
pub const PREFIX_COSMOS: &str = "cosmos";
pub const PREFIX_OSMOSIS: &str = "osmo";
pub const PREFIX_NOBLE: &str = "noble";
pub const PREFIX_CELESTIA: &str = "celestia";
pub const PREFIX_TERRA: &str = "terra";
pub const PREFIX_KAVA: &str = "kava";
pub const PREFIX_SECRET: &str = "secret";
pub const PREFIX_INJECTIVE: &str = "inj";
pub const PREFIX_TERP: &str = "terp";
pub const SLIP0044_TERP: u32 = 118;
pub const PREFIX_JUNO: &str = "juno";
pub const SLIP0044_JUNO: u32 = 118;
pub const PREFIX_ATOMONE: &str = "atone";
pub const SLIP0044_ATOMONE: u32 = 118;

/// Static description of a known Cosmos SDK chain. This table is only a
/// fallback: the signer prefers the bech32 prefix of the request's own signer
/// addresses (or an explicit QR extension) over anything listed here.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CosmosChainInfo {
    /// chain name as used in the QR `chain_name` field (e.g. "osmosis")
    pub name: &'static str,
    /// bech32 account prefix (e.g. "osmo")
    pub prefix: &'static str,
    /// SLIP-0044 coin type used in m/44'/coin'/account'/0/index
    pub coin_type: u32,
}

/// Known chains. Order matters only for documentation.
pub const KNOWN_COSMOS_CHAINS: &[CosmosChainInfo] = &[
    CosmosChainInfo {
        name: "cosmos",
        prefix: PREFIX_COSMOS,
        coin_type: SLIP0044_COSMOS,
    },
    CosmosChainInfo {
        name: "osmosis",
        prefix: PREFIX_OSMOSIS,
        coin_type: SLIP0044_OSMOSIS,
    },
    CosmosChainInfo {
        name: "noble",
        prefix: PREFIX_NOBLE,
        coin_type: SLIP0044_NOBLE,
    },
    CosmosChainInfo {
        name: "celestia",
        prefix: PREFIX_CELESTIA,
        coin_type: SLIP0044_CELESTIA,
    },
    CosmosChainInfo {
        name: "terra",
        prefix: PREFIX_TERRA,
        coin_type: SLIP0044_TERRA,
    },
    CosmosChainInfo {
        name: "kava",
        prefix: PREFIX_KAVA,
        coin_type: SLIP0044_KAVA,
    },
    CosmosChainInfo {
        name: "secret",
        prefix: PREFIX_SECRET,
        coin_type: SLIP0044_SECRET,
    },
    CosmosChainInfo {
        name: "injective",
        prefix: PREFIX_INJECTIVE,
        coin_type: SLIP0044_INJECTIVE,
    },
    CosmosChainInfo {
        name: "terp",
        prefix: PREFIX_TERP,
        coin_type: SLIP0044_TERP,
    },
    CosmosChainInfo {
        name: "juno",
        prefix: PREFIX_JUNO,
        coin_type: SLIP0044_JUNO,
    },
    CosmosChainInfo {
        name: "atomone",
        prefix: PREFIX_ATOMONE,
        coin_type: SLIP0044_ATOMONE,
    },
];

/// Look up a known chain by its exact (case-sensitive) chain name.
pub fn cosmos_chain_info(network_name: &str) -> Option<&'static CosmosChainInfo> {
    KNOWN_COSMOS_CHAINS.iter().find(|c| c.name == network_name)
}

/// Bech32 prefix for a chain name; unknown names fall back to `cosmos`
/// (unchanged legacy behaviour).
pub fn prefix_for_network(network_name: &str) -> &'static str {
    cosmos_chain_info(network_name)
        .map(|c| c.prefix)
        .unwrap_or(PREFIX_COSMOS)
}

/// SLIP-0044 coin type for a chain name; unknown names default to 118.
pub fn coin_type_for_network(network_name: &str) -> u32 {
    cosmos_chain_info(network_name)
        .map(|c| c.coin_type)
        .unwrap_or(SLIP0044_COSMOS)
}

/// Human-readable part of a bech32 address, lowercased, or `None` if the
/// string is not valid bech32.
pub fn bech32_prefix_of(address: &str) -> Option<String> {
    let (hrp, _) = bech32::decode(address).ok()?;
    Some(hrp.to_lowercase())
}

/// True if `prefix` is a usable bech32 human-readable part.
pub fn is_valid_bech32_prefix(prefix: &str) -> bool {
    !prefix.is_empty()
        && prefix.len() <= 83
        && prefix
            .bytes()
            .all(|b| b.is_ascii_lowercase() || b.is_ascii_digit())
        && bech32::Hrp::parse(prefix).is_ok()
}

/// Bech32 address for a compressed pubkey with an explicit prefix.
pub fn pubkey_to_bech32_with_prefix(pubkey_bytes: &[u8], prefix: &str) -> Result<String> {
    use bech32::{Bech32, Hrp};
    if pubkey_bytes.len() != 33 {
        return Err(Error::Other(anyhow::anyhow!(
            "expected 33-byte compressed secp256k1 pubkey, got {} bytes",
            pubkey_bytes.len()
        )));
    }
    let sha256_hash = Sha256::digest(pubkey_bytes);
    let ripemd160_hash = Ripemd160::digest(sha256_hash);
    let hrp = Hrp::parse(prefix)
        .map_err(|e| Error::Other(anyhow::anyhow!("Invalid bech32 prefix: {}", e)))?;
    bech32::encode::<Bech32>(hrp, &ripemd160_hash)
        .map_err(|e| Error::Other(anyhow::anyhow!("Bech32 encoding error: {}", e)))
}

/// Cosmos key pair with secp256k1 keys
#[derive(Zeroize, ZeroizeOnDrop)]
pub struct CosmosKeyPair {
    /// 32-byte secp256k1 secret key
    pub secret_key: [u8; 32],
    /// 33-byte compressed secp256k1 public key
    pub public_key: [u8; 33],
    /// 20-byte address (RIPEMD160(SHA256(pubkey)))
    pub address_bytes: [u8; 20],
}

impl CosmosKeyPair {
    /// Get bech32 address with the specified prefix
    pub fn bech32_address(&self, prefix: &str) -> Result<String> {
        use bech32::{Bech32, Hrp};

        let hrp = Hrp::parse(prefix)
            .map_err(|e| Error::Other(anyhow::anyhow!("Invalid bech32 prefix: {}", e)))?;

        let address = bech32::encode::<Bech32>(hrp, &self.address_bytes)
            .map_err(|e| Error::Other(anyhow::anyhow!("Bech32 encoding error: {}", e)))?;

        Ok(address)
    }

    // NOTE: signing is done in transaction_signing::cosmos::sign_cosmos_amino()
    // which correctly uses SHA256 pre-hash. Do NOT add a sign() method here
    // using sp_core::ecdsa — it uses blake2b-256, which is wrong for Cosmos.
}

/// Derive Cosmos key from seed phrase
///
/// # Arguments
/// * `seed_phrase` - BIP39 mnemonic phrase
/// * `coin_type` - SLIP-0044 coin type (118 for Cosmos, 330 for Terra, etc.)
/// * `account` - Account index (typically 0)
/// * `address_index` - Address index within the account (typically 0)
///
/// # Returns
/// A CosmosKeyPair containing the secret key, public key, and address bytes
pub fn derive_cosmos_key(
    seed_phrase: &str,
    coin_type: u32,
    account: u32,
    address_index: u32,
) -> Result<CosmosKeyPair> {
    use bip39::{Language, Mnemonic, Seed};

    // Parse mnemonic and generate seed
    let mnemonic = Mnemonic::from_phrase(seed_phrase, Language::English)
        .map_err(|e| Error::Other(anyhow::anyhow!("Invalid mnemonic: {}", e)))?;
    let seed = Seed::new(&mnemonic, ""); // No passphrase

    // Build derivation path: m/44'/coin_type'/account'/0/address_index
    let path_str = format!("m/44'/{}'/{}'/0/{}", coin_type, account, address_index);
    let path: DerivationPath = path_str
        .parse()
        .map_err(|e| Error::Other(anyhow::anyhow!("Invalid derivation path: {}", e)))?;

    // Derive extended private key
    let xprv = XPrv::derive_from_path(seed.as_bytes(), &path)
        .map_err(|e| Error::Other(anyhow::anyhow!("BIP32 derivation error: {}", e)))?;

    // Get the private key bytes
    let mut secret_key = [0u8; 32];
    secret_key.copy_from_slice(&xprv.private_key().to_bytes());

    // Derive public key using the bip32 crate's key
    let verifying_key = xprv.public_key();
    let public_key_bytes = verifying_key.to_bytes();

    let mut public_key = [0u8; 33];
    public_key.copy_from_slice(&public_key_bytes);

    // Derive address: RIPEMD160(SHA256(pubkey))
    let sha256_hash = Sha256::digest(public_key);
    let ripemd160_hash = Ripemd160::digest(sha256_hash);

    let mut address_bytes = [0u8; 20];
    address_bytes.copy_from_slice(&ripemd160_hash);

    Ok(CosmosKeyPair {
        secret_key,
        public_key,
        address_bytes,
    })
}

/// Derive the account's change-level extended public key (xpub) at
/// `m/44'/coin_type'/account'/0`.
///
/// This is the parent of the address-index leaves, so a watch-only holder can
/// derive `m/44'/coin_type'/account'/0/i` for any `i` via non-hardened CKD
/// WITHOUT the seed. That is the whole point: one cold export lets the hot
/// wallet mint unlimited burner receive addresses, each still signable on the
/// device at the matching `address_index`, and each recoverable from the seed.
///
/// Serialized as a standard base58 `xpub...` string (mainnet public prefix), so
/// any BIP32 library can consume it.
pub fn derive_cosmos_account_xpub(
    seed_phrase: &str,
    coin_type: u32,
    account: u32,
) -> Result<String> {
    use bip32::Prefix;
    use bip39::{Language, Mnemonic, Seed};

    let mnemonic = Mnemonic::from_phrase(seed_phrase, Language::English)
        .map_err(|e| Error::Other(anyhow::anyhow!("Invalid mnemonic: {}", e)))?;
    let seed = Seed::new(&mnemonic, "");

    // Change level: m/44'/coin_type'/account'/0. Stop above address_index so the
    // returned xpub can derive every leaf non-hardened.
    let path_str = format!("m/44'/{}'/{}'/0", coin_type, account);
    let path: DerivationPath = path_str
        .parse()
        .map_err(|e| Error::Other(anyhow::anyhow!("Invalid derivation path: {}", e)))?;

    let xprv = XPrv::derive_from_path(seed.as_bytes(), &path)
        .map_err(|e| Error::Other(anyhow::anyhow!("BIP32 derivation error: {}", e)))?;

    Ok(xprv.public_key().to_string(Prefix::XPUB))
}

/// Derive a Cosmos Hub key (m/44'/118'/account'/0/index)
pub fn derive_cosmos_hub_key(seed_phrase: &str, account: u32, index: u32) -> Result<CosmosKeyPair> {
    derive_cosmos_key(seed_phrase, SLIP0044_COSMOS, account, index)
}

/// Derive an Osmosis key (same path as Cosmos Hub)
pub fn derive_osmosis_key(seed_phrase: &str, account: u32, index: u32) -> Result<CosmosKeyPair> {
    derive_cosmos_key(seed_phrase, SLIP0044_OSMOSIS, account, index)
}

/// Derive a Noble key (same path as Cosmos Hub)
pub fn derive_noble_key(seed_phrase: &str, account: u32, index: u32) -> Result<CosmosKeyPair> {
    derive_cosmos_key(seed_phrase, SLIP0044_NOBLE, account, index)
}

/// Derive a Celestia key (same path as Cosmos Hub)
pub fn derive_celestia_key(seed_phrase: &str, account: u32, index: u32) -> Result<CosmosKeyPair> {
    derive_cosmos_key(seed_phrase, SLIP0044_CELESTIA, account, index)
}

/// Parse Cosmos BIP44 path to extract coin type, account, and address index
///
/// Supports paths like:
/// - `m/44'/118'/0'/0/0` - standard full path
/// - `//cosmos//0` - simplified UI input (uses coin type 118)
/// - `//osmosis//0` - simplified UI input for Osmosis
/// - `//0` - just account number (uses coin type 118, index 0)
pub fn parse_cosmos_path(path: &str) -> Result<(u32, u32, u32)> {
    // Try parsing as full BIP44 path: m/44'/coin_type'/account'/0/index
    if path.starts_with("m/44'/") || path.starts_with("44'/") {
        let parts: Vec<&str> = path.trim_start_matches("m/").split('/').collect();
        if parts.len() >= 5 {
            // parts[0] = "44'" (purpose)
            // parts[1] = "118'" (coin type)
            // parts[2] = "0'" (account)
            // parts[3] = "0" (change, always 0 for Cosmos)
            // parts[4] = "0" (address index)
            let coin_type_str = parts[1].trim_end_matches('\'');
            let account_str = parts[2].trim_end_matches('\'');
            let index_str = parts[4].trim_end_matches('\'');

            let coin_type = coin_type_str
                .parse::<u32>()
                .map_err(|_| Error::InvalidDerivation(path.to_string()))?;
            let account = account_str
                .parse::<u32>()
                .map_err(|_| Error::InvalidDerivation(path.to_string()))?;
            let index = index_str
                .parse::<u32>()
                .map_err(|_| Error::InvalidDerivation(path.to_string()))?;

            return Ok((coin_type, account, index));
        }
    }

    let path_lower = path.to_lowercase();
    let parts: Vec<&str> = path.split("//").filter(|s| !s.is_empty()).collect();

    // Extract numeric values from path
    let account = parts
        .iter()
        .filter_map(|p| p.parse::<u32>().ok())
        .next()
        .unwrap_or(0);

    // Determine coin type from chain name in path
    let coin_type = if path_lower.contains("terra") {
        SLIP0044_TERRA
    } else if path_lower.contains("kava") {
        SLIP0044_KAVA
    } else if path_lower.contains("secret") {
        SLIP0044_SECRET
    } else if path_lower.contains("injective") || path_lower.contains("inj") {
        SLIP0044_INJECTIVE
    } else {
        // Default to Cosmos coin type (118) for cosmos, osmo, noble, celestia, etc.
        SLIP0044_COSMOS
    };

    Ok((coin_type, account, 0))
}

#[cfg(test)]
mod tests {
    use super::*;

    const TEST_MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    #[test]
    fn table_covers_juno_and_atomone() {
        assert_eq!(prefix_for_network("juno"), "juno");
        assert_eq!(prefix_for_network("atomone"), "atone");
        assert_eq!(coin_type_for_network("juno"), 118);
        assert_eq!(coin_type_for_network("atomone"), 118);
        assert_eq!(coin_type_for_network("terra"), 330);
        assert_eq!(coin_type_for_network("nonexistent"), 118);
        assert_eq!(prefix_for_network("nonexistent"), "cosmos");
    }

    #[test]
    fn explicit_prefix_matches_table_and_roundtrips() {
        let key = derive_cosmos_key(TEST_MNEMONIC, 118, 0, 0).unwrap();
        for (name, prefix) in [
            ("osmosis", "osmo"),
            ("juno", "juno"),
            ("atomone", "atone"),
            ("terp", "terp"),
        ] {
            let a = pubkey_to_bech32_address(&key.public_key, name).unwrap();
            let b = pubkey_to_bech32_with_prefix(&key.public_key, prefix).unwrap();
            assert_eq!(a, b);
            assert_eq!(bech32_prefix_of(&a).as_deref(), Some(prefix));
        }
        assert!(bech32_prefix_of("not-bech32").is_none());
        assert!(is_valid_bech32_prefix("atone"));
        assert!(!is_valid_bech32_prefix("Bad Prefix"));
    }

    #[test]
    fn terp_address_uses_the_terp_prefix() {
        let pubkey = [2u8; 33];
        let address = pubkey_to_bech32_address(&pubkey, "terp").unwrap();
        assert!(address.starts_with("terp1"), "{address}");
    }

    #[test]
    fn test_cosmos_key_derivation() {
        // Test with standard mnemonic
        let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

        let key = derive_cosmos_hub_key(mnemonic, 0, 0).unwrap();

        println!("Public key: {}", hex::encode(&key.public_key));
        println!("Address bytes: {}", hex::encode(&key.address_bytes));
        println!(
            "Cosmos address: {}",
            key.bech32_address(PREFIX_COSMOS).unwrap()
        );
        println!(
            "Osmosis address: {}",
            key.bech32_address(PREFIX_OSMOSIS).unwrap()
        );
        println!(
            "Celestia address: {}",
            key.bech32_address(PREFIX_CELESTIA).unwrap()
        );

        // The public key should be deterministic
        assert_eq!(key.public_key.len(), 33);
        assert_eq!(key.address_bytes.len(), 20);
    }

    #[test]
    fn test_known_vector() {
        // Known test vector - "abandon" x11 + "about"
        // From: https://github.com/cosmos/cosmos-sdk/blob/main/crypto/hd/hdpath_test.go
        let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

        let key = derive_cosmos_hub_key(mnemonic, 0, 0).unwrap();
        let address = key.bech32_address(PREFIX_COSMOS).unwrap();

        println!("Cosmos address: {}", address);

        // Expected address for this mnemonic (standard Cosmos derivation)
        // This should match tools like Keplr, Leap, etc.
        assert!(address.starts_with("cosmos1"));
    }

    #[test]
    fn test_multiple_accounts() {
        let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

        let key0 = derive_cosmos_hub_key(mnemonic, 0, 0).unwrap();
        let key1 = derive_cosmos_hub_key(mnemonic, 1, 0).unwrap();

        // Different accounts should produce different keys
        assert_ne!(key0.public_key, key1.public_key);
        assert_ne!(key0.address_bytes, key1.address_bytes);

        println!("Account 0: {}", key0.bech32_address(PREFIX_COSMOS).unwrap());
        println!("Account 1: {}", key1.bech32_address(PREFIX_COSMOS).unwrap());
    }

    #[test]
    fn test_multiple_addresses_in_account() {
        let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

        let key0 = derive_cosmos_hub_key(mnemonic, 0, 0).unwrap();
        let key1 = derive_cosmos_hub_key(mnemonic, 0, 1).unwrap();

        // Different address indices should produce different keys
        assert_ne!(key0.public_key, key1.public_key);

        println!("Index 0: {}", key0.bech32_address(PREFIX_COSMOS).unwrap());
        println!("Index 1: {}", key1.bech32_address(PREFIX_COSMOS).unwrap());
    }

    #[test]
    fn test_different_chains_same_path() {
        // Many Cosmos chains use the same derivation path (coin type 118)
        // So the same mnemonic produces the same keys, just different address prefixes
        let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

        let cosmos_key = derive_cosmos_hub_key(mnemonic, 0, 0).unwrap();
        let osmo_key = derive_osmosis_key(mnemonic, 0, 0).unwrap();
        let noble_key = derive_noble_key(mnemonic, 0, 0).unwrap();

        // Same keys for all chains that use coin type 118
        assert_eq!(cosmos_key.secret_key, osmo_key.secret_key);
        assert_eq!(cosmos_key.secret_key, noble_key.secret_key);

        // But addresses look different due to different prefixes
        let cosmos_addr = cosmos_key.bech32_address(PREFIX_COSMOS).unwrap();
        let osmo_addr = osmo_key.bech32_address(PREFIX_OSMOSIS).unwrap();
        let noble_addr = noble_key.bech32_address(PREFIX_NOBLE).unwrap();

        println!("Cosmos: {}", cosmos_addr);
        println!("Osmosis: {}", osmo_addr);
        println!("Noble: {}", noble_addr);

        assert!(cosmos_addr.starts_with("cosmos1"));
        assert!(osmo_addr.starts_with("osmo1"));
        assert!(noble_addr.starts_with("noble1"));
    }

    #[test]
    fn test_xpub_derives_same_children_as_seed() {
        // The watch-only contract: a burner derived from the exported xpub at
        // .../0/i must be byte-identical to what the device signs with at the
        // same i. If this fails, the hot wallet shows an address the device can
        // never spend from - and post-store-release that is unfixable.
        use bip32::{ChildNumber, XPub};

        let mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
        let xpub_str = derive_cosmos_account_xpub(mnemonic, SLIP0044_COSMOS, 0).unwrap();
        assert!(
            xpub_str.starts_with("xpub"),
            "expected base58 xpub, got {xpub_str}"
        );

        let xpub: XPub = xpub_str.parse().expect("re-parse exported xpub");
        for i in 0..5u32 {
            let child = xpub
                .derive_child(ChildNumber::new(i, false).unwrap())
                .unwrap();
            let seed_key = derive_cosmos_key(mnemonic, SLIP0044_COSMOS, 0, i).unwrap();
            assert_eq!(
                child.to_bytes().as_slice(),
                &seed_key.public_key[..],
                "xpub child {i} must equal the seed-derived leaf",
            );
        }
    }

    #[test]
    fn test_parse_cosmos_path() {
        // Full BIP44 path
        let (coin, account, index) = parse_cosmos_path("m/44'/118'/0'/0/0").unwrap();
        assert_eq!(coin, 118);
        assert_eq!(account, 0);
        assert_eq!(index, 0);

        // Simplified path
        let (coin, account, _) = parse_cosmos_path("//cosmos//0").unwrap();
        assert_eq!(coin, SLIP0044_COSMOS);
        assert_eq!(account, 0);

        // Terra path
        let (coin, _, _) = parse_cosmos_path("//terra//0").unwrap();
        assert_eq!(coin, SLIP0044_TERRA);
    }
}
