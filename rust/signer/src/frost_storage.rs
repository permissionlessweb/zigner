//! FROST key shares at rest.
//!
//! A FROST share has no seed phrase behind it: it exists only in this
//! device's database, so it used to sit there as plaintext JSON. It is now
//! sealed with XChaCha20-Poly1305 under a key derived from one of the
//! device's seeds, the seed the user already backs up:
//!
//! ```text
//!   key  = HMAC-SHA512("zigner-frost-storage-v1", seed)[..32]
//!   aad  = "zigner-frost-v1" || wallet_id
//!   blob = version(1) || nonce(24) || ciphertext+tag
//! ```
//!
//! The wallet id in the associated data stops one wallet's sealed secrets
//! being moved onto another wallet's row. The record keeps a fingerprint of
//! the sealing seed so the right seed is found on a device with several.
//!
//! Reading a share therefore needs the seed, which needs the unlock prompt.
//! A wallet stored before sealing is sealed the first time its secrets are
//! read with a seed at hand, and only after the sealed copy has been written
//! and read back.

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305, XNonce,
};
use db_handling::frost::FrostWalletData;
use hmac::{Hmac, Mac};
use sha2::Sha512;
use zeroize::Zeroizing;

const KEY_DOMAIN: &[u8] = b"zigner-frost-storage-v1";
const ID_DOMAIN: &[u8] = b"zigner-frost-storage-id-v1";
const AAD_DOMAIN: &[u8] = b"zigner-frost-v1";
const SEALED_V1: u8 = 1;

/// A wallet's two secrets, wiped on drop.
pub struct Secrets {
    pub key_package_hex: Zeroizing<String>,
    pub ephemeral_seed_hex: Zeroizing<String>,
}

fn hmac(key: &[u8], data: &[u8]) -> Zeroizing<[u8; 64]> {
    let mut mac = <Hmac<Sha512> as Mac>::new_from_slice(key).expect("HMAC takes any key size");
    mac.update(data);
    Zeroizing::new(mac.finalize().into_bytes().into())
}

fn storage_key(seed_phrase: &str) -> Zeroizing<[u8; 32]> {
    let wide = hmac(KEY_DOMAIN, seed_phrase.as_bytes());
    let mut key = Zeroizing::new([0u8; 32]);
    key.copy_from_slice(&wide[..32]);
    key
}

/// Short public fingerprint of a seed, stored beside the sealed secrets.
pub fn seed_id(seed_phrase: &str) -> String {
    hex::encode(&hmac(ID_DOMAIN, seed_phrase.as_bytes())[..8])
}

fn aad(wallet_id: &str) -> Vec<u8> {
    [AAD_DOMAIN, wallet_id.as_bytes()].concat()
}

fn seal(seed_phrase: &str, wallet_id: &str, secrets: &Secrets) -> Result<String, String> {
    let plaintext = Zeroizing::new(
        serde_json::to_vec(&serde_json::json!({
            "k": secrets.key_package_hex.as_str(),
            "e": secrets.ephemeral_seed_hex.as_str(),
        }))
        .map_err(|e| e.to_string())?,
    );
    let mut nonce = [0u8; 24];
    crate::backup::getrandom(&mut nonce)?;
    let cipher = XChaCha20Poly1305::new((&*storage_key(seed_phrase)).into());
    let ct = cipher
        .encrypt(
            XNonce::from_slice(&nonce),
            Payload {
                msg: &plaintext,
                aad: &aad(wallet_id),
            },
        )
        .map_err(|_| "sealing the FROST share failed".to_string())?;
    let mut blob = Vec::with_capacity(1 + 24 + ct.len());
    blob.push(SEALED_V1);
    blob.extend_from_slice(&nonce);
    blob.extend_from_slice(&ct);
    Ok(hex::encode(blob))
}

fn open(seed_phrase: &str, wallet_id: &str, sealed_hex: &str) -> Result<Secrets, String> {
    let blob = hex::decode(sealed_hex).map_err(|_| "sealed FROST share is not hex")?;
    if blob.len() < 1 + 24 + 16 || blob[0] != SEALED_V1 {
        return Err("sealed FROST share has an unknown format".into());
    }
    let cipher = XChaCha20Poly1305::new((&*storage_key(seed_phrase)).into());
    let plaintext = Zeroizing::new(
        cipher
            .decrypt(
                XNonce::from_slice(&blob[1..25]),
                Payload {
                    msg: &blob[25..],
                    aad: &aad(wallet_id),
                },
            )
            .map_err(|_| "the FROST share does not open with this key set".to_string())?,
    );
    let v: serde_json::Value = serde_json::from_slice(&plaintext).map_err(|e| e.to_string())?;
    let field = |k: &str| {
        v[k].as_str()
            .map(|s| Zeroizing::new(s.to_string()))
            .ok_or_else(|| format!("sealed FROST share lacks `{k}`"))
    };
    Ok(Secrets {
        key_package_hex: field("k")?,
        ephemeral_seed_hex: field("e")?,
    })
}

/// Seal `data`'s secrets in place under `seed_phrase`, clearing the plaintext
/// fields. The result is checked by opening it again before it is returned.
pub fn seal_wallet(data: &mut FrostWalletData, seed_phrase: &str) -> Result<(), String> {
    let wallet_id = db_handling::frost::wallet_id_hex(&data.public_key_package_hex);
    let secrets = Secrets {
        key_package_hex: Zeroizing::new(std::mem::take(&mut data.key_package_hex)),
        ephemeral_seed_hex: Zeroizing::new(std::mem::take(&mut data.ephemeral_seed_hex)),
    };
    let sealed = seal(seed_phrase, &wallet_id, &secrets)?;
    let back = open(seed_phrase, &wallet_id, &sealed)?;
    if *back.key_package_hex != *secrets.key_package_hex
        || *back.ephemeral_seed_hex != *secrets.ephemeral_seed_hex
    {
        return Err("sealed FROST share did not read back".into());
    }
    data.sealed = Some(sealed);
    data.seed_id = Some(seed_id(seed_phrase));
    Ok(())
}

/// The secrets of a stored wallet.
///
/// A sealed wallet opens with whichever of `seed_phrases` it was sealed
/// under. A wallet stored before sealing is returned as is and, when a seed
/// is available, sealed under the first one and written back - only after
/// the sealed copy opens to the same secrets, so a failure leaves the old
/// record untouched.
pub fn wallet_secrets(
    database: &sled::Db,
    data: &FrostWalletData,
    seed_phrases: &[String],
) -> Result<Secrets, String> {
    let wallet_id = db_handling::frost::wallet_id_hex(&data.public_key_package_hex);
    if let Some(sealed) = &data.sealed {
        let seed = seed_phrases
            .iter()
            .find(|s| Some(seed_id(s)) == data.seed_id)
            .ok_or("the key set this FROST wallet is sealed under is not on this device")?;
        return open(seed, &wallet_id, sealed);
    }

    let secrets = Secrets {
        key_package_hex: Zeroizing::new(data.key_package_hex.clone()),
        ephemeral_seed_hex: Zeroizing::new(data.ephemeral_seed_hex.clone()),
    };
    if let Some(seed) = seed_phrases.first() {
        let mut sealed = data.clone();
        seal_wallet(&mut sealed, seed)?;
        db_handling::frost::store_frost_wallet(database, &sealed)
            .map_err(|e| format!("sealing the stored FROST share: {e}"))?;
        let stored = db_handling::frost::get_frost_wallet(database, &wallet_id)
            .map_err(|e| e.to_string())?
            .ok_or("sealed FROST wallet vanished")?;
        let back = wallet_secrets(database, &stored, seed_phrases)?;
        if *back.key_package_hex != *secrets.key_package_hex {
            return Err("sealed FROST share did not read back".into());
        }
    }
    Ok(secrets)
}

#[cfg(test)]
mod tests {
    use super::*;

    const SEED_A: &str = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";
    const SEED_B: &str =
        "legal winner thank year wave sausage worth useful legal winner thank yellow";

    fn plaintext_wallet(pkg: &str) -> FrostWalletData {
        FrostWalletData {
            key_package_hex: "kp-secret".into(),
            public_key_package_hex: pkg.into(),
            ephemeral_seed_hex: "es-secret".into(),
            label: "2-of-3".into(),
            min_signers: 2,
            max_signers: 3,
            mainnet: true,
            created_at: 0,
            orchard_fvk_uview: None,
            address: None,
            relay_url: None,
            sealed: None,
            seed_id: None,
        }
    }

    #[test]
    fn sealed_wallet_holds_no_plaintext_and_opens_with_its_seed() {
        let db = sled::Config::new().temporary(true).open().unwrap();
        let mut w = plaintext_wallet("aa01");
        seal_wallet(&mut w, SEED_A).unwrap();
        assert!(w.key_package_hex.is_empty() && w.ephemeral_seed_hex.is_empty());
        let raw = serde_json::to_string(&w).unwrap();
        assert!(!raw.contains("kp-secret") && !raw.contains("es-secret"));

        db_handling::frost::store_frost_wallet(&db, &w).unwrap();
        let seeds = vec![SEED_B.to_string(), SEED_A.to_string()];
        let s = wallet_secrets(&db, &w, &seeds).unwrap();
        assert_eq!(*s.key_package_hex, "kp-secret");
        assert_eq!(*s.ephemeral_seed_hex, "es-secret");
    }

    #[test]
    fn wrong_seed_or_moved_blob_is_refused_without_change() {
        let db = sled::Config::new().temporary(true).open().unwrap();
        let mut w = plaintext_wallet("aa02");
        seal_wallet(&mut w, SEED_A).unwrap();
        assert!(wallet_secrets(&db, &w, &[SEED_B.to_string()]).is_err());

        // the same blob on another wallet's record does not open (AAD)
        let mut other = plaintext_wallet("bb02");
        other.sealed = w.sealed.clone();
        other.seed_id = w.seed_id.clone();
        other.key_package_hex.clear();
        other.ephemeral_seed_hex.clear();
        assert!(wallet_secrets(&db, &other, &[SEED_A.to_string()]).is_err());
    }

    #[test]
    fn plaintext_wallet_is_sealed_on_first_read() {
        let db = sled::Config::new().temporary(true).open().unwrap();
        let w = plaintext_wallet("aa03");
        let id = db_handling::frost::store_frost_wallet(&db, &w).unwrap();

        // no seed: works as before, record untouched
        let s = wallet_secrets(&db, &w, &[]).unwrap();
        assert_eq!(*s.key_package_hex, "kp-secret");
        assert!(db_handling::frost::get_frost_wallet(&db, &id)
            .unwrap()
            .unwrap()
            .sealed
            .is_none());

        // with a seed: same secrets back, and the record is now sealed
        let s = wallet_secrets(&db, &w, &[SEED_A.to_string()]).unwrap();
        assert_eq!(*s.ephemeral_seed_hex, "es-secret");
        let stored = db_handling::frost::get_frost_wallet(&db, &id)
            .unwrap()
            .unwrap();
        assert!(stored.sealed.is_some() && stored.key_package_hex.is_empty());
        let again = wallet_secrets(&db, &stored, &[SEED_A.to_string()]).unwrap();
        assert_eq!(*again.key_package_hex, "kp-secret");
    }
}
