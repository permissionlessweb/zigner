//! penumbra transaction signing
//!
//! implements decaf377-rdsa signing for penumbra transactions.
//! based on ledger-penumbra implementation by zondax.
//!
//! TODO(multichain): add display for a future external-custody / IbcRelay
//! action (relayed IBC packets signed under external custody). Penumbra
//! signing itself is intentionally unchanged.

#[cfg(feature = "penumbra")]
use decaf377::{Fq, Fr};
#[cfg(feature = "penumbra")]
use decaf377_rdsa::{Signature, SigningKey, SpendAuth, VerificationKey};
#[cfg(feature = "penumbra")]
use rand_chacha::{rand_core::SeedableRng, ChaCha20Rng};
#[cfg(feature = "penumbra")]
use zeroize::{Zeroize, ZeroizeOnDrop};

use crate::{Error, Result};

// ============================================================================
// constants
// ============================================================================

/// penumbra bip44 coin type
pub const PENUMBRA_COIN_TYPE: u32 = 6532;

/// penumbra bip44 path: m/44'/6532'/0'
pub const PENUMBRA_BIP44_PATH: &str = "m/44'/6532'/0'";

/// key expansion label for spend authorization key
const SPEND_AUTH_EXPAND_LABEL: &[u8; 16] = b"Penumbra_ExpndSd";

/// spend key bytes - the 32-byte seed derived from bip44
#[derive(Zeroize, ZeroizeOnDrop)]
#[cfg(feature = "penumbra")]
pub struct SpendKeyBytes(pub [u8; 32]);

#[cfg(feature = "penumbra")]
impl SpendKeyBytes {
    /// create from raw bytes
    pub fn from_bytes(bytes: [u8; 32]) -> Self {
        Self(bytes)
    }

    /// derive from seed phrase using bip44 path m/44'/6532'/0'
    pub fn from_seed_phrase(seed_phrase: &str, account: u32) -> Result<Self> {
        use bip32::{Mnemonic, XPrv};
        use hmac::Hmac;
        use sha2::Sha512;

        // parse mnemonic
        let mnemonic = Mnemonic::new(seed_phrase, bip32::Language::English)
            .map_err(|e| Error::PenumbraKeyDerivation(format!("invalid mnemonic: {e}")))?;

        // derive seed using pbkdf2 with 2048 rounds
        let password = mnemonic.phrase();
        let salt = "mnemonic";
        let mut seed_bytes = [0u8; 64];
        pbkdf2::pbkdf2::<Hmac<Sha512>>(
            password.as_bytes(),
            salt.as_bytes(),
            2048, // NUM_PBKDF2_ROUNDS in penumbra
            &mut seed_bytes,
        )
        .map_err(|e| Error::PenumbraKeyDerivation(format!("pbkdf2 failed: {e}")))?;

        // derive child key from bip44 path
        let path = format!("m/44'/{}'/{}'", PENUMBRA_COIN_TYPE, account);
        let child_key = XPrv::derive_from_path(
            seed_bytes,
            &path.parse().map_err(|e| {
                Error::PenumbraKeyDerivation(format!("invalid derivation path: {e}"))
            })?,
        )
        .map_err(|e| Error::PenumbraKeyDerivation(format!("key derivation failed: {e}")))?;

        // extract 32-byte private key and zeroize intermediates
        let mut child_key_bytes = child_key.to_bytes();
        let mut key_bytes = [0u8; 32];
        key_bytes.copy_from_slice(&child_key_bytes[..32]);

        seed_bytes.zeroize();
        child_key_bytes.zeroize();

        Ok(Self(key_bytes))
    }

    /// get raw bytes
    pub fn as_bytes(&self) -> &[u8; 32] {
        &self.0
    }
}

/// expand a field element from the spend key bytes using blake2b
#[cfg(feature = "penumbra")]
fn expand_ff(label: &[u8; 16], key: &[u8], input: &[u8]) -> Result<Fr> {
    let mut params = blake2b_simd::Params::new();
    params.personal(label);
    params.key(key);
    let hash = params.hash(input);
    Ok(Fr::from_le_bytes_mod_order(hash.as_bytes()))
}

/// derive the spend authorization key (ask) from spend key bytes
#[cfg(feature = "penumbra")]
pub fn derive_spend_auth_key(spend_key_bytes: &SpendKeyBytes) -> Result<SigningKey<SpendAuth>> {
    // ask = expand_ff("Penumbra_ExpndSd", spend_key_bytes, [0])
    let ask = expand_ff(SPEND_AUTH_EXPAND_LABEL, &spend_key_bytes.0, &[0u8])?;
    Ok(SigningKey::new_from_field(ask))
}

/// sign a spend action with a randomized key
///
/// this is the core penumbra signing function, matching ledger-penumbra's implementation.
///
/// # arguments
/// * `effect_hash` - the 64-byte effect hash of the transaction
/// * `randomizer` - the 32-byte randomizer from the spend plan
/// * `spend_key_bytes` - the 32-byte spend key seed
///
/// # returns
/// * 64-byte signature
#[cfg(feature = "penumbra")]
pub fn sign_spend(
    effect_hash: &[u8; 64],
    randomizer: &[u8; 32],
    spend_key_bytes: &SpendKeyBytes,
) -> Result<[u8; 64]> {
    // 1. derive base spend authorization key
    let ask = derive_spend_auth_key(spend_key_bytes)?;

    // 2. randomize the key with the action's randomizer
    let randomizer_fr = Fr::from_le_bytes_mod_order(randomizer);
    let rsk = ask.randomize(&randomizer_fr);

    // 3. create deterministic rng from randomizer (matches ledger implementation)
    let mut rng = ChaCha20Rng::from_seed(*randomizer);

    // 4. sign the effect hash
    let sig: Signature<SpendAuth> = rsk.sign(&mut rng, effect_hash);

    Ok(sig.to_bytes())
}

/// penumbra authorization data - signatures for a transaction
#[derive(Debug, Clone)]
pub struct PenumbraAuthorizationData {
    /// the effect hash that was signed
    pub effect_hash: [u8; 64],
    /// signatures for spend actions
    pub spend_auths: Vec<[u8; 64]>,
    /// signatures for delegator vote actions
    pub delegator_vote_auths: Vec<[u8; 64]>,
    /// signatures for liquidity tournament vote actions
    pub lqt_vote_auths: Vec<[u8; 64]>,
}

impl PenumbraAuthorizationData {
    /// create new empty authorization data
    pub fn new(effect_hash: [u8; 64]) -> Self {
        Self {
            effect_hash,
            spend_auths: Vec::new(),
            delegator_vote_auths: Vec::new(),
            lqt_vote_auths: Vec::new(),
        }
    }

    /// encode for QR output
    ///
    /// format:
    /// - effect_hash: 64 bytes
    /// - spend_auth_count: 2 bytes (le)
    /// - spend_auth_sigs: 64 bytes each
    /// - delegator_vote_count: 2 bytes (le)
    /// - delegator_vote_sigs: 64 bytes each
    /// - lqt_vote_count: 2 bytes (le)
    /// - lqt_vote_sigs: 64 bytes each
    pub fn encode(&self) -> Result<Vec<u8>> {
        // pre-allocate exact size
        let capacity = 64
            + 6
            + (self.spend_auths.len()
                + self.delegator_vote_auths.len()
                + self.lqt_vote_auths.len())
                * 64;
        let mut output = Vec::with_capacity(capacity);

        // effect hash
        output.extend_from_slice(&self.effect_hash);

        // spend auths (bounds-checked to prevent silent u16 truncation)
        if self.spend_auths.len() > u16::MAX as usize {
            return Err(Error::Other(anyhow::anyhow!(
                "too many spend auths: {}",
                self.spend_auths.len()
            )));
        }
        output.extend_from_slice(&(self.spend_auths.len() as u16).to_le_bytes());
        for sig in &self.spend_auths {
            output.extend_from_slice(sig);
        }

        // delegator vote auths
        if self.delegator_vote_auths.len() > u16::MAX as usize {
            return Err(Error::Other(anyhow::anyhow!(
                "too many delegator vote auths: {}",
                self.delegator_vote_auths.len()
            )));
        }
        output.extend_from_slice(&(self.delegator_vote_auths.len() as u16).to_le_bytes());
        for sig in &self.delegator_vote_auths {
            output.extend_from_slice(sig);
        }

        // lqt vote auths
        if self.lqt_vote_auths.len() > u16::MAX as usize {
            return Err(Error::Other(anyhow::anyhow!(
                "too many lqt vote auths: {}",
                self.lqt_vote_auths.len()
            )));
        }
        output.extend_from_slice(&(self.lqt_vote_auths.len() as u16).to_le_bytes());
        for sig in &self.lqt_vote_auths {
            output.extend_from_slice(sig);
        }

        Ok(output)
    }
}

/// sign a penumbra transaction plan
///
/// # arguments
/// * `effect_hash` - the computed effect hash (64 bytes)
/// * `spend_randomizers` - randomizers for each spend action
/// * `delegator_vote_randomizers` - randomizers for each delegator vote
/// * `lqt_vote_randomizers` - randomizers for liquidity tournament votes
/// * `spend_key_bytes` - the spend key seed
#[cfg(feature = "penumbra")]
pub fn sign_transaction(
    effect_hash: [u8; 64],
    spend_randomizers: &[[u8; 32]],
    delegator_vote_randomizers: &[[u8; 32]],
    lqt_vote_randomizers: &[[u8; 32]],
    spend_key_bytes: &SpendKeyBytes,
) -> Result<PenumbraAuthorizationData> {
    let mut auth_data = PenumbraAuthorizationData::new(effect_hash);

    // sign each spend action
    for randomizer in spend_randomizers {
        let sig = sign_spend(&effect_hash, randomizer, spend_key_bytes)?;
        auth_data.spend_auths.push(sig);
    }

    // sign each delegator vote (same signing method)
    for randomizer in delegator_vote_randomizers {
        let sig = sign_spend(&effect_hash, randomizer, spend_key_bytes)?;
        auth_data.delegator_vote_auths.push(sig);
    }

    // sign each lqt vote (same signing method)
    for randomizer in lqt_vote_randomizers {
        let sig = sign_spend(&effect_hash, randomizer, spend_key_bytes)?;
        auth_data.lqt_vote_auths.push(sig);
    }

    Ok(auth_data)
}

// ============================================================================
// effect hash verification (using penumbra SDK)
// ============================================================================

/// Verify that the effect hash from the QR matches the correct effect hash
/// computed from the transaction plan and FVK derived from the spend key.
///
/// This is critical for airgap security: without this check, a compromised
/// hot wallet could send a different effect hash than what the plan describes,
/// tricking Zigner into signing an unintended transaction.
#[cfg(feature = "penumbra")]
pub fn verify_effect_hash(
    plan_bytes: &[u8],
    qr_effect_hash: &[u8; 64],
    spend_key_bytes: &SpendKeyBytes,
) -> Result<()> {
    use penumbra_keys::keys::SpendKey;
    use penumbra_keys::keys::SpendKeyBytes as SdkSpendKeyBytes;
    use penumbra_proto::DomainType;
    use penumbra_transaction::plan::TransactionPlan;

    // Construct the SDK's SpendKey from our raw 32-byte seed
    let sdk_spend_key: SpendKey = SdkSpendKeyBytes(spend_key_bytes.0).into();
    let fvk = sdk_spend_key.full_viewing_key();

    // Decode the transaction plan from protobuf
    let plan = TransactionPlan::decode(plan_bytes)
        .map_err(|e| Error::PenumbraKeyDerivation(format!("failed to decode plan: {e}")))?;

    // Compute the correct effect hash
    let computed_hash = plan
        .effect_hash(fvk)
        .map_err(|e| Error::PenumbraKeyDerivation(format!("failed to compute effect hash: {e}")))?;

    // Constant-time comparison to prevent timing side-channel attacks
    use subtle::ConstantTimeEq;
    if computed_hash.as_bytes().ct_eq(qr_effect_hash).unwrap_u8() != 1 {
        return Err(Error::PenumbraKeyDerivation(
            "effect hash mismatch: QR hash does not match computed hash from plan. \
             The hot wallet may be compromised."
                .to_string(),
        ));
    }

    Ok(())
}

// ============================================================================
// effect hash computation
// ============================================================================

/// personalization strings for action types (from ledger-penumbra constants.rs)
pub mod personalization {
    pub const SPEND: &str = "/penumbra.core.component.shielded_pool.v1.SpendBody";
    pub const OUTPUT: &str = "/penumbra.core.component.shielded_pool.v1.OutputBody";
    pub const SWAP: &str = "/penumbra.core.component.dex.v1.SwapBody";
    pub const ICS20_WITHDRAWAL: &str = "/penumbra.core.component.ibc.v1.Ics20Withdrawal";
    pub const DELEGATE: &str = "/penumbra.core.component.stake.v1.Delegate";
    pub const UNDELEGATE: &str = "/penumbra.core.component.stake.v1.Undelegate";
    pub const DELEGATOR_VOTE: &str = "/penumbra.core.component.governance.v1.DelegatorVoteBody";
    pub const UNDELEGATE_CLAIM: &str = "/penumbra.core.component.stake.v1.UndelegateClaimBody";
    pub const POSITION_OPEN: &str = "/penumbra.core.component.dex.v1.PositionOpen";
    pub const POSITION_CLOSE: &str = "/penumbra.core.component.dex.v1.PositionClose";
    pub const POSITION_WITHDRAW: &str = "/penumbra.core.component.dex.v1.PositionWithdraw";
    pub const DUTCH_AUCTION_SCHEDULE: &str =
        "/penumbra.core.component.auction.v1.ActionDutchAuctionSchedule";
    pub const DUTCH_AUCTION_END: &str = "/penumbra.core.component.auction.v1.ActionDutchAuctionEnd";
    pub const DUTCH_AUCTION_WITHDRAW: &str =
        "/penumbra.core.component.auction.v1.ActionDutchAuctionWithdraw";
}

/// effect hash (64 bytes blake2b-512)
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct EffectHash(pub [u8; 64]);

impl EffectHash {
    /// create from raw bytes
    pub fn from_bytes(bytes: [u8; 64]) -> Self {
        Self(bytes)
    }

    /// get as byte slice
    pub fn as_bytes(&self) -> &[u8; 64] {
        &self.0
    }
}

#[cfg(feature = "penumbra")]
impl EffectHash {
    /// create a personalized blake2b state for hashing
    ///
    /// the personalization is length-prefixed as in ledger-penumbra
    fn create_personalized_state(personalization: &str) -> blake2b_simd::State {
        let mut state = blake2b_simd::State::new();

        // prepend personalization length as u64 LE
        let length = personalization.len() as u64;
        state.update(&length.to_le_bytes());
        state.update(personalization.as_bytes());

        state
    }

    /// compute effect hash from proto-encoded effecting data
    pub fn from_proto_effecting_data(personalization: &str, data: &[u8]) -> Self {
        let mut state = Self::create_personalized_state(personalization);
        state.update(data);

        let mut hash = [0u8; 64];
        hash.copy_from_slice(state.finalize().as_bytes());
        Self(hash)
    }

    /// compute the combined effect hash for a transaction
    ///
    /// this combines:
    /// - transaction parameters hash
    /// - memo hash (if present)
    /// - detection data hash (if present)
    /// - action count
    /// - each action's effect hash
    pub fn compute_transaction_effect_hash(
        parameters_hash: &[u8; 64],
        memo_hash: Option<&[u8; 64]>,
        detection_data_hash: Option<&[u8; 64]>,
        action_hashes: &[[u8; 64]],
    ) -> Self {
        // use "PenumbraEfHs" personalization for transaction-level hash
        let mut params = blake2b_simd::Params::new();
        params.personal(b"PenumbraEfHs");
        let mut state = params.to_state();

        // hash transaction parameters
        state.update(parameters_hash);

        // hash memo (or zeros if not present)
        match memo_hash {
            Some(h) => {
                state.update(h);
            }
            None => {
                state.update(&[0u8; 64]);
            }
        }

        // hash detection data (or zeros if not present)
        match detection_data_hash {
            Some(h) => {
                state.update(h);
            }
            None => {
                state.update(&[0u8; 64]);
            }
        }

        // hash action count
        let num_actions = action_hashes.len() as u32;
        state.update(&num_actions.to_le_bytes());

        // hash each action's effect hash
        for action_hash in action_hashes {
            state.update(action_hash);
        }

        let mut hash = [0u8; 64];
        hash.copy_from_slice(state.finalize().as_bytes());
        Self(hash)
    }
}

impl Default for EffectHash {
    fn default() -> Self {
        Self([0u8; 64])
    }
}

impl AsRef<[u8]> for EffectHash {
    fn as_ref(&self) -> &[u8] {
        &self.0
    }
}

// ============================================================================
// full viewing key
// ============================================================================

/// nullifier key - derived from spend key bytes
#[cfg(feature = "penumbra")]
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct NullifierKey(pub Fq);

#[cfg(feature = "penumbra")]
impl NullifierKey {
    /// derive nullifier key from spend key bytes
    /// `nk = expand_ff("Penumbra_ExpndSd", spend_key_bytes, [1])`
    pub fn derive_from(spend_key_bytes: &SpendKeyBytes) -> Result<Self> {
        let nk = expand_fq(SPEND_AUTH_EXPAND_LABEL, &spend_key_bytes.0, &[1u8])?;
        Ok(Self(nk))
    }

    /// convert to bytes
    pub fn to_bytes(&self) -> [u8; 32] {
        self.0.to_bytes()
    }
}

/// expand to Fq field element (for nullifier key)
#[cfg(feature = "penumbra")]
fn expand_fq(label: &[u8; 16], key: &[u8], input: &[u8]) -> Result<Fq> {
    let mut params = blake2b_simd::Params::new();
    params.personal(label);
    params.key(key);
    let hash = params.hash(input);
    Ok(Fq::from_le_bytes_mod_order(hash.as_bytes()))
}

/// full viewing key - can view all transactions but cannot spend
#[cfg(feature = "penumbra")]
#[derive(Clone, Debug)]
pub struct FullViewingKey {
    /// spend verification key (ak) - public key for spend authorization
    pub ak: VerificationKey<SpendAuth>,
    /// nullifier key (nk) - used to compute nullifiers
    pub nk: NullifierKey,
}

#[cfg(feature = "penumbra")]
impl FullViewingKey {
    /// domain separator for wallet id computation
    pub const WALLET_ID_DOMAIN_SEP: &'static [u8] = b"Penumbra_HashFVK";

    /// derive full viewing key from spend key bytes
    pub fn derive_from(spend_key_bytes: &SpendKeyBytes) -> Result<Self> {
        // derive spend authorization key (ask) then get verification key (ak)
        let ask = derive_spend_auth_key(spend_key_bytes)?;
        let ak: VerificationKey<SpendAuth> = (&ask).into();

        // derive nullifier key
        let nk = NullifierKey::derive_from(spend_key_bytes)?;

        Ok(Self { ak, nk })
    }

    /// get the raw bytes of the FVK (ak || nk = 64 bytes)
    pub fn to_bytes(&self) -> [u8; 64] {
        let mut bytes = [0u8; 64];
        bytes[..32].copy_from_slice(&self.ak.to_bytes());
        bytes[32..].copy_from_slice(&self.nk.to_bytes());
        bytes
    }

    /// construct FVK from raw bytes (ak || nk)
    pub fn from_bytes(bytes: &[u8; 64]) -> Result<Self> {
        let ak_bytes: [u8; 32] = bytes[..32].try_into().unwrap();
        let nk_bytes: [u8; 32] = bytes[32..].try_into().unwrap();

        let ak = VerificationKey::try_from(ak_bytes)
            .map_err(|e| Error::PenumbraKeyDerivation(format!("invalid ak: {e}")))?;
        let nk = Fq::from_bytes_checked(&nk_bytes)
            .map(NullifierKey)
            .map_err(|_| Error::PenumbraKeyDerivation("invalid nk".to_string()))?;

        Ok(Self { ak, nk })
    }

    /// compute the wallet id (hash of FVK)
    /// wallet_id = poseidon_hash(WALLET_ID_DOMAIN_SEP, nk, ak)
    pub fn wallet_id(&self) -> Result<WalletId> {
        let domain_sep = Fq::from_le_bytes_mod_order(Self::WALLET_ID_DOMAIN_SEP);
        let ak_fq = Fq::from_le_bytes_mod_order(&self.ak.to_bytes());

        let hash_result = poseidon377::hash_2(&domain_sep, (self.nk.0, ak_fq));
        let mut wallet_id = [0u8; 32];
        wallet_id.copy_from_slice(&hash_result.to_bytes()[..32]);

        Ok(WalletId(wallet_id))
    }

    /// encode FVK as bech32m string with "penumbrafullviewingkey" prefix
    pub fn to_bech32m(&self) -> Result<String> {
        use bech32::{Bech32m, Hrp};

        let hrp = Hrp::parse("penumbrafullviewingkey")
            .map_err(|e| Error::PenumbraKeyDerivation(format!("invalid hrp: {e}")))?;

        let bytes = self.to_bytes();
        let encoded = bech32::encode::<Bech32m>(hrp, &bytes)
            .map_err(|e| Error::PenumbraKeyDerivation(format!("bech32 encode error: {e}")))?;

        Ok(encoded)
    }

    /// decode FVK from bech32m string
    pub fn from_bech32m(s: &str) -> Result<Self> {
        use bech32::Hrp;

        let (hrp, data) = bech32::decode(s)
            .map_err(|e| Error::PenumbraKeyDerivation(format!("bech32 decode error: {e}")))?;

        let expected_hrp = Hrp::parse("penumbrafullviewingkey")
            .map_err(|e| Error::PenumbraKeyDerivation(format!("invalid hrp: {e}")))?;

        if hrp != expected_hrp {
            return Err(Error::PenumbraKeyDerivation(format!(
                "expected hrp 'penumbrafullviewingkey', got '{}'",
                hrp
            )));
        }

        if data.len() != 64 {
            return Err(Error::PenumbraKeyDerivation(format!(
                "expected 64 bytes, got {}",
                data.len()
            )));
        }

        let bytes: [u8; 64] = data.try_into().unwrap();
        Self::from_bytes(&bytes)
    }
}

/// wallet id - hash of the full viewing key, used as account identifier
#[derive(Clone, Copy, PartialEq, Eq, Debug)]
pub struct WalletId(pub [u8; 32]);

impl WalletId {
    /// encode wallet id as bech32m string with "penumbrawalletid" prefix
    #[cfg(feature = "penumbra")]
    pub fn to_bech32m(&self) -> Result<String> {
        use bech32::{Bech32m, Hrp};

        let hrp = Hrp::parse("penumbrawalletid")
            .map_err(|e| Error::PenumbraKeyDerivation(format!("invalid hrp: {e}")))?;

        let encoded = bech32::encode::<Bech32m>(hrp, &self.0)
            .map_err(|e| Error::PenumbraKeyDerivation(format!("bech32 encode error: {e}")))?;

        Ok(encoded)
    }

    /// get raw bytes
    pub fn to_bytes(&self) -> [u8; 32] {
        self.0
    }
}

// ============================================================================
// fvk export qr format
// ============================================================================

/// QR code type for FVK export
pub const QR_TYPE_FVK_EXPORT: u8 = 0x01;

/// FVK export data for QR code
#[derive(Debug, Clone)]
pub struct FvkExportData {
    /// account index (which BIP44 account this is)
    pub account_index: u32,
    /// optional label for the wallet
    pub label: Option<String>,
    /// the full viewing key bytes (64 bytes)
    pub fvk_bytes: [u8; 64],
    /// the wallet id (32 bytes)
    pub wallet_id: [u8; 32],
    /// ZID pubkey (32 bytes) — canonical device identity for zafu dedup.
    /// None for legacy exports; newer zigner builds always include it.
    pub zid_pubkey: Option<[u8; 32]>,
}

impl FvkExportData {
    /// create FVK export data from spend key bytes
    #[cfg(feature = "penumbra")]
    pub fn from_spend_key(
        spend_key_bytes: &SpendKeyBytes,
        account_index: u32,
        label: Option<String>,
    ) -> Result<Self> {
        let fvk = FullViewingKey::derive_from(spend_key_bytes)?;
        let wallet_id = fvk.wallet_id()?;

        Ok(Self {
            account_index,
            label,
            fvk_bytes: fvk.to_bytes(),
            wallet_id: wallet_id.0,
            zid_pubkey: None,
        })
    }

    /// encode for QR code
    ///
    /// format (v1 — legacy):
    /// ```text
    /// [0x53][0x03][0x01]           - prelude (substrate compat, penumbra, fvk export)
    /// [account_index: 4 bytes LE]  - which account
    /// [label_len: 1 byte]          - label length (0 = no label)
    /// [label: label_len bytes]     - utf8 label
    /// [fvk: 64 bytes]              - ak || nk
    /// [wallet_id: 32 bytes]        - for verification
    /// ```
    ///
    /// format (v2 — extended with ZID):
    /// ```text
    /// ... above ...
    /// [0x01]                       - extension flag: 1 = ZID present
    /// [zid_pubkey: 32 bytes]       - canonical device identity for dedup
    /// ```
    ///
    /// parsers see v1 payloads as exactly 104 bytes; v2 adds 33 more bytes.
    pub fn encode_qr(&self) -> Vec<u8> {
        let mut output = Vec::new();

        // prelude
        output.push(0x53); // substrate compat
        output.push(0x03); // penumbra
        output.push(QR_TYPE_FVK_EXPORT); // fvk export

        // account index
        output.extend_from_slice(&self.account_index.to_le_bytes());

        // label (truncated to 255 bytes max for u8 length prefix)
        match &self.label {
            Some(label) => {
                let label_bytes = label.as_bytes();
                let len = label_bytes.len().min(255);
                output.push(len as u8);
                output.extend_from_slice(&label_bytes[..len]);
            }
            None => {
                output.push(0);
            }
        }

        // fvk bytes
        output.extend_from_slice(&self.fvk_bytes);

        // wallet id
        output.extend_from_slice(&self.wallet_id);

        // optional ZID extension
        if let Some(zid) = &self.zid_pubkey {
            output.push(0x01); // extension: ZID present
            output.extend_from_slice(zid);
        }

        output
    }

    /// decode from QR code bytes
    pub fn decode_qr(data: &[u8]) -> Result<Self> {
        // QR payloads come from a hot wallet over the camera — every offset
        // arithmetic must use checked_add so a crafted oversized field
        // cannot wrap usize and bypass bounds checks, and every slice must
        // come from `data.get(..)` so an unexpected length produces a parse
        // error rather than a panic.
        fn read_arr<const N: usize>(
            data: &[u8],
            offset: &mut usize,
            what: &str,
        ) -> Result<[u8; N]> {
            let end = offset
                .checked_add(N)
                .ok_or_else(|| Error::PenumbraKeyDerivation(format!("{what}: offset overflow")))?;
            let slice = data
                .get(*offset..end)
                .ok_or_else(|| Error::PenumbraKeyDerivation(format!("{what}: truncated")))?;
            let arr: [u8; N] = slice.try_into().map_err(|_| {
                Error::PenumbraKeyDerivation(format!("{what}: slice/length mismatch"))
            })?;
            *offset = end;
            Ok(arr)
        }
        fn read_byte(data: &[u8], offset: &mut usize, what: &str) -> Result<u8> {
            let b = *data
                .get(*offset)
                .ok_or_else(|| Error::PenumbraKeyDerivation(format!("{what}: truncated")))?;
            *offset += 1;
            Ok(b)
        }

        // minimum size: 3 (prelude) + 4 (account) + 1 (label len) + 64 (fvk) + 32 (wallet_id) = 104
        if data.len() < 104 {
            return Err(Error::PenumbraKeyDerivation(
                "QR data too short".to_string(),
            ));
        }

        // validate prelude
        if data[0] != 0x53 || data[1] != 0x03 || data[2] != QR_TYPE_FVK_EXPORT {
            return Err(Error::PenumbraKeyDerivation(
                "invalid QR prelude for FVK export".to_string(),
            ));
        }

        let mut offset = 3;

        // account index
        let account_index = u32::from_le_bytes(read_arr::<4>(data, &mut offset, "account index")?);

        // label
        let label_len = read_byte(data, &mut offset, "label length")? as usize;
        let label = if label_len > 0 {
            let end = offset.checked_add(label_len).ok_or_else(|| {
                Error::PenumbraKeyDerivation("label: offset overflow".to_string())
            })?;
            let label_bytes = data
                .get(offset..end)
                .ok_or_else(|| {
                    Error::PenumbraKeyDerivation("label extends beyond data".to_string())
                })?
                .to_vec();
            offset = end;
            Some(String::from_utf8(label_bytes).map_err(|e| {
                Error::PenumbraKeyDerivation(format!("invalid UTF-8 in label: {e}"))
            })?)
        } else {
            None
        };

        // fvk bytes
        let fvk_bytes: [u8; 64] = read_arr(data, &mut offset, "fvk")?;

        // wallet id
        let wallet_id: [u8; 32] = read_arr(data, &mut offset, "wallet id")?;

        // optional ZID extension (v2 format): [0x01][32 bytes]
        let zid_pubkey = if data.get(offset).copied() == Some(0x01) {
            let mut zid_offset = offset + 1;
            // read_arr will return a clean parse error if the 32 bytes
            // aren't there — older wallets without the ZID extension
            // simply have nothing past the wallet id, so no 0x01 marker.
            let zid: [u8; 32] = read_arr(data, &mut zid_offset, "zid pubkey")?;
            offset = zid_offset;
            Some(zid)
        } else {
            None
        };
        let _ = offset; // suppress unused-assignment lint after final write

        Ok(Self {
            account_index,
            label,
            fvk_bytes,
            wallet_id,
            zid_pubkey,
        })
    }

    /// encode as hex string for QR
    pub fn encode_qr_hex(&self) -> String {
        hex::encode(self.encode_qr())
    }

    /// decode from hex string
    pub fn decode_qr_hex(hex_str: &str) -> Result<Self> {
        let data = hex::decode(hex_str)
            .map_err(|e| Error::PenumbraKeyDerivation(format!("hex decode error: {e}")))?;
        Self::decode_qr(&data)
    }
}

#[cfg(all(test, feature = "penumbra"))]
mod tests {
    use super::*;

    #[test]
    fn test_expand_ff() {
        // test that expand_ff produces consistent results
        let key = [0u8; 32];
        let result = expand_ff(SPEND_AUTH_EXPAND_LABEL, &key, &[0u8]).unwrap();
        // just verify it doesn't panic and returns something
        let bytes = result.to_bytes();
        assert_eq!(bytes.len(), 32);
    }

    #[test]
    fn test_sign_spend() {
        let spend_key_bytes = SpendKeyBytes::from_bytes([0u8; 32]);
        let effect_hash = [1u8; 64];
        let randomizer = [2u8; 32];

        let sig = sign_spend(&effect_hash, &randomizer, &spend_key_bytes).unwrap();
        assert_eq!(sig.len(), 64);

        // same inputs should produce same signature (deterministic)
        let sig2 = sign_spend(&effect_hash, &randomizer, &spend_key_bytes).unwrap();
        assert_eq!(sig, sig2);
    }

    #[test]
    fn test_authorization_data_encode() {
        let mut auth_data = PenumbraAuthorizationData::new([0u8; 64]);
        auth_data.spend_auths.push([1u8; 64]);
        auth_data.spend_auths.push([2u8; 64]);

        let encoded = auth_data.encode().unwrap();

        // 64 (effect_hash) + 2 (count) + 128 (2 sigs) + 2 + 2 = 198
        assert_eq!(encoded.len(), 64 + 2 + 128 + 2 + 2);

        // verify effect hash at start
        assert_eq!(&encoded[..64], &[0u8; 64]);

        // verify spend count
        assert_eq!(&encoded[64..66], &2u16.to_le_bytes());
    }

    #[test]
    fn test_effect_hash_from_proto() {
        let data = b"test proto data";
        let hash = EffectHash::from_proto_effecting_data(personalization::SPEND, data);
        assert_eq!(hash.as_bytes().len(), 64);

        // same input should produce same hash
        let hash2 = EffectHash::from_proto_effecting_data(personalization::SPEND, data);
        assert_eq!(hash.as_bytes(), hash2.as_bytes());

        // different personalization should produce different hash
        let hash3 = EffectHash::from_proto_effecting_data(personalization::OUTPUT, data);
        assert_ne!(hash.as_bytes(), hash3.as_bytes());
    }

    #[test]
    fn test_effect_hash_transaction() {
        let params_hash = [1u8; 64];
        let memo_hash = [2u8; 64];
        let action_hashes = vec![[3u8; 64], [4u8; 64]];

        let hash = EffectHash::compute_transaction_effect_hash(
            &params_hash,
            Some(&memo_hash),
            None,
            &action_hashes,
        );
        assert_eq!(hash.as_bytes().len(), 64);

        // without memo should be different
        let hash2 =
            EffectHash::compute_transaction_effect_hash(&params_hash, None, None, &action_hashes);
        assert_ne!(hash.as_bytes(), hash2.as_bytes());
    }

    #[test]
    fn test_sign_transaction() {
        let spend_key_bytes = SpendKeyBytes::from_bytes([42u8; 32]);
        let effect_hash = [1u8; 64];
        let spend_randomizers = vec![[2u8; 32], [3u8; 32]];
        let vote_randomizers = vec![[4u8; 32]];

        let auth_data = sign_transaction(
            effect_hash,
            &spend_randomizers,
            &vote_randomizers,
            &[],
            &spend_key_bytes,
        )
        .unwrap();

        assert_eq!(auth_data.effect_hash, effect_hash);
        assert_eq!(auth_data.spend_auths.len(), 2);
        assert_eq!(auth_data.delegator_vote_auths.len(), 1);
        assert_eq!(auth_data.lqt_vote_auths.len(), 0);
    }

    // ========================================================================
    // FVK tests
    // ========================================================================

    #[test]
    fn test_nullifier_key_derivation() {
        let spend_key_bytes = SpendKeyBytes::from_bytes([42u8; 32]);
        let nk = NullifierKey::derive_from(&spend_key_bytes).unwrap();

        // should produce consistent results
        let nk2 = NullifierKey::derive_from(&spend_key_bytes).unwrap();
        assert_eq!(nk.to_bytes(), nk2.to_bytes());

        // different spend key should produce different nk
        let spend_key_bytes2 = SpendKeyBytes::from_bytes([43u8; 32]);
        let nk3 = NullifierKey::derive_from(&spend_key_bytes2).unwrap();
        assert_ne!(nk.to_bytes(), nk3.to_bytes());
    }

    #[test]
    fn test_full_viewing_key_derivation() {
        let spend_key_bytes = SpendKeyBytes::from_bytes([42u8; 32]);
        let fvk = FullViewingKey::derive_from(&spend_key_bytes).unwrap();

        // FVK should be 64 bytes (ak: 32, nk: 32)
        let fvk_bytes = fvk.to_bytes();
        assert_eq!(fvk_bytes.len(), 64);

        // should produce consistent results
        let fvk2 = FullViewingKey::derive_from(&spend_key_bytes).unwrap();
        assert_eq!(fvk.to_bytes(), fvk2.to_bytes());
    }

    #[test]
    fn test_full_viewing_key_roundtrip() {
        let spend_key_bytes = SpendKeyBytes::from_bytes([42u8; 32]);
        let fvk = FullViewingKey::derive_from(&spend_key_bytes).unwrap();

        // to_bytes -> from_bytes roundtrip
        let fvk_bytes = fvk.to_bytes();
        let fvk_restored = FullViewingKey::from_bytes(&fvk_bytes).unwrap();
        assert_eq!(fvk.to_bytes(), fvk_restored.to_bytes());
    }

    #[test]
    fn test_wallet_id() {
        let spend_key_bytes = SpendKeyBytes::from_bytes([42u8; 32]);
        let fvk = FullViewingKey::derive_from(&spend_key_bytes).unwrap();
        let wallet_id = fvk.wallet_id().unwrap();

        // wallet id should be 32 bytes
        assert_eq!(wallet_id.to_bytes().len(), 32);

        // should be consistent
        let wallet_id2 = fvk.wallet_id().unwrap();
        assert_eq!(wallet_id.to_bytes(), wallet_id2.to_bytes());

        // different FVK should produce different wallet id
        let spend_key_bytes2 = SpendKeyBytes::from_bytes([43u8; 32]);
        let fvk2 = FullViewingKey::derive_from(&spend_key_bytes2).unwrap();
        let wallet_id3 = fvk2.wallet_id().unwrap();
        assert_ne!(wallet_id.to_bytes(), wallet_id3.to_bytes());
    }

    #[test]
    fn test_fvk_bech32m_roundtrip() {
        let spend_key_bytes = SpendKeyBytes::from_bytes([42u8; 32]);
        let fvk = FullViewingKey::derive_from(&spend_key_bytes).unwrap();

        // encode to bech32m
        let encoded = fvk.to_bech32m().unwrap();
        assert!(encoded.starts_with("penumbrafullviewingkey1"));

        // decode back
        let fvk_decoded = FullViewingKey::from_bech32m(&encoded).unwrap();
        assert_eq!(fvk.to_bytes(), fvk_decoded.to_bytes());
    }

    #[test]
    fn test_wallet_id_bech32m() {
        let spend_key_bytes = SpendKeyBytes::from_bytes([42u8; 32]);
        let fvk = FullViewingKey::derive_from(&spend_key_bytes).unwrap();
        let wallet_id = fvk.wallet_id().unwrap();

        let encoded = wallet_id.to_bech32m().unwrap();
        assert!(encoded.starts_with("penumbrawalletid1"));
    }

    #[test]
    fn test_fvk_export_data_encode_decode() {
        let spend_key_bytes = SpendKeyBytes::from_bytes([42u8; 32]);
        let export_data =
            FvkExportData::from_spend_key(&spend_key_bytes, 0, Some("My Wallet".to_string()))
                .unwrap();

        // encode to QR bytes
        let encoded = export_data.encode_qr();

        // verify prelude
        assert_eq!(encoded[0], 0x53);
        assert_eq!(encoded[1], 0x03);
        assert_eq!(encoded[2], QR_TYPE_FVK_EXPORT);

        // decode back
        let decoded = FvkExportData::decode_qr(&encoded).unwrap();
        assert_eq!(decoded.account_index, 0);
        assert_eq!(decoded.label, Some("My Wallet".to_string()));
        assert_eq!(decoded.fvk_bytes, export_data.fvk_bytes);
        assert_eq!(decoded.wallet_id, export_data.wallet_id);
    }

    #[test]
    fn test_fvk_export_data_no_label() {
        let spend_key_bytes = SpendKeyBytes::from_bytes([42u8; 32]);
        let export_data = FvkExportData::from_spend_key(&spend_key_bytes, 5, None).unwrap();

        let encoded = export_data.encode_qr();
        let decoded = FvkExportData::decode_qr(&encoded).unwrap();

        assert_eq!(decoded.account_index, 5);
        assert_eq!(decoded.label, None);
    }

    #[test]
    fn test_fvk_export_data_hex_roundtrip() {
        let spend_key_bytes = SpendKeyBytes::from_bytes([42u8; 32]);
        let export_data =
            FvkExportData::from_spend_key(&spend_key_bytes, 0, Some("Test".to_string())).unwrap();

        let hex = export_data.encode_qr_hex();
        let decoded = FvkExportData::decode_qr_hex(&hex).unwrap();

        assert_eq!(decoded.fvk_bytes, export_data.fvk_bytes);
        assert_eq!(decoded.wallet_id, export_data.wallet_id);
    }
}
