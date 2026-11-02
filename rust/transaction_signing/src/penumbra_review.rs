//! What a Penumbra transaction does, for the review screen.
//!
//! Everything shown comes from the `TransactionPlan` itself - the same bytes
//! `verify_effect_hash` hashes before signing - decoded with the Penumbra
//! SDK. Nothing comes from the QR header or from the hot wallet's own
//! description. An action this module cannot show is refused, not skipped:
//! the signature would authorize it whether or not it was on the screen.

use penumbra_asset::{asset::Cache, Value, STAKING_TOKEN_ASSET_ID};
use penumbra_keys::Address;
use penumbra_proto::DomainType;
use penumbra_transaction::plan::{ActionPlan, TransactionPlan};

use crate::{Error, Result};

/// One line of the review: what moves, how much, and to or from whom.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ReviewLine {
    /// "Send", "Spend", "Swap", "Delegate", ...
    pub kind: String,
    /// Amount with its denomination, or the base-unit amount and asset ID
    /// when the denomination is not one the SDK knows.
    pub amount: String,
    /// Full address, validator identity or destination; empty when none.
    pub counterparty: String,
    /// The counterparty is one of this device's own receive addresses.
    pub is_own: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PlanReview {
    pub chain_id: String,
    pub expiry_height: u64,
    pub fee: String,
    pub memo: Option<String>,
    pub lines: Vec<ReviewLine>,
    /// Spend-authorization randomizers, in plan order. The QR carries its own
    /// copy; the signer checks the two agree.
    pub spend_randomizers: Vec<[u8; 32]>,
}

/// Decode `plan_bytes` and describe every action, or refuse.
///
/// `own_addresses` are this device's stored receive addresses (bech32m). An
/// output to one of them is marked as the user's own; nothing the hot wallet
/// supplies can mark an output that way.
pub fn review_plan(plan_bytes: &[u8], own_addresses: &[String]) -> Result<PlanReview> {
    let plan = TransactionPlan::decode(plan_bytes)
        .map_err(|e| Error::PenumbraKeyDerivation(format!("failed to decode plan: {e}")))?;
    let cache = Cache::with_known_assets();
    let fmt = |value: Value| value.format(&cache);
    let own = |addr: &Address| own_addresses.iter().any(|a| *a == addr.to_string());
    let line = |kind: &str, amount: String, counterparty: String, is_own: bool| ReviewLine {
        kind: kind.to_string(),
        amount,
        counterparty,
        is_own,
    };
    let refuse = |kind: &str| {
        Err(Error::PenumbraKeyDerivation(format!(
            "this transaction contains a {kind} action, which Zigner cannot show yet - \
             refusing to sign it"
        )))
    };

    let mut lines = Vec::with_capacity(plan.actions.len());
    for action in &plan.actions {
        lines.push(match action {
            ActionPlan::Spend(spend) => line(
                "Spend",
                fmt(spend.note.value()),
                spend.note.address().to_string(),
                own(&spend.note.address()),
            ),
            ActionPlan::Output(output) => {
                let is_own = own(&output.dest_address);
                line(
                    if is_own { "To you" } else { "Send" },
                    fmt(output.value),
                    output.dest_address.to_string(),
                    is_own,
                )
            }
            ActionPlan::Swap(swap) => {
                let p = &swap.swap_plaintext;
                let pair = &p.trading_pair;
                let give = if p.delta_1_i.value() != 0 {
                    Value {
                        amount: p.delta_1_i,
                        asset_id: pair.asset_1(),
                    }
                } else {
                    Value {
                        amount: p.delta_2_i,
                        asset_id: pair.asset_2(),
                    }
                };
                let into = if p.delta_1_i.value() != 0 {
                    pair.asset_2()
                } else {
                    pair.asset_1()
                };
                line(
                    "Swap",
                    format!(
                        "{} into {}",
                        fmt(give),
                        cache.get(&into).map_or(into.to_string(), |d| d.to_string())
                    ),
                    p.claim_address.to_string(),
                    own(&p.claim_address),
                )
            }
            ActionPlan::SwapClaim(claim) => {
                let p = &claim.swap_plaintext;
                line(
                    "Claim swap",
                    format!("claim fee {}", fmt(p.claim_fee.0)),
                    p.claim_address.to_string(),
                    own(&p.claim_address),
                )
            }
            ActionPlan::Delegate(d) => line(
                "Delegate",
                fmt(Value {
                    amount: d.unbonded_amount,
                    asset_id: *STAKING_TOKEN_ASSET_ID,
                }),
                d.validator_identity.to_string(),
                false,
            ),
            ActionPlan::Undelegate(u) => line(
                "Undelegate",
                fmt(Value {
                    amount: u.unbonded_amount,
                    asset_id: *STAKING_TOKEN_ASSET_ID,
                }),
                u.validator_identity.to_string(),
                false,
            ),
            ActionPlan::UndelegateClaim(c) => line(
                "Claim undelegation",
                format!("{} unbonding tokens", c.unbonding_amount),
                c.validator_identity.to_string(),
                false,
            ),
            ActionPlan::Ics20Withdrawal(w) => line(
                "IBC withdrawal",
                fmt(Value {
                    amount: w.amount,
                    asset_id: w.denom.id(),
                }),
                format!("{} via {}", w.destination_chain_address, w.source_channel),
                false,
            ),
            ActionPlan::ValidatorDefinition(_) => return refuse("validator definition"),
            ActionPlan::IbcAction(_) => return refuse("IBC relay"),
            ActionPlan::ProposalSubmit(_) => return refuse("proposal submission"),
            ActionPlan::ProposalWithdraw(_) => return refuse("proposal withdrawal"),
            ActionPlan::DelegatorVote(_) => return refuse("delegator vote"),
            ActionPlan::ValidatorVote(_) => return refuse("validator vote"),
            ActionPlan::ProposalDepositClaim(_) => return refuse("proposal deposit claim"),
            ActionPlan::PositionOpen(_) => return refuse("liquidity position open"),
            ActionPlan::PositionClose(_) => return refuse("liquidity position close"),
            ActionPlan::PositionWithdraw(_) => return refuse("liquidity position withdrawal"),
            ActionPlan::CommunityPoolSpend(_) => return refuse("community pool spend"),
            ActionPlan::CommunityPoolOutput(_) => return refuse("community pool output"),
            ActionPlan::CommunityPoolDeposit(_) => return refuse("community pool deposit"),
            ActionPlan::ActionDutchAuctionSchedule(_) => return refuse("Dutch auction"),
            ActionPlan::ActionDutchAuctionEnd(_) => return refuse("Dutch auction end"),
            ActionPlan::ActionDutchAuctionWithdraw(_) => return refuse("Dutch auction withdrawal"),
            ActionPlan::ActionLiquidityTournamentVote(_) => {
                return refuse("liquidity tournament vote")
            }
        });
    }

    let params = &plan.transaction_parameters;
    Ok(PlanReview {
        chain_id: params.chain_id.clone(),
        expiry_height: params.expiry_height,
        fee: fmt(params.fee.0),
        memo: plan
            .memo
            .as_ref()
            .map(|m| m.plaintext.text().to_string())
            .filter(|t| !t.is_empty()),
        lines,
        spend_randomizers: plan
            .spend_plans()
            .map(|s| s.randomizer.to_bytes())
            .collect(),
    })
}

#[cfg(test)]
mod tests {
    use super::*;
    use penumbra_keys::keys::{AddressIndex, SpendKey, SpendKeyBytes};
    use penumbra_proto::core::component::{
        dex::v1 as dex, fee::v1 as fee, shielded_pool::v1 as sp,
    };
    use penumbra_proto::core::transaction::v1 as tx;
    use penumbra_proto::Message;

    fn address(seed: u8) -> Address {
        SpendKey::from(SpendKeyBytes([seed; 32]))
            .full_viewing_key()
            .payment_address(AddressIndex::new(0))
            .0
    }

    fn output(to: &Address, amount: u64) -> tx::ActionPlan {
        let value = Value {
            amount: amount.into(),
            asset_id: *STAKING_TOKEN_ASSET_ID,
        };
        tx::ActionPlan {
            action: Some(tx::action_plan::Action::Output(sp::OutputPlan {
                value: Some(value.into()),
                dest_address: Some(to.clone().into()),
                rseed: vec![1; 32],
                value_blinding: vec![0; 32],
                proof_blinding_r: vec![0; 32],
                proof_blinding_s: vec![0; 32],
            })),
        }
    }

    fn plan(actions: Vec<tx::ActionPlan>) -> Vec<u8> {
        let fee_value = Value {
            amount: 2000u64.into(),
            asset_id: *STAKING_TOKEN_ASSET_ID,
        };
        let fee_proto: penumbra_proto::core::asset::v1::Value = fee_value.into();
        tx::TransactionPlan {
            actions,
            transaction_parameters: Some(tx::TransactionParameters {
                expiry_height: 123,
                chain_id: "penumbra-1".to_string(),
                fee: Some(fee::Fee {
                    amount: fee_proto.amount,
                    asset_id: None,
                }),
            }),
            detection_data: None,
            memo: None,
        }
        .encode_to_vec()
    }

    #[test]
    fn shows_recipient_amount_fee_and_chain_from_the_plan() {
        let them = address(9);
        let me = address(7);
        let bytes = plan(vec![output(&them, 5_000_000), output(&me, 1_000)]);
        let review = review_plan(&bytes, &[me.to_string()]).unwrap();

        assert_eq!(review.chain_id, "penumbra-1");
        assert_eq!(review.expiry_height, 123);
        assert!(
            review.fee.ends_with("penumbra") || review.fee.contains("UM"),
            "{}",
            review.fee
        );
        assert_eq!(review.lines.len(), 2);

        let send = &review.lines[0];
        assert_eq!(send.kind, "Send");
        assert_eq!(send.counterparty, them.to_string());
        assert!(!send.is_own);
        assert_eq!(send.amount, "5penumbra");

        let change = &review.lines[1];
        assert_eq!(change.kind, "To you");
        assert!(change.is_own);
    }

    /// An action the screen cannot show is refused, never skipped.
    #[test]
    fn refuses_an_action_it_cannot_show() {
        let close = tx::ActionPlan {
            action: Some(tx::action_plan::Action::PositionClose(dex::PositionClose {
                position_id: Some(dex::PositionId {
                    inner: vec![0; 32],
                    alt_bech32m: String::new(),
                }),
            })),
        };
        let bytes = plan(vec![output(&address(9), 1), close]);
        let err = review_plan(&bytes, &[]).unwrap_err().to_string();
        assert!(err.contains("liquidity position close"), "{}", err);
    }
}
