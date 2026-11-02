#![deny(unused_crate_dependencies)]
#![deny(rustdoc::broken_intra_doc_links)]

// dev-dependencies used only by penumbra-feature tests
#[cfg(test)]
use penumbra_asset as _;
#[cfg(test)]
use penumbra_shielded_pool as _;

use sp_runtime::MultiSigner;

use db_handling::db_transactions::TrDbColdStub;
use definitions::{
    crypto::Encryption, keyring::NetworkSpecsKey, navigation::MSCContent, users::AddressDetails,
};

mod sign_message;
use sign_message::{
    sufficient_crypto_add_specs, sufficient_crypto_load_metadata, sufficient_crypto_load_types,
};
mod sign_transaction;
#[cfg(test)]
mod tests;

mod error;
pub use error::{Error, Result};

// penumbra signing module
#[cfg(feature = "penumbra")]
pub mod penumbra;
#[cfg(feature = "penumbra")]
pub mod penumbra_review;
#[cfg(feature = "penumbra")]
pub use penumbra::{
    derive_spend_auth_key as penumbra_derive_spend_auth_key, sign_spend as penumbra_sign_spend,
    sign_transaction as penumbra_sign_transaction, EffectHash as PenumbraEffectHash,
    PenumbraAuthorizationData, SpendKeyBytes as PenumbraSpendKeyBytes, PENUMBRA_BIP44_PATH,
    PENUMBRA_COIN_TYPE,
};

// cosmos signing module
#[cfg(feature = "cosmos")]
pub mod cosmos;
#[cfg(feature = "cosmos")]
pub use cosmos::{sign_cosmos_amino, CosmosSignDocDisplay, CosmosSignRequest};

// zcash signing module
#[cfg(feature = "zcash")]
pub mod zcash;
#[cfg(feature = "zcash")]
pub use zcash::{
    attestation_digest, decode_notes_bundle_from_cbor,
    derive_orchard_fvk as zcash_derive_orchard_fvk,
    derive_transparent_address as zcash_derive_transparent_address, encode_notes_bundle_to_cbor,
    verify_merkle_path, OrchardFullViewingKey as ZcashOrchardFvk,
    OrchardSpendingKey as ZcashOrchardKey, TransparentSpendingKey as ZcashTransparentKey,
    ZcashFvkExportData, ZcashNoteSyncResult, ZcashNoteWithPath, ZcashNotesBundle,
    ZcashVerifiedNote, QR_TYPE_ZCASH_FVK_EXPORT, QR_TYPE_ZCASH_NOTES, ZCASH_COIN_TYPE,
};

pub use sign_transaction::{create_signature, SignatureAndChecksum, SignatureType};

pub fn handle_stub(database: &sled::Db, checksum: u32) -> Result<()> {
    Ok(TrDbColdStub::from_storage(database, checksum)?.apply(database)?)
}

pub fn handle_sign(
    database: &sled::Db,
    checksum: u32,
    seed_phrase: &str,
    pwd_entry: &str,
    user_comment: &str,
    idx: usize,
    encryption: Encryption,
) -> Result<Vec<u8>> {
    create_signature(
        database,
        seed_phrase,
        pwd_entry,
        user_comment,
        checksum,
        idx,
        encryption,
    )
    .map(|s| s.to_string().as_bytes().to_vec())
}

///Possible content to generate sufficient crypto for
#[derive(Debug, Clone)]
pub enum SufficientContent {
    AddSpecs(NetworkSpecsKey),
    LoadMeta(NetworkSpecsKey, u32),
    LoadTypes,
}

pub fn sign_content(
    database: &sled::Db,
    multisigner: &MultiSigner,
    address_details: &AddressDetails,
    content: SufficientContent,
    seed_phrase: &str,
    pwd_entry: &str,
) -> Result<(Vec<u8>, MSCContent)> {
    match content {
        SufficientContent::AddSpecs(network_specs_key) => sufficient_crypto_add_specs(
            database,
            &network_specs_key,
            multisigner,
            address_details,
            seed_phrase,
            pwd_entry,
        ),
        SufficientContent::LoadMeta(network_specs_key, version) => sufficient_crypto_load_metadata(
            database,
            &network_specs_key,
            version,
            multisigner,
            address_details,
            seed_phrase,
            pwd_entry,
        ),
        SufficientContent::LoadTypes => sufficient_crypto_load_types(
            database,
            multisigner,
            address_details,
            seed_phrase,
            pwd_entry,
        ),
    }
}
