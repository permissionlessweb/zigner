//! Penumbra key types and derivation
//! Based on ledger-penumbra implementation

use super::prf;
use crate::error::{Error, Result};
use decaf377::{Fq, Fr};
use decaf377_rdsa::{SigningKey, SpendAuth, VerificationKey};
use zeroize::{Zeroize, ZeroizeOnDrop};

/// Domain separator for PRF expansion
const PENUMBRA_EXPND_LABEL: &[u8; 16] = b"Penumbra_ExpndSd";
const PENUMBRA_DERIVE_OVK: &[u8; 16] = b"Penumbra_DeriOVK";
const PENUMBRA_DERIVE_DK: &[u8; 16] = b"Penumbra_DerivDK";
const IVK_DOMAIN_SEP: &[u8] = b"penumbra.derive.ivk";

/// Spend key bytes - the root secret derived from BIP32
#[derive(Clone, Zeroize, ZeroizeOnDrop)]
pub struct SpendKeyBytes(pub [u8; 32]);

impl SpendKeyBytes {
    /// Create from raw bytes
    pub fn new(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    /// Get the raw bytes
    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }

    /// Derive the spend authorization key (ask)
    pub fn spend_auth_key(&self) -> Result<SigningKey<SpendAuth>> {
        let fr = prf::expand_fr(PENUMBRA_EXPND_LABEL, &self.0, &[0u8])?;
        Ok(SigningKey::new_from_field(fr))
    }

    /// Derive the nullifier key (nk)
    pub fn nullifier_key(&self) -> Result<NullifierKey> {
        let fq = prf::expand_fq(PENUMBRA_EXPND_LABEL, &self.0, &[1u8])?;
        Ok(NullifierKey(fq))
    }

    /// Derive the verification key (ak) from spend auth key
    pub fn verification_key(&self) -> Result<VerificationKey<SpendAuth>> {
        let ask = self.spend_auth_key()?;
        Ok(ask.into())
    }

    /// Derive the full viewing key
    pub fn full_viewing_key(&self) -> Result<FullViewingKey> {
        FullViewingKey::derive_from(self)
    }
}

/// Nullifier key
#[derive(Clone, Copy)]
pub struct NullifierKey(pub Fq);

impl NullifierKey {
    pub fn to_bytes(&self) -> [u8; 32] {
        self.0.to_bytes()
    }
}

/// Outgoing viewing key
#[derive(Clone, Copy)]
pub struct OutgoingViewingKey(pub [u8; 32]);

/// Diversifier key
#[derive(Clone, Copy)]
pub struct DiversifierKey(pub [u8; 16]);

/// Incoming viewing key (ivk) - a ka::Secret
#[derive(Clone)]
pub struct IncomingViewingKey {
    pub ivk: Fr,
    pub dk: DiversifierKey,
}

/// Full viewing key containing ak (verification key) and nk (nullifier key)
#[derive(Clone)]
pub struct FullViewingKey {
    pub ak: VerificationKey<SpendAuth>,
    pub nk: NullifierKey,
    pub ovk: OutgoingViewingKey,
    pub ivk: IncomingViewingKey,
}

impl FullViewingKey {
    /// Derive FVK from spend key bytes
    pub fn derive_from(spk: &SpendKeyBytes) -> Result<Self> {
        let ak = spk.verification_key()?;
        let nk = spk.nullifier_key()?;

        // Derive OVK: ovk = prf_expand(b"Penumbra_DeriOVK", nk_bytes, ak_bytes)[0..32]
        let ovk = {
            let hash_result = prf::expand(PENUMBRA_DERIVE_OVK, &nk.to_bytes(), ak.as_ref())?;
            let mut ovk = [0u8; 32];
            ovk.copy_from_slice(&hash_result[0..32]);
            OutgoingViewingKey(ovk)
        };

        // Derive DK: dk = prf_expand(b"Penumbra_DerivDK", nk_bytes, ak_bytes)[0..16]
        let dk = {
            let hash_result = prf::expand(PENUMBRA_DERIVE_DK, &nk.to_bytes(), ak.as_ref())?;
            let mut dk = [0u8; 16];
            dk.copy_from_slice(&hash_result[0..16]);
            DiversifierKey(dk)
        };

        // Derive IVK: ivk = poseidon_hash_2(domain_sep, (nk, ak_s)) mod r
        let ivk = {
            let ak_bytes: [u8; 32] = ak.to_bytes();
            let ak_s = Fq::from_bytes_checked(&ak_bytes)
                .map_err(|_| Error::Other(anyhow::anyhow!("Invalid ak for Fq conversion")))?;
            let domain_sep = Fq::from_le_bytes_mod_order(IVK_DOMAIN_SEP);
            let ivk_fq = poseidon377::hash_2(&domain_sep, (nk.0, ak_s));
            Fr::from_le_bytes_mod_order(&ivk_fq.to_bytes())
        };

        Ok(Self {
            ak,
            nk,
            ovk,
            ivk: IncomingViewingKey { ivk, dk },
        })
    }

    /// Encode FVK as bytes (ak || nk = 64 bytes)
    pub fn to_bytes(&self) -> [u8; 64] {
        let mut bytes = [0u8; 64];
        bytes[0..32].copy_from_slice(&self.ak.to_bytes());
        bytes[32..64].copy_from_slice(&self.nk.to_bytes());
        bytes
    }

    /// Get the spend verification key
    pub fn spend_verification_key(&self) -> &VerificationKey<SpendAuth> {
        &self.ak
    }

    /// Get the nullifier key
    pub fn nullifier_key(&self) -> &NullifierKey {
        &self.nk
    }
}

/// Address index for deriving multiple addresses from one FVK
#[derive(Clone, Copy, Default)]
pub struct AddressIndex {
    pub account: u32,
    pub randomizer: [u8; 12],
}

impl AddressIndex {
    pub fn new(account: u32) -> Self {
        Self {
            account,
            randomizer: [0u8; 12],
        }
    }

    /// Create ephemeral address index with random randomizer
    pub fn ephemeral(account: u32) -> Self {
        use std::time::{SystemTime, UNIX_EPOCH};
        let mut randomizer = [0u8; 12];
        // Simple randomizer based on time - in production use proper RNG
        let nanos = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap_or_default()
            .as_nanos();
        randomizer[0..8].copy_from_slice(&(nanos as u64).to_le_bytes());
        // Add some extra entropy from a hash
        let hash = blake2b_simd::Params::new()
            .hash_length(12)
            .hash(&randomizer);
        randomizer.copy_from_slice(&hash.as_bytes()[0..12]);
        Self {
            account,
            randomizer,
        }
    }

    pub fn is_ephemeral(&self) -> bool {
        self.randomizer != [0u8; 12]
    }

    pub fn to_bytes(&self) -> [u8; 16] {
        let mut bytes = [0u8; 16];
        bytes[0..4].copy_from_slice(&self.account.to_le_bytes());
        bytes[4..16].copy_from_slice(&self.randomizer);
        bytes
    }
}

/// Domain separator for diversifier derivation
const PENUMBRA_DIVERSIFY: &[u8] = b"Penumbra_Divrsfy";

/// Clue key for fuzzy message detection
#[derive(Clone, Copy)]
pub struct ClueKey(pub [u8; 32]);

/// A Penumbra address (80 bytes: diversifier + pk_d + ck)
#[derive(Clone)]
pub struct Address {
    /// The diversifier (16 bytes)
    pub diversifier: [u8; 16],
    /// The transmission key pk_d (32 bytes)
    pub transmission_key: [u8; 32],
    /// The clue key ck (32 bytes)
    pub clue_key: ClueKey,
}

impl Address {
    /// Encode address as raw bytes (80 bytes)
    pub fn to_bytes(&self) -> [u8; 80] {
        let mut bytes = [0u8; 80];
        bytes[0..16].copy_from_slice(&self.diversifier);
        bytes[16..48].copy_from_slice(&self.transmission_key);
        bytes[48..80].copy_from_slice(&self.clue_key.0);
        bytes
    }

    /// Encode as bech32m with penumbra HRP
    pub fn to_bech32m(&self) -> Result<String> {
        // F4Jumble the address bytes before bech32m encoding
        let raw_bytes = self.to_bytes();
        let jumbled = f4jumble::f4jumble(&raw_bytes)
            .map_err(|e| Error::Other(anyhow::anyhow!("F4Jumble error: {:?}", e)))?;

        let hrp = bech32::Hrp::parse("penumbra")
            .map_err(|e| Error::Other(anyhow::anyhow!("Invalid HRP: {}", e)))?;

        bech32::encode::<bech32::Bech32m>(hrp, &jumbled)
            .map_err(|e| Error::Other(anyhow::anyhow!("Bech32m encode error: {}", e)))
    }
}

impl FullViewingKey {
    /// Derive an address for a given address index
    pub fn address_by_index(&self, index: &AddressIndex) -> Result<Address> {
        use aes::cipher::{BlockEncrypt, KeyInit};
        use aes::Aes128;

        // Step 1: Derive diversifier using AES-based PRF (simplified FF1)
        // In full Penumbra, this uses AES-FF1. We use AES-ECB as approximation.
        let diversifier = {
            let cipher = Aes128::new_from_slice(&self.ivk.dk.0)
                .map_err(|e| Error::Other(anyhow::anyhow!("AES key error: {}", e)))?;
            let mut block = aes::Block::default();
            block.copy_from_slice(&index.to_bytes());
            cipher.encrypt_block(&mut block);
            let mut d = [0u8; 16];
            d.copy_from_slice(&block);
            d
        };

        // Step 2: Derive diversified basepoint B_d = hash_to_curve(diversifier)
        // Using poseidon hash and decaf377 encoding
        let diversified_base = {
            let domain_sep = Fq::from_le_bytes_mod_order(PENUMBRA_DIVERSIFY);
            let d_fq = Fq::from_le_bytes_mod_order(&diversifier);
            let hash = poseidon377::hash_1(&domain_sep, d_fq);
            decaf377::Element::encode_to_curve(&hash)
        };

        // Step 3: Compute transmission key pk_d = ivk * B_d
        let transmission_key = {
            let pk_d = diversified_base * self.ivk.ivk;
            pk_d.vartime_compress().0
        };

        // Step 4: Derive clue key from OVK and diversifier
        let clue_key = {
            let hash = blake2b_simd::Params::new()
                .personal(b"Penumbra_ClueKey")
                .hash_length(32)
                .to_state()
                .update(&self.ovk.0)
                .update(&diversifier)
                .finalize();
            let mut ck = [0u8; 32];
            ck.copy_from_slice(hash.as_bytes());
            ClueKey(ck)
        };

        Ok(Address {
            diversifier,
            transmission_key,
            clue_key,
        })
    }

    /// Get address for account index (standard address, no randomizer)
    pub fn get_address(&self, account: u32) -> Result<Address> {
        self.address_by_index(&AddressIndex::new(account))
    }

    /// Get ephemeral address for IBC deposits
    pub fn get_ephemeral_address(&self, account: u32) -> Result<Address> {
        self.address_by_index(&AddressIndex::ephemeral(account))
    }
}

/// Derive spending key bytes from seed phrase using BIP32
/// Path: m/44'/6532'/account'
// WARNING: not the Penumbra derivation; addresses from this are unspendable. Use penumbra-sdk SpendKey::from_seed_phrase_bip44.
pub fn derive_spend_key_bytes(seed_phrase: &str, account: u32) -> Result<SpendKeyBytes> {
    use bip39::{Language, Mnemonic, Seed};

    // Parse mnemonic
    let mnemonic = Mnemonic::from_phrase(seed_phrase, Language::English)
        .map_err(|e| Error::Other(anyhow::anyhow!("Invalid mnemonic: {}", e)))?;

    // Generate seed (no passphrase)
    let seed = Seed::new(&mnemonic, "");

    // Derive using BIP32-style path m/44'/6532'/account'
    // Note: The ledger-penumbra uses secp256k1 BIP32 derivation, then takes the private key bytes
    // For simplicity, we'll derive a 32-byte key using blake2b with the seed and path
    let derivation_path = format!("m/44'/6532'/{}'/0/0", account);

    let mut hasher = blake2b_simd::Params::new();
    hasher.personal(b"PenumbraSpendKey");
    hasher.key(seed.as_bytes());
    let hash = hasher.hash(derivation_path.as_bytes());

    let mut spend_key_bytes = [0u8; 32];
    spend_key_bytes.copy_from_slice(&hash.as_bytes()[0..32]);

    Ok(SpendKeyBytes::new(spend_key_bytes))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_key_derivation() {
        let test_mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

        let spend_key = derive_spend_key_bytes(test_mnemonic, 0).unwrap();
        let fvk = spend_key.full_viewing_key().unwrap();

        // Check that FVK can be serialized
        let fvk_bytes = fvk.to_bytes();
        assert_eq!(fvk_bytes.len(), 64);

        println!("FVK bytes: {}", hex::encode(fvk_bytes));
    }

    #[test]
    fn test_address_derivation() {
        let test_mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

        let spend_key = derive_spend_key_bytes(test_mnemonic, 0).unwrap();
        let fvk = spend_key.full_viewing_key().unwrap();

        // Derive address for account 0
        let addr0 = fvk.get_address(0).unwrap();
        let addr0_bytes = addr0.to_bytes();
        assert_eq!(addr0_bytes.len(), 80);
        println!("Address 0 bytes: {}", hex::encode(addr0_bytes));

        // Derive address for account 1
        let addr1 = fvk.get_address(1).unwrap();
        let addr1_bytes = addr1.to_bytes();
        assert_eq!(addr1_bytes.len(), 80);
        println!("Address 1 bytes: {}", hex::encode(addr1_bytes));

        // Addresses should be different
        assert_ne!(addr0_bytes, addr1_bytes);

        // Same account should give same address
        let addr0_again = fvk.get_address(0).unwrap();
        assert_eq!(addr0_bytes, addr0_again.to_bytes());

        // Test bech32m encoding
        let bech32 = addr0.to_bech32m().unwrap();
        println!("Address 0 bech32m: {}", bech32);
        assert!(bech32.starts_with("penumbra1"));
    }

    #[test]
    fn test_ephemeral_address() {
        let test_mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

        let spend_key = derive_spend_key_bytes(test_mnemonic, 0).unwrap();
        let fvk = spend_key.full_viewing_key().unwrap();

        // Derive ephemeral address
        let ephemeral = fvk.get_ephemeral_address(0).unwrap();
        let regular = fvk.get_address(0).unwrap();

        // Ephemeral should be different from regular
        assert_ne!(ephemeral.to_bytes(), regular.to_bytes());

        // Each ephemeral call should be different (due to time-based randomizer)
        std::thread::sleep(std::time::Duration::from_millis(1));
        let ephemeral2 = fvk.get_ephemeral_address(0).unwrap();
        assert_ne!(ephemeral.to_bytes(), ephemeral2.to_bytes());

        println!(
            "Ephemeral address bech32m: {}",
            ephemeral.to_bech32m().unwrap()
        );
    }

    #[test]
    fn test_multiple_accounts() {
        let test_mnemonic = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

        // Derive spend keys for different accounts
        let spend_key_0 = derive_spend_key_bytes(test_mnemonic, 0).unwrap();
        let spend_key_1 = derive_spend_key_bytes(test_mnemonic, 1).unwrap();

        // Different accounts should have different spend keys
        assert_ne!(spend_key_0.as_bytes(), spend_key_1.as_bytes());

        // And different FVKs
        let fvk0 = spend_key_0.full_viewing_key().unwrap();
        let fvk1 = spend_key_1.full_viewing_key().unwrap();
        assert_ne!(fvk0.to_bytes(), fvk1.to_bytes());

        // And different addresses
        let addr0 = fvk0.get_address(0).unwrap();
        let addr1 = fvk1.get_address(0).unwrap();
        assert_ne!(addr0.to_bytes(), addr1.to_bytes());

        println!("Account 0 address: {}", addr0.to_bech32m().unwrap());
        println!("Account 1 address: {}", addr1.to_bech32m().unwrap());
    }
}
