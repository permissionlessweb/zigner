//! Constants used throughout in [Zigner](https://github.com/rotkonetworks/zigner)
//! and Zigner-supporting ecosystem.
//!
//! Zigner uses **cold** database.
//!
//! The database used on a non air-gapper device for updates generation is
//! called **hot** database.
//!
//! Cold database is generated during the build, so it gets addressed both on
//! active and on signer side.
//!
//! Cold database contains following trees:
//!
//! - [`SPECSTREE`], with network specs in cold database format
//! - [`VERIFIERS`], with network verifiers, i.e. data on who user trusts to
//!   sign updates for a given network
//! - [`METATREE`], with network metadata
//! - [`ADDRTREE`], with user addresses public information
//! - [`SETTREE`], containing general verifier, types information, and Vault
//!   danger status
//! - [`TRANSACTION`], used to store temporarily transaction data while the
//!   user accepts or declines it
//! - [`HISTORY`], with history log
//!
//! Hot database contains following trees:
//!
//! - [`SPECSTREEPREP`], with network specs in hot database format
//! - [`METATREE`], with network metadata, at most two versions for each network
//! - [`META_HISTORY`], with block hash at which the metadata was fetched
//! - [`ADDRESS_BOOK`], with network information needed to make RPC calls
//! - [`SETTREE`], containing types information
//!
//! Common constants are:
//!
//! - tree names in cold database alone or shared between cold and hot databases
//! - key names in [`SPECSTREE`] tree of cold database alone or shared between
//!   cold and hot databases
//! - recurring throughout the Vault seed phrase for Alice
//! - QR graphic settings, used for both static and animated PNG QR codes
//!
//! # Features
//! Feature `"signer"` corresponds to everything related exclusively to Vault
//! air-gapped device. It includes:
//!
//! - keys for [`TRANSACTION`] tree, used for temporary storage for various
//!   kinds of transactions while they are shown to user for approval
//! - display settings for history log and word guesser
//!
//! Feature `"active"` corresponds to all Vault-related things happening
//! exclusively **without** air-gap. It includes:
//!
//! - default database addresses for hot database and cold release database
//! - hot database specific tree names
//! - default color settings for network specs
//! - default files and folders name fragments, for use with `generate_message`
//! - fountain qr generation parameters (large apng fountain qr codes are
//!   generated only on the hot side)
//!
//! Feature `"test"` includes all `"signer"` and `"active"` contents, and some
//! recurring data for integration testing, such as known identicons and known
//! export QR codes.

#![deny(rustdoc::broken_intra_doc_links)]

pub mod test_values;

/// Default folder for cold database generated during the Vault build
pub const COLD_DB_NAME_RELEASE: &str = "../database/database_cold_release";

/// Tree name for the tree storing the network specs in cold database
pub const SPECSTREE: &[u8] = b"chainspecs";

/// Tree name for the tree storing the network verifiers information in cold
/// database
pub const VERIFIERS: &[u8] = b"verifiers";

/// Tree name for the tree storing the network metadata in cold and in hot
/// databases
pub const METATREE: &[u8] = b"metadata";

/// Tree name for the tree storing user addresses associated public
/// information in cold database
pub const ADDRTREE: &[u8] = b"addresses";

/// Tree that held Penumbra addresses up to 0.12.0. Those addresses came from a
/// non-standard derivation that no Penumbra key owns (zigner#35); the tree is
/// never read again and is dropped when a fixed address is stored.
pub const PENUMBRA_ADDRS_UNSPENDABLE: &[u8] = b"penumbra_addresses";

/// Tree storing Penumbra receive addresses derived with penumbra-sdk.
/// Key: hex-encoded key id (see `db_handling::penumbra::key_id`), Value:
/// bech32m address string
pub const PENUMBRA_RECEIVE_ADDRS: &[u8] = b"penumbra_receive_addresses";

/// Tree name for the tree storing Zcash unified addresses
/// Key: hex-encoded FVK prefix (32 bytes), Value: unified address string
pub const ZCASH_ADDRESS_TREE: &[u8] = b"zcash_addresses";

/// Tree name for the tree storing verified Zcash notes (synced via QR)
/// Key: nullifier (32 bytes), Value: CBOR-encoded note data
pub const ZCASH_NOTES_TREE: &[u8] = b"zcash_notes";

/// Tree name for the tree storing FROST multisig key packages
/// Key: wallet_id (8 bytes, truncated blake3 of public_key_package)
/// Value: JSON-encoded FrostWalletData
pub const FROST_KEYS_TREE: &[u8] = b"frost_keys";

/// Tree name for the tree storing fingerprints of consumed FROST signing
/// nonces. Persisting these across app restarts is critical: an attacker
/// who force-quits the app between commitment and signing could otherwise
/// replay an already-used nonce against a different message, leaking the
/// FROST private key. Key: SHA-256(nonces_hex) (32 bytes). Value: 8-byte
/// little-endian unix timestamp.
pub const FROST_NONCES_TREE: &[u8] = b"frost_used_nonces";

/// Tree name for the tree storing trusted Zcash anchor-attestation
/// verifier ed25519 public keys. The signer trusts ANY enabled entry to
/// attest that an anchor matches the chain — supporting self-hosted
/// zidecar instances alongside the built-in default. Key: 32-byte
/// ed25519 pubkey. Value: JSON `{label, added_at, source, enabled}`.
/// Bootstrapped from `ROTKO_ZCASH_VERIFIER` on first DB init when the
/// tree is empty.
pub const ANCHOR_VERIFIERS_TREE: &[u8] = b"anchor_verifiers";

/// Tree name for the tree storing contacts (address book)
/// Key: address string bytes, Value: JSON-encoded contact metadata
pub const CONTACTS_TREE: &[u8] = b"contacts";

/// Rotko Networks ed25519 public key for Zcash anchor verification.
/// The signer trusts this key to attest that anchors come from the real chain.
/// Same trust model as Parity's verifier key in Polkadot Vault.
///
pub const ROTKO_ZCASH_VERIFIER: [u8; 32] = [
    0x5f, 0xbc, 0xaa, 0xbe, 0x9b, 0x00, 0xd2, 0x3a, 0x2b, 0xeb, 0xb6, 0x37, 0x0f, 0x1a, 0xcb, 0xa3,
    0x0e, 0x61, 0x85, 0x4e, 0xce, 0x40, 0x20, 0x0d, 0xf6, 0xf3, 0x23, 0xd6, 0xa2, 0x1a, 0xc7, 0x84,
];

/// Tree name for the tree storing database settings
///
/// In cold database, the settings tree contains general verifier, types
/// information, Vault danger status.
///
/// In hot database, the settings tree contains types information.
pub const SETTREE: &[u8] = b"settings";

/// Tree name for the tree temporarily storing transaction entries
pub const TRANSACTION: &[u8] = b"transaction";

/// Tree name for the tree storing Vault history
pub const HISTORY: &[u8] = b"history";

/// Key in settings tree [`SETTREE`] for encoded types information
pub const TYPES: &[u8] = b"types";

/// Key in settings tree [`SETTREE`] for general verifier information
pub const GENERALVERIFIER: &[u8] = b"general_verifier";

/// Key in settings tree [`SETTREE`] for Vault danger status
pub const DANGER: &[u8] = b"dangerous_encounter";

/// Key in settings tree [`SETTREE`] for Vault database schema version
pub const SCHEMA_VERSION: &[u8] = b"schema_version";

/// Key in transactions tree [`TRANSACTION`] for updates data
pub const STUB: &[u8] = b"stub";

/// Key in transactions tree [`TRANSACTION`] for signable transactions
pub const SIGN: &[u8] = b"sign";

/// Key in transactions tree [`TRANSACTION`] for derivations import data
pub const DRV: &[u8] = b"derivations";

/// Address prefix to display general/chain agnostic addresses
pub const GENERAL_SUBSTRATE_PREFIX: u16 = 42;

/// Maximum number of words displayed to user based on user input in seed
/// recovery process
pub const MAX_WORDS_DISPLAY: usize = 8;

/// Number of entries on log history page
pub const HISTORY_PAGE_SIZE: usize = 20;

/// Default folder for hot database
pub const HOT_DB_NAME: &str = "../database/database_hot";

/// Tree name for the tree storing the network specs in hot database
pub const SPECSTREEPREP: &[u8] = b"chainspecs_prep";

/// Tree name for the tree storing the network block hash at the time of
/// metadata fetch, in hot database
pub const META_HISTORY: &[u8] = b"metadata_history";

/// Tree name for the tree storing the address book in hot database, with data
/// necessary for RPC calls
pub const ADDRESS_BOOK: &[u8] = b"address_book";

/// Default `color` to be used in generating network specs with no color
/// information provided
pub const COLOR: &str = "#660D35";

/// Default `secondary_color` to be used in generating network specs with no
/// color information provided
pub const SECONDARY_COLOR: &str = "#262626";

/// Folder to save payloads ready for signing, for `generate_message` crate
pub const FOLDER: &str = "../files/in_progress";

/// Folder to save completed update messages, for `generate_message` crate
pub const EXPORT_FOLDER: &str = "../files/completed";

/// Alice seed phrase
pub const ALICE_SEED_PHRASE: &str =
    "bottom drive obey lake curtain smoke basket hold race lonely fit walk";

/// Data chunk size for fountain QR code generation
pub const CHUNK_SIZE: u16 = 1072;

/// Main color for QR codes (both static and animated ones)
pub const MAIN_COLOR: [u8; 3] = [0x00, 0x00, 0x00];

/// Main color for **dangerous** QR codes (static only, in Vault)
pub const MAIN_COLOR_DANGER: [u8; 3] = [0xe6, 0x00, 0x7a];

/// Background color for QR codes (both static and animated ones)
pub const BACK_COLOR: [u8; 3] = [0xff, 0xff, 0xff];

/// Color palette for QR codes (both static and animated ones)
pub fn qr_palette() -> Vec<u8> {
    [MAIN_COLOR.to_vec(), BACK_COLOR.to_vec()].concat()
}

/// Color palette for **dangerous** QR codes (static only, in Vault)
pub fn qr_palette_danger() -> Vec<u8> {
    [MAIN_COLOR_DANGER.to_vec(), BACK_COLOR.to_vec()].concat()
}

/// Scaling factor for QR codes (size of QR code dot, in pixels)
pub const SCALING: i32 = 4;

/// Numerator of the fraction of time (in seconds) for which the frame in the
/// animated QR code is displayed
pub const FPS_NOM: u16 = 1;

/// Denominator of the fraction of time (in seconds) for which the frame in the
/// animated QR code is displayed
pub const FPS_DEN: u16 = 15;

/// Width of the QR code border, in QR code dots
pub const BORDER: i32 = 4;

/// Current database schema version
pub const LIVE_SCHEMA_VERSION: u32 = 1;

/// Feature flag to disable dynamic derivations
pub const ENABLE_DYNAMIC_DERIVATIONS: bool = true;
