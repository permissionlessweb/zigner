//! What the camera may hand to a parser on this device.
//!
//! The camera is the only untrusted input. The camera layer classifies each
//! code, and a network's content parser is reachable only when this device
//! holds something it would be used with: no Penumbra key, no Penumbra
//! parsing; no Zcash key or FROST wallet, no Zcash parsing; no Cosmos key, no Cosmos
//! parsing. The answer is
//! derived from the database every time it is asked, so there is no setting
//! that can drift from what the device holds.
//!
//! This gates content parsers only. The multi-frame transport decoder
//! (`qr_reader_phone`: RaptorQ, legacy frames, banana-split shares) runs on
//! byte-mode codes before classification and is not covered here.
//!
//! Three kinds stay open on an empty device, because they are how a device
//! stops being empty: recovery (banana split and backup import), creating
//! a FROST wallet, and signed module updates.

use constants::FROST_KEYS_TREE;
use definitions::crypto::Encryption;

use crate::identities::get_all_addresses;
use crate::Result;

/// Signed protocol-module updates. The signature is checked before use.
pub const MODULE: &str = "module";
/// Seed recovery and backup import.
pub const BACKUP: &str = "backup";
/// FROST key generation, which starts before any FROST wallet exists.
pub const FROST_DKG: &str = "frost-dkg";
/// FROST signing rounds.
pub const FROST: &str = "frost";
/// Zcash notes and PCZT signing requests.
pub const ZCASH: &str = "zcash";
/// Penumbra signing requests.
pub const PENUMBRA: &str = "penumbra";
/// ZID identity challenges, derived from a seed.
pub const AUTH: &str = "auth";
/// Cosmos (incl. Terp) sign requests, including raptorq multi-frame ones.
pub const COSMOS: &str = "cosmos";

/// The kinds of QR code this device's camera may parse. Polkadot is never
/// in it; Cosmos is in it only when a Cosmos key is held.
pub fn readable(database: &sled::Db) -> Result<Vec<&'static str>> {
    // A FROST wallet is any entry in its tree; nothing is deserialized.
    let has_frost_wallet = !database.open_tree(FROST_KEYS_TREE)?.is_empty();
    let (mut any_key, mut zcash_key, mut penumbra_key, mut cosmos_key) =
        (false, false, false, false);
    for (_, address_details) in get_all_addresses(database)? {
        any_key = true;
        match address_details.encryption {
            Encryption::Zcash => zcash_key = true,
            Encryption::Penumbra => penumbra_key = true,
            Encryption::Cosmos => cosmos_key = true,
            _ => {}
        }
    }

    let mut out = vec![MODULE, BACKUP, FROST_DKG];
    if has_frost_wallet {
        out.push(FROST);
    }
    if has_frost_wallet || zcash_key {
        out.push(ZCASH);
    }
    if penumbra_key {
        out.push(PENUMBRA);
    }
    if cosmos_key {
        out.push(COSMOS);
    }
    if any_key {
        out.push(AUTH);
    }
    Ok(out)
}
