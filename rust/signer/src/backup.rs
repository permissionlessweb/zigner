// backup.rs — encrypted backup and contact import/export via QR
//
// Backup key: HMAC-SHA512("zigner-backup", seed_phrase)[:32]
// Encryption: XChaCha20-Poly1305 (24-byte nonce, 16-byte tag)
// Format: version(1) || nonce(24) || ciphertext+tag(N+16)
//
// The backup contains everything except the seed phrase itself:
// accounts, contacts, FROST group metadata, wallet labels.
// The seed phrase is the decryption key — both are needed for full restore.

use chacha20poly1305::{
    aead::{Aead, KeyInit, Payload},
    XChaCha20Poly1305, XNonce,
};
use hmac::{Hmac, Mac};
use sha2::Sha512;
use zeroize::Zeroize;

type HmacSha512 = Hmac<Sha512>;

const BACKUP_DOMAIN: &[u8] = b"zigner-backup";
/// Backup format versions:
///   v1 — XChaCha20-Poly1305, empty AAD (legacy, accepted on decrypt)
///   v2 — XChaCha20-Poly1305, AAD = BACKUP_AAD_V2 (binds version into tag)
const BACKUP_VERSION_V1: u8 = 1;
const BACKUP_VERSION_V2: u8 = 2;
const BACKUP_VERSION: u8 = BACKUP_VERSION_V2;
/// Associated data bound into v2 AEAD tags. A v2 ciphertext can't be
/// presented as a forged v1 (or future v3) by stripping/swapping the
/// version byte — different AAD ⇒ tag mismatch.
const BACKUP_AAD_V2: &[u8] = b"zigner-backup\x00v2";
/// Maximum contacts to decode from untrusted CBOR to prevent OOM.
const MAX_DECODE_CONTACTS: usize = 10_000;

/// A backup key that zeroizes on drop.
pub struct BackupKey([u8; 32]);

impl BackupKey {
    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }
}

impl Drop for BackupKey {
    fn drop(&mut self) {
        self.0.zeroize();
    }
}

/// Derive the 32-byte backup encryption key from a seed phrase.
/// The returned key zeroizes itself on drop.
pub fn derive_backup_key(seed_phrase: &str) -> BackupKey {
    let mut mac =
        <HmacSha512 as Mac>::new_from_slice(BACKUP_DOMAIN).expect("HMAC accepts any key size");
    mac.update(seed_phrase.as_bytes());
    let mut result = mac.finalize().into_bytes();
    let mut key = [0u8; 32];
    key.copy_from_slice(&result[..32]);
    result.zeroize();
    BackupKey(key)
}

/// Encrypt arbitrary bytes with the backup key.
/// Returns: version(1) || nonce(24) || ciphertext+tag(N+16)
pub fn encrypt(key: &BackupKey, plaintext: &[u8]) -> Result<Vec<u8>, String> {
    let cipher = XChaCha20Poly1305::new(key.as_bytes().into());

    // Generate random nonce
    let mut nonce_bytes = [0u8; 24];
    getrandom(&mut nonce_bytes)?;
    let nonce = XNonce::from_slice(&nonce_bytes);

    let ciphertext = cipher
        .encrypt(
            nonce,
            Payload {
                msg: plaintext,
                aad: BACKUP_AAD_V2,
            },
        )
        .map_err(|e| format!("encryption failed: {e}"))?;

    let mut out = Vec::with_capacity(1 + 24 + ciphertext.len());
    out.push(BACKUP_VERSION);
    out.extend_from_slice(&nonce_bytes);
    out.extend_from_slice(&ciphertext);
    Ok(out)
}

/// Decrypt bytes produced by `encrypt`.
pub fn decrypt(key: &BackupKey, encrypted: &[u8]) -> Result<Vec<u8>, String> {
    if encrypted.len() < 1 + 24 + 16 {
        return Err("encrypted data too short".to_string());
    }

    let version = encrypted[0];
    let aad: &[u8] = match version {
        BACKUP_VERSION_V1 => &[], // legacy, no AAD
        BACKUP_VERSION_V2 => BACKUP_AAD_V2,
        _ => return Err(format!("unsupported backup version: {version}")),
    };

    let nonce = XNonce::from_slice(&encrypted[1..25]);
    let ciphertext = &encrypted[25..];

    let cipher = XChaCha20Poly1305::new(key.as_bytes().into());
    cipher
        .decrypt(
            nonce,
            Payload {
                msg: ciphertext,
                aad,
            },
        )
        .map_err(|_| "decryption failed — wrong seed phrase or corrupted data".to_string())
}

// ── contact CBOR encode/decode ──
//
// CBOR format for contacts (ur:zigner-contacts):
// array [ map { 1: address(tstr), 2: label(tstr), 3: chain_id(tstr) }, ... ]

use db_handling::contacts::Contact;

/// Encode contacts to CBOR bytes.
pub fn encode_contacts_cbor(contacts: &[Contact]) -> Vec<u8> {
    let mut buf = Vec::new();
    cbor_array_len(&mut buf, contacts.len());
    for c in contacts {
        buf.push(0xa3); // map(3)
        buf.push(0x01);
        cbor_tstr(&mut buf, &c.address);
        buf.push(0x02);
        cbor_tstr(&mut buf, &c.label);
        buf.push(0x03);
        cbor_tstr(&mut buf, &c.chain_id);
    }
    buf
}

/// Decode contacts from CBOR bytes.
pub fn decode_contacts_cbor(data: &[u8]) -> Result<Vec<Contact>, String> {
    let mut offset = 0;
    let (arr_len, consumed) = cbor_decode_array_len(data, offset)?;
    offset = consumed;

    if arr_len > MAX_DECODE_CONTACTS {
        return Err(format!(
            "too many contacts in CBOR ({arr_len} > {MAX_DECODE_CONTACTS})"
        ));
    }
    let mut contacts = Vec::with_capacity(arr_len);
    for _ in 0..arr_len {
        let (map_len, consumed) = cbor_decode_map_len(data, offset)?;
        offset = consumed;

        let mut address = String::new();
        let mut label = String::new();
        let mut chain_id = String::new();

        for _ in 0..map_len {
            let (key, consumed) = cbor_decode_uint(data, offset)?;
            offset = consumed;
            match key {
                1 => {
                    let (s, consumed) = cbor_decode_tstr(data, offset)?;
                    offset = consumed;
                    address = s;
                }
                2 => {
                    let (s, consumed) = cbor_decode_tstr(data, offset)?;
                    offset = consumed;
                    label = s;
                }
                3 => {
                    let (s, consumed) = cbor_decode_tstr(data, offset)?;
                    offset = consumed;
                    chain_id = s;
                }
                _ => {
                    offset = cbor_skip(data, offset)?;
                }
            }
        }
        contacts.push(Contact {
            address,
            label,
            chain_id,
        });
    }
    Ok(contacts)
}

// ── minimal CBOR helpers (inlined, no dependency on zcash.rs) ──

fn cbor_array_len(buf: &mut Vec<u8>, len: usize) {
    if len <= 23 {
        buf.push(0x80 | len as u8);
    } else if len <= 0xff {
        buf.push(0x98);
        buf.push(len as u8);
    } else {
        buf.push(0x99);
        buf.extend_from_slice(&(len as u16).to_be_bytes());
    }
}

fn cbor_tstr(buf: &mut Vec<u8>, s: &str) {
    let bytes = s.as_bytes();
    let len = bytes.len();
    if len <= 23 {
        buf.push(0x60 | len as u8);
    } else if len <= 0xff {
        buf.push(0x78);
        buf.push(len as u8);
    } else {
        buf.push(0x79);
        buf.extend_from_slice(&(len as u16).to_be_bytes());
    }
    buf.extend_from_slice(bytes);
}

fn cbor_decode_uint(data: &[u8], offset: usize) -> Result<(u64, usize), String> {
    if offset >= data.len() {
        return Err("truncated uint".to_string());
    }
    let first = data[offset];
    let additional = first & 0x1f;
    if first >> 5 != 0 {
        return Err(format!("expected uint, got major {}", first >> 5));
    }
    decode_length(data, offset, additional)
}

fn cbor_decode_tstr(data: &[u8], offset: usize) -> Result<(String, usize), String> {
    if offset >= data.len() {
        return Err("truncated tstr".to_string());
    }
    let first = data[offset];
    let major = first >> 5;
    let additional = first & 0x1f;
    if major != 3 {
        return Err(format!("expected tstr (major 3), got major {major}"));
    }
    let (len, header_end) = decode_length(data, offset, additional)?;
    let len = len as usize;
    if header_end + len > data.len() {
        return Err("truncated tstr data".to_string());
    }
    let s = std::str::from_utf8(&data[header_end..header_end + len])
        .map_err(|e| format!("invalid UTF-8 in tstr: {e}"))?;
    Ok((s.to_string(), header_end + len))
}

fn cbor_decode_array_len(data: &[u8], offset: usize) -> Result<(usize, usize), String> {
    if offset >= data.len() {
        return Err("truncated array".to_string());
    }
    let first = data[offset];
    if first >> 5 != 4 {
        return Err(format!(
            "expected array (major 4), got major {}",
            first >> 5
        ));
    }
    let (len, end) = decode_length(data, offset, first & 0x1f)?;
    Ok((len as usize, end))
}

fn cbor_decode_map_len(data: &[u8], offset: usize) -> Result<(usize, usize), String> {
    if offset >= data.len() {
        return Err("truncated map".to_string());
    }
    let first = data[offset];
    if first >> 5 != 5 {
        return Err(format!("expected map (major 5), got major {}", first >> 5));
    }
    let (len, end) = decode_length(data, offset, first & 0x1f)?;
    Ok((len as usize, end))
}

fn decode_length(data: &[u8], offset: usize, additional: u8) -> Result<(u64, usize), String> {
    match additional {
        0..=23 => Ok((additional as u64, offset + 1)),
        24 => {
            if offset + 2 > data.len() {
                return Err("truncated length".to_string());
            }
            Ok((data[offset + 1] as u64, offset + 2))
        }
        25 => {
            if offset + 3 > data.len() {
                return Err("truncated length".to_string());
            }
            Ok((
                u16::from_be_bytes([data[offset + 1], data[offset + 2]]) as u64,
                offset + 3,
            ))
        }
        _ => Err(format!("unsupported additional {additional}")),
    }
}

/// Skip a single CBOR value.
/// Maximum CBOR nesting depth. Hard cap defends against stack overflow
/// from a malicious deeply-nested QR payload.
const MAX_CBOR_DEPTH: u8 = 32;

fn cbor_skip(data: &[u8], offset: usize) -> Result<usize, String> {
    cbor_skip_depth(data, offset, 0)
}

fn cbor_skip_depth(data: &[u8], offset: usize, depth: u8) -> Result<usize, String> {
    if depth > MAX_CBOR_DEPTH {
        return Err(format!("CBOR nesting exceeds {MAX_CBOR_DEPTH}"));
    }
    if offset >= data.len() {
        return Err("truncated CBOR".to_string());
    }
    let first = data[offset];
    let major = first >> 5;
    let additional = first & 0x1f;
    let (content_len, header_end) = decode_length(data, offset, additional)?;
    let content_len = content_len as usize;
    match major {
        0 | 1 => Ok(header_end),
        2 | 3 => Ok(header_end + content_len),
        4 => {
            let mut pos = header_end;
            for _ in 0..content_len {
                pos = cbor_skip_depth(data, pos, depth + 1)?;
            }
            Ok(pos)
        }
        5 => {
            let mut pos = header_end;
            for _ in 0..content_len {
                pos = cbor_skip_depth(data, pos, depth + 1)?;
                pos = cbor_skip_depth(data, pos, depth + 1)?;
            }
            Ok(pos)
        }
        7 => Ok(offset + 1),
        _ => Err(format!("unsupported major {major}")),
    }
}

/// Fill buffer with cryptographically secure random bytes.
pub(crate) fn getrandom(buf: &mut [u8]) -> Result<(), String> {
    use std::io::Read;
    // /dev/urandom is the standard CSPRNG on Linux/Android.
    // We read it directly rather than pulling in the getrandom crate
    // to avoid adding another dependency. This is the same approach
    // used throughout the signer for key generation.
    let mut f = std::fs::File::open("/dev/urandom").map_err(|e| format!("open urandom: {e}"))?;
    f.read_exact(buf)
        .map_err(|e| format!("read urandom: {e}"))?;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    const TEST_PHRASE: &str =
        "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    #[test]
    fn test_backup_key_deterministic() {
        let k1 = derive_backup_key(TEST_PHRASE);
        let k2 = derive_backup_key(TEST_PHRASE);
        assert_eq!(k1.as_bytes(), k2.as_bytes());
        assert_ne!(k1.as_bytes(), &[0u8; 32]);
    }

    #[test]
    fn test_different_seeds_different_keys() {
        let k1 = derive_backup_key(TEST_PHRASE);
        let k2 = derive_backup_key("zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo zoo wrong");
        assert_ne!(k1.as_bytes(), k2.as_bytes());
    }

    #[test]
    fn test_encrypt_decrypt_roundtrip() {
        let key = derive_backup_key(TEST_PHRASE);
        let plaintext = b"hello world contacts data";
        let encrypted = encrypt(&key, plaintext).unwrap();
        let decrypted = decrypt(&key, &encrypted).unwrap();
        assert_eq!(decrypted, plaintext);
    }

    #[test]
    fn test_wrong_key_fails() {
        let key1 = derive_backup_key(TEST_PHRASE);
        let key2 = derive_backup_key("wrong phrase");
        let encrypted = encrypt(&key1, b"secret").unwrap();
        assert!(decrypt(&key2, &encrypted).is_err());
    }

    #[test]
    fn test_contacts_cbor_roundtrip() {
        let contacts = vec![
            Contact {
                address: "u1abc123".to_string(),
                label: "Alice".to_string(),
                chain_id: "zcash-mainnet".to_string(),
            },
            Contact {
                address: "u1def456".to_string(),
                label: "Bob".to_string(),
                chain_id: "zcash-mainnet".to_string(),
            },
        ];
        let cbor = encode_contacts_cbor(&contacts);
        let decoded = decode_contacts_cbor(&cbor).unwrap();
        assert_eq!(decoded.len(), 2);
        assert_eq!(decoded[0].address, "u1abc123");
        assert_eq!(decoded[0].label, "Alice");
        assert_eq!(decoded[1].label, "Bob");
        assert_eq!(decoded[1].chain_id, "zcash-mainnet");
    }

    #[test]
    fn test_encrypted_contacts_roundtrip() {
        let key = derive_backup_key(TEST_PHRASE);
        let contacts = vec![Contact {
            address: "u1test".to_string(),
            label: "Test".to_string(),
            chain_id: "zcash-mainnet".to_string(),
        }];
        let cbor = encode_contacts_cbor(&contacts);
        let encrypted = encrypt(&key, &cbor).unwrap();
        let decrypted = decrypt(&key, &encrypted).unwrap();
        let restored = decode_contacts_cbor(&decrypted).unwrap();
        assert_eq!(restored.len(), 1);
        assert_eq!(restored[0].label, "Test");
    }

    #[test]
    fn test_empty_contacts() {
        let contacts: Vec<Contact> = vec![];
        let cbor = encode_contacts_cbor(&contacts);
        let decoded = decode_contacts_cbor(&cbor).unwrap();
        assert!(decoded.is_empty());
    }
}
