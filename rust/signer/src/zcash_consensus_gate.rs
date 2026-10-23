//! Native-path twin of `pczt_signing::consensus_gate` (the module path).
//!
//! Both PCZT signers link the same zakura `pczt` release, but from different
//! workspaces, so the rules are restated here rather than shared - keep them
//! in step. See that module for the reasoning; in short: refuse, before any
//! summary reaches the screen, every PCZT this build cannot verify and fully
//! display. Activation needs no height table: the branch id in the PCZT is
//! chosen by the online wallet and enforced by the network (see that module).
//!
//! One rule is stricter here: this path has no display for transparent
//! outputs, so it refuses them. A transparent output's value is covered by the
//! sighash, but a payment the screen never shows is a payment the user never
//! approved. (The module path renders them; route t-address sends there.)

use crate::ErrorDisplayed;
use pczt::Pczt;

pub const BRANCH_NU5: u32 = 0xc2d6_d0b4;
pub const BRANCH_NU6: u32 = 0xc8e7_1055;
pub const BRANCH_NU6_1: u32 = 0x4dec_4df0;
pub const BRANCH_NU6_2: u32 = 0x5437_f330;
pub const BRANCH_NU6_3: u32 = 0x37a5_165b;
pub const BRANCH_NU7: u32 = 0x7719_0ad9;

const V5_TX_VERSION: u32 = 5;
const V5_VERSION_GROUP_ID: u32 = 0x26A7_270A;
const V6_TX_VERSION: u32 = zcash_protocol::constants::V6_TX_VERSION;
const V6_VERSION_GROUP_ID: u32 = zcash_protocol::constants::V6_VERSION_GROUP_ID;

fn refuse(s: impl Into<String>) -> ErrorDisplayed {
    ErrorDisplayed::Str { s: s.into() }
}

pub fn check_supported(pczt: &Pczt) -> Result<(), ErrorDisplayed> {
    let g = pczt.global();
    let (version, group) = (*g.tx_version(), *g.version_group_id());
    let branch = *g.consensus_branch_id();

    let is_v5 = version == V5_TX_VERSION && group == V5_VERSION_GROUP_ID;
    let is_v6 = version == V6_TX_VERSION && group == V6_VERSION_GROUP_ID;
    if !is_v5 && !is_v6 {
        return Err(refuse(format!(
            "Unsupported transaction format (version {version}, group {group:#010x}) \
             - refusing to display or sign it"
        )));
    }

    match branch {
        BRANCH_NU5 | BRANCH_NU6 | BRANCH_NU6_1 | BRANCH_NU6_2 if is_v5 => {}
        BRANCH_NU5 | BRANCH_NU6 | BRANCH_NU6_1 | BRANCH_NU6_2 => {
            return Err(refuse(format!(
                "V6 transaction on pre-NU6.3 branch {branch:#010x} - refusing to display or sign it"
            )))
        }
        BRANCH_NU6_3 | BRANCH_NU7 => {}
        other => {
            return Err(refuse(format!(
                "Unsupported consensus branch {other:#010x} - refusing to display or sign it"
            )))
        }
    }

    let sapling = pczt.sapling();
    if !sapling.spends().is_empty() || !sapling.outputs().is_empty() || *sapling.value_sum() != 0 {
        return Err(refuse(
            "Transaction contains a Sapling component, which Zigner cannot display \
             - refusing to sign it",
        ));
    }

    let t_outputs = pczt.transparent().outputs().len();
    if t_outputs != 0 {
        return Err(refuse(format!(
            "Transaction pays {t_outputs} transparent output(s), which this review screen \
             cannot display - refusing to sign it"
        )));
    }

    Ok(())
}
