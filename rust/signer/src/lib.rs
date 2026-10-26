// Copyright 2015-2021 Parity Technologies (UK) Ltd.
// This file is part of Parity.

// Parity is free software: you can redistribute it and/or modify
// it under the terms of the GNU General Public License as published by
// the Free Software Foundation, either version 3 of the License, or
// (at your option) any later version.

// Parity is distributed in the hope that it will be useful,
// but WITHOUT ANY WARRANTY; without even the implied warranty of
// MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
// GNU General Public License for more details.

// You should have received a copy of the GNU General Public License
// along with Parity.  If not, see <http://www.gnu.org/licenses/>.

//! This crate serves as interface between native frontend and Rust code. Try to avoid placing any
//! logic here, just interfacing. When porting to new platform, all Rust changes will probably
//! happen here.

#![deny(unused_crate_dependencies)]
#![deny(rustdoc::broken_intra_doc_links)]
#![allow(clippy::let_unit_value)]
#![allow(clippy::unnecessary_struct_initialization)]
#![allow(clippy::useless_conversion)]
#![allow(clippy::unneeded_struct_pattern)]

// These crates are used by pczt but need to be declared here
// to satisfy the unused_crate_dependencies lint
use zcash_transparent as _;

pub mod age_backup;
pub mod auth;
pub mod ed25519_ffi;
pub use ed25519_ffi::{
    chacha20poly1305_open, chacha20poly1305_seal, ed25519_pubkey_from_seed, ed25519_sign,
    ed25519_verify,
};
pub mod backup;
mod ffi_types;
pub mod frost_backup;
pub mod frost_multisig;
pub mod release_signing;
pub use release_signing::ReleaseSigningRequest;
pub mod ssh;
mod zcash_consensus_gate;

use crate::ffi_types::*;
use db_handling::identities::{import_all_addrs, inject_derivations_has_pwd};
use db_handling::{Error as DbHandlingError, Error};
use definitions::keyring::AddressKey;
use definitions::navigation::ZcashSimpleSignRequest;
use lazy_static::lazy_static;
use navigator::Error as NavigatorError;
use sled::Db;
use std::{
    collections::HashMap,
    convert::TryInto,
    fmt::Display,
    str::FromStr,
    sync::{Arc, RwLock},
};
use transaction_parsing::dynamic_derivations::process_dynamic_derivations;
use transaction_parsing::entry_to_transactions_with_decoding;
use transaction_parsing::Error as TxParsingError;
use transaction_signing::SufficientContent;

lazy_static! {
    static ref DB: Arc<RwLock<Option<Db>>> = Arc::new(RwLock::new(None));
}

/// Container for severe error message
///
/// TODO: implement properly or remove completely
#[derive(Debug)]
pub enum ErrorDisplayed {
    /// String description of error
    Str {
        /// Error description
        s: String,
    },
    MutexPoisoned,
    DbNotInitialized,
    /// Tried to load metadata for unknown network.
    LoadMetaUnknownNetwork {
        /// Name of the network not known to the Vault.
        name: String,
    },
    /// Tried to add specs already present in Vault.
    SpecsKnown {
        name: String,
        encryption: Encryption,
    },
    /// The metadata with this network version already in db.
    MetadataKnown {
        name: String,
        version: u32,
    },
    /// Do not have an up-to-date version of metadata in db
    MetadataOutdated {
        name: String,
        have: u32,
        want: u32,
    },
    /// Tried to sign transaction with an unknown network
    UnknownNetwork {
        genesis_hash: H256,
        encryption: Encryption,
    },
    /// No metadata for a known network found in store
    NoMetadata {
        name: String,
    },
    /// Provided password is incorrect
    WrongPassword,
    /// Database schema mismatch
    DbSchemaMismatch,
}

impl From<NavigatorError> for ErrorDisplayed {
    fn from(e: NavigatorError) -> Self {
        match &e {
            NavigatorError::MutexPoisoned => Self::MutexPoisoned,
            NavigatorError::DbNotInitialized => Self::DbNotInitialized,
            NavigatorError::TransactionParsing(t) => match t {
                TxParsingError::LoadMetaUnknownNetwork { name } => {
                    Self::LoadMetaUnknownNetwork { name: name.clone() }
                }
                TxParsingError::SpecsKnown { name, encryption } => Self::SpecsKnown {
                    name: name.clone(),
                    encryption: *encryption,
                },
                TxParsingError::MetadataKnown { name, version } => Self::MetadataKnown {
                    name: name.clone(),
                    version: *version,
                },
                TxParsingError::AllExtensionsParsingFailed {
                    ref network_name,
                    ref errors,
                } => {
                    if let Some((want, parser::Error::WrongNetworkVersion { in_metadata, .. })) =
                        errors.first()
                    {
                        Self::MetadataOutdated {
                            name: network_name.to_string(),
                            have: *in_metadata,
                            want: *want,
                        }
                    } else {
                        Self::Str { s: format!("{e}") }
                    }
                }
                TxParsingError::UnknownNetwork {
                    genesis_hash,
                    encryption,
                } => Self::UnknownNetwork {
                    genesis_hash: *genesis_hash,
                    encryption: *encryption,
                },
                TxParsingError::NoMetadata { name } => Self::NoMetadata {
                    name: name.to_string(),
                },
                _ => Self::Str { s: format!("{e}") },
            },
            NavigatorError::TransactionSigning(transaction_signing::Error::WrongPassword) => {
                Self::WrongPassword
            }
            _ => Self::Str { s: format!("{e}") },
        }
    }
}

impl From<DbHandlingError> for ErrorDisplayed {
    fn from(e: DbHandlingError) -> Self {
        match &e {
            Error::DbSchemaMismatch { .. } => Self::DbSchemaMismatch,
            _ => Self::Str { s: format!("{e}") },
        }
    }
}

impl From<anyhow::Error> for ErrorDisplayed {
    fn from(e: anyhow::Error) -> Self {
        Self::Str {
            s: format!("error on signer side: {e}"),
        }
    }
}

impl FromStr for ErrorDisplayed {
    type Err = ();

    fn from_str(s: &str) -> Result<Self, Self::Err> {
        Ok(ErrorDisplayed::Str { s: s.to_string() })
    }
}

impl From<String> for ErrorDisplayed {
    fn from(s: String) -> Self {
        ErrorDisplayed::Str { s }
    }
}

impl Display for ErrorDisplayed {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("TODO")
    }
}

/// An error type for QR sequence decoding errors.
#[derive(Debug)]
pub enum QrSequenceDecodeError {
    BananaSplitWrongPassword,
    BananaSplit { s: String },
    GenericError { s: String },
}

impl Display for QrSequenceDecodeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str("TODO")
    }
}

impl From<qr_reader_phone::Error> for QrSequenceDecodeError {
    fn from(value: qr_reader_phone::Error) -> Self {
        match value {
            qr_reader_phone::Error::BananaSplitWrongPassword => Self::BananaSplitWrongPassword,
            qr_reader_phone::Error::BananaSplitError(e) => Self::BananaSplit { s: format!("{e}") },
            other => QrSequenceDecodeError::GenericError {
                s: format!("{other}"),
            },
        }
    }
}

include!(concat!(env!("OUT_DIR"), "/signer.uniffi.rs"));

fn action_get_name(action: &Action) -> Option<FooterButton> {
    match action {
        Action::NavbarLog => Some(FooterButton::Log),
        Action::NavbarScan => Some(FooterButton::Scan),
        Action::NavbarKeys => Some(FooterButton::Keys),
        Action::NavbarSettings => Some(FooterButton::Settings),
        Action::GoBack => Some(FooterButton::Back),
        _ => None,
    }
}

/// Perform action in frontend.
///
/// This call should be debounced.
///
/// Action tries to acquire lock on app state mutex and is ignored on failure.
///
/// `seed_phrase` field is zeroized, it is expected to be used for secrets only.
///
/// `details` field is not always zeroized.
///
/// App view contents are returned as result, this should be sufficient to render view.
fn backend_action(
    action: Action,
    details: &str,
    seed_phrase: &str,
) -> Result<ActionResult, ErrorDisplayed> {
    Ok(navigator::do_action(action, details, seed_phrase)?)
}

/// Should be called once at start of the app and could be called on app reset
///
/// Accepts list of seed names to avoid calling [`update_seed_names`] every time
fn init_navigation(dbname: &str, seed_names: Vec<String>) -> Result<(), ErrorDisplayed> {
    let val = Some(sled::open(dbname).map_err(|e| ErrorDisplayed::from(e.to_string()))?);

    // Each DB lock MUST be released before the next is acquired. `DB` is a
    // std RwLock: a thread that holds the write guard and then calls read()
    // on the same lock deadlocks forever. 0.10.0 released the write lock
    // implicitly (`*DB.write().unwrap() = val;` drops the temporary at the
    // `;`); the poisoned-recovery refactor bound the write guard to a
    // variable that stayed alive across the reads below, freezing
    // init_navigation on EVERY launch (universal startup ANR). Keep the
    // poisoned recovery, but scope each guard so it drops before the next.
    // (A poisoned lock means an earlier call panicked while holding it;
    // unwrap() would then make every subsequent call panic too.)
    {
        let mut guard = match DB.write() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        *guard = val;
    }
    init_logging("Vault".to_string());

    // Bootstrap the anchor-verifier registry with the built-in default
    // on first run. Idempotent: skipped if the tree already has the
    // default key, and skipped entirely if the user has populated their
    // own verifiers (we don't override their explicit configuration).
    {
        let guard = match DB.read() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        if let Some(database) = guard.as_ref() {
            if constants::ROTKO_ZCASH_VERIFIER != [0u8; 32] {
                let _ = db_handling::anchor_verifiers::bootstrap_default(
                    database,
                    &constants::ROTKO_ZCASH_VERIFIER,
                    "Rotko Networks (built-in)",
                );
            }
        }
    }

    // Clone the sled handle out under a short-lived read lock, then release it
    // before calling navigator::init_navigation (which may lock DB itself).
    let database = {
        let guard = match DB.read() {
            Ok(g) => g,
            Err(poisoned) => poisoned.into_inner(),
        };
        guard.as_ref().unwrap().clone()
    };
    // New default networks (Terp) appear on an existing database without a wipe.
    let _ = db_handling::cold_default::ensure_missing_default_networks(&database);
    Ok(navigator::init_navigation(database, seed_names)?)
}

/// Should be called every time any change could have been done to seeds. Accepts updated list of
/// seeds, completely disregards previously known list
fn update_seed_names(seed_names: Vec<String>) -> Result<(), ErrorDisplayed> {
    Ok(navigator::update_seed_names(seed_names)?)
}

/// Determines estimated required number of multiframe QR that should be gathered before decoding
/// is attempted
fn qrparser_get_packets_total(data: &str, cleaned: bool) -> anyhow::Result<u32, ErrorDisplayed> {
    qr_reader_phone::get_length(data, cleaned).map_err(|e| e.to_string().into())
}

/// Attempts to convert QR data (transfered as json-like string) into decoded but not parsed UOS
/// payload
///
/// `cleaned` is platform-specific flag indicating whether QR payloads have QR prefix stripped by
/// QR parsing code
fn qrparser_try_decode_qr_sequence(
    data: &[String],
    password: Option<String>,
    cleaned: bool,
) -> anyhow::Result<DecodeSequenceResult, QrSequenceDecodeError> {
    let res = qr_reader_phone::decode_sequence(data, &password, cleaned);

    Ok(res?)
}

fn get_db() -> Result<sled::Db, ErrorDisplayed> {
    DB.read()
        .unwrap()
        .clone()
        .ok_or(ErrorDisplayed::DbNotInitialized)
}

/// Exports secret (private) key as QR code
///
/// `public_key` is hex-encoded public key of the key to export. Can be taken from [`MKeyDetails`]
/// `network_specs_key` is hex-encoded [`NetworkSpecsKey`]. Can be taken from [`MSCNetworkInfo`]
fn generate_secret_key_qr(
    public_key: &str,
    expected_seed_name: &str,
    network_specs_key: &str,
    seed_phrase: &str,
    key_password: Option<String>,
) -> Result<MKeyDetails, ErrorDisplayed> {
    db_handling::identities::export_secret_key(
        &get_db()?,
        public_key,
        expected_seed_name,
        network_specs_key,
        seed_phrase,
        key_password,
    )
    .map_err(|e| e.to_string().into())
}

fn import_derivations(seed_derived_keys: Vec<SeedKeysPreview>) -> Result<(), ErrorDisplayed> {
    import_all_addrs(&get_db()?, seed_derived_keys).map_err(|e| e.to_string().into())
}

/// Calculate if derivation path has a password
fn populate_derivations_has_pwd(
    seeds: HashMap<String, String>,
    seed_derived_keys: Vec<SeedKeysPreview>,
) -> Result<Vec<SeedKeysPreview>, anyhow::Error> {
    inject_derivations_has_pwd(seed_derived_keys, seeds).map_err(Into::into)
}

fn preview_dynamic_derivations(
    seeds: HashMap<String, String>,
    payload: String,
) -> Result<DDPreview, ErrorDisplayed> {
    process_dynamic_derivations(&get_db()?, seeds, &payload).map_err(|e| e.to_string().into())
}

/// Checks derivation path for validity and collisions
///
/// Returns struct that has information on collisions, presence of password and validity of path;
/// in case of valid path without collisions frontend should make a decision on whether to access
/// secure storage already or check password by requesting user to re-type it, so this could not be
/// isolated in backend navigation for now.
fn substrate_path_check(
    seed_name: &str,
    path: &str,
    network: &str,
) -> Result<DerivationCheck, ErrorDisplayed> {
    Ok(db_handling::interface_signer::dynamic_path_check(
        &get_db()?,
        seed_name,
        path,
        network,
    ))
}

fn try_create_address(
    seed_name: &str,
    seed_phrase: &str,
    path: &str,
    network: &str,
) -> anyhow::Result<(), ErrorDisplayed> {
    let network = NetworkSpecsKey::from_hex(network).map_err(|e| format!("{e}"))?;
    db_handling::identities::try_create_address(&get_db()?, seed_name, seed_phrase, path, &network)
        .map_err(|e| e.to_string().into())
}

/// Create address by looking up network via genesis hash
/// genesis_hash_hex: hex-encoded 32-byte genesis hash
fn try_create_address_by_genesis(
    seed_name: &str,
    seed_phrase: &str,
    path: &str,
    genesis_hash_hex: &str,
) -> anyhow::Result<(), ErrorDisplayed> {
    use db_handling::helpers::genesis_hash_in_specs;
    use sp_core::H256;

    let db = get_db()?;
    let genesis_hash_bytes = hex::decode(genesis_hash_hex).map_err(|e| ErrorDisplayed::Str {
        s: format!("Invalid genesis hash hex: {e}"),
    })?;
    if genesis_hash_bytes.len() != 32 {
        return Err(ErrorDisplayed::Str {
            s: "Genesis hash must be 32 bytes".to_string(),
        });
    }
    let genesis_hash = H256::from_slice(&genesis_hash_bytes);

    let specs_invariants = genesis_hash_in_specs(&db, genesis_hash)
        .map_err(|e| ErrorDisplayed::Str { s: format!("{e}") })?
        .ok_or_else(|| ErrorDisplayed::Str {
            s: format!("Network with genesis hash {} not found", genesis_hash_hex),
        })?;

    db_handling::identities::try_create_address(
        &db,
        seed_name,
        seed_phrase,
        path,
        &specs_invariants.first_network_specs_key,
    )
    .map_err(|e| e.to_string().into())
}

/// Must be called with `DecodeSequenceResult::DynamicDerivationTransaction` payload
fn sign_dd_transaction(
    payload: &[String],
    seeds: HashMap<String, String>,
) -> Result<MSignedTransaction, ErrorDisplayed> {
    navigator::sign_dd_transaction(&get_db()?, payload, seeds).map_err(|e| e.to_string().into())
}

/// Must be called once on normal first start of the app upon accepting conditions; relies on old
/// data being already removed
fn history_init_history_with_cert() -> anyhow::Result<(), ErrorDisplayed> {
    db_handling::cold_default::signer_init_with_cert(&get_db()?).map_err(|e| e.to_string().into())
}

/// Must be called once upon jailbreak (removal of general verifier) after all old data was removed
fn history_init_history_no_cert() -> anyhow::Result<(), ErrorDisplayed> {
    db_handling::cold_default::signer_init_no_cert(&get_db()?).map_err(|e| e.to_string().into())
}

/// Must be called every time network detector detects network. Sets alert flag in database that could
/// only be reset by full reset or calling [`history_acknowledge_warnings`]
///
/// This changes log, so it is expected to fail all operations that check that database remained
/// intact
fn history_device_was_online() -> anyhow::Result<(), ErrorDisplayed> {
    db_handling::manage_history::device_was_online(&get_db()?).map_err(|e| e.to_string().into())
}

/// Checks if network alert flag was set
fn history_get_warnings() -> anyhow::Result<bool, ErrorDisplayed> {
    db_handling::helpers::get_danger_status(&get_db()?).map_err(|e| e.to_string().into())
}

/// Resets network alert flag; makes record of reset in log
fn history_acknowledge_warnings() -> anyhow::Result<(), ErrorDisplayed> {
    db_handling::manage_history::reset_danger_status_to_safe(&get_db()?)
        .map_err(|e| e.to_string().into())
}

/// Allows frontend to send events into log; TODO: maybe this is not needed
fn history_entry_system(event: Event) -> anyhow::Result<(), ErrorDisplayed> {
    db_handling::manage_history::history_entry_system(&get_db()?, event)
        .map_err(|e| e.to_string().into())
}

/// Must be called every time seed backup shows seed to user
///
/// Makes record in log
fn history_seed_was_shown(seed_name: &str) -> anyhow::Result<(), ErrorDisplayed> {
    db_handling::manage_history::seed_name_was_shown(&get_db()?, seed_name.to_string())
        .map_err(|e| e.to_string().into())
}

fn export_key_info(
    seed_name: &str,
    exported_set: ExportedSet,
) -> anyhow::Result<MKeysInfoExport, ErrorDisplayed> {
    navigator::export_key_info(&get_db()?, seed_name, exported_set)
        .map_err(|e| e.to_string().into())
}

fn keys_by_seed_name(seed_name: &str) -> anyhow::Result<MKeysNew, ErrorDisplayed> {
    navigator::keys_by_seed_name(&get_db()?, seed_name).map_err(|e| e.to_string().into())
}

/// Encode binary info into qr code
fn set_bound_operator(owner: Option<String>) {
    transaction_signing::cosmos::set_bound_operator(owner);
}

fn encode_to_qr(payload: &[u8], is_danger: bool) -> anyhow::Result<Vec<u8>, String> {
    use qrcode_static::DataType;
    let sensitivity = if is_danger {
        DataType::Sensitive
    } else {
        DataType::Regular
    };
    qrcode_static::png_qr(payload, sensitivity).map_err(|e| format!("{e}"))
}

/// Get all networks registered within this device
fn get_managed_networks() -> anyhow::Result<MManageNetworks, ErrorDisplayed> {
    Ok(MManageNetworks {
        networks: db_handling::interface_signer::show_all_networks(&get_db()?)
            .map_err(|e| e.to_string())?,
    })
}

fn get_logs() -> anyhow::Result<MLog, ErrorDisplayed> {
    let history = db_handling::manage_history::get_history(&get_db()?)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))?;
    let log: Vec<_> = history
        .into_iter()
        .map(|(order, entry)| History {
            order: order.stamp(),
            timestamp: entry.timestamp,
            events: entry.events,
        })
        .collect();

    Ok(MLog { log })
}

fn get_log_details(order: u32) -> anyhow::Result<MLogDetails, ErrorDisplayed> {
    let e = db_handling::manage_history::get_history_entry_by_order(&get_db()?, order)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))?;

    let timestamp = e.timestamp.clone();

    let events = entry_to_transactions_with_decoding(&get_db()?, e)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))?;

    Ok(MLogDetails { timestamp, events })
}

fn clear_log_history() -> anyhow::Result<(), ErrorDisplayed> {
    db_handling::manage_history::clear_history(&get_db()?)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))
}

fn handle_log_comment(string_from_user: &str) -> anyhow::Result<(), ErrorDisplayed> {
    db_handling::manage_history::history_entry_user(&get_db()?, string_from_user)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))
}

fn get_seeds(names_phone_knows: &[String]) -> anyhow::Result<MSeeds, ErrorDisplayed> {
    let seed_name_cards = db_handling::interface_signer::get_all_seed_names_with_identicons(
        &get_db()?,
        names_phone_knows,
    )
    .map_err(|e| ErrorDisplayed::from(e.to_string()))?;

    Ok(MSeeds { seed_name_cards })
}

fn get_key_set_public_key(
    address: &str,
    network_specs_key: &str,
) -> anyhow::Result<MKeyDetails, ErrorDisplayed> {
    let address_key =
        AddressKey::from_hex(address).map_err(|e| ErrorDisplayed::from(e.to_string()))?;

    let network_specs_key = NetworkSpecsKey::from_hex(network_specs_key)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))?;

    let address_details = db_handling::helpers::get_address_details(&get_db()?, &address_key)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))?;

    db_handling::interface_signer::export_key(
        &get_db()?,
        address_key.multi_signer(),
        &address_details.seed_name,
        &network_specs_key,
    )
    .map_err(|e| ErrorDisplayed::from(e.to_string()))
}

fn remove_derived_key(
    address: &str,
    network_specs_key: &str,
) -> anyhow::Result<(), ErrorDisplayed> {
    let address_key =
        AddressKey::from_hex(address).map_err(|e| ErrorDisplayed::from(e.to_string()))?;
    let network_specs_key = NetworkSpecsKey::from_hex(network_specs_key)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))?;
    db_handling::identities::remove_key(&get_db()?, address_key.multi_signer(), &network_specs_key)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))
}

fn remove_key_set(seed_name: &str) -> anyhow::Result<(), ErrorDisplayed> {
    db_handling::identities::remove_seed(&get_db()?, seed_name)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))
}

fn get_managed_network_details(
    network_key: &str,
) -> anyhow::Result<MNetworkDetails, ErrorDisplayed> {
    let network_key = NetworkSpecsKey::from_hex(network_key).map_err(|e| format!("{e}"))?;
    db_handling::interface_signer::network_details_by_key(&get_db()?, &network_key)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))
}

fn remove_metadata_on_managed_network(
    network_key: &str,
    metadata_specs_version: &str,
) -> anyhow::Result<(), ErrorDisplayed> {
    let network_key = NetworkSpecsKey::from_hex(network_key).map_err(|e| format!("{e}"))?;
    let version = metadata_specs_version
        .parse::<u32>()
        .map_err(|e| format!("{e}"))?;
    db_handling::helpers::remove_metadata(&get_db()?, &network_key, version)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))
}

fn seed_phrase_guess_words(user_input: &str) -> Vec<String> {
    db_handling::interface_signer::guess(user_input)
        .into_iter()
        .map(|s| s.to_owned())
        .collect()
}

fn get_verifier_details() -> anyhow::Result<MVerifierDetails, ErrorDisplayed> {
    Ok(db_handling::helpers::get_general_verifier(&get_db()?)
        .map_err(|e| e.to_string())?
        .show_card())
}

fn remove_managed_network(network_key: &str) -> anyhow::Result<(), ErrorDisplayed> {
    let network_key = NetworkSpecsKey::from_hex(network_key).map_err(|e| format!("{e}"))?;
    db_handling::helpers::remove_network(&get_db()?, &network_key)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))
}

fn print_new_seed(new_seed_name: &str) -> anyhow::Result<MNewSeedBackup, ErrorDisplayed> {
    db_handling::interface_signer::print_new_seed(new_seed_name)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))
}

fn validate_seed_phrase(seed_phrase: &str) -> bool {
    db_handling::helpers::validate_mnemonic(seed_phrase)
}

fn create_key_set(
    seed_name: &str,
    seed_phrase: &str,
    networks: Vec<String>,
) -> anyhow::Result<(), ErrorDisplayed> {
    db_handling::identities::create_key_set(&get_db()?, seed_name, seed_phrase, networks)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))
}

fn check_db_version() -> anyhow::Result<(), ErrorDisplayed> {
    db_handling::helpers::assert_db_version(&get_db()?).map_err(ErrorDisplayed::from)
}

fn get_keys_for_signing() -> Result<MSignSufficientCrypto, ErrorDisplayed> {
    let identities = db_handling::interface_signer::print_all_identities(&get_db()?)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))?;
    Ok(MSignSufficientCrypto { identities })
}

fn validate_key_password(
    address_key: &str,
    seed_phrase: &str,
    password: &str,
) -> Result<bool, ErrorDisplayed> {
    let address_key = AddressKey::from_hex(address_key).map_err(|e| format!("{e}"))?;
    db_handling::identities::validate_key_password(&get_db()?, &address_key, seed_phrase, password)
        .map_err(|e| e.into())
}

/// Create Banana Split shares from secret
fn bs_encrypt(
    secret: &str,
    title: &str,
    passphrase: &str,
    total_shards: u32,
    required_shards: u32,
) -> Result<Vec<QrData>, ErrorDisplayed> {
    navigator::banana_split_encode(secret, title, passphrase, total_shards, required_shards)
        .map_err(|e| ErrorDisplayed::from(e.to_string()))
}

/// Generate Banana Split passphrase
fn bs_generate_passphrase(n: u32) -> String {
    navigator::banana_split_passphrase(n)
}

/// Export seed backup data as JSON including account metadata (NO seed phrase)
/// Returns JSON: {"v":2,"name":"...","accounts":[{"path":"...","genesis_hash":"...","network":"...","encryption":"..."}]}
/// The seed phrase must be restored separately via banana split or manual entry
fn bs_export_backup_data(seed_name: &str, _seed_phrase: &str) -> Result<String, ErrorDisplayed> {
    use db_handling::helpers::try_get_network_specs;
    use db_handling::identities::get_addresses_by_seed_name;
    use serde_json::{json, Value};

    let db = get_db()?;
    let addresses = get_addresses_by_seed_name(&db, seed_name)
        .map_err(|e| ErrorDisplayed::from(format!("{e}")))?;

    let accounts: Vec<Value> = addresses
        .into_iter()
        .filter(|(_, details)| !details.is_root()) // skip root key, only derived accounts
        .map(|(_, details)| {
            // Look up network specs to get genesis_hash, network name, and base58prefix
            let (genesis_hash, network_name, base58prefix) = if let Some(ref network_id) =
                details.network_id
            {
                if let Ok(Some(specs)) = try_get_network_specs(&db, network_id) {
                    // Only include base58prefix for Substrate networks (sr25519/ed25519/ecdsa)
                    // Zcash and Penumbra don't use SS58 addresses
                    let prefix: Option<u16> = match details.encryption {
                        definitions::crypto::Encryption::Sr25519
                        | definitions::crypto::Encryption::Ed25519
                        | definitions::crypto::Encryption::Ecdsa => Some(specs.specs.base58prefix),
                        _ => None,
                    };
                    (
                        Some(hex::encode(specs.specs.genesis_hash)),
                        Some(specs.specs.name),
                        prefix,
                    )
                } else {
                    (None, None, None)
                }
            } else {
                (None, None, None)
            };

            json!({
                "path": details.path,
                "genesis_hash": genesis_hash,
                "network": network_name,
                "encryption": details.encryption.show(),
                "has_pwd": details.has_pwd,
                "base58prefix": base58prefix,
            })
        })
        .collect();

    let backup = json!({
        "v": 2,
        "name": seed_name,
        "accounts": accounts,
    });

    serde_json::to_string(&backup)
        .map_err(|e| ErrorDisplayed::from(format!("JSON serialization error: {e}")))
}

/// Export seed backup as UR-encoded multipart QR frames for device-to-device migration
/// Returns QR image data ready for display as animated QR
/// max_fragment_len: max bytes per QR frame (0 = single QR, 200-500 typical for animated)
fn export_backup_qr(
    seed_name: &str,
    seed_phrase: &str,
    max_fragment_len: u32,
) -> Result<Vec<QrData>, ErrorDisplayed> {
    let ur_strings = export_backup_ur(seed_name, seed_phrase, max_fragment_len)?;

    // Convert each UR string to a QR image
    let qr_images: Result<Vec<QrData>, ErrorDisplayed> = ur_strings
        .into_iter()
        .map(|ur_string| {
            qrcode_static::png_qr_from_string(&ur_string, qrcode_static::DataType::Sensitive)
                .map(|data| QrData::Sensitive { data })
                .map_err(|e| ErrorDisplayed::Str {
                    s: format!("QR encoding error: {e}"),
                })
        })
        .collect();

    qr_images
}

/// Export seed backup as UR-encoded multipart string frames for device-to-device migration
/// Uses fountain codes for reliable animated QR scanning
/// max_fragment_len: max bytes per QR frame (0 = single QR, 200-500 typical for animated)
fn export_backup_ur(
    seed_name: &str,
    seed_phrase: &str,
    max_fragment_len: u32,
) -> Result<Vec<String>, ErrorDisplayed> {
    // Get JSON backup data
    let json_data = bs_export_backup_data(seed_name, seed_phrase)?;
    let data_bytes = json_data.as_bytes();

    // Wrap in simple CBOR byte string
    let mut cbor_data = Vec::with_capacity(data_bytes.len() + 10);
    let len = data_bytes.len();
    if len <= 23 {
        cbor_data.push(0x40 | len as u8);
    } else if len <= 255 {
        cbor_data.push(0x58);
        cbor_data.push(len as u8);
    } else if len <= 65535 {
        cbor_data.push(0x59);
        cbor_data.push((len >> 8) as u8);
        cbor_data.push(len as u8);
    } else {
        cbor_data.push(0x5a);
        cbor_data.push((len >> 24) as u8);
        cbor_data.push((len >> 16) as u8);
        cbor_data.push((len >> 8) as u8);
        cbor_data.push(len as u8);
    }
    cbor_data.extend_from_slice(data_bytes);

    if max_fragment_len == 0 || cbor_data.len() <= max_fragment_len as usize {
        // Single part UR
        let ur_string = ur::ur::encode(&cbor_data, &ur::Type::Custom("zigner-backup"));
        Ok(vec![ur_string])
    } else {
        // Multi-part (animated) UR using fountain codes
        let mut encoder =
            ur::ur::Encoder::new(&cbor_data, max_fragment_len as usize, "zigner-backup").map_err(
                |e| ErrorDisplayed::Str {
                    s: format!("Failed to create UR encoder: {:?}", e),
                },
            )?;

        // Generate enough frames for reliable scanning (2x the minimum)
        let frame_count = encoder.fragment_count() * 2;
        let mut frames = Vec::with_capacity(frame_count);
        for _ in 0..frame_count {
            let part = encoder.next_part().map_err(|e| ErrorDisplayed::Str {
                s: format!("Failed to encode UR part: {:?}", e),
            })?;
            frames.push(part);
        }
        Ok(frames)
    }
}

/// Decode UR-encoded backup and return JSON string
fn decode_backup_ur(ur_parts: Vec<String>) -> Result<String, ErrorDisplayed> {
    if ur_parts.is_empty() {
        return Err(ErrorDisplayed::Str {
            s: "No UR parts provided".to_string(),
        });
    }

    // Try single-part decode first
    if ur_parts.len() == 1 {
        let (_, cbor_data) = ur::ur::decode(&ur_parts[0]).map_err(|e| ErrorDisplayed::Str {
            s: format!("UR decode error: {:?}", e),
        })?;
        return extract_backup_json_from_cbor(&cbor_data);
    }

    // Multi-part decode using fountain codes
    let mut decoder = ur::ur::Decoder::default();

    for part in &ur_parts {
        decoder.receive(part).map_err(|e| ErrorDisplayed::Str {
            s: format!("UR receive error: {:?}", e),
        })?;

        if decoder.complete() {
            match decoder.message() {
                Ok(Some(cbor_data)) => return extract_backup_json_from_cbor(&cbor_data),
                Ok(None) => {
                    return Err(ErrorDisplayed::Str {
                        s: "UR decoder complete but no message".to_string(),
                    })
                }
                Err(e) => {
                    return Err(ErrorDisplayed::Str {
                        s: format!("UR message error: {:?}", e),
                    })
                }
            }
        }
    }

    Err(ErrorDisplayed::Str {
        s: "Incomplete UR data - need more frames".to_string(),
    })
}

fn extract_backup_json_from_cbor(cbor_data: &[u8]) -> Result<String, ErrorDisplayed> {
    // Parse CBOR bytes using existing helper
    let (json_bytes, _) = parse_cbor_bytes(cbor_data).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to parse CBOR: {}", e),
    })?;

    String::from_utf8(json_bytes).map_err(|e| ErrorDisplayed::Str {
        s: format!("Invalid UTF-8 in backup: {e}"),
    })
}

fn sign_metadata_with_key(
    network_key: &str,
    metadata_specs_version: &str,
    signing_address_key: &str,
    seed_phrase: &str,
    password: Option<String>,
) -> Result<MSufficientCryptoReady, ErrorDisplayed> {
    let network_key = NetworkSpecsKey::from_hex(network_key).map_err(|e| format!("{e}"))?;
    let version = metadata_specs_version
        .parse::<u32>()
        .map_err(|e| format!("{e}"))?;
    let address_key = AddressKey::from_hex(signing_address_key).map_err(|e| format!("{e}"))?;
    navigator::sign_sufficient_content(
        &get_db()?,
        &address_key,
        SufficientContent::LoadMeta(network_key, version),
        seed_phrase,
        &password.unwrap_or("".to_owned()),
    )
    .map_err(|e| e.into())
}

fn sign_network_spec_with_key(
    network_key: &str,
    signing_address_key: &str,
    seed_phrase: &str,
    password: Option<String>,
) -> Result<MSufficientCryptoReady, ErrorDisplayed> {
    let network_key = NetworkSpecsKey::from_hex(network_key).map_err(|e| format!("{e}"))?;
    let address_key = AddressKey::from_hex(signing_address_key).map_err(|e| format!("{e}"))?;
    navigator::sign_sufficient_content(
        &get_db()?,
        &address_key,
        SufficientContent::AddSpecs(network_key),
        seed_phrase,
        &password.unwrap_or("".to_owned()),
    )
    .map_err(|e| e.into())
}

/// Export Penumbra Full Viewing Key for import into watch-only wallet (e.g., Prax)
///
/// Returns both:
/// - FVK bech32m string (native Penumbra format) for direct import
/// - UR-encoded string (penumbra-accounts format) for hardware wallet QR compatibility
///
/// ## UR (Uniform Resource) Format for Penumbra
///
/// We define these UR types (proposed by Zigner, following Blockchain Commons pattern):
///
/// | UR Type                    | CBOR Tag | Description                    |
/// |----------------------------|----------|--------------------------------|
/// | penumbra-accounts          | 49301    | Container for accounts         |
/// | penumbra-full-viewing-key  | 49302    | Single FVK with metadata       |
///
/// These tags are in the "first-come-first-served" range (32768+), no registration needed.
/// Adjacent to Keystone's Zcash tags (49201-49204) for consistency.
///
/// ## CBOR Structure for penumbra-accounts
///
/// ```text
/// PenumbraAccounts = {
///   1: bytes,                ; wallet_id (32 bytes)
///   2: [+ #49302(FVK)]       ; accounts array
/// }
///
/// PenumbraFullViewingKey (#49302) = {
///   1: tstr,                 ; fvk - bech32m encoded ("penumbrafullviewingkey1...")
///   2: uint,                 ; index - account index
///   ? 3: tstr                ; name - optional label
/// }
/// ```
#[allow(clippy::vec_init_then_push)]
fn export_penumbra_fvk(
    seed_phrase: &str,
    account_index: u32,
    label: &str,
) -> Result<PenumbraFvkExport, ErrorDisplayed> {
    use transaction_signing::penumbra::{FullViewingKey, FvkExportData, SpendKeyBytes};

    // Derive spend key from seed phrase
    let spend_key_bytes =
        SpendKeyBytes::from_seed_phrase(seed_phrase, account_index).map_err(|e| {
            ErrorDisplayed::Str {
                s: format!("Failed to derive spend key: {e}"),
            }
        })?;

    // Create FVK export data
    let label_opt = if label.is_empty() {
        None
    } else {
        Some(label.to_string())
    };
    let mut export_data =
        FvkExportData::from_spend_key(&spend_key_bytes, account_index, label_opt.clone()).map_err(
            |e| ErrorDisplayed::Str {
                s: format!("Failed to create FVK export: {e}"),
            },
        )?;

    // embed ZID pubkey so zafu can link this wallet to the same zigner device
    // across network imports. best-effort — if ZID derivation fails, the FVK
    // export still succeeds, just without the canonical device identity.
    export_data.zid_pubkey = auth::derive_zid_pubkey(seed_phrase)
        .ok()
        .and_then(|hex_str| hex::decode(&hex_str).ok())
        .and_then(|bytes| bytes.try_into().ok());

    // Get bech32m encoded strings
    let fvk = FullViewingKey::derive_from(&spend_key_bytes).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to derive FVK: {e}"),
    })?;
    let wallet_id = fvk.wallet_id().map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to compute wallet ID: {e}"),
    })?;

    let fvk_bech32m = fvk.to_bech32m().map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to encode FVK: {e}"),
    })?;
    let wallet_id_bech32m = wallet_id.to_bech32m().map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to encode wallet ID: {e}"),
    })?;

    // ========================================================================
    // Build UR-encoded "penumbra-accounts" for hardware wallet QR compatibility
    //
    // CBOR tag 49301 = penumbra-accounts
    // CBOR tag 49302 = penumbra-full-viewing-key
    // Tag 49301 in hex = 0xC095, encoded as: 0xd9 0xc0 0x95
    // Tag 49302 in hex = 0xC096, encoded as: 0xd9 0xc0 0x96
    // ========================================================================
    // Pre-compute ZID for the inner CBOR map (best-effort, optional).
    let ur_zid: Option<[u8; 32]> = export_data.zid_pubkey;

    let ur_string = {
        let mut cbor_data = Vec::new();

        // PenumbraAccounts: map with 2 entries
        // CBOR: 0xa2 = map(2)
        cbor_data.push(0xa2);

        // Key 1: wallet_id (32 bytes)
        // CBOR: 0x01 = uint(1), 0x58 0x20 = bytes(32)
        cbor_data.push(0x01);
        cbor_data.push(0x58); // bytes with 1-byte length
        cbor_data.push(0x20); // 32 bytes
        cbor_data.extend_from_slice(&wallet_id.to_bytes());

        // Key 2: accounts array
        // CBOR: 0x02 = uint(2), 0x81 = array(1)
        cbor_data.push(0x02);
        cbor_data.push(0x81); // array(1) - single account

        // Tagged PenumbraFullViewingKey (tag 49302 = 0xC096)
        // Tag encoding: 0xd9 means "tag with 2-byte value follows"
        // 49302 = 0xC096 -> high byte 0xC0, low byte 0x96
        cbor_data.push(0xd9);
        cbor_data.push(0xc0); // high byte: 49302 >> 8 = 192 = 0xc0
        cbor_data.push(0x96); // low byte: 49302 & 0xff = 150 = 0x96

        // PenumbraFullViewingKey: map with 2..4 entries
        // (key 1 fvk, key 2 index, key 3 name optional, key 4 zid optional)
        let has_name = !label.is_empty();
        let has_zid = ur_zid.is_some();
        let map_entries: u8 = 2 + (has_name as u8) + (has_zid as u8);
        cbor_data.push(0xa0 + map_entries);

        // Key 1: fvk (text string - bech32m encoded)
        cbor_data.push(0x01); // key = 1
        let fvk_bytes = fvk_bech32m.as_bytes();
        if fvk_bytes.len() < 24 {
            cbor_data.push(0x60 + fvk_bytes.len() as u8);
        } else if fvk_bytes.len() < 256 {
            cbor_data.push(0x78); // text with 1-byte length
            cbor_data.push(fvk_bytes.len() as u8);
        } else {
            cbor_data.push(0x79); // text with 2-byte length (big-endian)
            cbor_data.extend_from_slice(&(fvk_bytes.len() as u16).to_be_bytes());
        }
        cbor_data.extend_from_slice(fvk_bytes);

        // Key 2: index (unsigned int)
        cbor_data.push(0x02); // key = 2
        if account_index < 24 {
            cbor_data.push(account_index as u8);
        } else if account_index < 256 {
            cbor_data.push(0x18);
            cbor_data.push(account_index as u8);
        } else {
            cbor_data.push(0x19);
            cbor_data.extend_from_slice(&(account_index as u16).to_be_bytes());
        }

        // Key 3: name (optional text string)
        if has_name {
            cbor_data.push(0x03); // key = 3
            let label_bytes = label.as_bytes();
            if label_bytes.len() < 24 {
                cbor_data.push(0x60 + label_bytes.len() as u8);
            } else {
                cbor_data.push(0x78);
                cbor_data.push(label_bytes.len() as u8);
            }
            cbor_data.extend_from_slice(label_bytes);
        }

        // Key 4: zid_pubkey (optional 32-byte string) — canonical device identity
        // for zafu dedup across network imports. Unknown to legacy parsers.
        if let Some(zid) = ur_zid {
            cbor_data.push(0x04); // key = 4
            cbor_data.push(0x58); // bytes with 1-byte length
            cbor_data.push(0x20); // 32 bytes
            cbor_data.extend_from_slice(&zid);
        }

        // Encode CBOR as UR string
        // Result format: "ur:penumbra-accounts/..."
        ur::ur::encode(&cbor_data, &ur::Type::Custom("penumbra-accounts"))
    };

    Ok(PenumbraFvkExport {
        account_index,
        label: label_opt.unwrap_or_default(),
        fvk_bech32m,
        wallet_id_bech32m,
        qr_data: export_data.encode_qr(),
        ur_string,
    })
}

// ============================================================================
// Cosmos account export
// ============================================================================

/// Export Cosmos chain addresses from a seed phrase.
///
/// Derives a secp256k1 key using BIP44 path m/44'/118'/account'/0/0
/// and generates bech32 addresses for the specified chain (or all if network_name is empty).
/// The QR data encodes a simple JSON payload for Zafu to import.
/// Chains the Cosmos account export QR carries an address for (chain id, bech32 prefix).
fn cosmos_export_chains() -> Vec<(&'static str, &'static str)> {
    use db_handling::cosmos::{PREFIX_CELESTIA, PREFIX_NOBLE, PREFIX_OSMOSIS, PREFIX_TERP};
    vec![
        ("osmosis", PREFIX_OSMOSIS),
        ("noble", PREFIX_NOBLE),
        ("celestia", PREFIX_CELESTIA),
        ("terp", PREFIX_TERP),
    ]
}

fn export_cosmos_accounts(
    seed_phrase: &str,
    account_index: u32,
    label: &str,
    network_name: &str,
) -> Result<CosmosAccountExport, ErrorDisplayed> {
    use db_handling::cosmos::{
        cosmos_chain_info, derive_cosmos_account_xpub, derive_cosmos_key, SLIP0044_COSMOS,
    };

    // The four legacy chains (and the empty "all" filter) keep the exact
    // pre-multichain output. Any other known chain exports just that chain,
    // with its own prefix and coin type.
    let name = network_name.to_lowercase();
    let legacy_chains: Vec<(&str, &str)> = cosmos_export_chains();
    let extra: Option<(&'static str, &'static str, u32)> =
        if name.is_empty() || legacy_chains.iter().any(|(id, _)| *id == name) {
            None
        } else {
            cosmos_chain_info(&name).map(|c| (c.name, c.prefix, c.coin_type))
        };
    let coin_type = extra.map(|(_, _, c)| c).unwrap_or(SLIP0044_COSMOS);

    let key = derive_cosmos_key(seed_phrase, coin_type, account_index, 0).map_err(|e| {
        ErrorDisplayed::Str {
            s: format!("Failed to derive Cosmos key: {e}"),
        }
    })?;

    let pubkey_hex = hex::encode(&key.public_key);

    // Change-level xpub (m/44'/118'/account'/0). Lets the hot wallet derive
    // burner receive addresses at .../0/i locally, watch-only, each still
    // signable on this device at the matching address_index and recoverable
    // from the seed. This is the rotation backbone - see the note in
    // db_handling::cosmos::derive_cosmos_account_xpub.
    let xpub = derive_cosmos_account_xpub(seed_phrase, coin_type, account_index).map_err(|e| {
        ErrorDisplayed::Str {
            s: format!("Failed to derive Cosmos xpub: {e}"),
        }
    })?;

    // Generate addresses for supported chains (filter by network_name if specified)
    let chains: Vec<(&str, &str)> = if let Some((id, prefix, _)) = extra {
        vec![(id, prefix)]
    } else if network_name.is_empty() {
        legacy_chains
    } else {
        legacy_chains
            .into_iter()
            .filter(|(id, _)| *id == name)
            .collect()
    };

    let mut addresses = Vec::new();
    for (chain_id, prefix) in &chains {
        let addr = key
            .bech32_address(prefix)
            .map_err(|e| ErrorDisplayed::Str {
                s: format!("Failed to encode {chain_id} address: {e}"),
            })?;
        addresses.push(CosmosChainAddress {
            chain_id: chain_id.to_string(),
            address: addr,
            prefix: prefix.to_string(),
        });
    }

    // Build QR data as JSON for Zafu import
    let label_str = if label.is_empty() { "Zigner" } else { label };
    let json = serde_json::json!({
        "type": "cosmos-accounts",
        "version": 1,
        "label": label_str,
        "account_index": account_index,
        "public_key": pubkey_hex,
        "xpub": xpub,
        "addresses": addresses.iter().map(|a| {
            serde_json::json!({
                "chain_id": a.chain_id,
                "address": a.address,
                "prefix": a.prefix,
            })
        }).collect::<Vec<_>>(),
    });

    let json_bytes = json.to_string().into_bytes();
    let qr_data = encode_to_qr(&json_bytes, false).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to generate QR: {e}"),
    })?;

    Ok(CosmosAccountExport {
        account_index,
        label: label_str.to_string(),
        public_key_hex: pubkey_hex,
        xpub,
        addresses,
        qr_data,
    })
}

// ============================================================================
// Cosmos cold signing functions
// ============================================================================

/// Parse a Cosmos sign request from QR hex data (amino JSON sign doc)
fn parse_cosmos_sign_request(qr_hex: &str) -> Result<CosmosSignRequest, ErrorDisplayed> {
    use transaction_signing::cosmos::{CosmosSignDocDisplay, CosmosSignRequest as InternalRequest};

    let req = InternalRequest::from_qr_hex(qr_hex).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to parse Cosmos QR: {e}"),
    })?;

    let display = CosmosSignDocDisplay::from_sign_bytes(&req.sign_doc_bytes).map_err(|e| {
        ErrorDisplayed::Str {
            s: format!("Failed to parse sign doc: {e}"),
        }
    })?;

    let msgs = display
        .msgs
        .into_iter()
        .map(|m| CosmosMsgDisplay {
            msg_type: m.msg_type,
            recipient: m.recipient,
            amount: m.amount,
            detail: m.detail,
            blind: m.blind,
        })
        .collect();

    Ok(CosmosSignRequest {
        account_index: req.account_index,
        address_index: req.address_index,
        chain_name: req.chain_name,
        chain_id: display.chain_id,
        msgs,
        fee: display.fee,
        memo: display.memo,
        raw_qr_hex: qr_hex.to_string(),
    })
}

/// Sign a Cosmos transaction and return 64-byte compact signature.
///
/// IMPORTANT: re-derives display fields from raw_qr_hex and verifies they
/// match what the user approved. This prevents a compromised hot wallet from
/// displaying one transaction but signing another.
fn sign_cosmos_transaction(
    seed_phrase: &str,
    request: CosmosSignRequest,
) -> Result<Vec<u8>, ErrorDisplayed> {
    use transaction_signing::cosmos::{
        extract_signers, granter_binding, sign_cosmos_amino, CosmosSignDocDisplay,
        CosmosSignRequest as InternalRequest,
    };

    // re-parse the QR to get the sign doc bytes
    let req =
        InternalRequest::from_qr_hex(&request.raw_qr_hex).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to re-parse QR: {e}"),
        })?;

    if let Some(binding) =
        granter_binding(&req.sign_doc_bytes).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to parse sign doc: {e}"),
        })?
    {
        let shown = request.msgs.first();
        let card_ok = request.msgs.len() == 1
            && request.chain_id.is_empty()
            && request.fee == "none"
            && request.memo.is_empty()
            && shown.is_some_and(|msg| {
                msg.msg_type == "Granter"
                    && msg.recipient == binding
                    && msg.detail == format!("This key becomes the granter for {binding}.")
            });
        if !card_ok {
            return Err(ErrorDisplayed::Str {
                s: "Granter card does not match the QR".to_string(),
            });
        }
        let key = cosmos_key_for_request(
            seed_phrase,
            &request.raw_qr_hex,
            &request.chain_name,
            req.account_index,
            req.address_index,
            &[],
        )?;
        let signature = sign_cosmos_amino(&key.secret_key, &req.sign_doc_bytes).map_err(|e| {
            ErrorDisplayed::Str {
                s: format!("Signing failed: {e}"),
            }
        })?;
        return Ok(signature.to_vec());
    }

    // re-derive display fields from the raw QR and verify they match
    // what the user was shown. this is the cosmos equivalent of
    // penumbra's verify_effect_hash — it binds display to signing.
    let display =
        CosmosSignDocDisplay::from_json(&req.sign_doc_bytes).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to re-parse sign doc: {e}"),
        })?;

    if display.chain_id != request.chain_id {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "Chain ID mismatch: display showed '{}' but QR contains '{}'",
                request.chain_id, display.chain_id
            ),
        });
    }
    if display.fee != request.fee {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "Fee mismatch: display showed '{}' but QR contains '{}'",
                request.fee, display.fee
            ),
        });
    }
    if display.memo != request.memo {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "Memo mismatch: display showed '{}' but QR contains '{}'",
                request.memo, display.memo
            ),
        });
    }
    if display.msgs.len() != request.msgs.len() {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "Message count mismatch: display showed {} but QR contains {}",
                request.msgs.len(),
                display.msgs.len()
            ),
        });
    }

    for (i, (shown, parsed)) in request.msgs.iter().zip(display.msgs.iter()).enumerate() {
        if shown.msg_type != parsed.msg_type
            || shown.recipient != parsed.recipient
            || shown.amount != parsed.amount
            || shown.detail != parsed.detail
            || shown.blind != parsed.blind
        {
            return Err(ErrorDisplayed::Str {
                s: format!("Message {} does not match the QR", i + 1),
            });
        }
    }

    // The QR account index is only a hint. A device with several Terp key
    // sets must sign with the derivation whose address is the message
    // signer, which is the address the preview shows.
    let signers = extract_signers(&req.sign_doc_bytes).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to read message signers: {e}"),
    })?;
    let key = cosmos_key_for_request(
        seed_phrase,
        &request.raw_qr_hex,
        &request.chain_name,
        req.account_index,
        req.address_index,
        &signers,
    )?;

    // sign with SHA256 prehash (NOT blake2b)
    let signature = sign_cosmos_amino(&key.secret_key, &req.sign_doc_bytes).map_err(|e| {
        ErrorDisplayed::Str {
            s: format!("Signing failed: {e}"),
        }
    })?;

    Ok(signature.to_vec())
}

/// Bech32 prefix and candidate coin types for a request.
///
/// Prefix: explicit QR extension, else the common bech32 HRP of the sign
/// doc's signer addresses, else the chain-name table (unknown -> `cosmos`).
/// Coin types: explicit QR extension only; otherwise the table value for
/// `chain_name` (unknown -> 118), followed by 118 when that differs, so every
/// legacy payload that signed before still finds the same 118 key.
fn cosmos_resolve_chain(
    raw_qr_hex: &str,
    chain_name: &str,
    signers: &[String],
) -> Result<(String, Vec<u32>), ErrorDisplayed> {
    use db_handling::cosmos::{
        bech32_prefix_of, coin_type_for_network, is_valid_bech32_prefix, prefix_for_network,
        SLIP0044_COSMOS,
    };
    use transaction_signing::cosmos::CosmosQrExtension;

    let ext = CosmosQrExtension::from_qr_hex(raw_qr_hex)
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to re-parse QR: {e}"),
        })?
        .unwrap_or_default();

    let signer_prefix = {
        let hrps: Vec<Option<String>> = signers.iter().map(|a| bech32_prefix_of(a)).collect();
        match hrps.first() {
            Some(Some(first)) if hrps.iter().all(|h| h.as_deref() == Some(first.as_str())) => {
                Some(first.clone())
            }
            _ => None,
        }
    };
    let prefix = ext
        .bech32_prefix
        .clone()
        .or(signer_prefix)
        .filter(|p| is_valid_bech32_prefix(p))
        .unwrap_or_else(|| prefix_for_network(chain_name).to_string());

    let coin_types = match ext.coin_type {
        Some(c) => vec![c],
        None => {
            let c = coin_type_for_network(chain_name);
            if c == SLIP0044_COSMOS {
                vec![c]
            } else {
                vec![c, SLIP0044_COSMOS]
            }
        }
    };
    Ok((prefix, coin_types))
}

fn cosmos_key_for_request(
    seed_phrase: &str,
    raw_qr_hex: &str,
    chain_name: &str,
    hint_account: u32,
    hint_address: u32,
    signers: &[String],
) -> Result<db_handling::cosmos::CosmosKeyPair, ErrorDisplayed> {
    let (prefix, coin_types) = cosmos_resolve_chain(raw_qr_hex, chain_name, signers)?;
    let mut first_err = None;
    for coin_type in coin_types {
        match cosmos_key_for_signers(
            seed_phrase,
            &prefix,
            coin_type,
            hint_account,
            hint_address,
            signers,
        ) {
            Ok(key) => return Ok(key),
            Err(e) => {
                if first_err.is_none() {
                    first_err = Some(e);
                }
            }
        }
    }
    Err(first_err.unwrap_or(ErrorDisplayed::Str {
        s: "no coin type to derive with".to_string(),
    }))
}

/// Derivation on `seed_phrase` whose bech32 address equals every message signer.
///
/// The QR indices are tried first. A key set often stores the Terp address at
/// account 0 while the QR still says account 0 of a different set, or the
/// address lives at another account index. The signature stays bound to the
/// signer shown in the preview.
fn cosmos_key_for_signers(
    seed_phrase: &str,
    prefix: &str,
    coin_type: u32,
    hint_account: u32,
    hint_address: u32,
    signers: &[String],
) -> Result<db_handling::cosmos::CosmosKeyPair, ErrorDisplayed> {
    use db_handling::cosmos::{derive_cosmos_key, pubkey_to_bech32_with_prefix};

    let mut candidates = vec![(hint_account, hint_address)];
    for account in 0..16 {
        for address in 0..4 {
            if (account, address) != (hint_account, hint_address) {
                candidates.push((account, address));
            }
        }
    }
    let mut hinted = None;
    for (account, address) in candidates {
        let key = derive_cosmos_key(seed_phrase, coin_type, account, address).map_err(|e| {
            ErrorDisplayed::Str {
                s: format!("Key derivation failed: {e}"),
            }
        })?;
        let device_addr = pubkey_to_bech32_with_prefix(&key.public_key, prefix).map_err(|e| {
            ErrorDisplayed::Str {
                s: format!("Address derivation failed: {e}"),
            }
        })?;
        if hinted.is_none() {
            hinted = Some(device_addr.clone());
        }
        if signers.is_empty() || signers.iter().all(|signer| signer == &device_addr) {
            return Ok(key);
        }
    }
    let device_addr = hinted.unwrap_or_default();
    let signer = signers.first().map(String::as_str).unwrap_or("");
    Err(ErrorDisplayed::Str {
        s: format!(
            "Source mismatch: this key is {device_addr}, but the transaction spends from {signer}"
        ),
    })
}

/// Compressed secp256k1 public key, 66 lowercase hex characters.
/// Same derivation `sign_cosmos_transaction` uses for coin type 118.
fn cosmos_public_key(
    seed_phrase: &str,
    account_index: u32,
    address_index: u32,
) -> Result<String, ErrorDisplayed> {
    use db_handling::cosmos::{derive_cosmos_key, SLIP0044_COSMOS};

    let key = derive_cosmos_key(seed_phrase, SLIP0044_COSMOS, account_index, address_index)
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("Key derivation failed: {e}"),
        })?;
    Ok(hex::encode(key.public_key))
}

/// Public key of the derivation that matches this transaction's signer.
fn cosmos_public_key_for_signers(
    seed_phrase: &str,
    request: CosmosSignRequest,
) -> Result<String, ErrorDisplayed> {
    use transaction_signing::cosmos::{
        extract_signers, granter_binding, CosmosSignRequest as InternalRequest,
    };

    let req =
        InternalRequest::from_qr_hex(&request.raw_qr_hex).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to re-parse QR: {e}"),
        })?;
    let signers = if granter_binding(&req.sign_doc_bytes)
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to parse sign doc: {e}"),
        })?
        .is_some()
    {
        Vec::new()
    } else {
        extract_signers(&req.sign_doc_bytes).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to read message signers: {e}"),
        })?
    };
    let key = cosmos_key_for_request(
        seed_phrase,
        &request.raw_qr_hex,
        &request.chain_name,
        req.account_index,
        req.address_index,
        &signers,
    )?;
    Ok(hex::encode(key.public_key))
}

/// `ur:cworch-sig` frames carrying the 33-byte pubkey and the 64-byte signature.
/// One frame when `max_fragment_len` is 0 and the text fits a camera. A positive
/// length forces fountain frames of that size, which is also the fallback when
/// one code would be too dense.
fn encode_cworch_signature(
    pubkey_hex: &str,
    signature: Vec<u8>,
    max_fragment_len: u32,
) -> Result<Vec<String>, ErrorDisplayed> {
    let pubkey = hex::decode(pubkey_hex.trim()).map_err(|e| ErrorDisplayed::Str {
        s: format!("public key hex: {e}"),
    })?;
    if pubkey.len() != 33 || signature.len() != 64 {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "cw-orch signature needs 33 pubkey bytes and 64 signature bytes, got {} and {}",
                pubkey.len(),
                signature.len()
            ),
        });
    }
    let mut payload = Vec::with_capacity(97);
    payload.extend_from_slice(&pubkey);
    payload.extend_from_slice(&signature);
    const ONE_CODE_LIMIT: usize = 1200;
    let single = ur::ur::encode(&payload, &ur::Type::Custom("cworch-sig"));
    if max_fragment_len == 0 && single.len() <= ONE_CODE_LIMIT {
        return Ok(vec![single]);
    }
    let fragment = if max_fragment_len == 0 {
        200
    } else {
        max_fragment_len as usize
    };
    let mut encoder = ur::ur::Encoder::new(&payload, fragment, "cworch-sig").map_err(|e| {
        ErrorDisplayed::Str {
            s: format!("Failed to create UR encoder: {e:?}"),
        }
    })?;
    let total_parts = (encoder.fragment_count() * 13).div_ceil(10).max(1);
    let mut parts = Vec::with_capacity(total_parts);
    for _ in 0..total_parts {
        parts.push(encoder.next_part().map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to encode UR part: {e:?}"),
        })?);
    }
    Ok(parts)
}

// ============================================================================
// Penumbra cold signing functions
// ============================================================================

/// Parse a Penumbra sign request from QR hex data
fn parse_penumbra_sign_request(qr_hex: &str) -> Result<PenumbraSignRequest, ErrorDisplayed> {
    use transaction_parsing::penumbra::parse_penumbra_transaction;

    let plan = parse_penumbra_transaction(qr_hex).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to parse Penumbra QR: {e}"),
    })?;

    let effect_hash_hex = plan.effect_hash.map(hex::encode).unwrap_or_default();

    Ok(PenumbraSignRequest {
        chain_id: plan.chain_id.unwrap_or_default(),
        effect_hash_hex,
        spend_count: plan.spend_randomizers.len() as u32,
        vote_count: plan.delegator_vote_randomizers.len() as u32,
        lqt_vote_count: plan.lqt_vote_randomizers.len() as u32,
        raw_qr_hex: qr_hex.to_string(),
    })
}

/// Sign a Penumbra transaction and return encoded signature QR bytes
fn sign_penumbra_transaction(
    seed_phrase: &str,
    request: PenumbraSignRequest,
) -> Result<Vec<u8>, ErrorDisplayed> {
    use transaction_parsing::penumbra::{
        parse_penumbra_transaction, sign_transaction, SpendKeyBytes,
    };
    use transaction_signing::penumbra::verify_effect_hash;

    // Re-parse to get the full plan data
    let plan =
        parse_penumbra_transaction(&request.raw_qr_hex).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to parse Penumbra QR: {e}"),
        })?;

    let effect_hash = plan.effect_hash.ok_or_else(|| ErrorDisplayed::Str {
        s: "No effect hash in QR".to_string(),
    })?;

    // Derive spend key from seed phrase (account 0)
    let spend_key =
        SpendKeyBytes::from_seed_phrase(seed_phrase, 0).map_err(|e| ErrorDisplayed::Str {
            s: format!("Key derivation failed: {e}"),
        })?;

    // SECURITY: Verify the effect hash from the QR matches what we compute
    // from the plan + our FVK. This prevents a compromised hot wallet from
    // tricking us into signing a different transaction.
    verify_effect_hash(&plan.plan_bytes, &effect_hash, &spend_key).map_err(|e| {
        ErrorDisplayed::Str {
            s: format!("Effect hash verification failed: {e}"),
        }
    })?;

    // Sign the transaction
    let auth_data = sign_transaction(
        effect_hash,
        &plan.spend_randomizers,
        &plan.delegator_vote_randomizers,
        &plan.lqt_vote_randomizers,
        &spend_key,
    )
    .map_err(|e| ErrorDisplayed::Str {
        s: format!("Signing failed: {e}"),
    })?;

    // Encode as QR response bytes
    auth_data.encode().map_err(|e| ErrorDisplayed::Str {
        s: format!("Encode failed: {e}"),
    })
}

// ============================================================================
// Zcash cold signing functions
// ============================================================================

/// Export Zcash Orchard full viewing key for import into watch-only wallet (e.g., Zafu/Prax)
///
/// Returns both:
/// - UFVK string (unified full viewing key) for direct import
/// - UR-encoded string (zcash-accounts format) for Zashi/Keystone QR compatibility
///
/// ## UR (Uniform Resource) Protocol
///
/// UR is a protocol by Blockchain Commons for encoding binary data in QR codes.
/// Format: `ur:<type>/<bytewords-encoded-cbor>`
///
/// Reference: https://github.com/BlockchainCommons/Research/blob/master/papers/bcr-2020-005-ur.md
///
/// ## Keystone SDK Registry Types for Zcash
///
/// The Keystone SDK defines these UR types for Zcash (see keystone-sdk-rust):
///
/// | Type Name                       | CBOR Tag | Description                    |
/// |---------------------------------|----------|--------------------------------|
/// | zcash-accounts                  | 49201    | Collection of accounts/UFVKs   |
/// | zcash-full-viewing-key          | 49202    | Single FVK (deprecated)        |
/// | zcash-unified-full-viewing-key  | 49203    | UFVK with metadata             |
/// | zcash-pczt                      | 49204    | Partially Created Zcash Tx     |
///
/// ## CBOR Structure for zcash-accounts
///
/// ```text
/// ZcashAccounts = {
///   1: bytes,              ; seed_fingerprint (32-byte ZIP-32 SeedFingerprint)
///   2: [+ #49203(UFVK)]    ; accounts array, each tagged with 49203
/// }
///
/// ZcashUnifiedFullViewingKey (#49203) = {
///   1: tstr,               ; ufvk - the unified full viewing key string
///   2: uint,               ; index - account index (0, 1, 2, ...)
///   ? 3: tstr              ; name - optional account label
/// }
/// ```
///
/// ## CBOR Encoding Reference
///
/// CBOR major types (high 3 bits of first byte):
/// - 0x00-0x17: unsigned int 0-23 (value in low 5 bits)
/// - 0x18: unsigned int, 1 byte follows
/// - 0x19: unsigned int, 2 bytes follow (big-endian)
/// - 0x40-0x57: bytes, length 0-23 in low 5 bits
/// - 0x58: bytes, 1-byte length follows
/// - 0x60-0x77: text string, length 0-23 in low 5 bits
/// - 0x78: text string, 1-byte length follows
/// - 0x79: text string, 2-byte length follows
/// - 0x80-0x97: array, length 0-23 in low 5 bits
/// - 0xa0-0xb7: map, length 0-23 in low 5 bits (pairs count)
/// - 0xd8: tag, 1-byte tag number follows
/// - 0xd9: tag, 2-byte tag number follows (big-endian)
///
/// Parse a Zcash simple sign request from QR hex data.
/// This is the non-PCZT signing flow used by Zafu wallet.
fn parse_zcash_sign_request(qr_hex: &str) -> Result<ZcashSimpleSignRequest, ErrorDisplayed> {
    let request = transaction_signing::ZcashSignRequest::from_qr_hex(qr_hex).map_err(|e| {
        ErrorDisplayed::Str {
            s: format!("Failed to parse Zcash sign request: {e}"),
        }
    })?;

    Ok(ZcashSimpleSignRequest {
        account_index: request.account_index,
        sighash_hex: hex::encode(request.sighash),
        action_count: request.orchard_alphas.len() as u32,
        summary: request.summary,
        mainnet: request.mainnet,
        raw_qr_hex: qr_hex.to_string(),
    })
}

/// Sign a Zcash simple sign request and return the signature QR hex string.
fn sign_zcash_simple(
    seed_phrase: &str,
    request: ZcashSimpleSignRequest,
) -> Result<String, ErrorDisplayed> {
    // Re-parse the original QR to get the full data (including alphas)
    let sign_data = transaction_signing::ZcashSignRequest::from_qr_hex(&request.raw_qr_hex)
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to parse Zcash sign request for signing: {e}"),
        })?;

    let response = sign_data
        .sign(seed_phrase)
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("Zcash signing failed: {e}"),
        })?;

    Ok(response.to_qr_hex())
}

// hand-rolls ZcashAccounts CBOR with per-byte annotated pushes (clearer than a
// dense vec![...] literal); silence the resulting vec-init-then-push lint.
#[allow(clippy::vec_init_then_push)]
fn export_zcash_fvk(
    seed_phrase: &str,
    account_index: u32,
    label: &str,
    mainnet: bool,
) -> Result<ZcashFvkExport, ErrorDisplayed> {
    use transaction_signing::zcash::{OrchardSpendingKey, QR_TYPE_ZCASH_FVK_EXPORT};

    // Derive Orchard spending key from seed phrase using ZIP-32 derivation
    let osk = OrchardSpendingKey::from_seed_phrase(seed_phrase, account_index).map_err(|e| {
        ErrorDisplayed::Str {
            s: format!("Failed to derive Orchard key: {e}"),
        }
    })?;

    // Get FVK bytes (96 bytes raw orchard full viewing key)
    let fvk_bytes = osk.fvk_bytes();

    // Get receiving address (unified address with orchard receiver)
    let address = osk.get_address(mainnet);

    // Get UFVK string with orchard + transparent components (ZIP-316)
    // Format: "uview1..." for mainnet, "uviewtest1..." for testnet
    let ufvk = OrchardSpendingKey::get_ufvk_with_transparent(seed_phrase, account_index, mainnet)
        .map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to derive UFVK: {e}"),
    })?;

    // ZIP-32 seed fingerprint (32 bytes): BLAKE2b-256 personalized
    // "Zcash_HD_Seed_FP" over the same 64-byte BIP39 seed used for key
    // derivation. Matches Keystone/Zashi/vizor so they recognize this account as
    // belonging to the same seed. (Previously SHA256(mnemonic)[..16] - wrong
    // length AND wrong preimage, which broke import into Keystone-compatible
    // wallets with "seed fingerprint must be 32 bytes".)
    let seed_fingerprint =
        OrchardSpendingKey::seed_fingerprint(seed_phrase).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to derive seed fingerprint: {e}"),
        })?;

    // ========================================================================
    // Build UR-encoded "zcash-accounts" for Zashi/Keystone QR code compatibility
    //
    // The UR string format is: ur:zcash-accounts/<bytewords-encoded-cbor>
    // where the CBOR payload matches the Keystone SDK zcash_accounts.rs structure
    // ========================================================================
    // Try to derive ZID early so we can include it as a CBOR field below.
    // Best-effort — if it fails, we just omit the field (backwards compatible).
    let ur_zid: Option<[u8; 32]> = auth::derive_zid_pubkey(seed_phrase)
        .ok()
        .and_then(|h| hex::decode(&h).ok())
        .and_then(|b| b.try_into().ok());

    let ur_string = {
        // hand-rolled CBOR: the per-byte pushes below are annotated with their
        // CBOR meaning, which reads clearer than a dense vec![...] literal.
        let mut cbor_data = Vec::new();

        // ZcashAccounts: map with 2 entries
        // CBOR: 0xa2 = map(2)
        cbor_data.push(0xa2);

        // Key 1: seed_fingerprint (bytes)
        // CBOR: 0x01 = uint(1), then bytes(32): 0x58 (bytes, 1-byte length) 0x20 (=32)
        cbor_data.push(0x01);
        cbor_data.push(0x58); // bytes with 1-byte length follows
        cbor_data.push(0x20); // length = 32
        cbor_data.extend_from_slice(&seed_fingerprint);

        // Key 2: accounts array
        // CBOR: 0x02 = uint(2), 0x81 = array(1)
        cbor_data.push(0x02);
        cbor_data.push(0x81); // array(1) - single account

        // Each account is tagged with CBOR tag 49203 (ZCASH_UNIFIED_FULL_VIEWING_KEY)
        // Tag 49203 = 0xC033 in hex, encoded as: 0xd9 0xc0 0x33
        // 0xd9 means "tag with 2-byte value follows"
        cbor_data.push(0xd9);
        cbor_data.push(0xc0); // high byte: 49203 >> 8 = 192 = 0xc0
        cbor_data.push(0x33); // low byte: 49203 & 0xff = 51 = 0x33

        // ZcashUnifiedFullViewingKey: map with 2..4 entries
        // (key 1 ufvk, key 2 index, key 3 name optional, key 4 zid optional)
        let has_name = !label.is_empty();
        let has_zid = ur_zid.is_some();
        let map_entries: u8 = 2 + (has_name as u8) + (has_zid as u8);
        cbor_data.push(0xa0 + map_entries); // 0xa2 / 0xa3 / 0xa4

        // Key 1: ufvk (text string)
        // CBOR text string encoding: 0x60-0x77 for len 0-23, 0x78+len for len<256, 0x79+2bytes for len<65536
        cbor_data.push(0x01); // key = 1
        let ufvk_bytes = ufvk.as_bytes();
        if ufvk_bytes.len() < 24 {
            cbor_data.push(0x60 + ufvk_bytes.len() as u8);
        } else if ufvk_bytes.len() < 256 {
            cbor_data.push(0x78); // text with 1-byte length
            cbor_data.push(ufvk_bytes.len() as u8);
        } else {
            cbor_data.push(0x79); // text with 2-byte length (big-endian)
            cbor_data.extend_from_slice(&(ufvk_bytes.len() as u16).to_be_bytes());
        }
        cbor_data.extend_from_slice(ufvk_bytes);

        // Key 2: index (unsigned int)
        // CBOR uint encoding: 0-23 inline, 0x18+byte for 24-255, 0x19+2bytes for 256-65535
        cbor_data.push(0x02); // key = 2
        if account_index < 24 {
            cbor_data.push(account_index as u8);
        } else if account_index < 256 {
            cbor_data.push(0x18);
            cbor_data.push(account_index as u8);
        } else {
            cbor_data.push(0x19);
            cbor_data.extend_from_slice(&(account_index as u16).to_be_bytes());
        }

        // Key 3: name (optional text string)
        if has_name {
            cbor_data.push(0x03); // key = 3
            let label_bytes = label.as_bytes();
            if label_bytes.len() < 24 {
                cbor_data.push(0x60 + label_bytes.len() as u8);
            } else {
                cbor_data.push(0x78);
                cbor_data.push(label_bytes.len() as u8);
            }
            cbor_data.extend_from_slice(label_bytes);
        }

        // Key 4: zid_pubkey (optional 32-byte string) — canonical device identity
        // for zafu dedup across network imports. Unknown to legacy parsers (Zashi,
        // Keystone) which simply ignore unrecognized keys.
        if let Some(zid) = ur_zid {
            cbor_data.push(0x04); // key = 4
            cbor_data.push(0x58); // bytes with 1-byte length
            cbor_data.push(0x20); // 32 bytes
            cbor_data.extend_from_slice(&zid);
        }

        // Encode CBOR as UR string using bytewords encoding
        // Result format: "ur:zcash-accounts/..."
        ur::ur::encode(&cbor_data, &ur::Type::Custom("zcash-accounts"))
    };

    // Build binary QR data for Zafu wallet
    // Format must match Zafu's parseZcashFvkQR():
    // [0x53][0x04][0x01][flags:1][account:4 LE][label_len:1][label][orchard_fvk:96][addr_len:2 LE][address]
    let label_bytes = label.as_bytes();
    let label_len = label_bytes.len().min(255) as u8;
    let address_bytes = address.as_bytes();

    // Try to derive ZID pubkey (32 bytes) — lets zafu link this wallet to the
    // same zigner device across network imports for dedup. Best-effort: if it
    // fails for any reason, omit the ZID bytes rather than failing FVK export.
    let zid_bytes: Option<[u8; 32]> = auth::derive_zid_pubkey(seed_phrase)
        .ok()
        .and_then(|hex_str| hex::decode(&hex_str).ok())
        .and_then(|bytes| bytes.try_into().ok());

    // flags: bit 0 = mainnet, bit 1 = has orchard, bit 2 = has transparent,
    //        bit 3 = has address, bit 4 = has ZID pubkey (32 bytes appended)
    let zid_flag: u8 = if zid_bytes.is_some() { 0x10 } else { 0x00 };
    let flags: u8 = (if mainnet { 0x01 } else { 0x00 }) | 0x02 | 0x08 | zid_flag;

    let mut qr_data =
        Vec::with_capacity(3 + 1 + 4 + 1 + label_bytes.len() + 96 + 2 + address_bytes.len() + 32);
    qr_data.push(0x53); // 'S' for Signer (substrate compat)
    qr_data.push(0x04); // Zcash chain ID
    qr_data.push(QR_TYPE_ZCASH_FVK_EXPORT);
    qr_data.push(flags);
    qr_data.extend_from_slice(&account_index.to_le_bytes());
    qr_data.push(label_len);
    qr_data.extend_from_slice(&label_bytes[..label_len as usize]);
    qr_data.extend_from_slice(&fvk_bytes);
    // Add unified address (bit 3 flag)
    qr_data.extend_from_slice(&(address_bytes.len() as u16).to_le_bytes());
    qr_data.extend_from_slice(address_bytes);
    // Add ZID pubkey (bit 4 flag) — canonical device identity
    if let Some(zid) = zid_bytes {
        qr_data.extend_from_slice(&zid);
    }

    Ok(ZcashFvkExport {
        account_index,
        label: label.to_string(),
        mainnet,
        address,
        fvk_hex: hex::encode(fvk_bytes),
        ufvk,
        qr_data,
        ur_string,
    })
}

/// Get a diversified Zcash Orchard address at a specific diversifier index.
///
/// Orchard supports unlimited diversified addresses from the same spending key.
/// Each index produces a unique address that maps to the same wallet.
fn get_zcash_diversified_address(
    seed_phrase: &str,
    account_index: u32,
    diversifier_index: u32,
    mainnet: bool,
) -> Result<String, ErrorDisplayed> {
    use transaction_signing::zcash::OrchardSpendingKey;

    let osk = OrchardSpendingKey::from_seed_phrase(seed_phrase, account_index).map_err(|e| {
        ErrorDisplayed::Str {
            s: format!("Failed to derive Orchard key: {e}"),
        }
    })?;

    Ok(osk.get_address_at(diversifier_index, mainnet))
}

/// Get the transparent (t-address) for a Zcash account.
///
/// Returns a t1... (mainnet) or tm... (testnet) P2PKH address.
/// Uses BIP44 path: m/44'/133'/account'/0/0
fn get_zcash_transparent_address(
    seed_phrase: &str,
    account: u32,
    mainnet: bool,
) -> Result<String, ErrorDisplayed> {
    use transaction_signing::zcash::{derive_transparent_address, TransparentSpendingKey};

    let tsk =
        TransparentSpendingKey::from_seed_phrase(seed_phrase, account, 0, 0).map_err(|e| {
            ErrorDisplayed::Str {
                s: format!("Failed to derive transparent key: {e}"),
            }
        })?;

    derive_transparent_address(&tsk, mainnet).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to derive transparent address: {e}"),
    })
}

// ============================================================================
// PCZT (Partially Created Zcash Transaction) functions for Zashi compatibility
// ============================================================================
//
// PCZT is the standard format for passing unsigned Zcash transactions between
// watch-only wallets (like Zashi) and hardware signers (like Zigner/Keystone).
//
// UR (Uniform Resource) encoding:
// - Type: zcash-pczt (CBOR tag 49204)
// - Format: ur:zcash-pczt/<bytewords-encoded-cbor>
// - For large PCZTs, UR supports animated QR sequences (fountain codes)
//
// Flow:
// 1. Zashi creates PCZT, encodes as ur:zcash-pczt, shows animated QR
// 2. Zigner scans QR sequence, decodes UR, parses PCZT
// 3. Zigner extracts sighash and rsk randomizers, signs with spending key
// 4. Zigner injects signatures into PCZT, encodes as ur:zcash-pczt
// 5. Zashi scans signed PCZT QR, extracts signatures, broadcasts tx

/// Decode UR string to get PCZT bytes
/// Handles both single QR and animated QR sequences
fn decode_ur_zcash_pczt(ur_parts: Vec<String>) -> Result<Vec<u8>, ErrorDisplayed> {
    use ur::ur::decode;

    if ur_parts.is_empty() {
        return Err(ErrorDisplayed::Str {
            s: "No UR parts provided".to_string(),
        });
    }

    // Verify UR type from the URI string (format: ur:type/data)
    fn verify_ur_type(ur_string: &str) -> Result<(), ErrorDisplayed> {
        let lower = ur_string.to_lowercase();
        if !lower.starts_with("ur:zcash-pczt/") {
            return Err(ErrorDisplayed::Str {
                s: format!(
                    "Expected ur:zcash-pczt/... got: {}",
                    ur_string.chars().take(30).collect::<String>()
                ),
            });
        }
        Ok(())
    }

    // For single-part UR, decode directly
    if ur_parts.len() == 1 {
        verify_ur_type(&ur_parts[0])?;

        let (_kind, cbor_data) = decode(&ur_parts[0]).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to decode UR: {:?}", e),
        })?;

        // Extract PCZT bytes from CBOR
        // CBOR structure: { 1: bytes } (map with key 1 containing the PCZT bytes)
        extract_pczt_from_cbor(&cbor_data)
    } else {
        // For multi-part (animated) UR, use fountain decoder
        let mut decoder = ur::ur::Decoder::default();

        // Verify type from first part
        verify_ur_type(&ur_parts[0])?;

        for part in &ur_parts {
            decoder.receive(part).map_err(|e| ErrorDisplayed::Str {
                s: format!("Failed to receive UR part: {:?}", e),
            })?;

            if decoder.complete() {
                break;
            }
        }

        if !decoder.complete() {
            return Err(ErrorDisplayed::Str {
                s: format!(
                    "Incomplete UR sequence: received {} parts but not complete",
                    ur_parts.len()
                ),
            });
        }

        let cbor_data = decoder
            .message()
            .map_err(|e| ErrorDisplayed::Str {
                s: format!("Failed to get UR message: {:?}", e),
            })?
            .ok_or_else(|| ErrorDisplayed::Str {
                s: "UR decoder returned None despite being complete".to_string(),
            })?;

        extract_pczt_from_cbor(&cbor_data)
    }
}

/// Extract PCZT bytes from CBOR wrapper
/// CBOR structure: { 1: bytes } - map with key 1 containing raw PCZT bytes
fn extract_pczt_from_cbor(cbor_data: &[u8]) -> Result<Vec<u8>, ErrorDisplayed> {
    // Simple CBOR parsing for { 1: bytes }
    // 0xa1 = map(1), 0x01 = key 1, then bytes
    if cbor_data.len() < 3 {
        return Err(ErrorDisplayed::Str {
            s: "CBOR data too short".to_string(),
        });
    }

    // Check for map(1)
    if cbor_data[0] != 0xa1 {
        return Err(ErrorDisplayed::Str {
            s: format!("Expected CBOR map(1), got: 0x{:02x}", cbor_data[0]),
        });
    }

    // Check for key 1
    if cbor_data[1] != 0x01 {
        return Err(ErrorDisplayed::Str {
            s: format!("Expected CBOR key 1, got: 0x{:02x}", cbor_data[1]),
        });
    }

    // Parse bytes at position 2
    let (bytes, _) = parse_cbor_bytes(&cbor_data[2..]).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to parse CBOR bytes: {}", e),
    })?;

    Ok(bytes)
}

/// Parse CBOR bytes (major type 2)
fn parse_cbor_bytes(data: &[u8]) -> Result<(Vec<u8>, usize), String> {
    if data.is_empty() {
        return Err("Empty CBOR data".to_string());
    }

    let first = data[0];
    let major_type = first >> 5;
    let additional = first & 0x1f;

    if major_type != 2 {
        return Err(format!(
            "Expected bytes (major type 2), got: {}",
            major_type
        ));
    }

    let (len, header_len) = match additional {
        0..=23 => (additional as usize, 1),
        24 => {
            if data.len() < 2 {
                return Err("Truncated CBOR length".to_string());
            }
            (data[1] as usize, 2)
        }
        25 => {
            if data.len() < 3 {
                return Err("Truncated CBOR length".to_string());
            }
            (u16::from_be_bytes([data[1], data[2]]) as usize, 3)
        }
        26 => {
            if data.len() < 5 {
                return Err("Truncated CBOR length".to_string());
            }
            (
                u32::from_be_bytes([data[1], data[2], data[3], data[4]]) as usize,
                5,
            )
        }
        _ => return Err(format!("Unsupported CBOR additional info: {}", additional)),
    };

    if data.len() < header_len + len {
        return Err("Truncated CBOR bytes".to_string());
    }

    Ok((
        data[header_len..header_len + len].to_vec(),
        header_len + len,
    ))
}

/// Sign a PCZT and return the signed PCZT bytes
///
/// Get signing context: what the signer knows about verified balance.
fn get_zcash_sign_context() -> Result<ZcashSignContext, ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    let balance = db_handling::zcash::get_verified_balance(database).unwrap_or(0);
    let notes = db_handling::zcash::get_verified_notes(database).unwrap_or_default();
    let anchor = db_handling::zcash::get_verified_anchor(database)
        .ok()
        .flatten();

    let (anchor_height, synced_at) = anchor.map(|(_, h, _, ts, _)| (h, ts)).unwrap_or((0, 0));

    Ok(ZcashSignContext {
        verified_balance: balance,
        note_count: notes.len() as u32,
        anchor_height,
        synced_at,
        has_notes: !notes.is_empty(),
    })
}

/// Encode a raw 43-byte Orchard address as a unified address string (u1...).
fn encode_orchard_recipient(raw: &[u8; 43], mainnet: bool) -> Option<String> {
    use zcash_address::unified::{Address as UnifiedAddress, Encoding, Receiver};
    use zcash_protocol::consensus::NetworkType as Network;

    let receiver = Receiver::Orchard(*raw);
    let network = if mainnet {
        Network::Main
    } else {
        Network::Test
    };
    UnifiedAddress::try_from_items(vec![receiver])
        .ok()
        .map(|ua| ua.encode(&network))
}

/// Inspect a PCZT: extract spend/output details, cross-reference against verified notes.
/// Defense-in-depth caps on PCZT parsing. The pczt crate uses postcard for
/// deserialization, which doesn't bound declared collection lengths against
/// remaining slice length — a hostile PCZT can claim a Vec<Action> of 2^32
/// items and trigger allocation-driven OOM. We reject obviously-bogus inputs
/// upfront. Real Orchard bundles top out in the low tens of actions.
const MAX_PCZT_BYTES: usize = 1024 * 1024; // 1 MiB — far above any real PCZT
const MAX_ORCHARD_ACTIONS: usize = 4096; // far above any real bundle (~tens)

/// Confirm that the recipient + amount the review screen is about to DISPLAY
/// are the ones the transaction actually pays.
///
/// A cold wallet's whole value is that the screen cannot lie. The `recipient`
/// and `value` fields of an orchard output are plaintext metadata the wallet
/// puts in the PCZT for exactly this display; nothing in the signing path
/// reads them, so on their own a hostile wallet could show any recipient and
/// any amount while the transaction pays somebody else. What the transaction
/// is actually bound to is the note commitment `cmx`, which is committed to by
/// the sighash we sign.
///
/// `Output::verify_note_commitment` recomputes `cmx` from
/// (recipient, value, rho = nf_old, rseed) and compares. zafu's
/// `redact_pczt_for_signer` deliberately KEEPS all of those on the output side
/// (only spend-side note plaintext and the fvk are stripped), so this check
/// runs on exactly the bytes that cross the airgap.
///
/// The rule is keyed on what is DISPLAYED, not on which field happens to be
/// present: **EVERY output must be proven against its note commitment. There
/// is no skip.**
///
/// The original version of this gate skipped whenever
/// `verify_note_commitment` reported `MissingRecipient` / `MissingValue` /
/// `MissingRandomSeed` — "a producer that withholds a field gets no display
/// guarantee, but also no false alarm". That made the control bypassable by an
/// ATTACKER rather than merely waived by an honest producer: the signing path
/// never reads the output `rseed` (`pczt`'s low-level signer sets
/// `rseed: None` itself, and the signature is over a sighash that binds the
/// real `cmx`), so a hostile wallet could strip the output `rseed`, set
/// `recipient` and `value` to whatever the victim expected to see, have the
/// device display those and sign, then reassemble with the true `rseed` and
/// broadcast. The review screen reads `value()`/`recipient()` directly
/// regardless of whether verification ran, so the skip was a silent no-op on
/// attacker-chosen input.
///
/// An intermediate fix keyed the skip on whether anything was DISPLAYED —
/// refuse an unprovable output that renders a recipient or a non-zero amount,
/// allow one that renders nothing. That escape hatch was itself reachable by a
/// hostile producer: strip `recipient`, `value` AND `rseed` from every output
/// and the review screen renders a blank recipient and 0 for each of them
/// while the transaction still pays a real amount bound by `cmx`, with a
/// plausible producer-supplied fee alongside. A device that displays "nothing,
/// 0" and then signs a real payment has failed at its only job.
///
/// So there is no skip. Any output this device cannot prove — for any reason,
/// including a withheld field — is a REFUSAL naming the action index and the
/// reason. zafu's `redact_pczt_for_signer` keeps `recipient`, `value` and
/// `rseed` on every output, dummy/padding actions included, and all of them
/// verify, so an honestly-redacted PCZT is unaffected (pinned by the
/// `zafu_redacted_*` fixtures). A third-party producer that redacts output
/// plaintext is refused — loudly, at review time, with the failing action
/// named. That is the correct failure direction: the alternative fails
/// silently and invisibly, which is how the original bug survived.
///
/// Still a silent no-op when the bundle cannot be reached at all (unparsable,
/// unknown consensus branch) — signing fails on those paths anyway.
///
/// The second half of this gate covers the FEE — see [`verify_value_balance`].
fn verify_displayed_summary(pczt: &pczt::Pczt) -> Result<(), ErrorDisplayed> {
    use pczt::roles::verifier::{OrchardError, Verifier};

    fn check(bundle: &orchard::pczt::Bundle) -> Result<(), OrchardError<String>> {
        for (index, action) in bundle.actions().iter().enumerate() {
            match action.output().verify_note_commitment(action.spend()) {
                Ok(()) => {}
                // Not verifiable because the producer withheld a field the
                // check needs. Unconditional refusal — see above.
                Err(
                    e @ (orchard::pczt::VerifyError::MissingRecipient
                    | orchard::pczt::VerifyError::MissingValue
                    | orchard::pczt::VerifyError::MissingRandomSeed),
                ) => {
                    return Err(OrchardError::Custom(format!(
                        "output cannot be proven against its note commitment \
                         (action {index}: {e:?})"
                    )))
                }
                // A PROVEN lie.
                Err(e) => return Err(OrchardError::Verify(e)),
            }
        }
        verify_value_balance(bundle).map_err(OrchardError::Custom)
    }

    let outcome = Verifier::new(pczt.clone())
        .with_orchard(check)
        .and_then(|v| v.with_ironwood(check));

    match outcome {
        Ok(_) => Ok(()),
        // A PROVEN lie: the displayed recipient/amount do not produce the note
        // commitment this transaction pays out to. Refuse.
        Err(OrchardError::Verify(e)) => Err(ErrorDisplayed::Str {
            s: format!(
                "PCZT output does not match what it claims to pay ({e:?}) — \
                 refusing to display or sign it."
            ),
        }),
        // Something the review screen would show that this device cannot
        // prove: an output missing a field the commitment check needs, or a
        // declared value balance the commitments contradict.
        Err(OrchardError::Custom(reason)) => Err(ErrorDisplayed::Str {
            s: format!("PCZT {reason} — refusing to display or sign it."),
        }),
        // Not verifiable at all (structural parse, unrecognised consensus
        // branch). This was `Ok(())` - "signing fails later anyway" - but the
        // review screen would already be showing outputs nobody proved.
        // zcash_consensus_gate rejects the known cases first; this is the
        // backstop.
        Err(e) => Err(ErrorDisplayed::Str {
            s: format!("PCZT cannot be verified ({e:?}) — refusing to display or sign it."),
        }),
    }
}

/// Confirm that a bundle's declared `value_sum` — the number the displayed FEE
/// is computed from — matches the action value commitments.
///
/// `value_sum` is plaintext metadata the producer writes into the PCZT.
/// Nothing in the note-commitment check above constrains it, so on its own a
/// hostile wallet could pay the user's expected recipient the expected amount
/// (both now provably displayed) while declaring a `value_sum` that renders a
/// trivial fee, and route the rest of a large spent note to the miner. An
/// inflated fee is fund loss, not a cosmetic error.
///
/// It is provable without any spend-side note plaintext — which matters,
/// because zafu's redaction strips ALL of it (spend `value`, `recipient`,
/// `rho`, `rseed` and the `fvk`), so `Spend::verify_nullifier` and
/// `Action::verify_cv_net` are both unusable here and a
/// `fee = Σ spend − Σ output` derivation is impossible. What survives is the
/// relation the IO Finalizer established and consensus later re-checks via the
/// binding signature:
///
/// ```text
///     Σ cv_net − ValueCommit(value_sum, 0) == VerificationKey::from(bsk)
/// ```
///
/// `cv_net` is present on every action and is covered by the sighash this
/// device signs; `bsk` survives redaction (zafu clears spend note plaintext
/// and the fvk, not the binding key). Forging a `bsk'` for a different
/// `value_sum'` means finding the discrete log of `[value_sum − value_sum']V`
/// in base `R`, so a producer cannot move the declared balance and stay
/// consistent. This is a genuinely independent derivation, not the same
/// metadata re-read by another route.
///
/// A missing `bsk` is a REFUSAL, not a skip — otherwise withholding one field
/// would disable this check exactly the way withholding `rseed` disabled the
/// output check. An action-less bundle (the canonical-empty Ironwood bundle a
/// V5 PCZT carries) has no commitments to check against, so its `value_sum`
/// must be exactly zero; a non-zero one would shift the displayed fee with
/// nothing backing it, since `fee_zat` sums the ironwood term unconditionally.
///
/// SCOPE: this binds the orchard and ironwood terms of the fee, which for a
/// shielded-only transaction is the entire fee. The transparent and sapling
/// terms are NOT bound — see the note on `fee_zat` in `inspect_zcash_pczt`.
fn verify_value_balance(bundle: &orchard::pczt::Bundle) -> Result<(), String> {
    use orchard::value::{ValueCommitTrapdoor, ValueCommitment};

    if bundle.actions().is_empty() {
        return if *bundle.value_sum() == orchard::value::ValueSum::default() {
            Ok(())
        } else {
            Err(format!(
                "bundle declares a value balance of {:?} but has no actions to back it",
                bundle.value_sum()
            ))
        };
    }

    let bsk = bundle.bsk().as_ref().ok_or_else(|| {
        "declared value balance (the displayed fee) cannot be proven: binding key absent"
            .to_string()
    })?;

    // ValueCommit(v, 0) — the zero trapdoor is not public API, but a 32-byte
    // zero scalar is the same thing.
    let zero_rcv = ValueCommitTrapdoor::from_bytes([0u8; 32])
        .into_option()
        .ok_or_else(|| "zero value-commitment trapdoor".to_string())?;
    let sum_cv: ValueCommitment = bundle.actions().iter().map(|a| a.cv_net()).sum();
    let bvk = (sum_cv - ValueCommitment::derive(*bundle.value_sum(), zero_rcv)).to_bytes();
    let expected: [u8; 32] = (&orchard::primitives::redpallas::VerificationKey::from(bsk)).into();

    if bvk == expected {
        Ok(())
    } else {
        Err(format!(
            "declared value balance {:?} (which the displayed fee is derived from) \
             does not match the action value commitments",
            bundle.value_sum()
        ))
    }
}

fn inspect_zcash_pczt(pczt_bytes: Vec<u8>) -> Result<ZcashPcztInspection, ErrorDisplayed> {
    use pczt::Pczt;

    if pczt_bytes.len() > MAX_PCZT_BYTES {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "PCZT too large: {} B (max {MAX_PCZT_BYTES})",
                pczt_bytes.len()
            ),
        });
    }

    // The pczt parse + every subsequent access can panic on adversarial input
    // (declared collection lengths > remaining slice, integer overflow on
    // count fields). Catch the panic so the UDL surface returns a clean
    // ErrorDisplayed instead of unwinding into the Kotlin/Swift FFI boundary.
    let pczt = std::panic::catch_unwind(|| Pczt::parse(&pczt_bytes))
        .map_err(|_| ErrorDisplayed::Str {
            s: "Panic during PCZT parse — input is malformed".to_string(),
        })?
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to parse PCZT: {:?}", e),
        })?;

    let orchard = pczt.orchard();
    let action_count_raw = orchard.actions().len();
    if action_count_raw > MAX_ORCHARD_ACTIONS {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "Orchard bundle has {action_count_raw} actions, exceeds cap {MAX_ORCHARD_ACTIONS}"
            ),
        });
    }
    let action_count = action_count_raw as u32;

    // Ironwood (NU6.3 / V6) is a second, orchard-shaped bundle. A V5 PCZT
    // carries the canonical EMPTY ironwood bundle, so everything below is a
    // no-op there and every ironwood-derived value is 0 — the V5 inspection
    // is unchanged. The same allocation cap applies.
    let ironwood = pczt.ironwood();
    let ironwood_count_raw = ironwood.actions().len();
    if ironwood_count_raw > MAX_ORCHARD_ACTIONS {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "Ironwood bundle has {ironwood_count_raw} actions, exceeds cap {MAX_ORCHARD_ACTIONS}"
            ),
        });
    }
    let ironwood_action_count = ironwood_count_raw as u32;

    // Screen-honesty gate: everything below feeds the review screen, so refuse
    // before building a summary that provably misstates what is being paid.
    zcash_consensus_gate::check_supported(&pczt)?;
    verify_displayed_summary(&pczt)?;

    // Load verified notes for cross-reference. Used by the known_spends warning
    // (zigner's distinguishing safety guard over a Keystone-equivalent signer —
    // we can warn when the PCZT spends notes we haven't verified) and to derive
    // the network for recipient-address encoding.
    //
    // The verified_anchor was previously used for byte-equality matching against
    // the PCZT's anchor; that check was rotko-invented and doesn't exist in the
    // canonical Zashi → Keystone flow, so it's gone. The DB still stores the
    // anchor for the note-sync UI on a separate screen; the PCZT path doesn't
    // consult it for signing decisions.
    let (verified_balance, verified_nullifiers, verified_notes_values, is_mainnet) = {
        let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
        if let Some(database) = db_guard.as_ref() {
            let balance = db_handling::zcash::get_verified_balance(database).unwrap_or(0);
            // Anchor is read only to recover the network flag; the bytes are
            // discarded. Defaults to mainnet when no notes have been synced.
            let mainnet = db_handling::zcash::get_verified_anchor(database)
                .ok()
                .flatten()
                .map(|(_, _, m, _, _)| m)
                .unwrap_or(true);
            let notes = db_handling::zcash::get_verified_notes(database).unwrap_or_default();
            let nullifiers: std::collections::HashSet<String> = notes
                .iter()
                .map(|(_, nf_hex, _, _, _)| nf_hex.clone())
                .collect();
            // Map nullifier_hex → value for spend amount lookup
            let values: std::collections::HashMap<String, u64> = notes
                .iter()
                .map(|(val, nf_hex, _, _, _)| (nf_hex.clone(), *val))
                .collect();
            (balance, nullifiers, values, mainnet)
        } else {
            (
                0,
                std::collections::HashSet::new(),
                std::collections::HashMap::new(),
                true,
            )
        }
    };

    // Extract spend details. Known nullifiers get their value from our
    // verified store; unknown ones report 0 here (the pczt crate's Spend
    // doesn't expose per-spend value). The UI tells dummies from real
    // unverified notes via the value balance instead — orchard_net_value +
    // outputs - verified spends.
    let mut spends = Vec::new();
    let mut known_spends = 0u32;
    for action in orchard.actions() {
        let nullifier_hex = hex::encode(action.spend().nullifier());
        let known = verified_nullifiers.contains(&nullifier_hex);
        let value = if known {
            known_spends += 1;
            verified_notes_values
                .get(&nullifier_hex)
                .copied()
                .unwrap_or(0)
        } else {
            0
        };
        spends.push(ZcashPcztSpend {
            value,
            nullifier_hex,
            known,
        });
    }
    // Ironwood spends are appended to the SAME list: they are orchard-shaped,
    // the verified-note store is keyed by nullifier regardless of pool, and the
    // review screen must show every note the transaction consumes. Empty for V5.
    for action in ironwood.actions() {
        let nullifier_hex = hex::encode(action.spend().nullifier());
        let known = verified_nullifiers.contains(&nullifier_hex);
        let value = if known {
            known_spends += 1;
            verified_notes_values
                .get(&nullifier_hex)
                .copied()
                .unwrap_or(0)
        } else {
            0
        };
        spends.push(ZcashPcztSpend {
            value,
            nullifier_hex,
            known,
        });
    }

    // Extract output details with human-readable addresses
    let mut outputs = Vec::new();
    for action in orchard.actions().iter().chain(ironwood.actions().iter()) {
        let value = action.output().value().unwrap_or(0);
        let recipient_hex = match action.output().recipient() {
            Some(raw_bytes) => {
                // Decode raw 43-byte Orchard address to unified address string.
                // Ironwood receivers are the same 43-byte raw orchard address,
                // so the same encoding applies.
                encode_orchard_recipient(raw_bytes, is_mainnet)
                    .unwrap_or_else(|| hex::encode(raw_bytes))
            }
            None => String::new(),
        };
        outputs.push(ZcashPcztOutput {
            value,
            recipient: recipient_hex,
        });
    }

    // Orchard's value_sum = (total spent − total output) in zatoshis.
    // Sign-extend into i64 for fee math.
    let &(value_sum_magnitude, value_sum_is_negative) = orchard.value_sum();
    let orchard_net_value: i64 = if value_sum_is_negative {
        -(value_sum_magnitude as i64)
    } else {
        value_sum_magnitude as i64
    };
    let &(iw_magnitude, iw_is_negative) = ironwood.value_sum();
    let ironwood_net_value: i64 = if iw_is_negative {
        -(iw_magnitude as i64)
    } else {
        iw_magnitude as i64
    };

    // True fee = Σ over ALL bundles of (in − out).
    // - transparent: explicit input.value − output.value sum
    // - sapling: bundle exposes value_sum (i128) — same sign convention
    // - orchard: orchard_net_value above
    // - ironwood: ironwood_net_value above
    //
    // For shielded-only txs the transparent + sapling terms are zero and the
    // fee equals orchard_net_value, so the old code accidentally worked.
    // For shielding / deshielding it didn't.
    //
    // The ironwood term is load-bearing for a turnstile migration: the migrated
    // value LEAVES the orchard pool, so orchard_net_value alone is ~the whole
    // migrated amount. Omitting the ironwood value_sum would display that as
    // the fee. It is exactly 0 for a V5 transaction, so V5 fees are unchanged.
    //
    // HOW FAR THIS FEE IS TRUSTED (read before relying on it, and note that an
    // inflated fee is fund loss, not a cosmetic error):
    //
    // Every term below is plaintext metadata in the PCZT, so for each one the
    // question is whether anything binds it to what will actually be mined.
    //
    // * orchard + ironwood: BOUND. `verify_value_balance`, called via
    //   `verify_displayed_summary` above before any of this runs, proves each
    //   bundle's value_sum against Σ cv_net and bsk; a producer cannot move it
    //   without solving a discrete log, and an action-less bundle must declare
    //   exactly zero. For a shielded-only transaction — the release-critical
    //   flow — that is the WHOLE fee, so the number displayed is trustworthy.
    // * transparent: NOT bound. `input.value` is taken at face value here.
    //   Those amounts are covered by the per-input transparent sighash we sign,
    //   but nothing cross-checks them before the review screen renders.
    // * sapling: NOT bound. The sapling bundle carries its own bsk and the same
    //   relation would prove it, but there is no sapling display path or
    //   fixture here, and the i128→i64 clamp below silently yields 0 on an
    //   out-of-range value_sum, which a hostile producer can force.
    //
    // So a PCZT carrying transparent or sapling components can still show a
    // partly producer-supplied fee. Extending the same binding to those
    // bundles is the next step if either becomes a supported flow.
    let transparent = pczt.transparent();
    let transparent_in: u64 = transparent.inputs().iter().map(|i| *i.value()).sum();
    let transparent_out: u64 = transparent.outputs().iter().map(|o| *o.value()).sum();
    let transparent_balance: i64 = (transparent_in as i64).saturating_sub(transparent_out as i64);
    // value_sum is i128 because intermediate arithmetic during PCZT
    // construction can exceed i64. A complete PCZT's value_sum always fits
    // in i64; clamp on overflow for safety.
    let sapling_v: i128 = *pczt.sapling().value_sum();
    let sapling_balance: i64 = if (i64::MIN as i128..=i64::MAX as i128).contains(&sapling_v) {
        sapling_v as i64
    } else {
        0
    };
    let fee_zat: i64 = transparent_balance
        .saturating_add(sapling_balance)
        .saturating_add(orchard_net_value)
        .saturating_add(ironwood_net_value);

    // Global tx metadata. Cold device displays these so the user knows
    // which consensus fork + expiry window they're authorizing.
    let global = pczt.global();
    let tx_version: u32 = *global.tx_version();
    let consensus_branch_id_hex: String = format!("{:08x}", global.consensus_branch_id());
    let expiry_height: u32 = *global.expiry_height();

    Ok(ZcashPcztInspection {
        action_count,
        ironwood_action_count,
        spends,
        outputs,
        fee_zat,
        orchard_net_value,
        ironwood_net_value,
        verified_balance,
        known_spends,
        tx_version,
        consensus_branch_id_hex,
        expiry_height,
    })
}

/// Error type for the low-level Signer role closures used by
/// [`sign_zcash_pczt`]. The role requires the closure's error to be `From` of
/// its bundle re-parse error; `ErrorDisplayed` is a foreign type here, so it
/// cannot carry that impl.
#[derive(Debug)]
struct LowLevelSignError(String);

impl From<pczt::roles::low_level_signer::OrchardParseError> for LowLevelSignError {
    fn from(e: pczt::roles::low_level_signer::OrchardParseError) -> Self {
        LowLevelSignError(format!("orchard-shaped bundle parse: {e:?}"))
    }
}

impl std::fmt::Display for LowLevelSignError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.write_str(&self.0)
    }
}

/// `tx_modifiable` bits the PCZT spec clears once a shielded spend has been
/// authorized (the signature commits to every transaction effect). The `pczt`
/// crate keeps its own copies `pub(crate)`, so they are restated here; the
/// high-level `Signer` role clears exactly these three after each successful
/// `sign_orchard` / `sign_ironwood`, and the low-level role leaves it to us.
const PCZT_FLAG_TRANSPARENT_INPUTS_MODIFIABLE: u8 = 0b0000_0001;
const PCZT_FLAG_TRANSPARENT_OUTPUTS_MODIFIABLE: u8 = 0b0000_0010;
const PCZT_FLAG_SHIELDED_MODIFIABLE: u8 = 0b1000_0000;

/// Spend-auth sign every action of one orchard-shaped bundle (orchard or
/// ironwood) that this seed controls, with the full viewing key supplied by
/// the caller rather than read from the PCZT.
///
/// Skips, rather than aborts on, actions that are not ours: dummy/padding
/// spends carry an ephemeral key the user does not hold (the wallet's
/// IoFinalizer already signed them), and a foreign spend in a multi-party
/// PCZT belongs to someone else. `signed` counts the actions we actually
/// authorized, so the caller can refuse a PCZT it contributed nothing to.
fn sign_orchard_shaped_bundle(
    bundle: &mut orchard::pczt::Bundle,
    fvk: &orchard::keys::FullViewingKey,
    ask: &orchard::keys::SpendAuthorizingKey,
    shielded_sighash: [u8; 32],
    tx_modifiable: &mut u8,
    signed: &mut usize,
) -> Result<(), LowLevelSignError> {
    for action in bundle.actions_mut().iter_mut() {
        // Consistency check against the caller-supplied fvk. The redaction
        // that crosses the airgap also strips the spent note's plaintext, so
        // recipient/value/rho/rseed surface as `Missing*`; tolerate exactly
        // the set the high-level `Signer` tolerates. Anything else (a real
        // `WrongFvkForNote` / `InvalidNullifier` on a note we do not own) is
        // a skip, not a hard failure.
        match action.spend().verify_nullifier(Some(fvk)) {
            Ok(())
            | Err(
                orchard::pczt::VerifyError::MissingRecipient
                | orchard::pczt::VerifyError::MissingValue
                | orchard::pczt::VerifyError::MissingRho
                | orchard::pczt::VerifyError::MissingRandomSeed,
            ) => {}
            Err(_) => continue,
        }
        // `Action::sign` re-derives `rk` from `ask` and `alpha` and refuses
        // unless it matches the PCZT's `rk`. That check — not the fvk — is
        // what binds the signature to a note this seed controls.
        if action
            .sign(
                shielded_sighash,
                ask,
                rand010::rand_core::UnwrapErr(rand010::rngs::SysRng),
            )
            .is_ok()
        {
            *signed += 1;
            *tx_modifiable &= !(PCZT_FLAG_TRANSPARENT_INPUTS_MODIFIABLE
                | PCZT_FLAG_TRANSPARENT_OUTPUTS_MODIFIABLE
                | PCZT_FLAG_SHIELDED_MODIFIABLE);
        }
    }
    Ok(())
}

/// This function:
/// 1. Parses the PCZT to extract orchard actions
/// 2. Signs each action with the spending key derived from seed
/// 3. Injects signatures back into the PCZT
fn sign_zcash_pczt(
    seed_phrase: &str,
    account_index: u32,
    pczt_bytes: Vec<u8>,
) -> Result<Vec<u8>, ErrorDisplayed> {
    use pczt::roles::low_level_signer::Signer as LowLevelSigner;
    use pczt::roles::signer::Signer;
    use pczt::Pczt;
    use transaction_signing::zcash::OrchardSpendingKey;

    // Run inspection; signing itself is intentionally permissive.
    //
    // The previous anchor-equality and "no verified notes" gates were
    // rotko-invented and don't appear in the canonical Zashi → Keystone PCZT
    // flow — Keystone's firmware signs whatever passes user review of
    // recipient + amount + fee, without inspecting the PCZT anchor at all
    // (verified at /steam/rotko/keystone3-firmware/rust/apps/zcash/src/pczt/
    // {sign,check}.rs). We mirror that.
    //
    // zigner's distinguishing safety value (the verified-notes store) is
    // surfaced as a SOFT on-device warning at review time, not a hard refuse
    // here (rotkonetworks/zigner#16). A hard `known_spends == 0` / implied-
    // spend-vs-balance refuse is impractical: the cold device can't learn its
    // own change notes (their tree position isn't assigned until the tx is
    // mined), so a synced wallet's change-spends would be refused until a
    // re-sync. Instead the sign screen shows the `WARN_UNRECOGNIZED`
    // acknowledge page when the tx spends value from a note not in the verified
    // set (0-value dummies excluded), plus inline per-spend verified / dummy /
    // unknown labels (see ZcashPcztScreen).
    let inspection = inspect_zcash_pczt(pczt_bytes.clone())?;

    // Ironwood (NU6.3 / V6) spends carry the SAME orchard spend-auth key: the
    // pool reuses the orchard key tree, so the one `ask` below authorizes both
    // bundles. 0 for a V5 transaction.
    let total_actions =
        inspection.action_count as usize + inspection.ironwood_action_count as usize;

    // Parse the PCZT to get action count first
    let pczt = Pczt::parse(&pczt_bytes).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to parse PCZT: {:?}", e),
    })?;

    // Derive the orchard spending key from seed
    let spending_key =
        OrchardSpendingKey::from_seed_phrase(seed_phrase, account_index).map_err(|e| {
            ErrorDisplayed::Str {
                s: format!("Failed to derive spending key: {}", e),
            }
        })?;

    // Get the actual orchard spending key for signing
    let orchard_sk = spending_key
        .to_spending_key()
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to get orchard spending key: {}", e),
        })?;

    // The signer needs the ask (spend authorizing key) derived from sk.
    let ask = orchard::keys::SpendAuthorizingKey::from(&orchard_sk);
    // ...and the matching full viewing key, RECONSTRUCTED FROM THE SEED. The
    // wallet-side redaction (zafu/zcli `redact_pczt_for_signer`) strips the
    // spend `fvk` from the PCZT before it crosses the airgap — it is the
    // account's 96-byte orchard FullViewingKey and shipping it over the QR
    // transport is a viewing-key leak. The high-level `pczt::roles::signer`
    // role cannot sign such a PCZT: `sign_orchard` / `sign_ironwood` hardcode
    // `Spend::verify_nullifier(None)`, which returns `MissingFullViewingKey`
    // and is NOT in the tolerated-Missing* set, so every action fails and the
    // device refuses a perfectly valid transaction. We hold the seed, so we
    // reconstruct the fvk and drive the LOW-LEVEL Signer role, passing it to
    // `verify_nullifier(Some(fvk))` ourselves (`fvk_for_validation` returns
    // the caller-supplied key when the PCZT's own field is absent). This is
    // the same construction the wasmi protocol module uses
    // (`pczt_signing::sign_redacted_pczt`), so both shipped signing paths
    // accept the identical redaction. `Action::sign` itself never reads the
    // fvk — the real ownership binding is `rk == alpha * ak`, which still
    // holds and still rejects notes this seed does not control.
    let orchard_fvk = orchard::keys::FullViewingKey::from(&orchard_sk);

    // The shielded sighash is a function of transaction EFFECTS only (it does
    // not depend on any spend-auth signature), so compute it once from the
    // parsed PCZT and reuse it for both bundles.
    let shielded_sighash = Signer::new(pczt.clone())
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to create PCZT signer: {:?}", e),
        })?
        .shielded_sighash();

    // Try-and-skip: ignore per-action sign failures (dummy actions are spent
    // under an ephemeral key the user doesn't hold; the wallet's IoFinalizer
    // already signed those). Refuse only if we couldn't sign anything at all.
    let mut signed_count = 0usize;
    let low = LowLevelSigner::new(pczt);
    let low = low
        .sign_orchard_with(|_pczt, bundle, tx_modifiable| {
            sign_orchard_shaped_bundle(
                bundle,
                &orchard_fvk,
                &ask,
                shielded_sighash,
                tx_modifiable,
                &mut signed_count,
            )
        })
        .map_err(|e: LowLevelSignError| ErrorDisplayed::Str {
            s: format!("Failed to sign orchard bundle: {e}"),
        })?;
    // Same treatment for the ironwood bundle. Empty (zero iterations) for V5.
    let low = low
        .sign_ironwood_with(|_pczt, bundle, tx_modifiable| {
            sign_orchard_shaped_bundle(
                bundle,
                &orchard_fvk,
                &ask,
                shielded_sighash,
                tx_modifiable,
                &mut signed_count,
            )
        })
        .map_err(|e: LowLevelSignError| ErrorDisplayed::Str {
            s: format!("Failed to sign ironwood bundle: {e}"),
        })?;
    if signed_count == 0 && total_actions > 0 {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "Could not sign any of the {total_actions} action(s) — none match the user's spending key."
            ),
        });
    }

    // Finish signing and get the signed PCZT
    let signed_pczt = low.finish();

    // Serialize back to bytes. Since pczt 0.9 `serialize` picks the MINIMAL
    // encoding that can carry the content: the v1 PCZT encoding whenever the
    // PCZT is v1-representable (V5 tx + canonical-empty ironwood bundle), the
    // v2 encoding otherwise. A V5 response therefore still goes back to
    // Zashi/zafu as v1 bytes, byte-identical in format to what shipped; only a
    // V6 / ironwood response needs v2.
    signed_pczt.serialize().map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to serialize signed PCZT: {:?}", e),
    })
}

/// Encode signed PCZT as UR string(s) for QR display
/// Returns a vector of UR parts for animated QR (or single part for small PCZTs)
fn encode_signed_pczt_ur(
    pczt_bytes: Vec<u8>,
    max_fragment_len: u32,
) -> Result<Vec<String>, ErrorDisplayed> {
    // Wrap PCZT bytes in CBOR: { 1: bytes }
    let cbor_data = encode_pczt_to_cbor(&pczt_bytes);

    if max_fragment_len == 0 || cbor_data.len() <= max_fragment_len as usize {
        // Single part UR
        let ur_string = ur::ur::encode(&cbor_data, &ur::Type::Custom("zcash-pczt"));
        Ok(vec![ur_string])
    } else {
        // Multi-part (animated) UR using fountain codes
        let mut encoder = ur::ur::Encoder::new(&cbor_data, max_fragment_len as usize, "zcash-pczt")
            .map_err(|e| ErrorDisplayed::Str {
                s: format!("Failed to create UR encoder: {:?}", e),
            })?;

        let mut parts = Vec::new();
        // 1.3× redundancy: with the slowed (~700ms/frame) display each frame
        // should land cleanly, so we only need a slim margin over the 1.05–1.20×
        // BC-UR convergence threshold.
        let total_parts = (encoder.fragment_count() * 13).div_ceil(10);

        for _ in 0..total_parts {
            let part = encoder.next_part().map_err(|e| ErrorDisplayed::Str {
                s: format!("Failed to encode UR part: {:?}", e),
            })?;
            parts.push(part);
        }

        Ok(parts)
    }
}

/// Encode PCZT bytes into CBOR wrapper
/// CBOR structure: { 1: bytes }
fn encode_pczt_to_cbor(pczt_bytes: &[u8]) -> Vec<u8> {
    let mut cbor = Vec::new();

    // map(1)
    cbor.push(0xa1);
    // key 1
    cbor.push(0x01);

    // bytes with appropriate length encoding
    let len = pczt_bytes.len();
    if len <= 23 {
        cbor.push(0x40 | len as u8);
    } else if len <= 255 {
        cbor.push(0x58);
        cbor.push(len as u8);
    } else if len <= 65535 {
        cbor.push(0x59);
        cbor.push((len >> 8) as u8);
        cbor.push(len as u8);
    } else {
        cbor.push(0x5a);
        cbor.push((len >> 24) as u8);
        cbor.push((len >> 16) as u8);
        cbor.push((len >> 8) as u8);
        cbor.push(len as u8);
    }

    cbor.extend_from_slice(pczt_bytes);
    cbor
}

// ========================================================================
// Zcash note sync (verified balance via animated QR)
// ========================================================================

/// Decode UR-encoded zcash-notes, verify merkle paths, store in sled.
///
/// Trust model:
/// - If attestation-required flag is set (sticky, set on first FROST DKG):
///   anchor attestation (CBOR key 5) is REQUIRED. Verified against stored
///   FROST group keys. Hard reject if missing, invalid, or no key matches.
/// - If flag is not set (never had a FROST wallet): accept without attestation.
///   The user trusts zcli in this mode — that's the model they chose.
fn decode_and_verify_zcash_notes(
    ur_parts: Vec<String>,
) -> Result<ZcashNoteSyncResult, ErrorDisplayed> {
    use transaction_signing::zcash::{decode_notes_bundle_from_cbor, verify_merkle_path};

    if ur_parts.is_empty() {
        return Err(ErrorDisplayed::Str {
            s: "No QR parts provided".to_string(),
        });
    }

    // auto-detect UR or zoda transport format
    let cbor_data = decode_qr_payload(&ur_parts, "zcash-notes")?;

    let bundle = decode_notes_bundle_from_cbor(&cbor_data).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to parse notes CBOR: {e}"),
    })?;

    // Single DB lock for the entire operation
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    // Monotonic height check: reject anchors older than what we already have
    if let Ok(Some((_, stored_height, _, _, _))) = db_handling::zcash::get_verified_anchor(database)
    {
        if bundle.anchor_height < stored_height {
            return Err(ErrorDisplayed::Str {
                s: format!(
                    "Anchor height {} is older than stored height {}. \
                     Rejecting stale anchor (possible replay).",
                    bundle.anchor_height, stored_height
                ),
            });
        }
    }

    // Verify anchor attestation against ANY enabled verifier in the
    // sled-backed registry. Bootstrapped from ROTKO_ZCASH_VERIFIER on
    // first run, but users running their own zidecar can scan in
    // additional verifier keys via the dedicated UR import path.
    let anchor_verified =
        if let Some(attestation) = &bundle.anchor_attestation {
            if attestation.len() != 64 {
                return Err(ErrorDisplayed::Str {
                    s: format!(
                        "attestation length {} bytes — expected exactly 64 (ed25519)",
                        attestation.len()
                    ),
                });
            }
            let signature = &attestation[..64];
            let verifier_pubkeys = db_handling::anchor_verifiers::enabled_pubkeys(database)
                .map_err(|e| ErrorDisplayed::Str {
                    s: format!("anchor verifier registry: {e}"),
                })?;

            if verifier_pubkeys.is_empty() {
                // No enabled verifiers — refuse rather than accept silently. A
                // device with no trusted verifier MUST NOT accept attested
                // bundles; re-add at least the built-in verifier or scan in
                // your own.
                return Err(ErrorDisplayed::Str {
                    s: "No enabled anchor verifiers. Re-enable the built-in verifier \
                    or add your own zidecar verifier key in Settings before \
                    importing notes."
                        .to_string(),
                });
            }

            let sig = sp_core::ed25519::Signature::from_raw({
                let mut s = [0u8; 64];
                s.copy_from_slice(signature);
                s
            });

            // Try each enabled verifier — accept on the first match.
            // Per-verifier digest because the verifier's pubkey is bound
            // into the digest's domain separation.
            let mut matched = false;
            for verifier_key in &verifier_pubkeys {
                use sha2::{Digest, Sha256};
                let mut hasher = Sha256::new();
                hasher.update(b"zcash-anchor-v1");
                hasher.update(verifier_key);
                hasher.update(bundle.anchor);
                hasher.update(bundle.anchor_height.to_le_bytes());
                hasher.update([u8::from(bundle.mainnet)]);
                let digest: [u8; 32] = hasher.finalize().into();
                let pubkey = sp_core::ed25519::Public::from_raw(*verifier_key);
                if <sp_core::ed25519::Pair as sp_core::Pair>::verify(&sig, digest, &pubkey) {
                    matched = true;
                    break;
                }
            }
            if !matched {
                return Err(ErrorDisplayed::Str {
                    s: "Anchor attestation signature invalid — not signed by any \
                    trusted verifier. Check Settings → Anchor verifiers."
                        .to_string(),
                });
            }
            true
        } else {
            false // no attestation present, accept as unverified
        };

    // Sticky attestation flag: once a device has participated in any FROST
    // wallet, attestation is required for all subsequent note imports. This
    // prevents a malicious zafu from feeding fabricated note bundles to an
    // ex-FROST device (which would otherwise be in single-signer "trust zcli"
    // mode after wallet deletion).
    if db_handling::zcash::is_attestation_required(database).unwrap_or(false) && !anchor_verified {
        return Err(ErrorDisplayed::Str {
            s: "This device requires FROST-attested anchors. Bundle has no \
                attestation or attestation failed verification. Refusing to \
                import unverified notes."
                .to_string(),
        });
    }

    // Verify each note's merkle path against anchor
    let mut verified_notes = Vec::new();
    for (i, note) in bundle.notes.iter().enumerate() {
        let valid = verify_merkle_path(&note.cmx, note.position, &note.merkle_path, &bundle.anchor)
            .map_err(|e| ErrorDisplayed::Str {
                s: format!("Merkle verification error for note {i}: {e}"),
            })?;

        if !valid {
            return Err(ErrorDisplayed::Str {
                s: format!(
                    "Note {i} failed merkle verification (cmx={}, pos={})",
                    hex::encode(note.cmx),
                    note.position
                ),
            });
        }

        verified_notes.push((
            note.value,
            note.nullifier,
            note.cmx,
            note.position,
            note.block_height,
        ));
    }

    let total_balance: u64 = verified_notes.iter().map(|(v, _, _, _, _)| v).sum();
    let notes_verified = verified_notes.len() as u32;

    db_handling::zcash::store_verified_notes(
        database,
        &verified_notes,
        &bundle.anchor,
        bundle.anchor_height,
        bundle.mainnet,
        bundle.anchor_time.unwrap_or(0),
    )
    .map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to store notes: {e}"),
    })?;

    // Surface when these notes were stored (device clock). Read it back from
    // the anchor we just wrote so the UI can answer "is this balance stale?"
    // with both a chain block height and a human scan time. A read failure
    // here must not fail the import -> degrade to 0 (unknown).
    let synced_at = db_handling::zcash::get_verified_anchor(database)
        .ok()
        .flatten()
        .map(|(_, _, _, ts, _)| ts)
        .unwrap_or(0);

    Ok(ZcashNoteSyncResult {
        notes_verified,
        total_balance,
        anchor_hex: hex::encode(bundle.anchor),
        anchor_height: bundle.anchor_height,
        mainnet: bundle.mainnet,
        anchor_verified,
        synced_at,
        anchor_time: bundle.anchor_time.unwrap_or(0),
    })
}

/// Get all verified zcash notes from sled
fn get_zcash_verified_notes() -> Result<Vec<ZcashVerifiedNoteDisplay>, ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    let notes =
        db_handling::zcash::get_verified_notes(database).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to get notes: {e}"),
        })?;

    Ok(notes
        .into_iter()
        .map(
            |(value, nullifier_hex, _cmx, _position, block_height)| ZcashVerifiedNoteDisplay {
                value,
                nullifier_hex,
                block_height,
            },
        )
        .collect())
}

/// Get total verified zcash balance
fn get_zcash_verified_balance() -> Result<u64, ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    db_handling::zcash::get_verified_balance(database).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to get balance: {e}"),
    })
}

/// Get zcash sync info (anchor, height, timestamp)
fn get_zcash_sync_info() -> Result<Option<ZcashSyncInfo>, ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    match db_handling::zcash::get_verified_anchor(database) {
        Ok(Some((anchor, height, mainnet, synced_at, anchor_time))) => Ok(Some(ZcashSyncInfo {
            anchor_hex: hex::encode(anchor),
            anchor_height: height,
            mainnet,
            synced_at,
            anchor_time,
        })),
        Ok(None) => Ok(None),
        Err(e) => Err(ErrorDisplayed::Str {
            s: format!("Failed to get sync info: {e}"),
        }),
    }
}

/// Clear all stored zcash notes
fn clear_zcash_notes() -> Result<(), ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    db_handling::zcash::clear_zcash_notes(database).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to clear notes: {e}"),
    })
}

/// Pure scan-progress probe for the camera. Reports how many zcash-notes
/// frames have been collected (`have`), how many are needed to reconstruct
/// (`needed`, -1 until the zoda frame-0 metadata or a single-part UR is
/// seen), and whether reconstruction is now possible (`complete`). No
/// attestation, no merkle check, no DB writes — the camera polls this while
/// scanning to drive the progress counter and to decide when to hand frames
/// to `decode_and_verify_zcash_notes`, which does the real verify + store.
fn zcash_notes_scan_progress(parts: Vec<String>) -> ZcashNotesScanProgress {
    // UR frames carry their own sequence header and are decoded elsewhere; for
    // the zoda (`zt:`) note-sync transport we can read live counts off the
    // decoder. Fall back to a decode attempt for the UR case.
    let first = parts.first().map(|s| s.to_lowercase()).unwrap_or_default();
    if !first.starts_with("zt:") {
        let complete = decode_qr_payload(&parts, "zcash-notes").is_ok();
        return ZcashNotesScanProgress {
            have: parts.len() as u32,
            needed: -1,
            complete,
        };
    }
    match zt_decoder_from_parts(&parts, "zcash-notes") {
        Ok(decoder) => ZcashNotesScanProgress {
            have: decoder.received() as u32,
            needed: decoder.threshold().map(i32::from).unwrap_or(-1),
            complete: decoder.complete(),
        },
        Err(_) => ZcashNotesScanProgress {
            have: 0,
            needed: -1,
            complete: false,
        },
    }
}

/// Generic UR decoder that handles both single-part and multi-part (fountain) URs
/// Decode QR payload — auto-detects UR (`ur:`) or zoda transport (`zt:`) format.
fn decode_qr_payload(parts: &[String], expected_type: &str) -> Result<Vec<u8>, ErrorDisplayed> {
    if parts.is_empty() {
        return Err(ErrorDisplayed::Str {
            s: "No QR parts provided".to_string(),
        });
    }
    let lower = parts[0].to_lowercase();
    if lower.starts_with("zt:") {
        decode_zt_payload(parts, expected_type)
    } else {
        decode_ur_payload(parts, expected_type)
    }
}

/// Feed collected `zt:type/hex` frames into a zoda decoder, skipping stray /
/// foreign-session frames. Shared by the scan-progress probe and the full
/// decode so both see identical accumulation semantics.
///
/// Animated QR frames arrive in cycling order, so the metadata frame (index 0,
/// carrying k/n) is rarely captured first. zoda's decoder cannot recover the
/// metadata if a non-zero frame is seen first (it pins the session and then
/// treats the later frame 0 as an ordinary chunk), so we hand it the index-0
/// frame first. Per-frame errors are skipped rather than fatal — a single
/// garbled QR read shouldn't abort a scan that's still collecting frames.
fn zt_decoder_from_parts(
    parts: &[String],
    expected_type: &str,
) -> Result<zoda_vss::transport::Decoder, ErrorDisplayed> {
    use zoda_vss::transport::Decoder as ZtDecoder;

    let zt_prefix = format!("zt:{}/", expected_type);

    // hex-decode the matching frames, skipping non-matching / partial reads.
    let mut frames: Vec<Vec<u8>> = Vec::new();
    for part in parts {
        if !part.to_lowercase().starts_with(&zt_prefix) {
            continue;
        }
        if let Ok(bytes) = hex::decode(&part[zt_prefix.len()..]) {
            frames.push(bytes);
        }
    }

    // frame layout: session_id(8) || index(1) || chunk.
    let mut decoder = ZtDecoder::new();

    // Wait for the metadata (index-0) frame before feeding anything. zoda's
    // decoder pins the session on the first frame it sees and panics
    // (`self.n.unwrap()`) if a *second* non-zero frame arrives before
    // metadata. Holding off until frame 0 is present — and feeding it first —
    // guarantees k/n are established before any chunk frame, so that unwrap is
    // never reached. Until then we report an empty decoder (have=0, k unknown).
    if !frames.iter().any(|f| f.get(8) == Some(&0)) {
        return Ok(decoder);
    }
    // stable-sort the index-0 frame to the front; everything else keeps order.
    frames.sort_by_key(|f| u8::from(f.get(8) != Some(&0)));

    for frame_bytes in &frames {
        // tolerate per-frame rejects (foreign session, dup).
        let _ = decoder.receive(frame_bytes);
        if decoder.complete() {
            break;
        }
    }

    Ok(decoder)
}

/// Decode zoda transport frames (`zt:type/hex`).
fn decode_zt_payload(parts: &[String], expected_type: &str) -> Result<Vec<u8>, ErrorDisplayed> {
    let decoder = zt_decoder_from_parts(parts, expected_type)?;
    decoder.reconstruct().map_err(|e| ErrorDisplayed::Str {
        s: format!("zt reconstruct failed: {e}"),
    })
}

fn decode_ur_payload(ur_parts: &[String], expected_type: &str) -> Result<Vec<u8>, ErrorDisplayed> {
    use ur::ur::decode;

    let expected_prefix = format!("ur:{}/", expected_type);

    // Verify UR type
    let lower = ur_parts[0].to_lowercase();
    if !lower.starts_with(&expected_prefix) {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "Expected ur:{}/... got: {}",
                expected_type,
                ur_parts[0].chars().take(40).collect::<String>()
            ),
        });
    }

    if ur_parts.len() == 1 {
        let (_kind, data) = decode(&ur_parts[0]).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to decode UR: {:?}", e),
        })?;
        Ok(data)
    } else {
        let mut decoder = ur::ur::Decoder::default();
        for part in ur_parts {
            decoder.receive(part).map_err(|e| ErrorDisplayed::Str {
                s: format!("Failed to receive UR part: {:?}", e),
            })?;
            if decoder.complete() {
                break;
            }
        }

        if !decoder.complete() {
            return Err(ErrorDisplayed::Str {
                s: format!(
                    "Incomplete UR sequence: received {} parts but not complete",
                    ur_parts.len()
                ),
            });
        }

        decoder
            .message()
            .map_err(|e| ErrorDisplayed::Str {
                s: format!("Failed to get UR message: {:?}", e),
            })?
            .ok_or_else(|| ErrorDisplayed::Str {
                s: "UR decoder returned None despite being complete".to_string(),
            })
    }
}

/// High-level function to sign a PCZT from UR-encoded QR data
/// This is the main entry point for Zashi compatibility
fn sign_zcash_pczt_ur(
    seed_phrase: &str,
    account_index: u32,
    ur_parts: Vec<String>,
    max_fragment_len: u32,
) -> Result<Vec<String>, ErrorDisplayed> {
    // Decode UR to get PCZT bytes
    let pczt_bytes = decode_ur_zcash_pczt(ur_parts)?;

    // Sign the PCZT
    let signed_pczt_bytes = sign_zcash_pczt(seed_phrase, account_index, pczt_bytes)?;

    // Encode back to UR
    encode_signed_pczt_ur(signed_pczt_bytes, max_fragment_len)
}

/// Must be called once to initialize logging from Rust in development mode.
///
/// Do not use in production.
#[cfg(target_os = "android")]
fn init_logging(tag: String) {
    android_logger::init_once(
        android_logger::Config::default()
            .with_max_level(log::LevelFilter::Trace) // limit log level
            .with_tag(tag) // logs will show under mytag tag
            .with_filter(
                // configure messages for specific crate
                android_logger::FilterBuilder::new()
                    .parse("debug,hello::crate=error")
                    .build(),
            ),
    );
}

#[cfg(target_os = "ios")]
fn init_logging(_tag: String) {
    use uniffi::deps::log::LevelFilter;

    let _ = oslog::OsLogger::new("net.rotko.zigner")
        .level_filter(LevelFilter::Warn)
        .category_level_filter("SIGNER", LevelFilter::Trace)
        .init();
}

/// Placeholder to init logging on non-android platforms
///
/// TODO: is this used?
#[cfg(all(not(target_os = "ios"), not(target_os = "android")))]
fn init_logging(_tag: String) {
    env_logger::init();
}

// ── ed25519 identity (zafu-compatible) ──

/// Derive base identity pubkey (compatible with zafu identity.ts)
fn auth_derive_identity(seed_phrase: &str, index: u32) -> Result<String, ErrorDisplayed> {
    auth::derive_identity(seed_phrase, index).map_err(|e| ErrorDisplayed::Str { s: e })
}

/// Derive domain-scoped identity pubkey (per-service, no cross-site correlation)
fn auth_derive_domain_identity(
    seed_phrase: &str,
    domain: &str,
    index: u32,
) -> Result<String, ErrorDisplayed> {
    auth::derive_domain_identity(seed_phrase, domain, index)
        .map_err(|e| ErrorDisplayed::Str { s: e })
}

/// Sign auth challenge with base identity
fn auth_sign_challenge(
    seed_phrase: &str,
    index: u32,
    domain: &str,
    nonce: &str,
    timestamp: u64,
) -> Result<AuthSignResult, ErrorDisplayed> {
    auth::validate_challenge_freshness(timestamp).map_err(|e| ErrorDisplayed::Str { s: e })?;
    let challenge = auth::build_auth_challenge(domain, nonce, timestamp);
    let (pubkey, sig) = auth::sign_challenge(seed_phrase, index, &challenge)
        .map_err(|e| ErrorDisplayed::Str { s: e })?;
    Ok(AuthSignResult {
        pubkey_hex: pubkey,
        signature_hex: sig,
        domain: domain.to_string(),
    })
}

/// Sign auth challenge with domain-scoped identity
fn auth_sign_domain_challenge(
    seed_phrase: &str,
    domain: &str,
    index: u32,
    nonce: &str,
    timestamp: u64,
) -> Result<AuthSignResult, ErrorDisplayed> {
    auth::validate_challenge_freshness(timestamp).map_err(|e| ErrorDisplayed::Str { s: e })?;
    let challenge = auth::build_auth_challenge(domain, nonce, timestamp);
    let (pubkey, sig) = auth::sign_domain_challenge(seed_phrase, domain, index, &challenge)
        .map_err(|e| ErrorDisplayed::Str { s: e })?;
    Ok(AuthSignResult {
        pubkey_hex: pubkey,
        signature_hex: sig,
        domain: domain.to_string(),
    })
}

/// Verify an auth signature
fn auth_verify(
    pubkey_hex: &str,
    signature_hex: &str,
    domain: &str,
    nonce: &str,
    timestamp: u64,
) -> Result<bool, ErrorDisplayed> {
    let challenge = auth::build_auth_challenge(domain, nonce, timestamp);
    auth::verify_signature(pubkey_hex, signature_hex, &challenge)
        .map_err(|e| ErrorDisplayed::Str { s: e })
}

/// Parse a ZID sign challenge QR (JSON), sign it, return JSON response.
///
/// Input JSON: {"type":"zid-sign","v":1,"challenge":"<hex>","identity":"default",
///              "mode":"site","origin":"...","rotation":0,"algorithm":"ed25519",...}
/// Output JSON: {"type":"zid-resp","v":1,"signature":"<hex>","publicKey":"<hex>"}
fn sign_zid_qr(seed_phrase: &str, qr_json: &str) -> Result<String, ErrorDisplayed> {
    let parsed: serde_json::Value =
        serde_json::from_str(qr_json).map_err(|e| ErrorDisplayed::Str {
            s: format!("invalid JSON: {e}"),
        })?;

    let qr_type = parsed["type"].as_str().unwrap_or("");
    if qr_type != "zid-sign" {
        return Err(ErrorDisplayed::Str {
            s: format!("unexpected type: {qr_type}"),
        });
    }

    let challenge_hex = parsed["challenge"]
        .as_str()
        .ok_or_else(|| ErrorDisplayed::Str {
            s: "missing challenge".into(),
        })?;
    let identity = parsed["identity"].as_str().unwrap_or("default");
    let mode = parsed["mode"].as_str().unwrap_or("site");
    let origin = parsed["origin"].as_str().unwrap_or("");
    let rotation = parsed["rotation"].as_u64().unwrap_or(0) as u32;
    let algorithm = parsed["algorithm"].as_str().unwrap_or("ed25519");

    if algorithm != "ed25519" {
        return Err(ErrorDisplayed::Str {
            s: format!("unsupported algorithm: {algorithm}"),
        });
    }

    let challenge = hex::decode(challenge_hex).map_err(|e| ErrorDisplayed::Str {
        s: format!("bad challenge hex: {e}"),
    })?;

    let (signature, pubkey) =
        auth::sign_zid_challenge(seed_phrase, identity, mode, origin, rotation, &challenge)
            .map_err(|e| ErrorDisplayed::Str { s: e })?;

    Ok(format!(
        r#"{{"type":"zid-resp","v":1,"signature":"{signature}","publicKey":"{pubkey}"}}"#
    ))
}

/// Derive a 12-word hot wallet mnemonic from the master seed.
/// The hot wallet is deterministic and can be used for ZID, pro subscription,
/// and day-to-day spending in zafu.
fn derive_hot_wallet(seed_phrase: &str) -> Result<String, ErrorDisplayed> {
    auth::derive_hot_wallet_mnemonic(seed_phrase).map_err(|e| ErrorDisplayed::Str { s: e })
}

/// Derive hot wallet mnemonic and encode as ur:zafu-hot-wallet QR PNG.
/// Returns the PNG bytes for a single static QR code.
fn derive_hot_wallet_qr(seed_phrase: &str) -> Result<Vec<u8>, ErrorDisplayed> {
    let mnemonic =
        auth::derive_hot_wallet_mnemonic(seed_phrase).map_err(|e| ErrorDisplayed::Str { s: e })?;

    // Build CBOR: map(2) { 1: text(mnemonic), 2: text("default") }
    let identity = "default";
    let mut cbor = Vec::new();
    // map with 2 entries: major type 5, additional 2
    cbor.push(0xa2);
    // key 1 (uint)
    cbor.push(0x01);
    // text string: mnemonic
    encode_cbor_text(&mut cbor, &mnemonic);
    // key 2 (uint)
    cbor.push(0x02);
    // text string: identity
    encode_cbor_text(&mut cbor, identity);

    // Encode as UR bytewords with CRC32 checksum
    let ur_string = encode_ur("zafu-hot-wallet", &cbor);

    // Encode UR string as QR PNG
    encode_to_qr(ur_string.as_bytes(), true).map_err(|e| ErrorDisplayed::Str { s: e })
}

/// Encode a text string in CBOR (major type 3)
fn encode_cbor_text(buf: &mut Vec<u8>, text: &str) {
    let len = text.len();
    if len < 24 {
        buf.push(0x60 | len as u8);
    } else if len < 256 {
        buf.push(0x78);
        buf.push(len as u8);
    } else {
        buf.push(0x79);
        buf.push((len >> 8) as u8);
        buf.push(len as u8);
    }
    buf.extend_from_slice(text.as_bytes());
}

/// Encode bytes as ur:TYPE/bytewords string
fn encode_ur(ur_type: &str, payload: &[u8]) -> String {
    // CRC32 checksum
    let checksum = crc32(payload);
    let mut data = payload.to_vec();
    data.push((checksum >> 24) as u8);
    data.push((checksum >> 16) as u8);
    data.push((checksum >> 8) as u8);
    data.push(checksum as u8);

    // Minimal bytewords encoding (2 chars per byte)
    let bytewords: String = data.iter().map(|&b| minimal_byteword(b)).collect();
    format!("ur:{ur_type}/{bytewords}")
}

fn crc32(data: &[u8]) -> u32 {
    let mut crc: u32 = 0xffffffff;
    for &byte in data {
        crc ^= byte as u32;
        for _ in 0..8 {
            crc = if crc & 1 != 0 {
                (crc >> 1) ^ 0xedb88320
            } else {
                crc >> 1
            };
        }
    }
    crc ^ 0xffffffff
}

fn minimal_byteword(byte: u8) -> &'static str {
    const WORDS: [&str; 256] = [
        "ae", "ad", "ao", "ax", "aa", "ah", "am", "at", "ay", "as", "bk", "bd", "bn", "bt", "ba",
        "bs", "be", "by", "bg", "bw", "bb", "bz", "cm", "ch", "cs", "cf", "cy", "cw", "ce", "ca",
        "ck", "ct", "cx", "cl", "cp", "cn", "dk", "da", "ds", "di", "de", "dt", "dr", "dn", "dw",
        "dp", "dm", "dl", "dy", "eh", "ey", "eo", "ee", "ec", "en", "em", "et", "es", "ft", "fr",
        "fn", "fs", "fm", "fh", "fz", "fp", "fw", "fx", "fy", "fe", "fg", "fl", "fd", "ga", "ge",
        "gr", "gs", "gt", "gl", "gw", "gd", "gy", "gm", "gu", "gh", "go", "hf", "hg", "hd", "hk",
        "ht", "hp", "hh", "hl", "hy", "he", "hn", "hs", "id", "ia", "ie", "ih", "iy", "io", "is",
        "in", "im", "je", "jz", "jn", "jt", "jl", "jo", "js", "jp", "jk", "jy", "kp", "ko", "kt",
        "ks", "kk", "kn", "kg", "ke", "ki", "kb", "lb", "la", "ly", "lf", "ls", "lr", "lp", "ln",
        "lt", "lo", "ld", "le", "lu", "lk", "lg", "mn", "my", "mh", "me", "mo", "mu", "mw", "md",
        "mt", "ms", "mk", "nl", "ny", "nd", "ns", "nt", "nn", "ne", "nb", "oy", "oe", "ot", "ox",
        "on", "ol", "os", "pd", "pt", "pk", "py", "ps", "pm", "pl", "pe", "pf", "pa", "pr", "qd",
        "qz", "re", "rp", "rl", "ro", "rh", "rd", "rk", "rf", "ry", "rn", "rs", "rt", "se", "sa",
        "sr", "ss", "sk", "sw", "st", "sp", "so", "sg", "sb", "sf", "sn", "to", "tk", "ti", "tt",
        "td", "te", "ty", "tl", "tb", "ts", "tp", "ta", "tn", "uy", "uo", "ut", "ue", "ur", "vt",
        "vy", "vo", "vl", "ve", "vw", "va", "vd", "vs", "wl", "wd", "wm", "wp", "we", "wy", "ws",
        "wt", "wn", "wz", "wf", "wk", "yk", "yn", "yl", "ya", "yt", "zs", "zo", "zt", "zc", "ze",
        "zm",
    ];
    WORDS[byte as usize]
}

// ── contacts (address book) ──

fn store_contact(address: &str, label: &str, chain_id: &str) -> Result<(), ErrorDisplayed> {
    let db = get_db()?;
    let contact = db_handling::contacts::Contact {
        address: address.to_string(),
        label: label.to_string(),
        chain_id: chain_id.to_string(),
    };
    db_handling::contacts::store_contact(&db, &contact)
        .map_err(|e| ErrorDisplayed::from(format!("{e}")))
}

fn get_contacts() -> Result<String, ErrorDisplayed> {
    let db = get_db()?;
    let contacts = db_handling::contacts::get_contacts(&db)
        .map_err(|e| ErrorDisplayed::from(format!("{e}")))?;
    let json: Vec<serde_json::Value> = contacts
        .iter()
        .map(|c| {
            serde_json::json!({
                "address": c.address,
                "label": c.label,
                "chain_id": c.chain_id,
            })
        })
        .collect();
    serde_json::to_string(&json).map_err(|e| ErrorDisplayed::from(format!("{e}")))
}

fn get_contact_label(address: &str) -> Result<String, ErrorDisplayed> {
    let db = get_db()?;
    match db_handling::contacts::get_contact_label(&db, address)
        .map_err(|e| ErrorDisplayed::from(format!("{e}")))?
    {
        Some(label) => Ok(label),
        None => Ok(String::new()),
    }
}

fn delete_contact(address: &str) -> Result<(), ErrorDisplayed> {
    let db = get_db()?;
    db_handling::contacts::delete_contact(&db, address)
        .map_err(|e| ErrorDisplayed::from(format!("{e}")))
}

/// Export contacts as CBOR for QR display (unencrypted, for live scan)
fn export_contacts_cbor() -> Result<Vec<u8>, ErrorDisplayed> {
    let db = get_db()?;
    let contacts = db_handling::contacts::get_contacts(&db)
        .map_err(|e| ErrorDisplayed::from(format!("{e}")))?;
    Ok(backup::encode_contacts_cbor(&contacts))
}

/// Import contacts from CBOR (from QR scan)
fn import_contacts_cbor(cbor: Vec<u8>) -> Result<u32, ErrorDisplayed> {
    let contacts = backup::decode_contacts_cbor(&cbor).map_err(|e| ErrorDisplayed::Str { s: e })?;
    let db = get_db()?;
    let count = db_handling::contacts::import_contacts(&db, &contacts)
        .map_err(|e| ErrorDisplayed::from(format!("{e}")))?;
    Ok(count as u32)
}

/// Export contacts as UR-encoded animated QR string frames
fn export_contacts_ur(max_fragment_len: u32) -> Result<Vec<String>, ErrorDisplayed> {
    let cbor = export_contacts_cbor()?;
    ur_encode_cbor(&cbor, "zigner-contacts", max_fragment_len)
}

/// Export contacts as zoda transport frames (verified erasure coding)
fn export_contacts_zt(k: u8, n: u8) -> Result<Vec<String>, ErrorDisplayed> {
    let cbor = export_contacts_cbor()?;
    zt_encode(&cbor, "zigner-contacts", k, n)
}

/// Encode data as zoda transport QR frames: `zt:type/hex`
fn zt_encode(data: &[u8], zt_type: &str, k: u8, n: u8) -> Result<Vec<String>, ErrorDisplayed> {
    let (frames, _session_id) = zoda_vss::transport::Encoder::encode(data, k, n);
    let strings: Vec<String> = frames
        .iter()
        .map(|f| format!("zt:{}/{}", zt_type, hex::encode(f.to_bytes())))
        .collect();
    Ok(strings)
}

// ── encrypted backup ──

/// Create encrypted backup of all exportable data.
/// Returns UR-encoded frames of the encrypted bundle.
fn create_encrypted_backup(
    seed_name: &str,
    seed_phrase: &str,
    max_fragment_len: u32,
) -> Result<Vec<String>, ErrorDisplayed> {
    // Gather all backup data
    let accounts_json = bs_export_backup_data(seed_name, seed_phrase)?;

    let db = get_db()?;
    let contacts = db_handling::contacts::get_contacts(&db)
        .map_err(|e| ErrorDisplayed::from(format!("{e}")))?;
    let contacts_cbor = backup::encode_contacts_cbor(&contacts);

    // Build the backup bundle: CBOR map { 1: accounts_json(tstr), 2: contacts_cbor(bstr) }
    let mut bundle = Vec::new();
    bundle.push(0xa2); // map(2)

    // key 1: accounts (tstr — JSON string)
    bundle.push(0x01);
    let json_bytes = accounts_json.as_bytes();
    cbor_tstr_into(&mut bundle, json_bytes);

    // key 2: contacts (bstr — raw CBOR)
    bundle.push(0x02);
    cbor_bstr_into(&mut bundle, &contacts_cbor);

    // Encrypt
    let key = backup::derive_backup_key(seed_phrase);
    let encrypted = backup::encrypt(&key, &bundle).map_err(|e| ErrorDisplayed::Str { s: e })?;

    ur_encode_cbor(&encrypted, "zigner-backup", max_fragment_len)
}

/// Restore from encrypted backup.
/// Returns the accounts JSON for the caller to process.
fn restore_encrypted_backup(
    seed_phrase: &str,
    ur_parts: Vec<String>,
) -> Result<String, ErrorDisplayed> {
    let cbor_data = ur_decode_parts(&ur_parts)?;

    // Decrypt
    let key = backup::derive_backup_key(seed_phrase);
    let bundle = backup::decrypt(&key, &cbor_data).map_err(|e| ErrorDisplayed::Str { s: e })?;

    // Parse bundle CBOR: map { 1: accounts_tstr, 2: contacts_bstr }
    if bundle.is_empty() {
        return Err(ErrorDisplayed::Str {
            s: "empty bundle".to_string(),
        });
    }

    let (map_len, mut offset) =
        cbor_parse_map_header(&bundle).map_err(|e| ErrorDisplayed::Str { s: e })?;

    let mut accounts_json = String::new();

    for _ in 0..map_len {
        let (key, consumed) =
            cbor_parse_uint(&bundle, offset).map_err(|e| ErrorDisplayed::Str { s: e })?;
        offset = consumed;

        match key {
            1 => {
                // tstr: accounts JSON
                let (s, consumed) =
                    parse_cbor_tstr(&bundle, offset).map_err(|e| ErrorDisplayed::Str { s: e })?;
                offset = consumed;
                accounts_json = s;
            }
            2 => {
                // bstr: contacts CBOR
                let (bytes, consumed) =
                    parse_cbor_bytes(&bundle[offset..]).map_err(|e| ErrorDisplayed::Str {
                        s: format!("contacts bstr: {e}"),
                    })?;
                offset += consumed;
                // Import contacts
                let contacts = backup::decode_contacts_cbor(&bytes)
                    .map_err(|e| ErrorDisplayed::Str { s: e })?;
                let db = get_db()?;
                db_handling::contacts::import_contacts(&db, &contacts)
                    .map_err(|e| ErrorDisplayed::from(format!("{e}")))?;
            }
            _ => {
                // skip unknown value (handles any CBOR type)
                offset =
                    cbor_skip_any(&bundle, offset).map_err(|e| ErrorDisplayed::Str { s: e })?;
            }
        }
    }

    Ok(accounts_json)
}

// ── UR helpers ──

fn ur_encode_cbor(
    data: &[u8],
    ur_type: &str,
    max_fragment_len: u32,
) -> Result<Vec<String>, ErrorDisplayed> {
    // Wrap in CBOR bstr
    let mut cbor = Vec::with_capacity(data.len() + 5);
    cbor_bstr_into(&mut cbor, data);

    if max_fragment_len == 0 || cbor.len() <= max_fragment_len as usize {
        let ur_string = ur::ur::encode(&cbor, &ur::Type::Custom(ur_type));
        Ok(vec![ur_string])
    } else {
        let mut encoder =
            ur::ur::Encoder::new(&cbor, max_fragment_len as usize, ur_type).map_err(|e| {
                ErrorDisplayed::Str {
                    s: format!("UR encoder: {e:?}"),
                }
            })?;
        let count = encoder.fragment_count() * 2;
        let mut frames = Vec::with_capacity(count);
        for _ in 0..count {
            let part = encoder.next_part().map_err(|e| ErrorDisplayed::Str {
                s: format!("UR part: {e:?}"),
            })?;
            frames.push(part);
        }
        Ok(frames)
    }
}

fn ur_decode_parts(ur_parts: &[String]) -> Result<Vec<u8>, ErrorDisplayed> {
    if ur_parts.is_empty() {
        return Err(ErrorDisplayed::Str {
            s: "no UR parts".to_string(),
        });
    }
    if ur_parts.len() == 1 {
        let (_, cbor) = ur::ur::decode(&ur_parts[0]).map_err(|e| ErrorDisplayed::Str {
            s: format!("UR decode: {e:?}"),
        })?;
        let (bytes, _) = parse_cbor_bytes(&cbor).map_err(|e| ErrorDisplayed::Str {
            s: format!("CBOR: {e}"),
        })?;
        return Ok(bytes);
    }
    let mut decoder = ur::ur::Decoder::default();
    for part in ur_parts {
        decoder.receive(part).map_err(|e| ErrorDisplayed::Str {
            s: format!("UR receive: {e:?}"),
        })?;
        if decoder.complete() {
            let cbor = decoder
                .message()
                .map_err(|e| ErrorDisplayed::Str {
                    s: format!("UR message: {e:?}"),
                })?
                .ok_or_else(|| ErrorDisplayed::Str {
                    s: "UR complete but no message".to_string(),
                })?;
            let (bytes, _) = parse_cbor_bytes(&cbor).map_err(|e| ErrorDisplayed::Str {
                s: format!("CBOR: {e}"),
            })?;
            return Ok(bytes);
        }
    }
    Err(ErrorDisplayed::Str {
        s: "incomplete UR".to_string(),
    })
}

fn cbor_tstr_into(buf: &mut Vec<u8>, s: &[u8]) {
    let len = s.len();
    if len <= 23 {
        buf.push(0x60 | len as u8);
    } else if len <= 0xff {
        buf.push(0x78);
        buf.push(len as u8);
    } else if len <= 0xffff {
        buf.push(0x79);
        buf.extend_from_slice(&(len as u16).to_be_bytes());
    } else {
        buf.push(0x7a);
        buf.extend_from_slice(&(len as u32).to_be_bytes());
    }
    buf.extend_from_slice(s);
}

fn cbor_bstr_into(buf: &mut Vec<u8>, data: &[u8]) {
    let len = data.len();
    if len <= 23 {
        buf.push(0x40 | len as u8);
    } else if len <= 0xff {
        buf.push(0x58);
        buf.push(len as u8);
    } else if len <= 0xffff {
        buf.push(0x59);
        buf.extend_from_slice(&(len as u16).to_be_bytes());
    } else {
        buf.push(0x5a);
        buf.extend_from_slice(&(len as u32).to_be_bytes());
    }
    buf.extend_from_slice(data);
}

fn parse_cbor_tstr(data: &[u8], offset: usize) -> Result<(String, usize), String> {
    if offset >= data.len() {
        return Err("truncated tstr".to_string());
    }
    let first = data[offset];
    let major = first >> 5;
    if major != 3 {
        return Err(format!("expected tstr, got major {major}"));
    }
    let additional = first & 0x1f;
    let (len, header_end) = match additional {
        0..=23 => (additional as usize, offset + 1),
        24 => {
            if offset + 2 > data.len() {
                return Err("truncated".to_string());
            }
            (data[offset + 1] as usize, offset + 2)
        }
        25 => {
            if offset + 3 > data.len() {
                return Err("truncated".to_string());
            }
            (
                u16::from_be_bytes([data[offset + 1], data[offset + 2]]) as usize,
                offset + 3,
            )
        }
        _ => return Err(format!("unsupported tstr additional {additional}")),
    };
    if header_end + len > data.len() {
        return Err("truncated tstr data".to_string());
    }
    let s = std::str::from_utf8(&data[header_end..header_end + len])
        .map_err(|e| format!("invalid UTF-8: {e}"))?;
    Ok((s.to_string(), header_end + len))
}

/// Parse a CBOR map header, returning (length, offset after header).
fn cbor_parse_map_header(data: &[u8]) -> Result<(usize, usize), String> {
    if data.is_empty() {
        return Err("empty CBOR".to_string());
    }
    let first = data[0];
    let major = first >> 5;
    if major != 5 {
        return Err(format!("expected map (major 5), got major {major}"));
    }
    let additional = first & 0x1f;
    cbor_parse_length(data, 0, additional).map(|(len, off)| (len as usize, off))
}

/// Parse a CBOR unsigned integer at offset.
fn cbor_parse_uint(data: &[u8], offset: usize) -> Result<(u64, usize), String> {
    if offset >= data.len() {
        return Err("truncated uint".to_string());
    }
    let first = data[offset];
    let major = first >> 5;
    if major != 0 {
        return Err(format!("expected uint (major 0), got major {major}"));
    }
    cbor_parse_length(data, offset, first & 0x1f)
}

/// Decode CBOR additional info into a length/value.
fn cbor_parse_length(data: &[u8], offset: usize, additional: u8) -> Result<(u64, usize), String> {
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
        26 => {
            if offset + 5 > data.len() {
                return Err("truncated length".to_string());
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
        _ => Err(format!("unsupported CBOR additional {additional}")),
    }
}

/// Skip a single CBOR value of any type at offset.
fn cbor_skip_any(data: &[u8], offset: usize) -> Result<usize, String> {
    if offset >= data.len() {
        return Err("truncated CBOR value".to_string());
    }
    let first = data[offset];
    let major = first >> 5;
    let additional = first & 0x1f;
    let (content_len, header_end) = cbor_parse_length(data, offset, additional)?;
    let content_len = content_len as usize;
    match major {
        0 | 1 => Ok(header_end),               // uint/negint
        2 | 3 => Ok(header_end + content_len), // bstr/tstr
        4 => {
            let mut pos = header_end;
            for _ in 0..content_len {
                pos = cbor_skip_any(data, pos)?;
            }
            Ok(pos)
        }
        5 => {
            let mut pos = header_end;
            for _ in 0..content_len {
                pos = cbor_skip_any(data, pos)?; // key
                pos = cbor_skip_any(data, pos)?; // value
            }
            Ok(pos)
        }
        7 => Ok(offset + 1), // simple (bool/null)
        _ => Err(format!("unsupported CBOR major {major}")),
    }
}

// ── FROST threshold multisig ──

fn frost_dkg_part1(max_signers: u16, min_signers: u16) -> Result<String, ErrorDisplayed> {
    frost_multisig::frost_dkg_part1(max_signers, min_signers)
        .map_err(|e| ErrorDisplayed::Str { s: e })
}

fn frost_dkg_part2(secret_hex: &str, peer_broadcasts_json: &str) -> Result<String, ErrorDisplayed> {
    frost_multisig::frost_dkg_part2(secret_hex, peer_broadcasts_json)
        .map_err(|e| ErrorDisplayed::Str { s: e })
}

fn frost_dkg_part3(
    secret_hex: &str,
    round1_broadcasts_json: &str,
    round2_packages_json: &str,
) -> Result<String, ErrorDisplayed> {
    frost_multisig::frost_dkg_part3(secret_hex, round1_broadcasts_json, round2_packages_json)
        .map_err(|e| ErrorDisplayed::Str { s: e })
}

fn frost_sign_round1(
    ephemeral_seed_hex: &str,
    key_package_hex: &str,
) -> Result<String, ErrorDisplayed> {
    frost_multisig::frost_sign_round1(ephemeral_seed_hex, key_package_hex)
        .map_err(|e| ErrorDisplayed::Str { s: e })
}

fn frost_spend_sign_round2(
    key_package_hex: &str,
    nonces_hex: &str,
    sighash_hex: &str,
    alpha_hex: &str,
    commitments_json: &str,
) -> Result<String, ErrorDisplayed> {
    frost_multisig::frost_spend_sign_round2(
        key_package_hex,
        nonces_hex,
        sighash_hex,
        alpha_hex,
        commitments_json,
    )
    .map_err(|e| ErrorDisplayed::Str { s: e })
}

fn frost_spend_sign_round2_signed(
    ephemeral_seed_hex: &str,
    key_package_hex: &str,
    nonces_hex: &str,
    sighash_hex: &str,
    alpha_hex: &str,
    commitments_json: &str,
) -> Result<String, ErrorDisplayed> {
    frost_multisig::frost_spend_sign_round2_signed(
        ephemeral_seed_hex,
        key_package_hex,
        nonces_hex,
        sighash_hex,
        alpha_hex,
        commitments_json,
    )
    .map_err(|e| ErrorDisplayed::Str { s: e })
}

fn frost_spend_sign_actions(
    key_package_hex: &str,
    nonces_hex: &str,
    sighash_hex: &str,
    alphas_json: &str,
    commitments_json: &str,
) -> Result<String, ErrorDisplayed> {
    frost_multisig::frost_spend_sign_actions(
        key_package_hex,
        nonces_hex,
        sighash_hex,
        alphas_json,
        commitments_json,
    )
    .map_err(|e| ErrorDisplayed::Str { s: e })
}

fn frost_derive_address_raw(
    public_key_package_hex: &str,
    diversifier_index: u32,
) -> Result<String, ErrorDisplayed> {
    frost_multisig::frost_derive_address_raw(public_key_package_hex, diversifier_index)
        .map_err(|e| ErrorDisplayed::Str { s: e })
}

fn frost_verify_pczt(
    pczt_hex: &str,
    claimed_sighash_hex: &str,
    orchard_fvk_uview: &str,
) -> Result<String, ErrorDisplayed> {
    frost_multisig::frost_verify_pczt(pczt_hex, claimed_sighash_hex, orchard_fvk_uview)
        .map_err(|e| ErrorDisplayed::Str { s: e })
}

// ── FROST wallet storage ──

// Signature mirrors the UDL entry — the argument list is the wire format.
#[allow(clippy::too_many_arguments)]
fn frost_store_wallet(
    key_package_hex: &str,
    public_key_package_hex: &str,
    ephemeral_seed_hex: &str,
    label: &str,
    min_signers: u16,
    max_signers: u16,
    mainnet: bool,
    orchard_fvk_uview: &str,
    address: &str,
    relay_url: &str,
) -> Result<String, ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    // Empty strings → None, so older callers that pass "" don't poison the
    // optional fields with empty data.
    let none_if_empty = |s: &str| {
        if s.is_empty() {
            None
        } else {
            Some(s.to_string())
        }
    };

    let data = db_handling::frost::FrostWalletData {
        key_package_hex: key_package_hex.to_string(),
        public_key_package_hex: public_key_package_hex.to_string(),
        ephemeral_seed_hex: ephemeral_seed_hex.to_string(),
        label: label.to_string(),
        min_signers,
        max_signers,
        mainnet,
        created_at: std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_secs(),
        orchard_fvk_uview: none_if_empty(orchard_fvk_uview),
        address: none_if_empty(address),
        relay_url: none_if_empty(relay_url),
    };

    db_handling::frost::store_frost_wallet(database, &data).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to store FROST wallet: {e}"),
    })
}

fn frost_list_wallets() -> Result<Vec<FrostWalletSummaryFFI>, ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    let wallets =
        db_handling::frost::list_frost_wallets(database).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to list FROST wallets: {e}"),
        })?;

    Ok(wallets
        .into_iter()
        .map(|w| FrostWalletSummaryFFI {
            wallet_id: w.wallet_id,
            label: w.label,
            min_signers: w.min_signers,
            max_signers: w.max_signers,
            mainnet: w.mainnet,
            created_at: w.created_at,
        })
        .collect())
}

fn frost_load_wallet(wallet_id: &str) -> Result<String, ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    let data = db_handling::frost::get_frost_wallet(database, wallet_id)
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to load FROST wallet: {e}"),
        })?
        .ok_or_else(|| ErrorDisplayed::Str {
            s: format!("FROST wallet not found: {wallet_id}"),
        })?;

    // Include the optional public-derived metadata fields so callers building
    // backup payloads or "send to zafu" QRs don't need a separate FFI.
    serde_json::to_string(&serde_json::json!({
        "key_package": data.key_package_hex,
        "ephemeral_seed": data.ephemeral_seed_hex,
        "public_key_package": data.public_key_package_hex,
        "label": data.label,
        "min_signers": data.min_signers,
        "max_signers": data.max_signers,
        "mainnet": data.mainnet,
        "created_at": data.created_at,
        "orchard_fvk_uview": data.orchard_fvk_uview,
        "address": data.address,
        "relay_url": data.relay_url,
    }))
    .map_err(|e| ErrorDisplayed::Str {
        s: format!("Serialize: {e}"),
    })
}

fn frost_delete_wallet(wallet_id: &str) -> Result<(), ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    db_handling::frost::delete_frost_wallet(database, wallet_id).map_err(|e| ErrorDisplayed::Str {
        s: format!("Failed to delete FROST wallet: {e}"),
    })
}

/// Rename a FROST wallet (label only, no key material touched).
fn frost_rename_wallet(wallet_id: &str, new_label: &str) -> Result<(), ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    db_handling::frost::rename_frost_wallet(database, wallet_id, new_label).map_err(|e| {
        ErrorDisplayed::Str {
            s: format!("Failed to rename FROST wallet: {e}"),
        }
    })
}

/// Derive UFVK + address from a FROST public_key_package and host-broadcast sk.
/// Returns JSON `{"orchard_fvk_uview": "...", "address": "..."}`. Used during
/// DKG so zigner can compute its own metadata locally instead of requiring
/// a post-DKG round-trip from zafu.
fn frost_derive_metadata(
    public_key_package_hex: &str,
    sk_hex: &str,
    mainnet: bool,
    diversifier_index: u32,
) -> Result<String, ErrorDisplayed> {
    frost_multisig::frost_derive_metadata(
        public_key_package_hex,
        sk_hex,
        mainnet,
        diversifier_index,
    )
    .map_err(|e| ErrorDisplayed::Str { s: e })
}

/// Update an existing FROST wallet's public-derived metadata (orchard FVK,
/// address, relay URL). Called after DKG when zafu has computed these from
/// the public_key_package + sk and hands them back to zigner so the airgap
/// re-add QR can carry them later.
fn frost_set_wallet_metadata(
    wallet_id: &str,
    orchard_fvk_uview: &str,
    address: &str,
    relay_url: &str,
) -> Result<(), ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    db_handling::frost::update_wallet_metadata(
        database,
        wallet_id,
        orchard_fvk_uview,
        address,
        relay_url,
    )
    .map_err(|e| ErrorDisplayed::Str {
        s: format!("update FROST wallet metadata: {e}"),
    })
}

// ── FROST backup encryption (zigner ↔ zafu interoperable envelope) ──

fn frost_export_backup_envelope(
    wallet_id: &str,
    passphrase: &str,
) -> Result<String, ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    let data = db_handling::frost::get_frost_wallet(database, wallet_id)
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("load FROST wallet: {e}"),
        })?
        .ok_or_else(|| ErrorDisplayed::Str {
            s: format!("FROST wallet not found: {wallet_id}"),
        })?;

    let payload = frost_backup::PlaintextPayload {
        version: 1,
        kind: "frost-share".into(),
        label: data.label,
        public_key_package: data.public_key_package_hex,
        key_package: data.key_package_hex,
        ephemeral_seed: data.ephemeral_seed_hex,
        threshold: data.min_signers,
        max_signers: data.max_signers,
        mainnet: data.mainnet,
        orchard_fvk: data.orchard_fvk_uview,
        address: data.address,
        relay_url: data.relay_url,
        created_at: data.created_at,
    };

    frost_backup::seal_envelope(&payload, passphrase).map_err(|e| ErrorDisplayed::Str { s: e })
}

fn frost_import_backup_envelope(
    envelope_json: &str,
    passphrase: &str,
) -> Result<String, ErrorDisplayed> {
    let payload = frost_backup::open_envelope(envelope_json, passphrase)
        .map_err(|e| ErrorDisplayed::Str { s: e })?;

    // Cross-check envelope-supplied threshold/participant counts against the
    // PublicKeyPackage itself. An attacker who controls the envelope (correct
    // PKP, lowered threshold) would otherwise downgrade the m-of-n the user
    // originally consented to without changing the spend key.
    let (pkp_min, pkp_max) = verify_envelope_threshold(
        &payload.public_key_package,
        &payload.key_package,
        payload.threshold,
        payload.max_signers,
    )?;

    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    let data = db_handling::frost::FrostWalletData {
        key_package_hex: payload.key_package,
        public_key_package_hex: payload.public_key_package,
        ephemeral_seed_hex: payload.ephemeral_seed,
        label: payload.label,
        min_signers: pkp_min,
        max_signers: pkp_max,
        mainnet: payload.mainnet,
        created_at: payload.created_at,
        orchard_fvk_uview: payload.orchard_fvk,
        address: payload.address,
        relay_url: payload.relay_url,
    };

    db_handling::frost::store_frost_wallet(database, &data).map_err(|e| ErrorDisplayed::Str {
        s: format!("store imported wallet: {e}"),
    })
}

/// Parse the KeyPackage + PublicKeyPackage hex and assert they agree with
/// the envelope's claimed signing policy. Returns the cryptographically
/// authoritative `(min_signers, max_signers)` for storage so future code
/// reads canonical values, not envelope claims.
///
/// Threat: a hostile envelope can ship correct KeyPackage + PublicKeyPackage
/// material with tampered envelope-level threshold/max_signers fields. The
/// UI then displays a different m-of-n than the FROST polynomial encodes.
/// Cross-check pins the displayed policy to the cryptographic ground truth.
fn verify_envelope_threshold(
    public_key_package_hex: &str,
    key_package_hex: &str,
    claimed_threshold: u16,
    claimed_max_signers: u16,
) -> Result<(u16, u16), ErrorDisplayed> {
    use frost_spend::frost_keys::{KeyPackage, PublicKeyPackage};
    use frost_spend::orchestrate::from_hex;
    let pubkeys: PublicKeyPackage =
        from_hex(public_key_package_hex).map_err(|e| ErrorDisplayed::Str {
            s: format!("parse public_key_package: {e}"),
        })?;
    let key_pkg: KeyPackage = from_hex(key_package_hex).map_err(|e| ErrorDisplayed::Str {
        s: format!("parse key_package: {e}"),
    })?;
    let pkp_max: u16 =
        pubkeys
            .verifying_shares()
            .len()
            .try_into()
            .map_err(|_| ErrorDisplayed::Str {
                s: "PublicKeyPackage participant count exceeds u16".into(),
            })?;
    let kp_min: u16 = *key_pkg.min_signers();
    if kp_min != claimed_threshold {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "envelope threshold {claimed_threshold} disagrees with KeyPackage min_signers {kp_min}"
            ),
        });
    }
    if pkp_max != claimed_max_signers {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "envelope max_signers {claimed_max_signers} disagrees with PublicKeyPackage participant count {pkp_max}"
            ),
        });
    }
    Ok((kp_min, pkp_max))
}

/// Encrypt every FROST wallet on this device into a single envelope.
/// Returns the envelope JSON string. Caller writes to a `.json` file.
/// Gather every FROST share on this device into transport-independent form.
///
/// Shared by both backup transports - the passphrase envelope and the age
/// path - so the two can never disagree about what a backup contains.
fn collect_all_frost_shares(
    database: &sled::Db,
) -> Result<Vec<frost_backup::ShareEntry>, ErrorDisplayed> {
    let summaries =
        db_handling::frost::list_frost_wallets(database).map_err(|e| ErrorDisplayed::Str {
            s: format!("list FROST wallets: {e}"),
        })?;

    if summaries.is_empty() {
        return Err(ErrorDisplayed::Str {
            s: "no FROST wallets to backup".into(),
        });
    }

    let mut shares = Vec::with_capacity(summaries.len());
    for s in summaries {
        let data = db_handling::frost::get_frost_wallet(database, &s.wallet_id)
            .map_err(|e| ErrorDisplayed::Str {
                s: format!("load FROST wallet {}: {}", s.wallet_id, e),
            })?
            .ok_or_else(|| ErrorDisplayed::Str {
                s: format!("FROST wallet not found: {}", s.wallet_id),
            })?;
        shares.push(frost_backup::ShareEntry {
            label: data.label,
            public_key_package: data.public_key_package_hex,
            key_package: data.key_package_hex,
            ephemeral_seed: data.ephemeral_seed_hex,
            threshold: data.min_signers,
            max_signers: data.max_signers,
            mainnet: data.mainnet,
            orchard_fvk: data.orchard_fvk_uview,
            address: data.address,
            relay_url: data.relay_url,
            created_at: data.created_at,
        });
    }
    Ok(shares)
}

fn frost_export_all_backup_envelope(passphrase: &str) -> Result<String, ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;
    let shares = collect_all_frost_shares(database)?;
    frost_backup::seal_batch_envelope(&shares, passphrase).map_err(|e| ErrorDisplayed::Str { s: e })
}

/// Decrypt a batch envelope and import every share inside. Returns JSON
/// `{ "imported": N, "skipped": M }` so the caller can show a summary.
/// Skipped = wallets whose publicKeyPackage already exists locally.
fn frost_import_all_backup_envelope(
    envelope_json: &str,
    passphrase: &str,
) -> Result<String, ErrorDisplayed> {
    let shares = frost_backup::open_batch_envelope(envelope_json, passphrase)
        .map_err(|e| ErrorDisplayed::Str { s: e })?;
    import_frost_shares(shares)
}

/// Store a decrypted share list. The half of import that is the same however
/// the bytes were protected - including the envelope-vs-KeyPackage threshold
/// cross-check, which must not be skippable by arriving via a new transport.
fn import_frost_shares(shares: Vec<frost_backup::ShareEntry>) -> Result<String, ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    let mut imported = 0u32;
    let mut skipped = 0u32;
    for share in shares {
        // Same envelope-vs-KeyPackage/PKP cross-check as single import.
        let (pkp_min, pkp_max) = verify_envelope_threshold(
            &share.public_key_package,
            &share.key_package,
            share.threshold,
            share.max_signers,
        )?;
        let id = db_handling::frost::wallet_id_hex(&share.public_key_package);
        let already = db_handling::frost::get_frost_wallet(database, &id)
            .map_err(|e| ErrorDisplayed::Str {
                s: format!("lookup: {e}"),
            })?
            .is_some();
        if already {
            skipped += 1;
            continue;
        }
        let data = db_handling::frost::FrostWalletData {
            key_package_hex: share.key_package,
            public_key_package_hex: share.public_key_package,
            ephemeral_seed_hex: share.ephemeral_seed,
            label: share.label,
            min_signers: pkp_min,
            max_signers: pkp_max,
            mainnet: share.mainnet,
            created_at: share.created_at,
            orchard_fvk_uview: share.orchard_fvk,
            address: share.address,
            relay_url: share.relay_url,
        };
        db_handling::frost::store_frost_wallet(database, &data).map_err(|e| {
            ErrorDisplayed::Str {
                s: format!("store imported wallet: {e}"),
            }
        })?;
        imported += 1;
    }

    serde_json::to_string(&serde_json::json!({
        "imported": imported,
        "skipped": skipped,
    }))
    .map_err(|e| ErrorDisplayed::Str {
        s: format!("serialize result: {e}"),
    })
}

// ── FROST backup, addressed to public keys instead of to a passphrase ──

/// Fixed derivation index for the backup recipient.
///
/// Pinned rather than threaded up to the UI: a different index is a different
/// key, so an index that disagreed between export and import would produce a
/// backup that simply never opens, with nothing to indicate why.
const AGE_BACKUP_INDEX: u32 = 0;

/// This device's own recipient line, for the UI to display and share.
fn age_device_recipient(seed_phrase: &str) -> Result<String, ErrorDisplayed> {
    age_backup::device_recipient(seed_phrase, AGE_BACKUP_INDEX)
        .map_err(|e| ErrorDisplayed::Str { s: e })
}

/// Export every FROST share, encrypted to public keys rather than under a
/// passphrase. Returns armored age text for the caller to write to a file.
///
/// This device's own recipient is always included, so the seed alone can
/// always restore it. `extra_recipients` adds co-signers or a second device -
/// which is the point, since a backup only this seed can open fails at the
/// same moment the seed does.
fn frost_export_all_backup_age(
    seed_phrase: &str,
    extra_recipients: Vec<String>,
) -> Result<String, ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;
    let shares = collect_all_frost_shares(database)?;
    let plaintext =
        frost_backup::batch_plaintext(&shares).map_err(|e| ErrorDisplayed::Str { s: e })?;

    let mut recipients = vec![age_backup::device_recipient(seed_phrase, AGE_BACKUP_INDEX)
        .map_err(|e| ErrorDisplayed::Str { s: e })?];
    // A duplicate recipient is not an error in age, but it does put a
    // confusing second stanza in the file for the same key. Drop the case
    // where the user pasted this device's own line by hand.
    for r in extra_recipients {
        let r = r.trim().to_string();
        if !r.is_empty() && !recipients.contains(&r) {
            recipients.push(r);
        }
    }

    age_backup::encrypt_to_recipients(&plaintext, &recipients)
        .map_err(|e| ErrorDisplayed::Str { s: e })
}

/// Import an age-encrypted batch backup addressed to this device.
/// Returns the same `{ "imported": N, "skipped": M }` as the passphrase path.
fn frost_import_all_backup_age(seed_phrase: &str, armored: &str) -> Result<String, ErrorDisplayed> {
    let plaintext = age_backup::decrypt_with_device_key(seed_phrase, AGE_BACKUP_INDEX, armored)
        .map_err(|e| ErrorDisplayed::Str { s: e })?;
    let shares = frost_backup::parse_batch_plaintext(&plaintext)
        .map_err(|e| ErrorDisplayed::Str { s: e })?;
    import_frost_shares(shares)
}

// ── anchor verifier registry FFI ──

fn list_anchor_verifiers() -> Result<Vec<AnchorVerifierFFI>, ErrorDisplayed> {
    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;
    let entries = db_handling::anchor_verifiers::list_verifiers(database).map_err(|e| {
        ErrorDisplayed::Str {
            s: format!("list verifiers: {e}"),
        }
    })?;
    Ok(entries
        .into_iter()
        .map(|e| AnchorVerifierFFI {
            pubkey_hex: e.pubkey_hex,
            label: e.label,
            source: match e.source {
                db_handling::anchor_verifiers::VerifierSource::BuiltIn => "built-in".to_string(),
                db_handling::anchor_verifiers::VerifierSource::User => "user".to_string(),
            },
            added_at: e.added_at,
            enabled: e.enabled,
        })
        .collect())
}

/// Decode a `ur:zcash-verifier-key` UR (CBOR map: 1=pubkey, 2=label) into
/// a preview struct. Does NOT add the verifier — the native layer must
/// show a confirmation screen with the full hex pubkey before calling
/// add_anchor_verifier.
fn decode_verifier_key_qr(ur_parts: Vec<String>) -> Result<AnchorVerifierImport, ErrorDisplayed> {
    if ur_parts.is_empty() {
        return Err(ErrorDisplayed::Str {
            s: "no QR parts".into(),
        });
    }
    let cbor = decode_qr_payload(&ur_parts, "zcash-verifier-key")?;

    // CBOR: a2 01 (bstr 32 bytes) 02 (tstr label)
    if cbor.len() < 3 || cbor[0] != 0xa2 {
        return Err(ErrorDisplayed::Str {
            s: "verifier-key CBOR must be a 2-entry map".into(),
        });
    }
    let mut pubkey: Option<[u8; 32]> = None;
    let mut label: Option<String> = None;
    let mut o = 1;
    for _ in 0..2 {
        let key = *cbor.get(o).ok_or_else(|| ErrorDisplayed::Str {
            s: "truncated key".into(),
        })?;
        o += 1;
        match key {
            0x01 => {
                // bstr 32: 0x58 0x20 || 32 bytes (or 0x40+len for short)
                let prefix = *cbor.get(o).ok_or_else(|| ErrorDisplayed::Str {
                    s: "truncated pubkey header".into(),
                })?;
                let body_start = if prefix == 0x58 {
                    let len = *cbor.get(o + 1).ok_or_else(|| ErrorDisplayed::Str {
                        s: "truncated pubkey length".into(),
                    })?;
                    if len != 32 {
                        return Err(ErrorDisplayed::Str {
                            s: format!("expected 32-byte pubkey, got {len}"),
                        });
                    }
                    o + 2
                } else if prefix == 0x40 + 32 {
                    o + 1
                } else {
                    return Err(ErrorDisplayed::Str {
                        s: format!("expected bstr(32), got prefix 0x{prefix:02x}"),
                    });
                };
                let end = body_start
                    .checked_add(32)
                    .ok_or_else(|| ErrorDisplayed::Str {
                        s: "pubkey overflow".into(),
                    })?;
                let bytes = cbor
                    .get(body_start..end)
                    .ok_or_else(|| ErrorDisplayed::Str {
                        s: "pubkey truncated".into(),
                    })?;
                let mut pk = [0u8; 32];
                pk.copy_from_slice(bytes);
                pubkey = Some(pk);
                o = end;
            }
            0x02 => {
                // tstr: prefix 0x60+len (short) or 0x78 len (medium)
                let prefix = *cbor.get(o).ok_or_else(|| ErrorDisplayed::Str {
                    s: "truncated label header".into(),
                })?;
                let (body_start, body_len) = if (0x60..0x78).contains(&prefix) {
                    (o + 1, (prefix - 0x60) as usize)
                } else if prefix == 0x78 {
                    let len = *cbor.get(o + 1).ok_or_else(|| ErrorDisplayed::Str {
                        s: "truncated label length".into(),
                    })?;
                    (o + 2, len as usize)
                } else {
                    return Err(ErrorDisplayed::Str {
                        s: format!("expected tstr label, got prefix 0x{prefix:02x}"),
                    });
                };
                if body_len > 128 {
                    return Err(ErrorDisplayed::Str {
                        s: format!("label too long ({body_len} bytes, max 128)"),
                    });
                }
                let end = body_start
                    .checked_add(body_len)
                    .ok_or_else(|| ErrorDisplayed::Str {
                        s: "label overflow".into(),
                    })?;
                let bytes = cbor
                    .get(body_start..end)
                    .ok_or_else(|| ErrorDisplayed::Str {
                        s: "label truncated".into(),
                    })?;
                label =
                    Some(
                        String::from_utf8(bytes.to_vec()).map_err(|e| ErrorDisplayed::Str {
                            s: format!("label utf-8: {e}"),
                        })?,
                    );
                o = end;
            }
            other => {
                return Err(ErrorDisplayed::Str {
                    s: format!("unexpected CBOR map key {other}"),
                });
            }
        }
    }
    let pubkey = pubkey.ok_or_else(|| ErrorDisplayed::Str {
        s: "missing pubkey field".into(),
    })?;
    let label = label.unwrap_or_default();
    Ok(AnchorVerifierImport {
        pubkey_hex: hex::encode(pubkey),
        label,
    })
}

fn add_anchor_verifier(pubkey_hex: &str, label: &str) -> Result<(), ErrorDisplayed> {
    let bytes = hex::decode(pubkey_hex).map_err(|e| ErrorDisplayed::Str {
        s: format!("bad pubkey hex: {e}"),
    })?;
    if bytes.len() != 32 {
        return Err(ErrorDisplayed::Str {
            s: format!("pubkey must be 32 bytes, got {}", bytes.len()),
        });
    }
    let mut pk = [0u8; 32];
    pk.copy_from_slice(&bytes);

    let trimmed = label.trim();
    if trimmed.is_empty() {
        return Err(ErrorDisplayed::Str {
            s: "label must not be empty".into(),
        });
    }
    if trimmed.len() > 128 {
        return Err(ErrorDisplayed::Str {
            s: format!("label too long ({} bytes, max 128)", trimmed.len()),
        });
    }

    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;
    db_handling::anchor_verifiers::add_verifier(
        database,
        &pk,
        trimmed,
        db_handling::anchor_verifiers::VerifierSource::User,
    )
    .map_err(|e| ErrorDisplayed::Str {
        s: format!("add verifier: {e}"),
    })
}

/// Refuse the change if it would leave the device with zero enabled
/// verifiers AND the sticky FROST attestation flag is set, unless the
/// caller explicitly passes `force = true` (a clear user opt-in).
fn check_lockout(
    database: &sled::Db,
    upcoming: Vec<[u8; 32]>,
    force: bool,
) -> Result<(), ErrorDisplayed> {
    if force {
        return Ok(());
    }
    let attestation_required =
        db_handling::zcash::is_attestation_required(database).unwrap_or(false);
    if !attestation_required {
        return Ok(());
    }
    if !upcoming.is_empty() {
        return Ok(());
    }
    Err(ErrorDisplayed::Str {
        s: "Refusing to leave the device with no enabled verifiers — \
            attestation is required (FROST history) and removing this \
            entry would block all future note imports. Add another verifier \
            first, or pass force=true if you understand the consequence."
            .into(),
    })
}

fn remove_anchor_verifier(pubkey_hex: &str, force: bool) -> Result<(), ErrorDisplayed> {
    let bytes = hex::decode(pubkey_hex).map_err(|e| ErrorDisplayed::Str {
        s: format!("bad pubkey hex: {e}"),
    })?;
    if bytes.len() != 32 {
        return Err(ErrorDisplayed::Str {
            s: "pubkey must be 32 bytes".into(),
        });
    }
    let mut pk = [0u8; 32];
    pk.copy_from_slice(&bytes);

    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    let remaining: Vec<[u8; 32]> = db_handling::anchor_verifiers::enabled_pubkeys(database)
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("enabled list: {e}"),
        })?
        .into_iter()
        .filter(|k| k != &pk)
        .collect();
    check_lockout(database, remaining, force)?;

    db_handling::anchor_verifiers::remove_verifier(database, &pk).map_err(|e| ErrorDisplayed::Str {
        s: format!("remove verifier: {e}"),
    })
}

fn set_anchor_verifier_enabled(
    pubkey_hex: &str,
    enabled: bool,
    force: bool,
) -> Result<(), ErrorDisplayed> {
    let bytes = hex::decode(pubkey_hex).map_err(|e| ErrorDisplayed::Str {
        s: format!("bad pubkey hex: {e}"),
    })?;
    if bytes.len() != 32 {
        return Err(ErrorDisplayed::Str {
            s: "pubkey must be 32 bytes".into(),
        });
    }
    let mut pk = [0u8; 32];
    pk.copy_from_slice(&bytes);

    let db_guard = DB.read().map_err(|_| ErrorDisplayed::MutexPoisoned)?;
    let database = db_guard.as_ref().ok_or(ErrorDisplayed::DbNotInitialized)?;

    if !enabled {
        // Disabling: simulate post-state (drop pk from enabled list).
        let remaining: Vec<[u8; 32]> = db_handling::anchor_verifiers::enabled_pubkeys(database)
            .map_err(|e| ErrorDisplayed::Str {
                s: format!("enabled list: {e}"),
            })?
            .into_iter()
            .filter(|k| k != &pk)
            .collect();
        check_lockout(database, remaining, force)?;
    }

    db_handling::anchor_verifiers::update_verifier(database, &pk, None, Some(enabled)).map_err(
        |e| ErrorDisplayed::Str {
            s: format!("update verifier: {e}"),
        },
    )
}

ffi_support::define_string_destructor!(signer_destroy_string);

// ── protocol module runtime bridge (uniffi) ─────────────────────────────

/// Summary of one PCZT message for the confirm screen. `output_lines` are
/// preformatted "label=zatoshi" pairs from the module's summarize; the
/// screen renders them - it never re-derives amounts from anything the
/// wallet sent outside the PCZT.
pub struct ModulePcztSummary {
    pub orchard_actions: u32,
    /// Ironwood (NU6.3 / V6) actions present in the request. Nonzero marks a
    /// turnstile migration; the confirm screen must surface this so the
    /// ironwood destination is never signed invisibly. The default (non
    /// NU6.3) module emits 0 here.
    pub ironwood_actions: u32,
    pub transparent_inputs: u32,
    /// Canonical fee in zatoshi (already includes the ironwood value balance),
    /// or None when the module could not derive it from the PCZT. The screen
    /// shows "unknown" for None rather than an understated number.
    pub fee_zat: Option<u64>,
    pub output_lines: Vec<String>,
    /// True when the module recognized this PCZT as a Zcash voting
    /// delegation authorization rather than a payment (`kind=delegation` in
    /// the head line - see `pczt_signing::detect_delegation`). Absent /
    /// `kind=payment` on older modules parses as `false`, so a pre-delegation
    /// module keeps rendering the plain payment screen it always has.
    pub is_delegation: bool,
}

fn module_runtime(module_wasm: &[u8]) -> Result<module_host::ModuleRuntime, ErrorDisplayed> {
    module_host::ModuleRuntime::load(module_wasm).map_err(|e| ErrorDisplayed::Str {
        s: format!("module load: {e:?}"),
    })
}

pub fn module_summarize_request(
    module_wasm: &[u8],
    payload: &[u8],
) -> Result<Vec<ModulePcztSummary>, ErrorDisplayed> {
    let mut rt = module_runtime(module_wasm)?;
    let raw = rt
        .summarize_request(payload)
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("{e:?}"),
        })?;
    // module ABI: records separated by 0x1e; head line
    // "actions=N ironwood_actions=I t_inputs=M fee=F" (fee is a zatoshi
    // integer or the literal "unknown"), following lines "label=value".
    // ironwood_actions/fee are absent from pre-ironwood modules; treat a
    // missing ironwood_actions as 0 and a missing/"unknown" fee as None so
    // old modules keep parsing.
    let mut out = Vec::new();
    for record in raw.split(|b| *b == 0x1e).filter(|r| !r.is_empty()) {
        let text = String::from_utf8_lossy(record);
        let mut lines = text.lines();
        let head = lines.next().unwrap_or_default();
        let mut actions = 0u32;
        let mut ironwood_actions = 0u32;
        let mut t_inputs = 0u32;
        let mut fee_zat = None;
        let mut is_delegation = false;
        for part in head.split_whitespace() {
            if let Some(v) = part.strip_prefix("ironwood_actions=") {
                ironwood_actions = v.parse().unwrap_or(0);
            } else if let Some(v) = part.strip_prefix("actions=") {
                actions = v.parse().unwrap_or(0);
            } else if let Some(v) = part.strip_prefix("t_inputs=") {
                t_inputs = v.parse().unwrap_or(0);
            } else if let Some(v) = part.strip_prefix("fee=") {
                fee_zat = v.parse::<u64>().ok();
            } else if let Some(v) = part.strip_prefix("kind=") {
                is_delegation = v == "delegation";
            }
        }
        out.push(ModulePcztSummary {
            orchard_actions: actions,
            ironwood_actions,
            transparent_inputs: t_inputs,
            fee_zat,
            output_lines: lines.map(str::to_owned).collect(),
            is_delegation,
        });
    }
    Ok(out)
}

/// Result of verifying a signed module package: everything the confirm
/// screen shows, plus the wasm ready for the slot store. Only returned
/// when 2-of-3 release signatures, the hash, the kernel version gate and
/// the anti-rollback check ALL pass.
pub struct ModulePackageInfo {
    pub module_version: u32,
    pub min_kernel_version: u32,
    pub description: String,
    /// Full sha256 of the module wasm, hex - the UI shows a prefix and the
    /// user cross-checks it against the publisher's display.
    pub module_hash_hex: String,
    pub wasm: Vec<u8>,
}

/// Verify a module package against the kernel trust anchors. Fails closed
/// while the release keys are unprovisioned placeholders.
/// This seed's release public key, hex - one key per seed. Collect three (from
/// three seeds) to form the 2-of-3 set baked into RELEASE_KEY_BYTES.
pub fn release_signing_pubkey(seed_phrase: &str) -> Result<String, ErrorDisplayed> {
    release_signing::public_key_hex(seed_phrase).map_err(|s| ErrorDisplayed::Str { s })
}

/// Parse a scanned manifest prefix for the approve screen. Refuses anything
/// that is not exactly a well-formed manifest, so a release key cannot be
/// turned into a general signing oracle by whatever is driving the QR.
pub fn release_classify_request(prefix: &[u8]) -> Result<ReleaseSigningRequest, ErrorDisplayed> {
    release_signing::classify_request(prefix).map_err(|s| ErrorDisplayed::Str { s })
}

/// Sign a manifest prefix. The signed message is built on-device as
/// "zigner-module-v1" || prefix - never accepted pre-domained from the host.
/// Returns the signature hex; the caller shows it as a QR.
pub fn release_sign_request(seed_phrase: &str, prefix: &[u8]) -> Result<String, ErrorDisplayed> {
    release_signing::sign_request(seed_phrase, prefix)
        .map(|(_pubkey, sig)| sig)
        .map_err(|s| ErrorDisplayed::Str { s })
}

/// Version of the module baked into the APK, so the slot store can refuse to
/// let a stale installed slot shadow a newer module shipped by an APK update.
pub fn baked_module_version() -> u32 {
    module_host::BAKED_MODULE_VERSION
}

/// `base` is the currently-active module, needed only when the package carries
/// a delta rather than a whole module. Passing it always is simplest and costs
/// nothing: a full package ignores it.
pub fn module_verify_package(
    package: &[u8],
    last_installed_version: u32,
    base: Option<Vec<u8>>,
) -> Result<ModulePackageInfo, ErrorDisplayed> {
    use sha2::Digest;
    let keys = module_host::release_keys().ok_or(ErrorDisplayed::Str {
        s: "release keys not provisioned - module updates disabled in this build".to_string(),
    })?;
    let v = module_host::manifest::verify_package_with_base(
        package,
        &keys,
        module_host::KERNEL_VERSION,
        last_installed_version,
        base.as_deref(),
    )
    .map_err(|e| ErrorDisplayed::Str {
        s: format!("module package rejected: {e:?}"),
    })?;
    let hash = sha2::Sha256::digest(v.module_bytes.as_ref());
    Ok(ModulePackageInfo {
        module_version: v.module_version,
        min_kernel_version: v.min_kernel_version,
        description: v.description,
        module_hash_hex: hex::encode(hash),
        wasm: v.module_bytes.to_vec(),
    })
}

/// Kernel self-test for a staged module: instantiate + ABI probe. Run
/// before activation; a failing module never becomes the active slot.
pub fn module_self_test(wasm: &[u8]) -> bool {
    module_host::self_test(wasm)
}

pub fn module_sign_request(
    module_wasm: &[u8],
    payload: &[u8],
    seed_phrase: &str,
    account: u32,
    mainnet: bool,
) -> Result<Vec<u8>, ErrorDisplayed> {
    let mut rt = module_runtime(module_wasm)?;
    rt.sign_request(payload, seed_phrase, account, mainnet)
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("{e:?}"),
        })
}

/// Fountain-decode `ur:zigner-module` protocol-module REQUEST frames back to
/// the raw prelude envelope bytes (`[0x53][crypto][tx_type] || payload`).
///
/// The emitting wallet (zafu) animates the module request over the SAME BC-UR
/// fountain as `ur:zcash-pczt`, but wraps the RAW prelude envelope directly -
/// there is NO CBOR `{1: bytes}` wrap on the request side (the wallet feeds the
/// bare envelope to `ur_encode_frames`). So unlike `decode_ur_zcash_pczt` we
/// return the fountain message verbatim without a CBOR unwrap; the scan
/// dispatcher then routes on the 6-hex prelude exactly as for a raw-byte /
/// substrate multi-QR request.
fn decode_ur_module_request(ur_parts: Vec<String>) -> Result<Vec<u8>, ErrorDisplayed> {
    if ur_parts.is_empty() {
        return Err(ErrorDisplayed::Str {
            s: "No UR parts provided".to_string(),
        });
    }

    // Type guard: every part must be ur:zigner-module/... so a stray QR mid
    // stream can never poison the fountain decoder.
    for part in &ur_parts {
        if !part.to_lowercase().starts_with("ur:zigner-module/") {
            return Err(ErrorDisplayed::Str {
                s: format!(
                    "Expected ur:zigner-module/... got: {}",
                    part.chars().take(30).collect::<String>()
                ),
            });
        }
    }

    if ur_parts.len() == 1 {
        let (_kind, bytes) = ur::ur::decode(&ur_parts[0]).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to decode UR: {e:?}"),
        })?;
        return Ok(bytes);
    }

    let mut decoder = ur::ur::Decoder::default();
    for part in &ur_parts {
        decoder.receive(part).map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to receive UR part: {e:?}"),
        })?;
        if decoder.complete() {
            break;
        }
    }
    if !decoder.complete() {
        return Err(ErrorDisplayed::Str {
            s: format!(
                "Incomplete UR sequence: received {} parts but not complete",
                ur_parts.len()
            ),
        });
    }
    decoder
        .message()
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to get UR message: {e:?}"),
        })?
        .ok_or_else(|| ErrorDisplayed::Str {
            s: "UR decoder returned None despite being complete".to_string(),
        })
}

/// CBOR unsigned integer (major type 0), shortest form.
fn cbor_uint(out: &mut Vec<u8>, v: u32) {
    match v {
        0..=0x17 => out.push(v as u8),
        0x18..=0xff => out.extend_from_slice(&[0x18, v as u8]),
        0x100..=0xffff => {
            out.push(0x19);
            out.extend_from_slice(&(v as u16).to_be_bytes());
        }
        _ => {
            out.push(0x1a);
            out.extend_from_slice(&v.to_be_bytes());
        }
    }
}

/// `{1: response, 2: {1: baked, 2: active, 3: host_abi}}`.
///
/// Key 2 is what the KERNEL knows about itself - the module cannot attest its
/// own version, so these values come from the APK, never from module output.
/// Wallets use them the way they use Keystone's firmware version: to tell the
/// user to update the device before a request it cannot handle, rather than
/// after they approved it. Wallets that predate key 2 reject map(2), so this
/// format requires a wallet release that accepts it (zafu
/// `unwrapSignerEnvelope`).
fn encode_module_response_cbor(response: &[u8], active_module_version: u32) -> Vec<u8> {
    let mut cbor = encode_pczt_to_cbor(response);
    debug_assert_eq!(cbor[0], 0xa1);
    cbor[0] = 0xa2; // map(2)
    cbor_uint(&mut cbor, 2);
    cbor.push(0xa3); // map(3)
    cbor_uint(&mut cbor, 1);
    cbor_uint(&mut cbor, module_host::BAKED_MODULE_VERSION);
    cbor_uint(&mut cbor, 2);
    cbor_uint(&mut cbor, active_module_version);
    cbor_uint(&mut cbor, 3);
    cbor_uint(&mut cbor, module_host::HOST_ABI_VERSION);
    cbor
}

/// Frame a module sign-response envelope (prelude || digests || signed
/// PCZTs) as UR strings for animated QR display, with the kernel's version
/// facts (see [`encode_module_response_cbor`]). Same fountain encoder as the
/// signed-PCZT path, but under a distinct UR type so wallets never mistake the
/// envelope for a bare PCZT.
///
/// `active_module_version` is the version of the module that produced
/// `response` (installed slot or baked asset), as the caller loaded it.
pub fn module_response_to_ur(
    response: &[u8],
    active_module_version: u32,
    max_fragment_len: u32,
) -> Result<Vec<String>, ErrorDisplayed> {
    const UR_TYPE: &str = "zigner-module";
    let cbor_data = encode_module_response_cbor(response, active_module_version);

    if max_fragment_len == 0 || cbor_data.len() <= max_fragment_len as usize {
        return Ok(vec![ur::ur::encode(&cbor_data, &ur::Type::Custom(UR_TYPE))]);
    }

    let mut encoder = ur::ur::Encoder::new(&cbor_data, max_fragment_len as usize, UR_TYPE)
        .map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to create UR encoder: {e:?}"),
        })?;

    // 1.3x redundancy, matching encode_signed_pczt_ur (see comment there).
    let total_parts = (encoder.fragment_count() * 13).div_ceil(10);
    let mut parts = Vec::with_capacity(total_parts);
    for _ in 0..total_parts {
        parts.push(encoder.next_part().map_err(|e| ErrorDisplayed::Str {
            s: format!("Failed to encode UR part: {e:?}"),
        })?);
    }
    Ok(parts)
}

#[cfg(test)]
mod multichain_cosmos_tests {
    use super::*;
    use db_handling::cosmos::{derive_cosmos_key, pubkey_to_bech32_with_prefix};
    use transaction_signing::cosmos::{
        encode_cosmos_sign_request, sign_cosmos_amino, CosmosQrExtension,
    };

    const M: &str = "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

    fn doc(chain_id: &str, from: &str, denom: &str) -> String {
        format!(
            r#"{{"account_number":"7","chain_id":"{chain_id}","fee":{{"amount":[{{"amount":"2500","denom":"{denom}"}}],"gas":"200000"}},"memo":"t","msgs":[{{"type":"cosmos-sdk/MsgSend","value":{{"amount":[{{"amount":"1000","denom":"{denom}"}}],"from_address":"{from}","to_address":"{from}"}}}}],"sequence":"3"}}"#
        )
    }

    /// (chain_name in QR, chain-id, prefix, fee denom)
    const FIXTURES: &[(&str, &str, &str, &str)] = &[
        ("osmosis", "osmosis-1", "osmo", "uosmo"),
        ("juno", "juno-1", "juno", "ujuno"),
        ("atomone", "atomone-1", "atone", "uatone"),
        ("terp", "morocco-1", "terp", "uthiol"),
    ];

    fn roundtrip(
        qr_chain: &str,
        chain_id: &str,
        prefix: &str,
        denom: &str,
        ext: Option<&CosmosQrExtension>,
    ) {
        let (acct, idx) = (1u32, 2u32);
        let key = derive_cosmos_key(M, 118, acct, idx).unwrap();
        let addr = pubkey_to_bech32_with_prefix(&key.public_key, prefix).unwrap();
        assert!(addr.starts_with(&format!("{prefix}1")), "{addr}");
        let d = doc(chain_id, &addr, denom);
        let qr = hex::encode(encode_cosmos_sign_request(
            acct,
            qr_chain,
            d.as_bytes(),
            idx,
            ext,
        ));

        let req = parse_cosmos_sign_request(&qr).unwrap();
        assert_eq!(req.chain_id, chain_id);
        assert_eq!(req.chain_name, qr_chain);
        assert_eq!(req.fee, format!("2500 {denom}"));
        assert_eq!(req.msgs.len(), 1);
        assert_eq!(req.msgs[0].amount, format!("1000 {denom}"));
        assert_eq!(req.msgs[0].recipient, addr);

        assert_eq!(
            cosmos_public_key_for_signers(M, req.clone()).unwrap(),
            hex::encode(key.public_key)
        );
        let sig = sign_cosmos_transaction(M, req).unwrap();
        let expect = sign_cosmos_amino(&key.secret_key, d.as_bytes()).unwrap();
        assert_eq!(sig, expect.to_vec());

        // display/sign binding still enforced
        let mut tampered = parse_cosmos_sign_request(&qr).unwrap();
        tampered.fee = "1 uatom".into();
        assert!(sign_cosmos_transaction(M, tampered).is_err());
    }

    #[test]
    fn four_chain_fixtures_legacy_payload() {
        for (n, c, p, d) in FIXTURES {
            roundtrip(n, c, p, d, None);
        }
    }

    #[test]
    fn unknown_chain_name_takes_prefix_from_signer() {
        // e.g. the camera wraps raw JSON as chain_name "terp"; a juno doc must still sign.
        roundtrip("terp", "juno-1", "juno", "ujuno", None);
        roundtrip("somechain", "atomone-1", "atone", "uatone", None);
    }

    #[test]
    fn extension_prefix_and_coin_type() {
        let ext = CosmosQrExtension {
            coin_type: Some(118),
            bech32_prefix: Some("juno".into()),
        };
        roundtrip("juno", "juno-1", "juno", "ujuno", Some(&ext));

        // coin type 330 from the extension
        let key = derive_cosmos_key(M, 330, 0, 0).unwrap();
        let addr = pubkey_to_bech32_with_prefix(&key.public_key, "terra").unwrap();
        let d = doc("phoenix-1", &addr, "uluna");
        let ext = CosmosQrExtension {
            coin_type: Some(330),
            bech32_prefix: None,
        };
        let qr = hex::encode(encode_cosmos_sign_request(
            0,
            "whatever",
            d.as_bytes(),
            0,
            Some(&ext),
        ));
        let sig = sign_cosmos_transaction(M, parse_cosmos_sign_request(&qr).unwrap()).unwrap();
        assert_eq!(
            sig,
            sign_cosmos_amino(&key.secret_key, d.as_bytes())
                .unwrap()
                .to_vec()
        );
        // table coin type for "terra" without extension also works
        let qr = hex::encode(encode_cosmos_sign_request(
            0,
            "terra",
            d.as_bytes(),
            0,
            None,
        ));
        let sig = sign_cosmos_transaction(M, parse_cosmos_sign_request(&qr).unwrap()).unwrap();
        assert_eq!(
            sig,
            sign_cosmos_amino(&key.secret_key, d.as_bytes())
                .unwrap()
                .to_vec()
        );
    }

    #[test]
    fn create_validator_signed_by_operator_account() {
        use transaction_signing::cosmos::account_to_operator;
        let key = derive_cosmos_key(M, 118, 0, 1).unwrap();
        let owner = pubkey_to_bech32_with_prefix(&key.public_key, "terp").unwrap();
        let valoper = account_to_operator(&owner).unwrap();
        let d = format!(
            r#"{{"account_number":"42","chain_id":"morocco-1","fee":{{"amount":[{{"amount":"40000","denom":"uthiol"}}],"gas":"400000"}},"memo":"","msgs":[{{"type":"cosmos-sdk/MsgCreateValidator","value":{{"commission":{{"max_change_rate":"0.010000000000000000","max_rate":"0.200000000000000000","rate":"0.100000000000000000"}},"description":{{"details":"zcinti","moniker":"zcinti2"}},"min_self_delegation":"1","pubkey":{{"type":"tendermint/PubKeyEd25519","value":"BwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwcHBwc="}},"validator_address":"{valoper}","value":{{"amount":"1000000","denom":"uterp"}}}}}}],"sequence":"0"}}"#
        );
        // QR hints account 0 / address 0; the signer search finds 0/1.
        let qr = hex::encode(encode_cosmos_sign_request(0, "terp", d.as_bytes(), 0, None));
        let req = parse_cosmos_sign_request(&qr).unwrap();
        assert_eq!(req.msgs[0].msg_type, "Create Validator");
        assert!(!req.msgs[0].blind);
        let sig = sign_cosmos_transaction(M, req).unwrap();
        assert_eq!(
            sig,
            sign_cosmos_amino(&key.secret_key, d.as_bytes())
                .unwrap()
                .to_vec()
        );

        // a tampered card is refused
        let mut t = parse_cosmos_sign_request(&qr).unwrap();
        t.msgs[0].detail = t.msgs[0].detail.replace("10%", "1%");
        assert!(sign_cosmos_transaction(M, t).is_err());
        let mut t = parse_cosmos_sign_request(&qr).unwrap();
        t.msgs[0].amount = "1 uterp".into();
        assert!(sign_cosmos_transaction(M, t).is_err());

        // an operator this seed does not own is a source mismatch
        let far = derive_cosmos_key(M, 118, 20, 0).unwrap();
        let far = pubkey_to_bech32_with_prefix(&far.public_key, "terp").unwrap();
        {
            let foreign = account_to_operator(&far).unwrap();
            let d2 = d.replace(&valoper, &foreign);
            let qr2 = hex::encode(encode_cosmos_sign_request(
                0,
                "terp",
                d2.as_bytes(),
                0,
                None,
            ));
            let err =
                sign_cosmos_transaction(M, parse_cosmos_sign_request(&qr2).unwrap()).unwrap_err();
            assert!(format!("{err:?}").contains("Source mismatch"), "{err:?}");
        }
    }

    #[test]
    fn wrong_signer_still_rejected() {
        let d = doc(
            "juno-1",
            "juno1qqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqqzz9vml",
            "ujuno",
        );
        let qr = hex::encode(encode_cosmos_sign_request(0, "juno", d.as_bytes(), 0, None));
        let err = sign_cosmos_transaction(M, parse_cosmos_sign_request(&qr).unwrap()).unwrap_err();
        assert!(format!("{err:?}").contains("Source mismatch"), "{err:?}");
    }

    /// Pre-change export algorithm, kept here as the byte-identical reference.
    fn legacy_export_json(account_index: u32, label: &str, filter: &str) -> Vec<u8> {
        use db_handling::cosmos::derive_cosmos_account_xpub;
        let key = derive_cosmos_key(M, 118, account_index, 0).unwrap();
        let xpub = derive_cosmos_account_xpub(M, 118, account_index).unwrap();
        let all = [
            ("osmosis", "osmo"),
            ("noble", "noble"),
            ("celestia", "celestia"),
            ("terp", "terp"),
        ];
        let addrs: Vec<_> = all
            .iter()
            .filter(|(id, _)| filter.is_empty() || *id == filter)
            .map(|(id, p)| serde_json::json!({"chain_id": id, "address": key.bech32_address(p).unwrap(), "prefix": p}))
            .collect();
        serde_json::json!({
            "type": "cosmos-accounts", "version": 1,
            "label": if label.is_empty() { "Zigner" } else { label },
            "account_index": account_index,
            "public_key": hex::encode(key.public_key), "xpub": xpub, "addresses": addrs,
        })
        .to_string()
        .into_bytes()
    }

    #[test]
    fn export_legacy_chains_byte_identical() {
        for f in ["", "osmosis", "noble", "celestia", "terp", "unknownchain"] {
            let got = export_cosmos_accounts(M, 3, "L", f).unwrap();
            let want = encode_to_qr(&legacy_export_json(3, "L", f), false).unwrap();
            assert_eq!(got.qr_data, want, "filter {f:?}");
        }
    }

    #[test]
    fn export_juno_and_atomone() {
        for (n, p) in [("juno", "juno"), ("atomone", "atone")] {
            let e = export_cosmos_accounts(M, 0, "", n).unwrap();
            assert_eq!(e.addresses.len(), 1);
            assert_eq!(e.addresses[0].prefix, p);
            assert!(e.addresses[0].address.starts_with(&format!("{p}1")));
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn cosmos_export_includes_terp() {
        let chains = super::cosmos_export_chains();
        assert!(chains.contains(&("terp", "terp")), "{chains:?}");
        for c in ["osmosis", "noble", "celestia"] {
            assert!(chains.iter().any(|(id, _)| *id == c));
        }
    }

    use super::*;

    /// Byte layout pinned so zafu's `unwrapSignerEnvelope` test vectors stay
    /// valid: `{1: bytes, 2: {1: baked, 2: active, 3: abi}}`.
    #[test]
    fn module_response_cbor_carries_kernel_info() {
        let out = encode_module_response_cbor(&[9, 9], 256);
        let mut expect = vec![0xa2, 0x01, 0x42, 9, 9, 0x02, 0xa3, 0x01];
        cbor_uint(&mut expect, module_host::BAKED_MODULE_VERSION);
        expect.extend_from_slice(&[0x02, 0x19, 0x01, 0x00, 0x03]);
        cbor_uint(&mut expect, module_host::HOST_ABI_VERSION);
        assert_eq!(out, expect);
    }

    #[test]
    fn cworch_signature_roundtrip_one_frame_and_many() {
        let pubkey = "02".repeat(33);
        let signature = vec![0x11u8; 64];
        let one = encode_cworch_signature(&pubkey, signature.clone(), 0).unwrap();
        assert_eq!(one.len(), 1);
        assert!(one[0].to_lowercase().starts_with("ur:cworch-sig/"));
        let back = decode_ur_payload(&one, "cworch-sig").unwrap();
        assert_eq!(back.len(), 97);
        assert_eq!(&back[..33], &hex::decode(&pubkey).unwrap());
        assert_eq!(&back[33..], &signature);

        let many = encode_cworch_signature(&pubkey, signature, 20).unwrap();
        assert!(many.len() > 1, "a short fragment length has to animate");
        let back = decode_ur_payload(&many, "cworch-sig").unwrap();
        assert_eq!(back.len(), 97);
    }
}

/// PCZT money-path tests for the SHIPPED signer entry points
/// (`inspect_zcash_pczt` / `sign_zcash_pczt`), driven over real PCZTs built
/// with the wallet-side roles.
///
/// Two axes, both mandatory:
///   * V5 NON-REGRESSION - an ordinary orchard send must inspect and sign
///     exactly as before the pczt 0.7 -> 0.9.2 move, and the signed response
///     must still be a v1-encoded PCZT.
///   * V6 / IRONWOOD - a turnstile migration must be visible on the review
///     screen (ironwood action count, destination, honest fee) and every
///     ironwood action must come back signed.
///
/// These tests intentionally do NOT strip the spend `fvk`: the high-level
/// `pczt::roles::signer::Signer` used here needs it (its `verify_nullifier`
/// runs with `None` and does not tolerate `MissingFullViewingKey`). That is
/// the Zashi-style redaction this entry point has always assumed. The
/// fvk-stripped ("R3") transport is the wasm module's path and is covered in
/// `rust/pczt_signing`.
#[cfg(test)]
mod zcash_pczt_tests {
    use super::{inspect_zcash_pczt, sign_zcash_pczt};
    use pczt::roles::{creator::Creator, io_finalizer::IoFinalizer};
    use pczt::Pczt;
    use transaction_signing::zcash::OrchardSpendingKey;
    use zcash_primitives::transaction::{
        builder::{BuildConfig, Builder, BundlePadding},
        fees::zip317,
        TxVersion,
    };
    use zcash_protocol::{
        consensus::BlockHeight, local_consensus::LocalNetwork, memo::MemoBytes, value::Zatoshis,
    };

    /// 24 words: `bip32` 0.5 (what the shipped `OrchardSpendingKey` derivation
    /// uses) only accepts 256-bit entropy, i.e. 24-word mnemonics.
    const MNEMONIC: &str = "abandon abandon abandon abandon abandon abandon abandon abandon \
                            abandon abandon abandon abandon abandon abandon abandon abandon \
                            abandon abandon abandon abandon abandon abandon abandon art";
    const NOTE_VALUE: u64 = 1_000_000;

    /// Regtest params with every upgrade live from height 1. `nu6_3` selects
    /// whether the builder targets branch `Nu6_3` (`TxVersion::V6`, Ironwood
    /// available) or `Nu6_2` (`TxVersion::V5`, the shipped path).
    fn params(nu6_3: bool) -> LocalNetwork {
        let h = |x: u32| Some(BlockHeight::from_u32(x));
        LocalNetwork {
            overwinter: h(1),
            sapling: h(1),
            blossom: h(1),
            heartwood: h(1),
            canopy: h(1),
            nu5: h(1),
            nu6: h(1),
            nu6_1: h(1),
            nu6_2: h(1),
            nu6_3: if nu6_3 { h(1) } else { None },
            nu7: None,
        }
    }

    /// As `params(true)` with NU7 live too: the builder targets branch NU7.
    fn params_nu7() -> LocalNetwork {
        LocalNetwork {
            nu7: Some(BlockHeight::from_u32(1)),
            ..params(true)
        }
    }

    /// The exact orchard key the signer derives from `MNEMONIC` at account 0.
    /// Deriving it through the SHIPPED path means a key-derivation change
    /// would break these tests rather than silently produce fixtures
    /// this key cannot sign.
    fn wallet_keys() -> (orchard::keys::SpendingKey, orchard::keys::FullViewingKey) {
        let sk = OrchardSpendingKey::from_seed_phrase(MNEMONIC, 0)
            .expect("derive orchard spending key")
            .to_spending_key()
            .expect("orchard spending key");
        let fvk = orchard::keys::FullViewingKey::from(&sk);
        (sk, fvk)
    }

    /// A spendable orchard note owned by the wallet, plus its witness/anchor.
    fn wallet_note(
        fvk: &orchard::keys::FullViewingKey,
    ) -> (orchard::Note, orchard::tree::MerklePath, orchard::Anchor) {
        let rho = orchard::note::Rho::from_bytes(&[1u8; 32]).unwrap();
        let rseed = (0u8..=255)
            .find_map(|b| orchard::note::RandomSeed::from_bytes([b; 32], &rho).into_option())
            .expect("test rseed");
        let note: orchard::Note = Option::from(orchard::Note::from_parts(
            fvk.address_at(0u32, orchard::keys::Scope::External),
            orchard::value::NoteValue::from_raw(NOTE_VALUE),
            rho,
            rseed,
            orchard::note::NoteVersion::V2,
        ))
        .expect("test note");

        let zero = Option::from(orchard::tree::MerkleHashOrchard::from_bytes(&[0u8; 32]))
            .expect("zero merkle hash");
        let witness = orchard::tree::MerklePath::from_parts(0, [zero; 32]);
        let cmx: orchard::note::ExtractedNoteCommitment = note.commitment().into();
        let anchor = witness.root(cmx);
        (note, witness, anchor)
    }

    /// Everything a V5 orchard-send test needs to assert against.
    struct V5Fixture {
        /// Serialized PCZT as it would cross the airgap.
        pczt: Vec<u8>,
        /// Network fee in zatoshi.
        fee: u64,
        /// Value paid to the external recipient.
        sent: u64,
        /// Raw 43-byte orchard receiver of that recipient.
        recipient_raw: [u8; 43],
        /// The wallet's 96-byte orchard FVK — must NOT appear in a redacted PCZT.
        fvk_bytes: Vec<u8>,
    }

    /// Wallet-side: an ordinary V5 orchard send to an external recipient.
    /// Returns `(pczt_bytes, fee, sent_value)`.
    fn build_v5_send() -> (Vec<u8>, u64, u64) {
        let fx = build_v5_fixture(false, false);
        (fx.pczt, fx.fee, fx.sent)
    }

    /// Wallet-side V5 orchard send. `redact` applies zafu's
    /// `redact_pczt_for_signer` (including the fvk strip) before serializing;
    /// `prove` runs the Halo2 Prover first, which the wallet must do before the
    /// PCZT crosses the airgap if it is going to be extracted afterwards.
    fn build_v5_fixture(redact: bool, prove: bool) -> V5Fixture {
        let net = params(false);
        let (_sk, fvk) = wallet_keys();
        let (note, witness, anchor) = wallet_note(&fvk);

        let recipient_sk = orchard::keys::SpendingKey::from_bytes([9u8; 32]).unwrap();
        let recipient = orchard::keys::FullViewingKey::from(&recipient_sk)
            .address_at(0u32, orchard::keys::Scope::External);

        let make = |sent: u64| {
            let mut b = Builder::new(
                net,
                BlockHeight::from_u32(100),
                BuildConfig::Standard {
                    sapling_anchor: None,
                    orchard_anchor: Some(anchor),
                    ironwood_anchor: None,
                    orchard_padding: BundlePadding::DEFAULT,
                    ironwood_padding: BundlePadding::DEFAULT,
                },
            );
            b.propose_version::<zip317::FeeRule>(TxVersion::V5)
                .expect("propose V5");
            b.add_orchard_spend::<zip317::FeeRule>(fvk.clone(), note, witness.clone())
                .expect("orchard spend");
            b.add_orchard_output::<zip317::FeeRule>(
                None,
                recipient,
                Zatoshis::const_from_u64(sent),
                MemoBytes::empty(),
            )
            .expect("orchard output");
            b
        };

        let fee = u64::from(
            make(1)
                .get_fee(&zip317::FeeRule::standard())
                .expect("estimate fee"),
        );
        let sent = NOTE_VALUE - fee;
        let parts = make(sent)
            .build_for_pczt(
                rand010::rand_core::UnwrapErr(rand010::rngs::SysRng),
                &zip317::FeeRule::standard(),
            )
            .expect("build_for_pczt")
            .pczt_parts;
        assert_eq!(parts.version, TxVersion::V5);
        let pczt = Creator::build_from_parts(parts).expect("Creator");
        let pczt = IoFinalizer::new(pczt).finalize_io().expect("IoFinalizer");
        // Proving is the wallet's job and must happen before redaction, since
        // the Prover consumes fields the redaction strips.
        let pczt = if prove {
            pczt::roles::prover::Prover::new(pczt)
                .create_orchard_proof(&orchard::circuit::ProvingKey::build(
                    orchard::circuit::OrchardCircuitVersion::FixedPostNu6_2,
                ))
                .expect("orchard prove")
                .finish()
        } else {
            pczt
        };
        let pczt = if redact {
            redact_for_signer(pczt)
        } else {
            pczt
        };
        V5Fixture {
            pczt: pczt.serialize().expect("serialize"),
            fee,
            sent,
            recipient_raw: recipient.to_raw_address_bytes(),
            fvk_bytes: fvk.to_bytes().to_vec(),
        }
    }

    /// Line-for-line mirror of zafu's `redact_pczt_for_signer`
    /// (zcli `crates/zcash-wasm/src/lib.rs`) — what the wallet strips before
    /// the PCZT crosses the airgap. Note the asymmetry the device depends on:
    /// the SPEND side loses its note plaintext AND the 96-byte orchard `fvk`;
    /// the OUTPUT side keeps `recipient` / `value` / `rseed`, because in an
    /// ordinary send the orchard output IS what the user must confirm.
    ///
    /// Kept in sync by hand with zcli and with
    /// `rust/pczt_signing/tests/common/mod.rs`.
    fn redact_for_signer(pczt: Pczt) -> Pczt {
        use pczt::roles::redactor::Redactor;
        Redactor::new(pczt)
            .redact_global_with(|mut g| {
                g.clear_proprietary();
            })
            .redact_orchard_with(|mut o| {
                o.redact_actions(|mut a| {
                    a.clear_spend_witness();
                    a.clear_spend_zip32_derivation();
                    a.clear_spend_dummy_sk();
                    a.clear_spend_proprietary();
                    a.clear_spend_rseed();
                    a.clear_spend_rho();
                    a.clear_spend_recipient();
                    a.clear_spend_value();
                    a.clear_spend_fvk();
                    a.clear_output_zip32_derivation();
                    a.clear_output_user_address();
                    a.clear_output_proprietary();
                });
            })
            .redact_ironwood_with(|mut o| {
                o.redact_actions(|mut a| {
                    a.clear_spend_witness();
                    a.clear_spend_zip32_derivation();
                    a.clear_spend_dummy_sk();
                    a.clear_spend_proprietary();
                    a.clear_spend_rseed();
                    a.clear_spend_rho();
                    a.clear_spend_recipient();
                    a.clear_spend_value();
                    a.clear_spend_fvk();
                });
            })
            .redact_transparent_with(|mut t| {
                t.redact_outputs(|mut o| {
                    o.clear_user_address();
                    o.clear_proprietary();
                });
            })
            .finish()
    }

    /// `needle` occurs in `haystack`?
    fn contains(haystack: &[u8], needle: &[u8]) -> bool {
        !needle.is_empty() && haystack.windows(needle.len()).any(|w| w == needle)
    }

    /// Wallet-side: a V6 orchard -> ironwood turnstile migration into the
    /// wallet's own internal address. Returns
    /// `(pczt_bytes, fee, migrated, ironwood_recipient_raw)`.
    fn build_v6_migration() -> (Vec<u8>, u64, u64, [u8; 43]) {
        build_v6_migration_inner(false)
    }

    fn build_v6_migration_inner(redact: bool) -> (Vec<u8>, u64, u64, [u8; 43]) {
        build_v6_migration_on(params(true), redact)
    }

    fn build_v6_migration_on(net: LocalNetwork, redact: bool) -> (Vec<u8>, u64, u64, [u8; 43]) {
        let (_sk, fvk) = wallet_keys();
        let (note, witness, anchor) = wallet_note(&fvk);

        let recipient = fvk.address_at(0u32, orchard::keys::Scope::Internal);
        let internal_ovk = Some(fvk.to_ovk(orchard::keys::Scope::Internal));

        let make = |migrated: u64| {
            let mut b = Builder::new(
                net,
                BlockHeight::from_u32(100),
                BuildConfig::Standard {
                    sapling_anchor: None,
                    orchard_anchor: Some(anchor),
                    ironwood_anchor: Some(orchard::Anchor::empty_tree()),
                    orchard_padding: BundlePadding::DEFAULT,
                    ironwood_padding: BundlePadding::DEFAULT,
                },
            );
            b.propose_version::<zip317::FeeRule>(TxVersion::V6)
                .expect("propose V6");
            b.add_orchard_spend::<zip317::FeeRule>(fvk.clone(), note, witness.clone())
                .expect("orchard migration spend");
            b.add_ironwood_output::<zip317::FeeRule>(
                internal_ovk.clone(),
                recipient,
                Zatoshis::const_from_u64(migrated),
                MemoBytes::empty(),
            )
            .expect("ironwood migration output");
            b
        };

        let fee = u64::from(
            make(1)
                .get_fee(&zip317::FeeRule::standard())
                .expect("estimate fee"),
        );
        let migrated = NOTE_VALUE - fee;
        let parts = make(migrated)
            .build_for_pczt(
                rand010::rand_core::UnwrapErr(rand010::rngs::SysRng),
                &zip317::FeeRule::standard(),
            )
            .expect("build_for_pczt")
            .pczt_parts;
        assert_eq!(parts.version, TxVersion::V6);
        let pczt = Creator::build_from_parts(parts).expect("Creator");
        let pczt = IoFinalizer::new(pczt).finalize_io().expect("IoFinalizer");
        let pczt = if redact {
            redact_for_signer(pczt)
        } else {
            pczt
        };
        (
            pczt.serialize().expect("serialize"),
            fee,
            migrated,
            recipient.to_raw_address_bytes(),
        )
    }

    #[test]
    fn v5_orchard_send_inspects_and_signs_unchanged() {
        let (bytes, fee, sent) = build_v5_send();

        let i = inspect_zcash_pczt(bytes.clone()).expect("inspect V5");
        assert_eq!(i.tx_version, 5, "V5 transaction");
        assert_eq!(
            i.ironwood_action_count, 0,
            "a V5 PCZT has no ironwood actions"
        );
        assert_eq!(
            i.ironwood_net_value, 0,
            "a V5 PCZT contributes nothing from the ironwood bundle"
        );
        assert!(i.action_count >= 1, "orchard actions present");
        assert_eq!(
            i.spends.len(),
            i.action_count as usize,
            "V5 spend list is orchard-only"
        );
        assert_eq!(
            i.outputs.len(),
            i.action_count as usize,
            "V5 output list is orchard-only"
        );
        // The whole point of the multi-bundle fee: it is the real network fee.
        assert_eq!(
            i.fee_zat, fee as i64,
            "V5 fee unchanged by the ironwood term"
        );
        assert_eq!(
            i.fee_zat, i.orchard_net_value,
            "shielded-only V5: fee is exactly the orchard value balance"
        );
        let recipient_total: u64 = i.outputs.iter().map(|o| o.value).sum();
        assert_eq!(recipient_total, sent, "recipient value visible for review");

        let signed_bytes = sign_zcash_pczt(MNEMONIC, 0, bytes).expect("sign V5");
        assert_eq!(
            &signed_bytes[4..8],
            &[1, 0, 0, 0],
            "a V5 response must still be a v1-encoded PCZT for pre-v2 wallets"
        );
        let signed = Pczt::parse(&signed_bytes).expect("signed V5 PCZT parses");
        assert_eq!(
            *signed.global().tx_version(),
            zcash_protocol::constants::V5_TX_VERSION
        );
        assert!(signed.ironwood().actions().is_empty());
        for (n, a) in signed.orchard().actions().iter().enumerate() {
            assert!(
                a.spend().spend_auth_sig().is_some(),
                "V5 orchard action {} left unsigned",
                n
            );
        }
    }

    #[test]
    fn v6_ironwood_migration_is_visible_and_signed() {
        let (bytes, fee, migrated, ironwood_recipient) = build_v6_migration();

        let i = inspect_zcash_pczt(bytes.clone()).expect("inspect V6");
        assert_eq!(
            i.tx_version,
            zcash_protocol::constants::V6_TX_VERSION,
            "V6 transaction"
        );
        assert!(
            i.ironwood_action_count >= 1,
            "the migration destination pool must be visible on the review screen"
        );

        // Honest fee: the migrated value LEAVES the orchard pool, so the
        // orchard bundle alone reports ~the whole amount. Only summing the
        // ironwood value balance too gives the real network fee. This is the
        // ironwood-blind bug this wiring exists to prevent.
        assert_eq!(i.fee_zat, fee as i64, "review shows the real network fee");
        assert!(
            i.fee_zat < migrated as i64,
            "fee ({}) must be far below the migrated value ({}) - an \
             ironwood-blind fee reports ~the whole amount",
            i.fee_zat,
            migrated
        );
        assert!(
            i.orchard_net_value > i.fee_zat,
            "sanity: the orchard-only balance is the ironwood-blind (wrong) number"
        );

        // The migration destination is displayed against the ironwood outputs.
        // `inspect_zcash_pczt` reads the network flag from the verified-note
        // store; with no DB open in the test it defaults to mainnet, so encode
        // the expected receiver the same way.
        let encoded = super::encode_orchard_recipient(&ironwood_recipient, true)
            .unwrap_or_else(|| hex::encode(ironwood_recipient));
        let dest = i
            .outputs
            .iter()
            .find(|o| o.recipient == encoded)
            .expect("ironwood destination present in the review outputs");
        assert_eq!(
            dest.value, migrated,
            "migrated value shown at the destination"
        );

        let signed_bytes = sign_zcash_pczt(MNEMONIC, 0, bytes).expect("sign V6");
        let signed = Pczt::parse(&signed_bytes).expect("signed V6 PCZT parses");
        assert_eq!(
            *signed.global().tx_version(),
            zcash_protocol::constants::V6_TX_VERSION
        );
        assert!(!signed.ironwood().actions().is_empty());
        for (n, a) in signed.orchard().actions().iter().enumerate() {
            assert!(
                a.spend().spend_auth_sig().is_some(),
                "orchard action {} left unsigned",
                n
            );
        }
        for (n, a) in signed.ironwood().actions().iter().enumerate() {
            assert!(
                a.spend().spend_auth_sig().is_some(),
                "ironwood action {} left unsigned",
                n
            );
        }
    }

    /// RELEASE-GATING for the orchard cold wallet: a V5 orchard SPEND, redacted
    /// exactly the way zafu redacts it (fvk stripped), must inspect honestly and
    /// sign through the SHIPPED `inspect_zcash_pczt` / `sign_zcash_pczt`.
    ///
    /// Before the low-level-Signer fix this failed outright: the high-level
    /// `pczt::roles::signer` role calls `Spend::verify_nullifier(None)`, which
    /// returns `MissingFullViewingKey` on an fvk-stripped spend, so every action
    /// failed and the device refused the transaction.
    #[test]
    fn zafu_redacted_v5_orchard_spend_inspects_and_signs() {
        let fx = build_v5_fixture(true, false);

        // R3 anti-regression: the viewing key really is gone from the wire.
        assert!(
            !contains(&fx.pczt, &fx.fvk_bytes),
            "redacted PCZT still leaks the 96-byte orchard FVK over the airgap"
        );
        Pczt::parse(&fx.pczt).expect("redacted PCZT parses");

        // ── what the user SEES ──
        let i = inspect_zcash_pczt(fx.pczt.clone()).expect("inspect fvk-stripped V5");
        assert_eq!(i.tx_version, zcash_protocol::constants::V5_TX_VERSION);
        assert_eq!(i.ironwood_action_count, 0);
        assert!(i.action_count >= 1);
        assert_eq!(i.spends.len(), i.action_count as usize);
        assert_eq!(i.outputs.len(), i.action_count as usize);
        assert_eq!(i.fee_zat, fx.fee as i64, "the screen shows the real fee");
        let displayed_total: u64 = i.outputs.iter().map(|o| o.value).sum();
        assert_eq!(displayed_total, fx.sent, "the screen shows the real amount");
        let expected_recipient = super::encode_orchard_recipient(&fx.recipient_raw, true)
            .unwrap_or_else(|| hex::encode(fx.recipient_raw));
        let shown = i
            .outputs
            .iter()
            .find(|o| o.recipient == expected_recipient)
            .expect("the screen shows the real recipient");
        assert_eq!(shown.value, fx.sent);

        // ── what the device SIGNS ──
        let signed_bytes = sign_zcash_pczt(MNEMONIC, 0, fx.pczt).expect(
            "SHIPPED sign path must handle zafu's fvk-stripped redaction — \
             this is the cold-signing release gate",
        );
        assert_eq!(
            &signed_bytes[4..8],
            &[1, 0, 0, 0],
            "a V5 response must still be a v1-encoded PCZT"
        );
        let signed = Pczt::parse(&signed_bytes).expect("signed PCZT parses");
        assert!(signed.ironwood().actions().is_empty());
        for (n, a) in signed.orchard().actions().iter().enumerate() {
            assert!(
                a.spend().spend_auth_sig().is_some(),
                "orchard action {} left unsigned",
                n
            );
        }
    }

    /// The ironwood/turnstile path must survive the same redaction. Not the
    /// release-critical pool, but a non-regression guard on the fvk fix.
    #[test]
    fn zafu_redacted_v6_migration_inspects_and_signs() {
        let (bytes, fee, migrated, _recipient) = build_v6_migration_inner(true);
        let i = inspect_zcash_pczt(bytes.clone()).expect("inspect fvk-stripped V6");
        assert_eq!(i.tx_version, zcash_protocol::constants::V6_TX_VERSION);
        assert!(i.ironwood_action_count >= 1);
        assert_eq!(i.fee_zat, fee as i64);
        assert!(i.fee_zat < migrated as i64);

        let signed_bytes = sign_zcash_pczt(MNEMONIC, 0, bytes).expect("sign fvk-stripped V6");
        let signed = Pczt::parse(&signed_bytes).expect("signed PCZT parses");
        for (n, a) in signed
            .orchard()
            .actions()
            .iter()
            .chain(signed.ironwood().actions().iter())
            .enumerate()
        {
            assert!(
                a.spend().spend_auth_sig().is_some(),
                "action {} left unsigned",
                n
            );
        }
    }

    /// Full airgap round trip for the release-critical flow: wallet builds +
    /// proves + redacts a V5 orchard SPEND, the SHIPPED device entry points
    /// inspect and sign it, and the wallet finalizes + extracts a real V5
    /// transaction from the result.
    ///
    /// `SpendFinalizer::finalize_spends` is the load-bearing assertion: it fails
    /// unless the device actually produced spend-auth signatures.
    ///
    /// Slow: builds the Halo2 orchard proving + verifying keys.
    #[test]
    fn redacted_v5_orchard_spend_round_trips_to_an_extractable_transaction() {
        use pczt::roles::{spend_finalizer::SpendFinalizer, tx_extractor::TransactionExtractor};

        let fx = build_v5_fixture(true, true);
        let i = inspect_zcash_pczt(fx.pczt.clone()).expect("inspect");
        assert_eq!(i.fee_zat, fx.fee as i64);

        let signed_bytes = sign_zcash_pczt(MNEMONIC, 0, fx.pczt).expect("device signs");
        let signed = Pczt::parse(&signed_bytes).expect("signed PCZT parses");

        let finalized = SpendFinalizer::new(signed)
            .finalize_spends()
            .expect("SpendFinalizer — fails if the device did not actually sign");
        let tx = TransactionExtractor::new(finalized)
            .with_orchard(&orchard::circuit::VerifyingKey::build(
                orchard::circuit::OrchardCircuitVersion::FixedPostNu6_2,
            ))
            .extract()
            .expect("extract a transaction");

        let mut tx_bytes = Vec::new();
        tx.write(&mut tx_bytes).expect("serialize tx");
        // v5 header: (version | 1<<31) LE, then the V5 version group id LE.
        assert_eq!(&tx_bytes[0..4], &[0x05, 0x00, 0x00, 0x80], "V5 tx version");
        assert_eq!(
            &tx_bytes[4..8],
            &zcash_protocol::constants::V5_VERSION_GROUP_ID.to_le_bytes(),
            "V5 version group id"
        );
        assert!(tx_bytes.len() > 1000, "extracted tx suspiciously small");
    }

    /// Pins the upstream constraint that forces `sign_zcash_pczt` to drive the
    /// LOW-LEVEL Signer role: `pczt::roles::signer::Signer` hardcodes
    /// `Spend::verify_nullifier(None)`, and `MissingFullViewingKey` is not in
    /// the tolerated-`Missing*` set, so it cannot sign an fvk-stripped spend at
    /// all. If a future `pczt` release lifts this, the fvk workaround can be
    /// reconsidered — and this test will tell us.
    #[test]
    fn upstream_high_level_signer_cannot_sign_an_fvk_stripped_spend() {
        let fx = build_v5_fixture(true, false);
        let pczt = Pczt::parse(&fx.pczt).expect("parse");
        let n_actions = pczt.orchard().actions().len();
        assert!(n_actions >= 1);
        let (sk, _fvk) = wallet_keys();
        let ask = orchard::keys::SpendAuthorizingKey::from(&sk);
        let mut signer = pczt::roles::signer::Signer::new(pczt).expect("high-level Signer");
        for index in 0..n_actions {
            let err = signer
                .sign_orchard(index, &ask)
                .expect_err("high-level Signer must fail without the spend fvk");
            let msg = format!("{err:?}");
            assert!(
                msg.contains("MissingFullViewingKey"),
                "unexpected error from the high-level Signer: {}",
                msg
            );
        }
    }

    /// LEB128, the varint postcard uses for `u64`.
    fn varint(mut v: u64) -> Vec<u8> {
        let mut out = Vec::new();
        loop {
            let b = (v & 0x7f) as u8;
            v >>= 7;
            if v == 0 {
                out.push(b);
                return out;
            }
            out.push(b | 0x80);
        }
    }

    /// Replace every occurrence of `needle` with `replacement` (same length).
    /// Returns the patched bytes and the number of substitutions.
    fn patch_all(bytes: &[u8], needle: &[u8], replacement: &[u8]) -> (Vec<u8>, usize) {
        assert_eq!(needle.len(), replacement.len());
        let mut out = bytes.to_vec();
        let mut n = 0;
        let mut i = 0;
        while i + needle.len() <= out.len() {
            if &out[i..i + needle.len()] == needle {
                out[i..i + needle.len()].copy_from_slice(replacement);
                n += 1;
                i += needle.len();
            } else {
                i += 1;
            }
        }
        (out, n)
    }

    /// TAMPER DETECTION: a hostile wallet inflates the output `value` field —
    /// the plaintext number the review screen displays — while the transaction
    /// still pays what the note commitment says. The device must not display
    /// the lie, and must not sign it.
    #[test]
    fn tampered_output_value_is_rejected() {
        let fx = build_v5_fixture(true, false);
        inspect_zcash_pczt(fx.pczt.clone()).expect("honest PCZT inspects");

        // Option<u64> on the wire: 0x01 tag + LEB128. Same-length replacement
        // keeps every following offset intact.
        let mut needle = vec![0x01];
        needle.extend_from_slice(&varint(fx.sent));
        let mut replacement = vec![0x01];
        replacement.extend_from_slice(&varint(fx.sent + 1));
        assert_eq!(needle.len(), replacement.len());
        let (tampered, n) = patch_all(&fx.pczt, &needle, &replacement);
        assert!(n > 0, "output value field not found in the wire bytes");

        // The patch really did change the field the screen reads.
        let parsed = Pczt::parse(&tampered).expect("tampered PCZT still parses");
        assert!(
            parsed
                .orchard()
                .actions()
                .iter()
                .any(|a| a.output().value() == &Some(fx.sent + 1)),
            "the value the review screen would display was not actually changed"
        );

        let err = inspect_zcash_pczt(tampered.clone()).expect_err(
            "a PCZT whose displayed amount contradicts its note commitment must be refused",
        );
        let msg = format!("{err:?}");
        assert!(
            msg.contains("does not match what it claims to pay"),
            "unexpected rejection reason: {}",
            msg
        );
        assert!(
            sign_zcash_pczt(MNEMONIC, 0, tampered).is_err(),
            "the device must refuse to sign a PCZT it refused to display"
        );
    }

    /// TAMPER DETECTION, the bypass variant — the reason the gate is keyed on
    /// what is DISPLAYED rather than on which field is missing.
    ///
    /// The two tamper tests around this one only catch an attacker who leaves
    /// the output `rseed` in place. `verify_note_commitment` needs recipient +
    /// value + rseed, and the gate used to treat any of those being absent as
    /// "not verifiable, don't block". Nothing in the signing path reads the
    /// output `rseed` — `pczt`'s low-level signer sets `rseed: None` itself and
    /// signs a sighash that binds the real `cmx` — so a hostile wallet could
    /// simply omit it, display any recipient and any amount, collect the
    /// signature, then reassemble with the true `rseed` and broadcast.
    #[test]
    fn stripped_output_rseed_does_not_disable_the_display_gate() {
        let fx = build_v5_fixture(true, false);
        inspect_zcash_pczt(fx.pczt.clone()).expect("honest PCZT inspects");

        // Attacker step 1: drop the output rseed so `verify_note_commitment`
        // cannot be computed at all.
        let stripped =
            pczt::roles::redactor::Redactor::new(Pczt::parse(&fx.pczt).expect("parse honest PCZT"))
                .redact_orchard_with(|mut o| {
                    o.redact_actions(|mut a| {
                        a.clear_output_rseed();
                    });
                })
                .finish()
                .serialize()
                .expect("serialize rseed-stripped PCZT");

        // Attacker step 2: now lie freely about both displayed fields.
        let mut needle = vec![0x01];
        needle.extend_from_slice(&varint(fx.sent));
        let mut replacement = vec![0x01];
        replacement.extend_from_slice(&varint(fx.sent + 1));
        let (tampered, n) = patch_all(&stripped, &needle, &replacement);
        assert!(n > 0, "output value field not found in the wire bytes");

        let decoy_sk = orchard::keys::SpendingKey::from_bytes([11u8; 32]).unwrap();
        let decoy = orchard::keys::FullViewingKey::from(&decoy_sk)
            .address_at(0u32, orchard::keys::Scope::External)
            .to_raw_address_bytes();
        let (tampered, n) = patch_all(&tampered, &fx.recipient_raw, &decoy);
        assert!(n > 0, "output recipient not found in the wire bytes");

        // The bytes really do carry the attacker's chosen display values.
        let parsed = Pczt::parse(&tampered).expect("tampered PCZT still parses");
        assert!(
            parsed
                .orchard()
                .actions()
                .iter()
                .any(|a| a.output().recipient() == &Some(decoy)
                    && a.output().value() == &Some(fx.sent + 1)),
            "the values the review screen would display were not actually changed"
        );

        let err = inspect_zcash_pczt(tampered.clone()).expect_err(
            "an output that displays a recipient/amount it cannot prove must be refused, \
             not silently skipped",
        );
        let msg = format!("{err:?}");
        assert!(
            msg.contains("cannot be proven"),
            "unexpected rejection reason: {}",
            msg
        );
        assert!(
            sign_zcash_pczt(MNEMONIC, 0, tampered).is_err(),
            "the device must refuse to sign a PCZT it refused to display"
        );
    }

    /// TAMPER DETECTION, the blank-output variant — the reason there is NO
    /// skip, not even for an output that renders nothing.
    ///
    /// An intermediate version of the gate allowed an output to skip
    /// verification when it displayed nothing (no recipient, no/zero value),
    /// on the grounds that there was nothing to lie about. There was: a
    /// hostile producer strips `recipient`, `value` AND `rseed` from every
    /// output, so each renders blank/0 while the transaction still pays a real
    /// amount bound by `cmx`, alongside a plausible producer-supplied fee. The
    /// device would display nothing and sign a real payment.
    #[test]
    fn fully_blank_output_is_refused_rather_than_skipped() {
        let fx = build_v5_fixture(true, false);
        let honest = inspect_zcash_pczt(fx.pczt.clone()).expect("honest PCZT inspects");
        assert!(
            honest.outputs.iter().any(|o| o.value == fx.sent),
            "the honest review screen shows the real payment: {:?}",
            honest
        );

        // Strip every field the screen reads AND the one the proof needs, so
        // the output renders blank and cannot be verified.
        let blanked =
            pczt::roles::redactor::Redactor::new(Pczt::parse(&fx.pczt).expect("parse honest PCZT"))
                .redact_orchard_with(|mut o| {
                    o.redact_actions(|mut a| {
                        a.clear_output_rseed();
                        a.clear_output_recipient();
                        a.clear_output_value();
                    });
                })
                .finish()
                .serialize()
                .expect("serialize blanked PCZT");

        let err = inspect_zcash_pczt(blanked.clone())
            .expect_err("an output this device cannot prove must be refused, blank or not");
        let msg = format!("{err:?}");
        assert!(
            msg.contains("cannot be proven"),
            "unexpected rejection reason: {}",
            msg
        );
        assert!(
            sign_zcash_pczt(MNEMONIC, 0, blanked).is_err(),
            "the device must refuse to sign a payment it cannot display"
        );
    }

    /// FEE TAMPERING: the review screen's fee comes from each bundle's
    /// `value_sum`, plaintext metadata the producer writes into the PCZT.
    /// Nothing in the note-commitment check constrains it, so a hostile wallet
    /// could pay the expected recipient the expected amount — both now provably
    /// displayed — while declaring a `value_sum` that renders a trivial fee and
    /// routing the rest of a large spent note to the miner.
    #[test]
    fn tampered_value_sum_is_rejected() {
        let fx = build_v5_fixture(true, false);
        let honest = inspect_zcash_pczt(fx.pczt.clone()).expect("honest PCZT inspects");
        assert_eq!(honest.fee_zat, fx.fee as i64, "honest fee: {honest:?}");

        // For this shielded-only send the orchard value_sum IS the fee.
        let (tampered, n) = patch_all(&fx.pczt, &varint(fx.fee), &varint(fx.fee + 1));
        assert!(n > 0, "value_sum varint not found in the wire bytes");

        let err = inspect_zcash_pczt(tampered.clone())
            .expect_err("a declared value balance the commitments contradict must be refused");
        let msg = format!("{err:?}");
        assert!(
            msg.contains("does not match the action value commitments"),
            "unexpected rejection reason: {}",
            msg
        );
        assert!(
            sign_zcash_pczt(MNEMONIC, 0, tampered).is_err(),
            "the device must refuse to sign a fee it refused to display"
        );
    }

    /// The fee check must not be disableable by withholding `bsk`, the way the
    /// output check was disableable by withholding `rseed`.
    #[test]
    fn stripped_bsk_does_not_disable_the_fee_check() {
        let fx = build_v5_fixture(true, false);
        inspect_zcash_pczt(fx.pczt.clone()).expect("honest PCZT inspects");

        let no_bsk =
            pczt::roles::redactor::Redactor::new(Pczt::parse(&fx.pczt).expect("parse honest PCZT"))
                .redact_orchard_with(|mut o| {
                    o.clear_bsk();
                })
                .finish()
                .serialize()
                .expect("serialize bsk-stripped PCZT");

        let err = inspect_zcash_pczt(no_bsk.clone())
            .expect_err("without bsk the declared fee is unprovable and must be refused");
        let msg = format!("{err:?}");
        assert!(
            msg.contains("binding key absent"),
            "unexpected rejection reason: {}",
            msg
        );
        assert!(
            sign_zcash_pczt(MNEMONIC, 0, no_bsk).is_err(),
            "the device must refuse to sign a fee it refused to display"
        );
    }

    /// TAMPER DETECTION: a hostile wallet swaps the displayed recipient for one
    /// the user trusts while the transaction pays somebody else.
    #[test]
    fn tampered_output_recipient_is_rejected() {
        let fx = build_v5_fixture(true, false);
        inspect_zcash_pczt(fx.pczt.clone()).expect("honest PCZT inspects");

        // A different, well-formed orchard receiver.
        let decoy_sk = orchard::keys::SpendingKey::from_bytes([11u8; 32]).unwrap();
        let decoy = orchard::keys::FullViewingKey::from(&decoy_sk)
            .address_at(0u32, orchard::keys::Scope::External)
            .to_raw_address_bytes();
        assert_ne!(decoy, fx.recipient_raw);

        let (tampered, n) = patch_all(&fx.pczt, &fx.recipient_raw, &decoy);
        assert!(n > 0, "output recipient not found in the wire bytes");

        let parsed = Pczt::parse(&tampered).expect("tampered PCZT still parses");
        assert!(
            parsed
                .orchard()
                .actions()
                .iter()
                .any(|a| a.output().recipient() == &Some(decoy)),
            "the recipient the review screen would display was not actually changed"
        );

        let err = inspect_zcash_pczt(tampered.clone()).expect_err(
            "a PCZT whose displayed recipient contradicts its note commitment must be refused",
        );
        let msg = format!("{err:?}");
        assert!(
            msg.contains("does not match what it claims to pay"),
            "unexpected rejection reason: {}",
            msg
        );
        assert!(
            sign_zcash_pczt(MNEMONIC, 0, tampered).is_err(),
            "the device must refuse to sign a PCZT it refused to display"
        );
    }

    // ── consensus gate (zcash_consensus_gate) ──────────────────────────────
    // Patch the global branch id in place. postcard LEB128-encodes the u32 and
    // every id used here is >= 2^28 (5 bytes), so offsets stay intact.
    fn leb128(mut v: u32) -> Vec<u8> {
        let mut out = Vec::new();
        loop {
            let b = (v & 0x7f) as u8;
            v >>= 7;
            if v == 0 {
                out.push(b);
                return out;
            }
            out.push(b | 0x80);
        }
    }

    fn with_branch(pczt: &[u8], from: u32, to: u32) -> Vec<u8> {
        let (needle, repl) = (leb128(from), leb128(to));
        let at = pczt
            .windows(needle.len())
            .position(|w| w == needle.as_slice())
            .expect("branch id present");
        let mut out = pczt.to_vec();
        out[at..at + repl.len()].copy_from_slice(&repl);
        assert_eq!(
            *Pczt::parse(&out).unwrap().global().consensus_branch_id(),
            to
        );
        out
    }

    #[test]
    fn unknown_branch_is_refused_before_display() {
        use super::zcash_consensus_gate::BRANCH_NU6_3;
        let (pczt, _, _, _) = build_v6_migration();
        let bad = with_branch(&pczt, BRANCH_NU6_3, 0xdead_beef);
        let e = format!("{:?}", inspect_zcash_pczt(bad).expect_err("refused"));
        assert!(
            e.contains("Unsupported consensus branch 0xdeadbeef"),
            "{}",
            e
        );
    }

    #[test]
    fn nu7_migration_is_visible_and_signed() {
        use super::zcash_consensus_gate::BRANCH_NU7;
        let (bytes, fee, migrated, _) = build_v6_migration_on(params_nu7(), false);
        assert_eq!(
            *Pczt::parse(&bytes).unwrap().global().consensus_branch_id(),
            BRANCH_NU7,
            "builder targeted NU7"
        );

        let i = inspect_zcash_pczt(bytes.clone()).expect("inspect NU7");
        assert_eq!(i.fee_zat, fee as i64, "review shows the real network fee");
        let shown: u64 = i.outputs.iter().map(|o| o.value).sum();
        assert_eq!(shown, migrated, "migrated value visible for review");

        let signed = Pczt::parse(&sign_zcash_pczt(MNEMONIC, 0, bytes).expect("sign NU7"))
            .expect("signed NU7 PCZT parses");
        for (n, a) in signed.orchard().actions().iter().enumerate() {
            assert!(
                a.spend().spend_auth_sig().is_some(),
                "NU7 orchard action {} left unsigned",
                n
            );
        }
    }

    // ── FROST payout verify (frost_multisig::frost_verify_pczt) ────────────

    /// The wallet key as the UFVK string zigner stores for a FROST group.
    fn wallet_uview() -> String {
        use zcash_address::unified::{Encoding, Fvk, Ufvk};
        let (_, fvk) = wallet_keys();
        Ufvk::try_from_items(vec![Fvk::Orchard(fvk.to_bytes())])
            .expect("ufvk")
            .encode(&zcash_protocol::consensus::NetworkType::Regtest)
    }

    /// V6 shielded sighash straight from `v6_signature_hash` over the PCZT's
    /// effects. The verifier's `Signer` picks the digest by tx version; this
    /// pins it to V6, so a match shows the verifier chose the V6 digest (the
    /// old one took V5). Same crate on both sides - not a cross-version check.
    fn v6_sighash(pczt: &[u8]) -> String {
        use zcash_primitives::transaction::sighash::SignableInput;
        use zcash_primitives::transaction::sighash_v6::v6_signature_hash;
        use zcash_primitives::transaction::txid::TxIdDigester;
        let tx = Pczt::parse(pczt).unwrap().into_effects().expect("effects");
        let parts = tx.digest(TxIdDigester);
        hex::encode(v6_signature_hash(&tx, &SignableInput::Shielded, &parts).as_bytes())
    }

    fn assert_frost_verifies(net: LocalNetwork, what: &str) {
        let (bytes, _, migrated, _) = build_v6_migration_on(net, true);
        let pczt_hex = hex::encode(&bytes);
        let sighash = v6_sighash(&bytes);

        let v: serde_json::Value = serde_json::from_str(
            &crate::frost_multisig::frost_verify_pczt(&pczt_hex, &sighash, &wallet_uview())
                .unwrap_or_else(|e| panic!("{}: verify failed: {}", what, e)),
        )
        .unwrap();
        assert_eq!(v["sighash_match"], true, "{what}: {v}");
        assert_eq!(
            v["total_change_zat"], migrated,
            "{what}: the ironwood output is decrypted and shown: {v}"
        );

        let mut wrong = sighash.into_bytes();
        wrong[0] = if wrong[0] == b'0' { b'1' } else { b'0' };
        let v: serde_json::Value = serde_json::from_str(
            &crate::frost_multisig::frost_verify_pczt(
                &pczt_hex,
                std::str::from_utf8(&wrong).unwrap(),
                &wallet_uview(),
            )
            .unwrap(),
        )
        .unwrap();
        assert_eq!(
            v["sighash_match"], false,
            "{what}: other sighash must not match"
        );
    }

    #[test]
    fn frost_verify_binds_a_v6_ironwood_spend() {
        assert_frost_verifies(params(true), "NU6.3");
    }

    #[test]
    fn frost_verify_binds_an_nu7_spend() {
        assert_frost_verifies(params_nu7(), "NU7");
    }
}
