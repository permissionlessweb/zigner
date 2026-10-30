//! Penumbra keys as Zigner stores them
//!
//! Spend keys, viewing keys and addresses come from penumbra-sdk, the same
//! derivation the FVK export and signing use: `SpendKey::from_seed_phrase_bip44`
//! at `m/44'/6532'/account'`. The receive address is index 0 of that key, which
//! is the address Zafu shows after importing the exported FVK.
//!
//! [`key_id`] is only a database identifier. Zigner 0.12.0 and older keyed
//! Penumbra entries by a verification key derived from a blake2b hash of the
//! seed, and existing installs keep those keys, so new entries use the same id.
//! It is not a Penumbra key and must not be shown as one.

pub mod schema;

pub use schema::*;

use crate::error::Result;
use constants::{PENUMBRA_ADDRS_UNSPENDABLE, PENUMBRA_RECEIVE_ADDRS};

/// Database id of the Penumbra key for `account`, unchanged since Zigner added
/// Penumbra so stored entries keep matching.
#[cfg(feature = "penumbra")]
pub fn key_id(seed_phrase: &str, account: u32) -> Result<[u8; 32]> {
    use bip39::{Language, Mnemonic, Seed};
    use penumbra_keys::keys::{SpendKey, SpendKeyBytes};
    use zeroize::Zeroize;

    let mnemonic = Mnemonic::from_phrase(seed_phrase, Language::English)
        .map_err(|e| crate::error::Error::Other(anyhow::anyhow!("Invalid mnemonic: {e}")))?;
    let seed = Seed::new(&mnemonic, "");
    let hash = blake2b_simd::Params::new()
        .personal(b"PenumbraSpendKey")
        .key(seed.as_bytes())
        .hash(format!("m/44'/6532'/{account}'/0/0").as_bytes());
    let mut bytes = [0u8; 32];
    bytes.copy_from_slice(&hash.as_bytes()[..32]);
    let key: SpendKey = SpendKeyBytes(bytes).into();
    bytes.zeroize();
    Ok(key.full_viewing_key().spend_verification_key().to_bytes())
}

/// The receive address for `account`: index 0 of the BIP44 account's key.
#[cfg(feature = "penumbra")]
pub fn receive_address(seed_phrase: &str, account: u32) -> Result<String> {
    use penumbra_keys::keys::{AddressIndex, Bip44Path, SeedPhrase, SpendKey};
    use std::str::FromStr;

    let phrase = SeedPhrase::from_str(seed_phrase)
        .map_err(|e| crate::error::Error::Other(anyhow::anyhow!("Invalid mnemonic: {e}")))?;
    let key = SpendKey::from_seed_phrase_bip44(phrase, &Bip44Path::new(account));
    let (address, _) = key.full_viewing_key().payment_address(AddressIndex::new(0));
    Ok(address.to_string())
}

/// Store the receive address for a key id. Drops the pre-fix address tree.
pub fn store_penumbra_address(
    database: &sled::Db,
    key_id_hex: &str,
    bech32m_address: &str,
) -> Result<()> {
    database.drop_tree(PENUMBRA_ADDRS_UNSPENDABLE)?;
    let tree = database.open_tree(PENUMBRA_RECEIVE_ADDRS)?;
    tree.insert(key_id_hex.as_bytes(), bech32m_address.as_bytes())?;
    Ok(())
}

/// The stored receive address for a key id. `None` for a key added before the
/// fix whose address has not been derived again yet.
pub fn get_penumbra_address(database: &sled::Db, key_id_hex: &str) -> Result<Option<String>> {
    let tree = database.open_tree(PENUMBRA_RECEIVE_ADDRS)?;
    match tree.get(key_id_hex.as_bytes())? {
        Some(bytes) => {
            let address = String::from_utf8(bytes.to_vec())
                .map_err(|e| crate::error::Error::Other(anyhow::anyhow!("Invalid UTF-8: {}", e)))?;
            Ok(Some(address))
        }
        None => Ok(None),
    }
}

/// Derive the receive address for `account` and store it under its key id.
/// Run whenever the seed is at hand, so keys added before the fix get theirs.
#[cfg(feature = "penumbra")]
pub fn refresh_receive_address(
    database: &sled::Db,
    seed_phrase: &str,
    account: u32,
) -> Result<String> {
    let address = receive_address(seed_phrase, account)?;
    let id = hex::encode(key_id(seed_phrase, account)?);
    store_penumbra_address(database, &id, &address)?;
    Ok(address)
}
