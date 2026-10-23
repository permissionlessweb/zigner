//! cosmos transaction signing
//!
//! parses amino JSON sign requests from QR codes and signs with secp256k1 ECDSA.
//! cosmos amino signing: sign SHA256(sign_doc_bytes) with secp256k1.
//!
//! NOTE: we cannot use sp_core::ecdsa::Pair::sign() because it uses blake2b-256
//! as prehash (Substrate convention). Cosmos requires SHA256.

use crate::{Error, Result};

/// QR prelude bytes for cosmos sign request: [0x53, 0x05, 0x10]
const COSMOS_PRELUDE: [u8; 3] = [0x53, 0x05, 0x10];

/// maximum sign doc size (64 KiB). amino JSON for any reasonable transaction
/// is well under this. defense in depth against oversized payloads.
const MAX_SIGN_DOC_LEN: usize = 65536;

/// parsed cosmos sign request from QR
pub struct CosmosSignRequest {
    /// BIP44 account index (hardened `account'` in m/44'/coin'/account'/0/index)
    pub account_index: u32,
    /// BIP44 address index (the non-hardened `.../0/index`). Lets the wallet
    /// spend from fresh, never-reused receive addresses derived off one account.
    pub address_index: u32,
    /// chain name (e.g. "noble", "osmosis")
    pub chain_name: String,
    /// amino JSON sign doc bytes (UTF-8)
    pub sign_doc_bytes: Vec<u8>,
}

/// a single parsed message from the amino sign doc
pub struct CosmosMsg {
    /// message type (e.g. "Send", "IBC Transfer", "Swap", "Contract Call")
    pub msg_type: String,
    /// target address (recipient, validator, contract, etc.)
    pub recipient: String,
    /// amount string (e.g. "1000000 uusdc")
    pub amount: String,
    /// extra detail the user must see (min output, pool id, raw contract msg, etc.)
    pub detail: String,
    /// true if this message cannot be fully verified by the wallet.
    /// contract calls and unknown types are inherently blind — the UI
    /// must warn the user prominently before signing.
    pub blind: bool,
}

/// display info extracted from amino JSON sign doc
pub struct CosmosSignDocDisplay {
    /// chain_id from sign doc
    pub chain_id: String,
    /// ALL messages in the sign doc — a cold wallet must never hide messages
    pub msgs: Vec<CosmosMsg>,
    /// fee string
    pub fee: String,
    /// memo
    pub memo: String,
}

impl CosmosSignRequest {
    /// parse a cosmos sign request from QR hex data
    ///
    /// format:
    /// ```text
    /// [0x53][0x05][0x10]       3B  prelude
    /// [account_index: 4 LE]    4B
    /// [chain_name_len: 1]      1B
    /// [chain_name: N]          NB
    /// [sign_doc_len: 4 LE]     4B
    /// [sign_doc_bytes: N]      NB  canonical amino JSON
    /// [address_index: 4 LE]    4B  OPTIONAL - absent means 0 (back-compat)
    /// ```
    pub fn from_qr_hex(hex: &str) -> Result<Self> {
        let bytes =
            hex::decode(hex).map_err(|e| Error::Other(anyhow::anyhow!("invalid hex: {e}")))?;

        if bytes.len() < 12 {
            return Err(Error::Other(anyhow::anyhow!("QR data too short")));
        }

        // check prelude
        if bytes[0..3] != COSMOS_PRELUDE {
            return Err(Error::Other(anyhow::anyhow!(
                "invalid cosmos QR prelude: expected 530510, got {:02x}{:02x}{:02x}",
                bytes[0],
                bytes[1],
                bytes[2]
            )));
        }

        let mut offset = 3;

        // account index (4 bytes LE)
        let account_index = u32::from_le_bytes(
            bytes[offset..offset + 4]
                .try_into()
                .map_err(|_| Error::Other(anyhow::anyhow!("failed to read account index")))?,
        );
        offset += 4;

        // chain name (length-prefixed, 1 byte)
        let chain_name_len = bytes[offset] as usize;
        offset += 1;

        if offset + chain_name_len > bytes.len() {
            return Err(Error::Other(anyhow::anyhow!(
                "chain name extends past end of data"
            )));
        }

        let chain_name = String::from_utf8(bytes[offset..offset + chain_name_len].to_vec())
            .map_err(|e| Error::Other(anyhow::anyhow!("invalid chain name UTF-8: {e}")))?;
        offset += chain_name_len;

        // sign doc bytes (length-prefixed, 4 bytes LE)
        if offset + 4 > bytes.len() {
            return Err(Error::Other(anyhow::anyhow!("missing sign doc length")));
        }
        let sign_doc_len = u32::from_le_bytes(
            bytes[offset..offset + 4]
                .try_into()
                .map_err(|_| Error::Other(anyhow::anyhow!("failed to read sign doc length")))?,
        ) as usize;
        offset += 4;

        if sign_doc_len > MAX_SIGN_DOC_LEN {
            return Err(Error::Other(anyhow::anyhow!(
                "sign doc too large ({sign_doc_len} bytes, max {MAX_SIGN_DOC_LEN})"
            )));
        }

        if offset + sign_doc_len > bytes.len() {
            return Err(Error::Other(anyhow::anyhow!(
                "sign doc extends past end of data (need {}, have {})",
                offset + sign_doc_len,
                bytes.len()
            )));
        }

        let sign_doc_bytes = bytes[offset..offset + sign_doc_len].to_vec();
        offset += sign_doc_len;

        // Optional trailing address_index (4 bytes LE). Absent = 0, keeping QRs
        // that predate fresh-address derivation valid. Any other trailing length
        // is a malformed/ambiguous payload and is rejected.
        let address_index = match bytes.len() - offset {
            0 => 0,
            4 => u32::from_le_bytes(
                bytes[offset..offset + 4]
                    .try_into()
                    .map_err(|_| Error::Other(anyhow::anyhow!("failed to read address index")))?,
            ),
            n => {
                return Err(Error::Other(anyhow::anyhow!(
                    "trailing data after sign doc ({n} extra bytes)"
                )))
            }
        };

        Ok(CosmosSignRequest {
            account_index,
            address_index,
            chain_name,
            sign_doc_bytes,
        })
    }
}

// ============================================================================
// JSON field helpers — no allocations escape scope, no Box::leak
// ============================================================================

/// format a single coin from {"amount": "X", "denom": "Y"}
fn format_coin(c: &serde_json::Value) -> String {
    let a = c["amount"].as_str().unwrap_or("0");
    let d = c["denom"].as_str().unwrap_or("");
    format!("{a} {d}")
}

fn allowance_spend(v: &serde_json::Value) -> String {
    match v["allowance"]["value"]["spend_limit"].as_array() {
        Some(coins) if !coins.is_empty() => coins.iter().map(format_coin).collect::<Vec<_>>().join(", "),
        _ => "unlimited".to_string(),
    }
}

/// format the first coin from an array of coins
fn format_first_coin(arr: &serde_json::Value) -> String {
    arr.as_array()
        .and_then(|a| a.first())
        .map(format_coin)
        .unwrap_or_else(|| "unknown".to_string())
}

/// format coin from an object (not array) — used by delegate/undelegate/single-token fields
fn format_coin_obj(obj: &serde_json::Value) -> String {
    obj.as_object()
        .map(|o| {
            let a = o.get("amount").and_then(|v| v.as_str()).unwrap_or("0");
            let d = o.get("denom").and_then(|v| v.as_str()).unwrap_or("");
            format!("{a} {d}")
        })
        .unwrap_or_else(|| "unknown".to_string())
}

/// extract a JSON number or string as a display string
fn json_num_or_str(v: &serde_json::Value) -> String {
    if let Some(s) = v.as_str() {
        s.to_string()
    } else if let Some(n) = v.as_u64() {
        n.to_string()
    } else if let Some(n) = v.as_i64() {
        n.to_string()
    } else {
        "?".to_string()
    }
}

/// format swap routes: "pool 1 → uatom, pool 7 → uosmo"
fn format_routes(routes: &[serde_json::Value]) -> String {
    if routes.is_empty() {
        return String::new();
    }
    let parts: Vec<String> = routes
        .iter()
        .map(|r| {
            let id = json_num_or_str(&r["pool_id"]);
            let denom = r["token_out_denom"].as_str().unwrap_or("?");
            format!("pool {id} → {denom}")
        })
        .collect();
    parts.join(", ")
}

// ============================================================================
// message parsing
// ============================================================================

impl CosmosMsg {
    fn from_json(m: &serde_json::Value) -> Self {
        let typ = m["type"].as_str().unwrap_or("");
        let v = &m["value"];
        match typ {
            // ================================================================
            // standard cosmos-sdk
            // ================================================================
            "cosmos-sdk/MsgSend" => CosmosMsg {
                msg_type: "Send".to_string(),
                recipient: v["to_address"].as_str().unwrap_or("").to_string(),
                amount: format_first_coin(&v["amount"]),
                detail: String::new(),
                blind: false,
            },
            "cosmos-sdk/MsgTransfer" => CosmosMsg {
                msg_type: "IBC Transfer".to_string(),
                recipient: v["receiver"].as_str().unwrap_or("").to_string(),
                amount: format_coin_obj(&v["token"]),
                detail: {
                    let ch = v["source_channel"].as_str().unwrap_or("");
                    if ch.is_empty() {
                        String::new()
                    } else {
                        format!("channel: {ch}")
                    }
                },
                blind: false,
            },
            "cosmos-sdk/MsgDelegate" => CosmosMsg {
                msg_type: "Delegate".to_string(),
                recipient: v["validator_address"].as_str().unwrap_or("").to_string(),
                amount: format_coin_obj(&v["amount"]),
                detail: String::new(),
                blind: false,
            },
            "cosmos-sdk/MsgUndelegate" => CosmosMsg {
                msg_type: "Undelegate".to_string(),
                recipient: v["validator_address"].as_str().unwrap_or("").to_string(),
                amount: format_coin_obj(&v["amount"]),
                detail: String::new(),
                blind: false,
            },
            "cosmos-sdk/MsgBeginRedelegate" => CosmosMsg {
                msg_type: "Redelegate".to_string(),
                recipient: v["validator_dst_address"]
                    .as_str()
                    .unwrap_or("")
                    .to_string(),
                amount: format_coin_obj(&v["amount"]),
                detail: {
                    let src = v["validator_src_address"].as_str().unwrap_or("");
                    if src.is_empty() {
                        String::new()
                    } else {
                        format!("from: {src}")
                    }
                },
                blind: false,
            },
            "cosmos-sdk/MsgWithdrawDelegationReward" => CosmosMsg {
                msg_type: "Claim Rewards".to_string(),
                recipient: v["validator_address"].as_str().unwrap_or("").to_string(),
                amount: String::new(),
                detail: String::new(),
                blind: false,
            },
            "cosmos-sdk/MsgVote" => CosmosMsg {
                msg_type: "Vote".to_string(),
                recipient: String::new(),
                amount: String::new(),
                detail: format!(
                    "proposal {} — {}",
                    json_num_or_str(&v["proposal_id"]),
                    v["option"].as_str().unwrap_or("?")
                ),
                blind: false,
            },

            // ================================================================
            // osmosis GAMM (classic AMM pools)
            // ================================================================
            "osmosis/gamm/swap-exact-amount-in" => {
                let routes = v["routes"].as_array().map(|a| a.as_slice()).unwrap_or(&[]);
                CosmosMsg {
                    msg_type: "Swap".to_string(),
                    recipient: String::new(),
                    amount: format_coin_obj(&v["token_in"]),
                    detail: {
                        let min_out = v["token_out_min_amount"].as_str().unwrap_or("0");
                        let route_str = format_routes(routes);
                        if route_str.is_empty() {
                            format!("min output: {min_out}")
                        } else {
                            format!("min output: {min_out}, route: {route_str}")
                        }
                    },
                    blind: false,
                }
            }
            "osmosis/gamm/swap-exact-amount-out" => {
                let routes = v["routes"].as_array().map(|a| a.as_slice()).unwrap_or(&[]);
                CosmosMsg {
                    msg_type: "Swap (exact out)".to_string(),
                    recipient: String::new(),
                    amount: {
                        let max_in = v["token_in_max_amount"].as_str().unwrap_or("0");
                        format!("max input: {max_in}")
                    },
                    detail: {
                        let out = format_coin_obj(&v["token_out"]);
                        let route_str = format_routes(routes);
                        if route_str.is_empty() {
                            format!("output: {out}")
                        } else {
                            format!("output: {out}, route: {route_str}")
                        }
                    },
                    blind: false,
                }
            }
            "osmosis/gamm/join-pool" => CosmosMsg {
                msg_type: "Join Pool".to_string(),
                recipient: String::new(),
                amount: {
                    let tokens: Vec<String> = v["token_in_maxs"]
                        .as_array()
                        .map(|a| a.iter().map(format_coin).collect())
                        .unwrap_or_default();
                    tokens.join(", ")
                },
                detail: {
                    let pool_id = json_num_or_str(&v["pool_id"]);
                    let shares = v["share_out_amount"].as_str().unwrap_or("?");
                    format!("pool {pool_id}, min shares: {shares}")
                },
                blind: false,
            },
            "osmosis/gamm/exit-pool" => CosmosMsg {
                msg_type: "Exit Pool".to_string(),
                recipient: String::new(),
                amount: {
                    let shares = v["share_in_amount"].as_str().unwrap_or("?");
                    format!("{shares} shares")
                },
                detail: {
                    let pool_id = json_num_or_str(&v["pool_id"]);
                    let mins: Vec<String> = v["token_out_mins"]
                        .as_array()
                        .map(|a| a.iter().map(format_coin).collect())
                        .unwrap_or_default();
                    if mins.is_empty() {
                        format!("pool {pool_id}")
                    } else {
                        format!("pool {pool_id}, min out: {}", mins.join(", "))
                    }
                },
                blind: false,
            },

            // ================================================================
            // osmosis concentrated liquidity
            // ================================================================
            "osmosis/cl/create-position" => CosmosMsg {
                msg_type: "Create CL Position".to_string(),
                recipient: String::new(),
                amount: {
                    let t0 = format_coin_obj(&v["token_desired0"]);
                    let t1 = format_coin_obj(&v["token_desired1"]);
                    format!("{t0}, {t1}")
                },
                detail: {
                    let pool_id = json_num_or_str(&v["pool_id"]);
                    let lower = json_num_or_str(&v["lower_tick"]);
                    let upper = json_num_or_str(&v["upper_tick"]);
                    format!("pool {pool_id}, ticks [{lower}, {upper}]")
                },
                blind: false,
            },
            "osmosis/cl/withdraw-position" => CosmosMsg {
                msg_type: "Withdraw CL Position".to_string(),
                recipient: String::new(),
                amount: v["liquidity_amount"].as_str().unwrap_or("?").to_string(),
                detail: format!("position {}", json_num_or_str(&v["position_id"])),
                blind: false,
            },

            // ================================================================
            // cosmwasm contract calls — BLIND SIGNING REQUIRED
            //
            // the contract address determines what code runs. we cannot
            // verify what the contract does. show the contract addr (full,
            // never truncated) and the raw msg JSON. the UI must display
            // a prominent warning that this is a blind sign.
            // ================================================================
            "cosmwasm/wasm/MsgExecuteContract" | "wasm/MsgExecuteContract" => {
                let contract = v["contract"].as_str().unwrap_or("").to_string();
                let funds: Vec<String> = v["funds"]
                    .as_array()
                    .or_else(|| v["sent_funds"].as_array())
                    .map(|a| a.iter().map(format_coin).collect())
                    .unwrap_or_default();
                // inner msg — show raw JSON so user sees exactly what's being signed.
                // we do NOT interpret the msg contents because we can't trust
                // that the contract does what the msg labels claim.
                let raw_msg = if v["msg"].is_object() || v["msg"].is_array() {
                    serde_json::to_string(&v["msg"]).unwrap_or_else(|_| "?".to_string())
                } else {
                    v["msg"].as_str().unwrap_or("?").to_string()
                };
                CosmosMsg {
                    msg_type: "Contract Call (BLIND)".to_string(),
                    recipient: contract,
                    amount: if funds.is_empty() {
                        "no funds attached".to_string()
                    } else {
                        funds.join(", ")
                    },
                    detail: raw_msg,
                    blind: true,
                }
            }

            // cw-orch/Approval is expanded in `cosmos_msgs_from_amino` so each
            // action is its own card. This arm is unreachable for that type.

            // cw-orch session setup. One card per grant or revoke so the cold
            // key sees the grantee, the type URL, the expiration, and the
            // spend limit before it signs.
            "cosmos-sdk/MsgGrant" => CosmosMsg {
                msg_type: "Grant".to_string(),
                recipient: v["grantee"].as_str().unwrap_or("").to_string(),
                amount: v["grant"]["authorization"]["value"]["msg"]
                    .as_str()
                    .unwrap_or("")
                    .to_string(),
                detail: format!(
                    "granter {}\nexpires {}",
                    v["granter"].as_str().unwrap_or(""),
                    v["grant"]["expiration"].as_str().unwrap_or("no expiration")
                ),
                blind: false,
            },
            "cosmos-sdk/MsgRevoke" => CosmosMsg {
                msg_type: "Revoke".to_string(),
                recipient: v["grantee"].as_str().unwrap_or("").to_string(),
                amount: v["msg_type_url"].as_str().unwrap_or("").to_string(),
                detail: format!(
                    "granter {}\nremoves this authorization",
                    v["granter"].as_str().unwrap_or("")
                ),
                blind: false,
            },
            "cosmos-sdk/MsgGrantAllowance" => CosmosMsg {
                msg_type: "Fee grant".to_string(),
                recipient: v["grantee"].as_str().unwrap_or("").to_string(),
                amount: allowance_spend(v),
                detail: format!(
                    "granter {}\nexpires {}",
                    v["granter"].as_str().unwrap_or(""),
                    v["allowance"]["value"]["expiration"]
                        .as_str()
                        .unwrap_or("no expiration")
                ),
                blind: false,
            },
            "cosmos-sdk/MsgRevokeAllowance" => CosmosMsg {
                msg_type: "Revoke fee grant".to_string(),
                recipient: v["grantee"].as_str().unwrap_or("").to_string(),
                amount: "fee allowance".to_string(),
                detail: format!(
                    "granter {}\nremoves the fee allowance",
                    v["granter"].as_str().unwrap_or("")
                ),
                blind: false,
            },

            // ================================================================
            // unknown msg type — BLIND SIGNING REQUIRED
            //
            // we don't recognize this message type. show the raw amino type
            // string so the user can at least see what it claims to be.
            // ================================================================
            _ => CosmosMsg {
                msg_type: if typ.is_empty() {
                    "(empty type)".to_string()
                } else {
                    typ.to_string()
                },
                recipient: String::new(),
                amount: String::new(),
                detail: serde_json::to_string(v).unwrap_or_default(),
                blind: true,
            },
        }
    }
}

fn cosmos_msgs_from_amino(message: &serde_json::Value) -> Vec<CosmosMsg> {
    match message["type"].as_str() {
        Some("cw-orch/Approval") => approval_cards(&message["value"]),
        // One amino MsgGrant can authorize several wasm actions. Each action
        // is its own card. GenericAuthorization still reads value.msg.
        Some("cosmos-sdk/MsgGrant") => grant_cards(&message["value"]),
        _ => vec![CosmosMsg::from_json(message)],
    }
}

/// Cards for one `MsgGrant`. Store-code and contract-execution grants do not
/// put the authorized action in `authorization.value.msg`, so reading only
/// that string hides the code hashes and the contract calls.
fn grant_cards(value: &serde_json::Value) -> Vec<CosmosMsg> {
    let grantee = value["grantee"].as_str().unwrap_or("").to_string();
    let granter = value["granter"].as_str().unwrap_or("");
    let expiration = value["grant"]["expiration"]
        .as_str()
        .unwrap_or("no expiration");
    let auth = &value["grant"]["authorization"];
    let auth_type = auth["type"].as_str().unwrap_or("");
    let heading = format!("granter {granter}\ngrantee {grantee}\nexpires {expiration}");
    match auth_type {
        "wasm/StoreCodeAuthorization" => {
            let hashes: Vec<&str> = auth["value"]["grants"]
                .as_array()
                .map(|grants| {
                    grants
                        .iter()
                        .filter_map(|grant| grant["code_hash"].as_str())
                        .filter(|hash| !hash.is_empty())
                        .collect()
                })
                .unwrap_or_default();
            if hashes.is_empty() {
                return vec![grant_fallback(&grantee, auth_type, &heading, auth)];
            }
            hashes
                .into_iter()
                .map(|hash| CosmosMsg {
                    msg_type: "Store code".to_string(),
                    recipient: grantee.clone(),
                    amount: hash.to_string(),
                    detail: heading.clone(),
                    blind: false,
                })
                .collect()
        }
        "wasm/ContractExecutionAuthorization" => {
            let grants = auth["value"]["grants"].as_array().cloned().unwrap_or_default();
            if grants.is_empty() {
                return vec![grant_fallback(&grantee, auth_type, &heading, auth)];
            }
            grants
                .iter()
                .map(|grant| {
                    let contract = grant["contract"].as_str().unwrap_or("").to_string();
                    let keys = grant["filter"]["value"]["keys"]
                        .as_array()
                        .map(|keys| {
                            keys.iter()
                                .filter_map(|key| key.as_str())
                                .collect::<Vec<_>>()
                                .join(", ")
                        })
                        .unwrap_or_default();
                    let calls = grant["limit"]["value"]["remaining"]
                        .as_str()
                        .unwrap_or("");
                    CosmosMsg {
                        msg_type: "Execute".to_string(),
                        recipient: contract,
                        amount: keys,
                        detail: format!("{heading}\ncalls {calls}"),
                        blind: false,
                    }
                })
                .collect()
        }
        _ => {
            let msg = auth["value"]["msg"].as_str().unwrap_or("");
            vec![CosmosMsg {
                msg_type: "Grant".to_string(),
                recipient: grantee,
                amount: if msg.is_empty() {
                    auth_type.to_string()
                } else {
                    msg.to_string()
                },
                detail: heading,
                blind: msg.is_empty() && auth_type.is_empty(),
            }]
        }
    }
}

fn grant_fallback(
    grantee: &str,
    auth_type: &str,
    heading: &str,
    auth: &serde_json::Value,
) -> CosmosMsg {
    CosmosMsg {
        msg_type: "Grant".to_string(),
        recipient: grantee.to_string(),
        amount: auth_type.to_string(),
        detail: format!(
            "{heading}\n{}",
            serde_json::to_string(&auth["value"]).unwrap_or_default()
        ),
        blind: true,
    }
}

/// One card per cw-orch action. The body is shown in full, except a CosmWasm
/// binary, which is replaced by its length. Base64 and hex that decode to
/// text stay readable, with the original form after `@@encoded`.
fn approval_cards(value: &serde_json::Value) -> Vec<CosmosMsg> {
    let principal = value["principal"].as_str().unwrap_or("");
    let Some(list) = value["actions"].as_array().filter(|list| !list.is_empty()) else {
        return vec![CosmosMsg {
            msg_type: "cw-orch approval".to_string(),
            recipient: principal.to_string(),
            amount: "0 actions".to_string(),
            detail: String::new(),
            blind: false,
        }];
    };
    list.iter()
        .map(|action| {
            let code = json_num_or_str(&action["code_id"]);
            let body = action["body"].as_str().unwrap_or("");
            let mut detail = String::new();
            if !principal.is_empty() {
                detail.push_str("principal ");
                detail.push_str(principal);
            }
            if !body.is_empty() {
                if !detail.is_empty() {
                    detail.push('\n');
                }
                detail.push_str(&present_body(body));
            }
            CosmosMsg {
                msg_type: action["type"].as_str().unwrap_or("(no type)").to_string(),
                recipient: action["contract"].as_str().unwrap_or("").to_string(),
                amount: if code.is_empty() || code == "?" {
                    String::new()
                } else {
                    format!("code {code}")
                },
                detail,
                blind: false,
            }
        })
        .collect()
}

fn present_body(raw: &str) -> String {
    if let Some(json) = json_text(raw) {
        let mut encoded = String::new();
        let walked = walk_value(&json, "$", &mut encoded);
        let shown = serde_json::to_string_pretty(&walked).unwrap_or_else(|_| raw.to_string());
        return join_encoded(shown, &encoded);
    }
    match classify(raw) {
        Shown::Keep => raw.to_string(),
        Shown::Wasm(n) => format!("wasm bytecode, {n} bytes"),
        Shown::Decoded(value, original) => {
            let shown = shown_value(&value);
            join_encoded(shown, &format!("$\n{original}\n"))
        }
        Shown::Binary { n, preview, original } => {
            join_encoded(format!("binary, {n} bytes, {preview}"), &format!("$\n{original}\n"))
        }
    }
}

fn shown_value(value: &serde_json::Value) -> String {
    match value {
        serde_json::Value::String(text) => text.clone(),
        other => serde_json::to_string_pretty(other).unwrap_or_default(),
    }
}

fn join_encoded(shown: String, encoded: &str) -> String {
    if encoded.is_empty() {
        shown
    } else {
        format!("{shown}\n@@encoded\n{encoded}")
    }
}

fn walk_value(value: &serde_json::Value, path: &str, encoded: &mut String) -> serde_json::Value {
    match value {
        serde_json::Value::String(text) => {
            if let Some(inner) = json_text(text) {
                return walk_value(&inner, path, encoded);
            }
            match classify(text) {
                Shown::Keep => serde_json::Value::String(text.clone()),
                Shown::Wasm(n) => serde_json::Value::String(format!("wasm bytecode, {n} bytes")),
                Shown::Decoded(inner, original) => {
                    encoded.push_str(path);
                    encoded.push('\n');
                    encoded.push_str(&original);
                    encoded.push('\n');
                    walk_value(&inner, path, encoded)
                }
                Shown::Binary { n, preview, original } => {
                    encoded.push_str(path);
                    encoded.push('\n');
                    encoded.push_str(&original);
                    encoded.push('\n');
                    serde_json::Value::String(format!("binary, {n} bytes, {preview}"))
                }
            }
        }
        serde_json::Value::Array(items) => serde_json::Value::Array(
            items
                .iter()
                .enumerate()
                .map(|(index, item)| walk_value(item, &format!("{path}[{index}]"), encoded))
                .collect(),
        ),
        serde_json::Value::Object(map) => {
            let mut out = serde_json::Map::new();
            for (key, item) in map {
                out.insert(key.clone(), walk_value(item, &format!("{path}.{key}"), encoded));
            }
            serde_json::Value::Object(out)
        }
        other => other.clone(),
    }
}

enum Shown {
    Keep,
    Wasm(usize),
    Decoded(serde_json::Value, String),
    Binary { n: usize, preview: String, original: String },
}

fn classify(value: &str) -> Shown {
    let compact: String = value.chars().filter(|c| !c.is_whitespace()).collect();
    if compact.len() < 16 {
        return Shown::Keep;
    }
    let hex_body = compact.strip_prefix("0x").unwrap_or(&compact);
    if hex_body.len() >= 16 && hex_body.len() % 2 == 0 && hex_body.chars().all(|c| c.is_ascii_hexdigit()) {
        if let Ok(bytes) = hex::decode(hex_body) {
            return interpret(&bytes, false, value);
        }
    }
    if let Some(bytes) = decode_b64(&compact) {
        return interpret(&bytes, true, value);
    }
    Shown::Keep
}

fn interpret(bytes: &[u8], summarize_binary: bool, original: &str) -> Shown {
    if is_wasm(bytes) {
        return Shown::Wasm(bytes.len());
    }
    if let Some(text) = printable_text(bytes) {
        let trimmed = text.trim();
        if trimmed.starts_with('{') || trimmed.starts_with('[') {
            if let Ok(json) = serde_json::from_str::<serde_json::Value>(trimmed) {
                return Shown::Decoded(json, original.to_string());
            }
        }
        return Shown::Decoded(serde_json::Value::String(text), original.to_string());
    }
    if summarize_binary || original.len() > 128 {
        let count = bytes.len().min(8);
        let preview = hex::encode(&bytes[..count]);
        return Shown::Binary {
            n: bytes.len(),
            preview,
            original: original.to_string(),
        };
    }
    Shown::Keep
}

fn is_wasm(bytes: &[u8]) -> bool {
    bytes.starts_with(&[0x00, 0x61, 0x73, 0x6d])
        || (bytes.len() >= 32 && bytes.starts_with(&[0x1f, 0x8b]))
}

fn printable_text(bytes: &[u8]) -> Option<String> {
    let text = std::str::from_utf8(bytes).ok()?;
    if text.is_empty() {
        return None;
    }
    let printable = text.chars().filter(|c| {
        matches!(c, '\t' | '\n' | '\r') || ('\u{20}'..='\u{7e}').contains(c)
    }).count();
    if printable * 10 >= text.chars().count() * 9 {
        Some(text.to_string())
    } else {
        None
    }
}

fn json_text(value: &str) -> Option<serde_json::Value> {
    let trimmed = value.trim();
    if trimmed.len() < 2 || !(trimmed.starts_with('{') || trimmed.starts_with('[')) {
        return None;
    }
    serde_json::from_str(trimmed).ok()
}

fn decode_b64(compact: &str) -> Option<Vec<u8>> {
    if compact.len() < 16 || compact.len() % 4 != 0 {
        return None;
    }
    let bytes = compact.as_bytes();
    let mut out = Vec::with_capacity(compact.len() / 4 * 3);
    for chunk in bytes.chunks(4) {
        if chunk.iter().filter(|&&c| c == b'=').count() > 2 {
            return None;
        }
        if chunk[2] == b'=' && chunk[3] != b'=' {
            return None;
        }
        let a = b64_val(chunk[0])?;
        let b = b64_val(chunk[1])?;
        let c = if chunk[2] == b'=' { 0 } else { b64_val(chunk[2])? };
        let d = if chunk[3] == b'=' { 0 } else { b64_val(chunk[3])? };
        out.push((a << 2) | (b >> 4));
        if chunk[2] != b'=' {
            out.push((b << 4) | (c >> 2));
        }
        if chunk[3] != b'=' {
            out.push((c << 6) | d);
        }
    }
    Some(out)
}

fn b64_val(c: u8) -> Option<u8> {
    match c {
        b'A'..=b'Z' => Some(c - b'A'),
        b'a'..=b'z' => Some(c - b'a' + 26),
        b'0'..=b'9' => Some(c - b'0' + 52),
        b'+' => Some(62),
        b'/' => Some(63),
        b'=' => Some(0),
        _ => None,
    }
}

const GRANTER_CHALLENGE: &[u8] = b"cw-orch-granter:v1\n";

/// Binding of a granter challenge, or `None` when these bytes are not that challenge.
///
/// The signed payload is exactly `cw-orch-granter:v1`, one newline, and one binding
/// line. No NULs and no second line. The signature covers these raw bytes.
pub fn granter_binding(bytes: &[u8]) -> Result<Option<&str>> {
    if !bytes.starts_with(GRANTER_CHALLENGE) {
        return Ok(None);
    }
    let rest = &bytes[GRANTER_CHALLENGE.len()..];
    if rest.is_empty() || rest.contains(&0) || rest.contains(&b'\n') {
        return Err(Error::Other(anyhow::anyhow!(
            "granter challenge must be one binding line"
        )));
    }
    let binding = std::str::from_utf8(rest)
        .map_err(|e| Error::Other(anyhow::anyhow!("granter challenge is not UTF-8: {e}")))?;
    if binding.chars().any(|c| c.is_control()) {
        return Err(Error::Other(anyhow::anyhow!(
            "granter challenge must be one binding line"
        )));
    }
    Ok(Some(binding))
}

impl CosmosSignDocDisplay {
    /// One card. The key that signs the raw challenge becomes the granter for `binding`.
    pub fn granter(binding: &str) -> Self {
        Self {
            chain_id: String::new(),
            fee: "none".to_string(),
            memo: String::new(),
            msgs: vec![CosmosMsg {
                msg_type: "Granter".to_string(),
                recipient: binding.to_string(),
                amount: String::new(),
                detail: format!("This key becomes the granter for {binding}."),
                blind: false,
            }],
        }
    }

    /// Amino JSON stays on [`Self::from_json`]. A granter challenge is one card.
    /// Any other payload is an error and is not signed.
    pub fn from_sign_bytes(bytes: &[u8]) -> Result<Self> {
        if bytes.first() == Some(&b'{') {
            return Self::from_json(bytes);
        }
        match granter_binding(bytes)? {
            Some(binding) => Ok(Self::granter(binding)),
            None => Err(Error::Other(anyhow::anyhow!(
                "not a transaction and not a granter challenge"
            ))),
        }
    }

    /// parse display info from amino JSON sign doc bytes.
    /// parses ALL messages — a cold wallet must never silently hide messages
    /// from the user, as that enables multi-message blind signing attacks.
    pub fn from_json(bytes: &[u8]) -> Result<Self> {
        let json: serde_json::Value = serde_json::from_slice(bytes)
            .map_err(|e| Error::Other(anyhow::anyhow!("invalid sign doc JSON: {e}")))?;

        let chain_id = json["chain_id"].as_str().unwrap_or("").to_string();
        let memo = json["memo"].as_str().unwrap_or("").to_string();

        // parse ALL fee coins — never hide fee components from user
        let fee_amount = json["fee"]["amount"]
            .as_array()
            .map(|coins| {
                let parts: Vec<String> = coins.iter().map(format_coin).collect();
                if parts.is_empty() {
                    "none".to_string()
                } else {
                    parts.join(" + ")
                }
            })
            .unwrap_or_else(|| "unknown".to_string());

        // parse ALL messages. One cw-orch approval becomes one card per action.
        let msgs = match json["msgs"].as_array() {
            Some(arr) => arr.iter().flat_map(cosmos_msgs_from_amino).collect(),
            None => vec![],
        };

        Ok(CosmosSignDocDisplay {
            chain_id,
            msgs,
            fee: fee_amount,
            memo,
        })
    }
}

/// Extract the signer (source) address from every message in an amino sign doc.
///
/// Cosmos messages name their signer differently by type; we check the standard
/// field names in each message's `value`. Messages with no recognizable signer
/// field contribute nothing - they are already surfaced as `blind` in the
/// display and cannot be source-bound here (the chain still rejects a signature
/// from the wrong key).
///
/// The device uses this to refuse signing with a key that is not the message's
/// signer, so a compromised host cannot silently choose which of the user's
/// addresses pays by varying `address_index`.
pub fn extract_signers(sign_doc_bytes: &[u8]) -> Result<Vec<String>> {
    const SIGNER_FIELDS: &[&str] = &[
        "from_address",      // MsgSend
        "sender",            // MsgTransfer (IBC), swaps
        "delegator_address", // staking
        "signer",            // generic
        "voter",             // gov vote
        "depositor",         // gov deposit
        "granter",           // authz / feegrant
        "admin",             // wasm
        "proposer",          // gov submit
    ];

    let doc: serde_json::Value = serde_json::from_slice(sign_doc_bytes)
        .map_err(|e| Error::Other(anyhow::anyhow!("sign doc is not JSON: {e}")))?;

    let mut signers = Vec::new();
    if let Some(msgs) = doc["msgs"].as_array() {
        for m in msgs {
            let v = &m["value"];
            for field in SIGNER_FIELDS {
                if let Some(addr) = v[*field].as_str() {
                    if !addr.is_empty() {
                        signers.push(addr.to_string());
                        break;
                    }
                }
            }
        }
    }
    Ok(signers)
}

/// Sign Cosmos Amino sign doc bytes with `secp256k1` ECDSA.
///
/// Computes `SHA256(sign_doc_bytes)` then signs with the secret key.
/// Returns 64-byte compact signature (r || s).
///
/// IMPORTANT: this does NOT use `sp_core::ecdsa` because that uses `blake2b-256`
/// as pre-hash. Cosmos Amino requires `SHA256`.
#[cfg(feature = "cosmos")]
pub fn sign_cosmos_amino(secret_key: &[u8; 32], sign_doc_bytes: &[u8]) -> Result<[u8; 64]> {
    use secp256k1::{Message, Secp256k1, SecretKey};
    use sha2::{Digest, Sha256};

    let secp = Secp256k1::new();

    // SHA256 prehash (cosmos amino convention)
    let hash = Sha256::digest(sign_doc_bytes);

    let sk = SecretKey::from_slice(secret_key)
        .map_err(|e| Error::Other(anyhow::anyhow!("invalid secret key: {e}")))?;

    let message = Message::from_digest_slice(&hash)
        .map_err(|e| Error::Other(anyhow::anyhow!("invalid message hash: {e}")))?;

    let signature = secp.sign_ecdsa(&message, &sk);

    // compact serialization = 64 bytes (r || s)
    Ok(signature.serialize_compact())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_cosmos_sign_request() {
        // build a test QR payload
        let account_index: u32 = 0;
        let chain_name = b"noble";
        let sign_doc = br#"{"chain_id":"noble-1","memo":"","msgs":[],"fee":{"amount":[],"gas":"0"},"account_number":"0","sequence":"0"}"#;

        let mut payload = Vec::new();
        payload.extend_from_slice(&COSMOS_PRELUDE);
        payload.extend_from_slice(&account_index.to_le_bytes());
        payload.push(chain_name.len() as u8);
        payload.extend_from_slice(chain_name);
        payload.extend_from_slice(&(sign_doc.len() as u32).to_le_bytes());
        payload.extend_from_slice(sign_doc);

        let hex = hex::encode(&payload);
        let req = CosmosSignRequest::from_qr_hex(&hex).unwrap();

        assert_eq!(req.account_index, 0);
        assert_eq!(req.chain_name, "noble");
        assert_eq!(req.sign_doc_bytes, sign_doc.to_vec());
        // no trailing address_index -> defaults to 0 (back-compat)
        assert_eq!(req.address_index, 0);
    }

    #[test]
    fn test_parse_cosmos_sign_request_address_index() {
        let sign_doc = br#"{"chain_id":"noble-1","memo":"","msgs":[],"fee":{"amount":[],"gas":"0"},"account_number":"0","sequence":"0"}"#;
        let build = |trailing: &[u8]| {
            let mut payload = Vec::new();
            payload.extend_from_slice(&COSMOS_PRELUDE);
            payload.extend_from_slice(&0u32.to_le_bytes()); // account_index
            payload.push(b"noble".len() as u8);
            payload.extend_from_slice(b"noble");
            payload.extend_from_slice(&(sign_doc.len() as u32).to_le_bytes());
            payload.extend_from_slice(sign_doc);
            payload.extend_from_slice(trailing);
            hex::encode(&payload)
        };

        // present: 4-byte LE address_index is parsed
        let req = CosmosSignRequest::from_qr_hex(&build(&7u32.to_le_bytes())).unwrap();
        assert_eq!(req.address_index, 7);

        // absent: defaults to 0
        assert_eq!(
            CosmosSignRequest::from_qr_hex(&build(&[]))
                .unwrap()
                .address_index,
            0
        );

        // malformed: any trailing length other than 0 or 4 is rejected
        assert!(CosmosSignRequest::from_qr_hex(&build(&[1, 2, 3])).is_err());
    }

    fn frame(sign_doc: &[u8]) -> String {
        let mut payload = Vec::new();
        payload.extend_from_slice(&COSMOS_PRELUDE);
        payload.extend_from_slice(&0u32.to_le_bytes());
        payload.push(b"terp".len() as u8);
        payload.extend_from_slice(b"terp");
        payload.extend_from_slice(&(sign_doc.len() as u32).to_le_bytes());
        payload.extend_from_slice(sign_doc);
        hex::encode(payload)
    }

    #[test]
    fn granter_challenge_is_one_card_and_signs_the_raw_bytes() {
        let doc = b"cw-orch-granter:v1\napp-setup";
        let req = CosmosSignRequest::from_qr_hex(&frame(doc)).unwrap();
        assert_eq!(req.sign_doc_bytes, doc);
        let display = CosmosSignDocDisplay::from_sign_bytes(&req.sign_doc_bytes).unwrap();
        assert_eq!(display.msgs.len(), 1);
        assert_eq!(display.msgs[0].msg_type, "Granter");
        assert_eq!(display.msgs[0].recipient, "app-setup");
        assert_eq!(
            display.msgs[0].detail,
            "This key becomes the granter for app-setup."
        );
        assert_eq!(display.chain_id, "");
        assert_eq!(display.fee, "none");
        assert_eq!(display.memo, "");
        assert!(granter_binding(b"cw-orch-granter:v1\napp-setup\nextra").is_err());
        assert!(granter_binding(b"cw-orch-granter:v1\napp\0setup").is_err());
        assert!(CosmosSignDocDisplay::from_sign_bytes(b"not-json").is_err());
        let amino = br#"{"chain_id":"morocco-1","memo":"cw-orch-session:v1","msgs":[{"type":"cosmos-sdk/MsgGrant","value":{"granter":"terp1g","grantee":"terp1e"}}],"fee":{"amount":[{"denom":"uthiol","amount":"40000"}],"gas":"800000"}}"#;
        let session = CosmosSignDocDisplay::from_sign_bytes(amino).unwrap();
        assert_eq!(session.chain_id, "morocco-1");
        assert_eq!(session.memo, "cw-orch-session:v1");
        assert_ne!(session.msgs.first().map(|m| m.msg_type.as_str()), Some("Granter"));
    }

    #[test]
    fn test_extract_signers() {
        // MsgSend -> from_address; MsgTransfer -> sender; both collected in order
        let doc = br#"{"chain_id":"noble-1","msgs":[
            {"type":"cosmos-sdk/MsgSend","value":{"from_address":"noble1src","to_address":"noble1dst","amount":[]}},
            {"type":"cosmos-sdk/MsgTransfer","value":{"sender":"noble1src","receiver":"osmo1dst"}}
        ],"fee":{"amount":[],"gas":"0"},"memo":"","account_number":"0","sequence":"0"}"#;
        assert_eq!(
            extract_signers(doc).unwrap(),
            vec!["noble1src", "noble1src"]
        );

        // a message with no recognizable signer field contributes nothing
        let blind = br#"{"msgs":[{"type":"wasm/MsgExecuteContract","value":{"contract":"noble1c","msg":{}}}]}"#;
        assert!(extract_signers(blind).unwrap().is_empty());

        // not JSON -> error
        assert!(extract_signers(b"not json").is_err());
    }

    #[test]
    fn test_parse_sign_doc_display() {
        let sign_doc = br#"{"chain_id":"noble-1","memo":"test","msgs":[{"type":"cosmos-sdk/MsgSend","value":{"from_address":"noble1abc","to_address":"noble1xyz","amount":[{"denom":"uusdc","amount":"1000000"}]}}],"fee":{"amount":[{"denom":"uusdc","amount":"500"}],"gas":"100000"},"account_number":"42","sequence":"7"}"#;

        let display = CosmosSignDocDisplay::from_json(sign_doc).unwrap();

        assert_eq!(display.chain_id, "noble-1");
        assert_eq!(display.msgs.len(), 1);
        assert_eq!(display.msgs[0].msg_type, "Send");
        assert_eq!(display.msgs[0].recipient, "noble1xyz");
        assert_eq!(display.msgs[0].amount, "1000000 uusdc");
        assert!(!display.msgs[0].blind);
        assert_eq!(display.fee, "500 uusdc");
        assert_eq!(display.memo, "test");
    }

    #[test]
    fn test_multi_message_display() {
        let sign_doc = br#"{"chain_id":"osmosis-1","memo":"","msgs":[{"type":"cosmos-sdk/MsgSend","value":{"from_address":"osmo1abc","to_address":"osmo1xyz","amount":[{"denom":"uosmo","amount":"100"}]}},{"type":"cosmos-sdk/MsgTransfer","value":{"sender":"osmo1abc","receiver":"noble1evil","token":{"denom":"uusdc","amount":"9999999"},"source_channel":"channel-0","source_port":"transfer"}}],"fee":{"amount":[{"denom":"uosmo","amount":"500"}],"gas":"200000"},"account_number":"1","sequence":"0"}"#;

        let display = CosmosSignDocDisplay::from_json(sign_doc).unwrap();

        assert_eq!(display.msgs.len(), 2, "must parse ALL messages");
        assert_eq!(display.msgs[0].msg_type, "Send");
        assert_eq!(display.msgs[0].amount, "100 uosmo");
        assert!(!display.msgs[0].blind);
        assert_eq!(display.msgs[1].msg_type, "IBC Transfer");
        assert_eq!(display.msgs[1].recipient, "noble1evil");
        assert_eq!(display.msgs[1].amount, "9999999 uusdc");
        assert!(display.msgs[1].detail.contains("channel-0"));
    }

    #[test]
    fn test_delegate_undelegate_display() {
        let sign_doc = br#"{"chain_id":"cosmoshub-4","memo":"","msgs":[{"type":"cosmos-sdk/MsgDelegate","value":{"delegator_address":"cosmos1abc","validator_address":"cosmosvaloper1xyz","amount":{"denom":"uatom","amount":"1000000"}}},{"type":"cosmos-sdk/MsgUndelegate","value":{"delegator_address":"cosmos1abc","validator_address":"cosmosvaloper1def","amount":{"denom":"uatom","amount":"500000"}}}],"fee":{"amount":[{"denom":"uatom","amount":"5000"}],"gas":"300000"},"account_number":"5","sequence":"3"}"#;

        let display = CosmosSignDocDisplay::from_json(sign_doc).unwrap();

        assert_eq!(display.msgs.len(), 2);
        assert_eq!(display.msgs[0].msg_type, "Delegate");
        assert_eq!(display.msgs[0].recipient, "cosmosvaloper1xyz");
        assert_eq!(display.msgs[0].amount, "1000000 uatom");
        assert_eq!(display.msgs[1].msg_type, "Undelegate");
        assert_eq!(display.msgs[1].recipient, "cosmosvaloper1def");
        assert_eq!(display.msgs[1].amount, "500000 uatom");
    }

    #[test]
    fn test_osmosis_swap() {
        let sign_doc = br#"{"chain_id":"osmosis-1","memo":"","msgs":[{"type":"osmosis/gamm/swap-exact-amount-in","value":{"sender":"osmo1abc","routes":[{"pool_id":"1","token_out_denom":"uusdc"}],"token_in":{"denom":"uosmo","amount":"5000000"},"token_out_min_amount":"4900000"}}],"fee":{"amount":[{"denom":"uosmo","amount":"2500"}],"gas":"250000"},"account_number":"1","sequence":"5"}"#;

        let display = CosmosSignDocDisplay::from_json(sign_doc).unwrap();

        assert_eq!(display.msgs.len(), 1);
        assert_eq!(display.msgs[0].msg_type, "Swap");
        assert_eq!(display.msgs[0].amount, "5000000 uosmo");
        assert!(display.msgs[0].detail.contains("min output: 4900000"));
        assert!(display.msgs[0].detail.contains("pool 1"));
        assert!(!display.msgs[0].blind);
    }

    #[test]
    fn test_osmosis_join_pool() {
        let sign_doc = br#"{"chain_id":"osmosis-1","memo":"","msgs":[{"type":"osmosis/gamm/join-pool","value":{"sender":"osmo1abc","pool_id":"1","share_out_amount":"100000","token_in_maxs":[{"denom":"uosmo","amount":"5000000"},{"denom":"uusdc","amount":"5000000"}]}}],"fee":{"amount":[{"denom":"uosmo","amount":"2500"}],"gas":"250000"},"account_number":"1","sequence":"6"}"#;

        let display = CosmosSignDocDisplay::from_json(sign_doc).unwrap();

        assert_eq!(display.msgs[0].msg_type, "Join Pool");
        assert!(display.msgs[0].amount.contains("uosmo"));
        assert!(display.msgs[0].amount.contains("uusdc"));
        assert!(display.msgs[0].detail.contains("pool 1"));
        assert!(!display.msgs[0].blind);
    }

    #[test]
    fn test_cosmwasm_contract_call_is_blind() {
        let sign_doc = br#"{"chain_id":"osmosis-1","memo":"","msgs":[{"type":"cosmwasm/wasm/MsgExecuteContract","value":{"sender":"osmo1abc","contract":"osmo1contractaddr","msg":{"swap":{"input_denom":"uosmo"}},"funds":[{"denom":"uosmo","amount":"1000000"}]}}],"fee":{"amount":[{"denom":"uosmo","amount":"2500"}],"gas":"300000"},"account_number":"1","sequence":"7"}"#;

        let display = CosmosSignDocDisplay::from_json(sign_doc).unwrap();

        assert_eq!(display.msgs[0].msg_type, "Contract Call (BLIND)");
        assert_eq!(display.msgs[0].recipient, "osmo1contractaddr");
        assert_eq!(display.msgs[0].amount, "1000000 uosmo");
        assert!(display.msgs[0].detail.contains("swap"));
        assert!(display.msgs[0].blind, "contract calls must be marked blind");
    }

    #[test]
    fn test_unknown_msg_type_is_blind() {
        let sign_doc = br#"{"chain_id":"osmosis-1","memo":"","msgs":[{"type":"some-module/MsgDoSomething","value":{"foo":"bar"}}],"fee":{"amount":[{"denom":"uosmo","amount":"500"}],"gas":"100000"},"account_number":"1","sequence":"0"}"#;

        let display = CosmosSignDocDisplay::from_json(sign_doc).unwrap();

        assert_eq!(display.msgs[0].msg_type, "some-module/MsgDoSomething");
        assert!(display.msgs[0].blind, "unknown types must be marked blind");
        assert!(
            display.msgs[0].detail.contains("bar"),
            "should show raw value JSON"
        );
    }

    #[test]
    fn test_cw_orch_approval_lists_each_action() {
        let wasm = "AGFzbQAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";
        let encoded = "eyJvayI6dHJ1ZX0=";
        let sign_doc = serde_json::json!({
            "chain_id": "terp-1",
            "memo": "cw-orch-approval:v1",
            "msgs": [{
                "type": "cw-orch/Approval",
                "value": {
                    "actions": [
                        {
                            "type": "/cosmwasm.wasm.v1.MsgStoreCode",
                            "code_id": 7,
                            "body": serde_json::json!({"wasm_byte_code": wasm}).to_string()
                        },
                        {
                            "type": "/cosmwasm.wasm.v1.MsgExecuteContract",
                            "contract": "terp1c",
                            "body": serde_json::json!({
                                "swap": {"denom": "uterp"},
                                "payload": encoded,
                                "note": "68656c6c6f20776f726c642121"
                            }).to_string()
                        }
                    ],
                    "principal": "treasury"
                }
            }]
        });
        let sign_doc = serde_json::to_vec(&sign_doc).unwrap();
        let display = CosmosSignDocDisplay::from_json(&sign_doc).unwrap();
        assert_eq!(display.msgs.len(), 2);
        assert_eq!(display.msgs[0].msg_type, "/cosmwasm.wasm.v1.MsgStoreCode");
        assert_eq!(display.msgs[0].amount, "code 7");
        assert!(display.msgs[0].detail.contains("principal treasury"));
        assert!(display.msgs[0].detail.contains("wasm bytecode, 32 bytes"));
        assert!(
            !display.msgs[0].detail.contains(wasm),
            "the contract binary stays off the card"
        );
        assert!(!display.msgs[0].blind);
        assert_eq!(display.msgs[1].msg_type, "/cosmwasm.wasm.v1.MsgExecuteContract");
        assert_eq!(display.msgs[1].recipient, "terp1c");
        assert!(display.msgs[1].detail.contains("uterp"));
        assert!(display.msgs[1].detail.contains("\"ok\": true") || display.msgs[1].detail.contains("\"ok\":true"));
        assert!(display.msgs[1].detail.contains("hello world!!"));
        assert!(display.msgs[1].detail.contains(encoded));
        assert!(display.msgs[1].detail.contains("68656c6c6f20776f726c642121"));
        assert!(!display.msgs[1].blind);
        assert!(extract_signers(&sign_doc).unwrap().is_empty());
    }

    #[test]
    fn test_session_grant_msgs_are_not_blind() {
        let sign_doc = serde_json::json!({
            "chain_id": "120u-1",
            "memo": "cw-orch-session:v1",
            "msgs": [
                {
                    "type": "cosmos-sdk/MsgRevoke",
                    "value": {
                        "granter": "terp1cold",
                        "grantee": "terp1eph",
                        "msg_type_url": "/cosmwasm.wasm.v1.MsgStoreCode"
                    }
                },
                {
                    "type": "cosmos-sdk/MsgRevokeAllowance",
                    "value": { "granter": "terp1cold", "grantee": "terp1eph" }
                },
                {
                    "type": "cosmos-sdk/MsgGrant",
                    "value": {
                        "granter": "terp1cold",
                        "grantee": "terp1eph",
                        "grant": {
                            "authorization": {
                                "type": "cosmos-sdk/GenericAuthorization",
                                "value": { "msg": "/cosmwasm.wasm.v1.MsgExecuteContract" }
                            },
                            "expiration": "1970-01-01T01:24:50Z"
                        }
                    }
                },
                {
                    "type": "cosmos-sdk/MsgGrantAllowance",
                    "value": {
                        "granter": "terp1cold",
                        "grantee": "terp1eph",
                        "allowance": {
                            "type": "cosmos-sdk/BasicAllowance",
                            "value": {
                                "spend_limit": [{ "amount": "25", "denom": "uterp" }],
                                "expiration": "1970-01-01T01:24:50Z"
                            }
                        }
                    }
                }
            ]
        });
        let sign_doc = serde_json::to_vec(&sign_doc).unwrap();
        let display = CosmosSignDocDisplay::from_json(&sign_doc).unwrap();
        assert_eq!(display.msgs.len(), 4);
        assert_eq!(display.msgs[0].msg_type, "Revoke");
        assert_eq!(display.msgs[0].recipient, "terp1eph");
        assert_eq!(display.msgs[0].amount, "/cosmwasm.wasm.v1.MsgStoreCode");
        assert!(display.msgs[0].detail.contains("terp1cold"));
        assert!(!display.msgs[0].blind);
        assert_eq!(display.msgs[1].msg_type, "Revoke fee grant");
        assert!(display.msgs[1].detail.contains("removes the fee allowance"));
        assert!(!display.msgs[1].blind);
        assert_eq!(display.msgs[2].msg_type, "Grant");
        assert_eq!(display.msgs[2].amount, "/cosmwasm.wasm.v1.MsgExecuteContract");
        assert!(display.msgs[2].detail.contains("1970-01-01T01:24:50Z"));
        assert!(!display.msgs[2].blind);
        assert_eq!(display.msgs[3].msg_type, "Fee grant");
        assert_eq!(display.msgs[3].amount, "25 uterp");
        assert!(display.msgs[3].detail.contains("expires 1970-01-01T01:24:50Z"));
        assert!(!display.msgs[3].blind);
        let signers = extract_signers(&sign_doc).unwrap();
        assert_eq!(signers.len(), 4);
        assert!(signers.iter().all(|signer| signer == "terp1cold"));
    }

    #[test]
    fn wasm_grant_lists_each_store_hash_and_each_contract_call() {
        let sign_doc = serde_json::json!({
            "chain_id": "240u-1",
            "memo": "cw-orch-session:v1",
            "msgs": [
                {
                    "type": "cosmos-sdk/MsgGrant",
                    "value": {
                        "granter": "terp1cold",
                        "grantee": "terp1eph",
                        "grant": {
                            "authorization": {
                                "type": "wasm/StoreCodeAuthorization",
                                "value": {
                                    "grants": [
                                        {"code_hash": "ju9Ahn12PPAIbfyQyvU47jXIxbMy4wHFw1YPuCogG6c="},
                                        {"code_hash": "0XnivwkuatCU0nH+fdxE9kddC5mmQqG7I3xy31ovHfo="}
                                    ]
                                }
                            },
                            "expiration": "2026-10-23T18:49:55Z"
                        }
                    }
                },
                {
                    "type": "cosmos-sdk/MsgGrant",
                    "value": {
                        "granter": "terp1cold",
                        "grantee": "terp1eph",
                        "grant": {
                            "authorization": {
                                "type": "wasm/ContractExecutionAuthorization",
                                "value": {
                                    "grants": [{
                                        "contract": "terp1bounty",
                                        "filter": {
                                            "type": "wasm/AcceptedMessageKeysFilter",
                                            "value": {"keys": ["update_config"]}
                                        },
                                        "limit": {
                                            "type": "wasm/MaxCallsLimit",
                                            "value": {"remaining": "1"}
                                        }
                                    }]
                                }
                            },
                            "expiration": "2026-10-23T18:49:55Z"
                        }
                    }
                }
            ]
        });
        let bytes = serde_json::to_vec(&sign_doc).unwrap();
        let display = CosmosSignDocDisplay::from_json(&bytes).unwrap();
        assert_eq!(display.msgs.len(), 3);
        assert_eq!(display.msgs[0].msg_type, "Store code");
        assert_eq!(display.msgs[0].amount, "ju9Ahn12PPAIbfyQyvU47jXIxbMy4wHFw1YPuCogG6c=");
        assert!(!display.msgs[0].blind);
        assert_eq!(display.msgs[1].msg_type, "Store code");
        assert_eq!(display.msgs[2].msg_type, "Execute");
        assert_eq!(display.msgs[2].recipient, "terp1bounty");
        assert_eq!(display.msgs[2].amount, "update_config");
        assert!(display.msgs[2].detail.contains("calls 1"));
        assert!(!display.msgs[2].blind);
    }
}
