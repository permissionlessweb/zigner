//! Ed25519 and ChaCha20-Poly1305 for the Android validator signer.
//!
//! Android's JCA only has Ed25519 from API 33 and ChaCha20-Poly1305 from
//! API 28, so the app calls these instead. Keys cross the FFI as the raw
//! 32-byte RFC 8032 seed (the same bytes the stored PKCS#8 wraps); every
//! secret copy made here is zeroized before return.

use crate::ErrorDisplayed;
use chacha20poly1305::aead::{Aead, AeadInPlace, KeyInit, Payload};
use chacha20poly1305::ChaCha20Poly1305;
use ed25519_dalek::{Signature, Signer, SigningKey, VerifyingKey};
use std::convert::TryFrom;
use zeroize::Zeroizing;

fn err(s: &str) -> ErrorDisplayed {
    ErrorDisplayed::Str { s: s.to_string() }
}

fn signing_key(seed: &[u8]) -> Result<SigningKey, ErrorDisplayed> {
    if seed.len() != 32 {
        return Err(err("ed25519 seed must be 32 bytes"));
    }
    let mut buf = Zeroizing::new([0u8; 32]);
    buf.copy_from_slice(seed);
    // SigningKey zeroizes itself on drop (ed25519-dalek "zeroize" feature).
    Ok(SigningKey::from_bytes(&buf))
}

pub fn ed25519_pubkey_from_seed(seed: &[u8]) -> Result<Vec<u8>, ErrorDisplayed> {
    Ok(signing_key(seed)?.verifying_key().to_bytes().to_vec())
}

pub fn ed25519_sign(seed: &[u8], msg: &[u8]) -> Result<Vec<u8>, ErrorDisplayed> {
    Ok(signing_key(seed)?.sign(msg).to_bytes().to_vec())
}

/// Plain RFC 8032 verify, same acceptance as the JCA provider it replaces.
pub fn ed25519_verify(pubkey: &[u8], msg: &[u8], sig: &[u8]) -> bool {
    let Ok(pk) = <[u8; 32]>::try_from(pubkey) else {
        return false;
    };
    let Ok(sig) = <[u8; 64]>::try_from(sig) else {
        return false;
    };
    let Ok(vk) = VerifyingKey::from_bytes(&pk) else {
        return false;
    };
    ed25519_dalek::Verifier::verify(&vk, msg, &Signature::from_bytes(&sig)).is_ok()
}

fn cipher(key: &[u8], nonce: &[u8]) -> Result<ChaCha20Poly1305, ErrorDisplayed> {
    if key.len() != 32 || nonce.len() != 12 {
        return Err(err("chacha20poly1305 key/nonce length"));
    }
    ChaCha20Poly1305::new_from_slice(key).map_err(|_| err("chacha20poly1305 key"))
}

pub fn chacha20poly1305_seal(
    key: &[u8],
    nonce: &[u8],
    aad: &[u8],
    plain: &[u8],
) -> Result<Vec<u8>, ErrorDisplayed> {
    cipher(key, nonce)?
        .encrypt(nonce.into(), Payload { msg: plain, aad })
        .map_err(|_| err("chacha20poly1305 seal"))
}

pub fn chacha20poly1305_open(
    key: &[u8],
    nonce: &[u8],
    aad: &[u8],
    ct: &[u8],
) -> Result<Vec<u8>, ErrorDisplayed> {
    // Decrypt in a buffer that is wiped on drop (also on the error path).
    // The returned Vec is the one copy uniffi hands to Kotlin.
    let mut buf = Zeroizing::new(ct.to_vec());
    cipher(key, nonce)?
        .decrypt_in_place(nonce.into(), aad, &mut *buf)
        .map_err(|_| err("chacha20poly1305 open"))?;
    Ok(buf.to_vec())
}

#[cfg(test)]
mod tests {
    use super::*;

    fn h(s: &str) -> Vec<u8> {
        hex::decode(s).unwrap()
    }

    /// RFC 8032 section 7.1, TEST 1/2/3 and TEST SHA(abc).
    pub(crate) const RFC8032: &[(&str, &str, &str, &str)] = &[
        ("9d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60",
         "d75a980182b10ab7d54bfed3c964073a0ee172f3daa62325af021a68f707511a", "",
         "e5564300c360ac729086e2cc806e828a84877f1eb8e5d974d873e065224901555fb8821590a33bacc61e39701cf9b46bd25bf5f0595bbe24655141438e7a100b"),
        ("4ccd089b28ff96da9db6c346ec114e0f5b8a319f35aba624da8cf6ed4fb8a6fb",
         "3d4017c3e843895a92b70aa74d1b7ebc9c982ccf2ec4968cc0cd55f12af4660c", "72",
         "92a009a9f0d4cab8720e820b5f642540a2b27b5416503f8fb3762223ebdb69da085ac1e43e15996e458f3613d0f11d8c387b2eaeb4302aeeb00d291612bb0c00"),
        ("c5aa8df43f9f837bedb7442f31dcb7b166d38535076f094b85ce3a2e0b4458f7",
         "fc51cd8e6218a1a38da47ed00230f0580816ed13ba3303ac5deb911548908025", "af82",
         "6291d657deec24024827e69c3abe01a30ce548a284743a445e3680d7db5ac3ac18ff9b538d16f290ae67f760984dc6594a7c15e9716ed28dc027beceea1ec40a"),
        ("833fe62409237b9d62ec77587520911e9a759cec1d19755b7da901b96dca3d42",
         "ec172b93ad5e563bf4932c70e1245034c35467ef2efd4d64ebf819683467e2bf",
         "ddaf35a193617abacc417349ae20413112e6fa4e89a97ea20a9eeee64b55d39a2192992a274fc1a836ba3c23a3feebbd454d4423643ce80e2a9ac94fa54ca49f",
         "dc2a4459e7369633a52b1bf277839a00201009a3efbf3ecb69bea2186c26b58909351fc9ac90b3ecfdfbc7c66431e0303dca179c138ac17ad9bef1177331a704"),
    ];

    #[test]
    fn rfc8032_vectors() {
        for (seed, pk, msg, sig) in RFC8032 {
            assert_eq!(ed25519_pubkey_from_seed(&h(seed)).unwrap(), h(pk));
            assert_eq!(ed25519_sign(&h(seed), &h(msg)).unwrap(), h(sig));
            assert!(ed25519_verify(&h(pk), &h(msg), &h(sig)));
            let mut bad = h(sig);
            bad[0] ^= 1;
            assert!(!ed25519_verify(&h(pk), &h(msg), &bad));
        }
    }

    /// The app stores keys as the 48-byte RFC 8410 PKCS#8 the JCA emits;
    /// the seed is its last 32 bytes. Parse the TEST 1 PKCS#8 that way and
    /// check the same pub/sig come out.
    #[test]
    fn pkcs8_seed_round_trip() {
        let pkcs8 = h("302e020100300506032b6570042204209d61b19deffd5a60ba844af492ec2cc44449c5697b326919703bac031cae7f60");
        let seed = &pkcs8[16..];
        let (_, pk, _, sig) = RFC8032[0];
        assert_eq!(ed25519_pubkey_from_seed(seed).unwrap(), h(pk));
        assert_eq!(ed25519_sign(seed, b"").unwrap(), h(sig));
    }

    #[test]
    fn bad_lengths() {
        assert!(ed25519_sign(&[0u8; 31], b"x").is_err());
        assert!(!ed25519_verify(&[0u8; 31], b"x", &[0u8; 64]));
    }

    /// RFC 8439 section 2.8.2 AEAD vector.
    #[test]
    fn chacha_rfc8439() {
        let key = h("808182838485868788898a8b8c8d8e8f909192939495969798999a9b9c9d9e9f");
        let nonce = h("070000004041424344454647");
        let aad = h("50515253c0c1c2c3c4c5c6c7");
        let pt = b"Ladies and Gentlemen of the class of '99: If I could offer you only one tip for the future, sunscreen would be it.";
        let ct = chacha20poly1305_seal(&key, &nonce, &aad, pt).unwrap();
        assert_eq!(
            hex::encode(&ct[ct.len() - 16..]),
            "1ae10b594f09e26a7e902ecbd0600691"
        );
        assert_eq!(
            chacha20poly1305_open(&key, &nonce, &aad, &ct).unwrap(),
            pt.to_vec()
        );
        let mut bad = ct.clone();
        bad[0] ^= 1;
        assert!(chacha20poly1305_open(&key, &nonce, &aad, &bad).is_err());
    }
}
