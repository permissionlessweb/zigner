//! QR envelope for PCZT signing requests and responses.
//!
//! Zigner QR payloads open with the prelude `[0x53][crypto_type][tx_type]`.
//! Zcash is crypto_type 0x04. tx_type 0x02 was a legacy request that carried
//! a bare digest to sign; it is refused as an unknown type. This module adds:
//!
//!   0x03  single redacted PCZT         -> single signed PCZT back
//!   0x04  batch envelope, 1..=40 PCZTs -> batch of signed PCZTs back
//!   0x05  single redacted PCZT, COMPACT -> signatures-only response (0x07)
//!   0x06  batch envelope, 1..=40 COMPACT -> signatures-only response (0x08)
//!   0x07  single compact response (orchard/ironwood spend-auth signatures)
//!   0x08  batch compact response
//!
//! COMPACT (0x05/0x06 request -> 0x07/0x08 response) is the upstream Ironwood
//! migration performance work (librustzcash PRs 2602/2643/2662; Keystone
//! firmware PRs 2207/2213): the device returns ONLY the RedPallas spend-auth
//! signatures it produced (per PCZT, per action, tagged with the value pool
//! and action index) instead of the full signed PCZT. The wallet merges them
//! into the already-proven, binding-signed PCZT and extracts the transaction
//! itself - so the return QR shrinks from ~132 KB/662 fragments to ~6.5 KB/33
//! for a 35-PCZT migration. Signatures are positional by PCZT (request order)
//! and by action index, using the same (value_pool, action_index, sig:64)
//! triple the upstream `pczt` crate's `SpendAuthSignature` uses.
//!
//! Old firmware rejects unknown tx_types with a named error (fails closed),
//! so a wallet MUST retry a compact request against an old signer with
//! 0x03/0x04. Full-PCZT responses carry `sha256(signed_pczt)` per message
//! (Keystone integrity rule - a checksum of the PCZT bytes, not the sighash).
//! Compact responses carry a module/firmware version prefix so the wallet can
//! gate merging on what it can parse (Keystone firmware PR 2211).
//!
//! Wire format (little-endian lengths, deliberately dumb):
//!   single request:  prelude || pczt_bytes
//!   batch request:   prelude || count:u8 ||
//!                    count * ( id_len:u8 || id || pczt_len:u32 || pczt )
//!   single response: prelude || digest:32 || pczt_len:u32 || signed_pczt
//!   batch response:  prelude || count:u8 ||
//!                    count * ( id_len:u8 || id || digest:32 ||
//!                              pczt_len:u32 || signed_pczt )
//!   single compact response:
//!       prelude || ver_len:u8 || version || sig_count:u16 ||
//!       sig_count * ( pool:u8 || action_index:u32 || signature:64 )
//!   batch compact response:
//!       prelude || ver_len:u8 || version || count:u8 ||
//!       count * ( id_len:u8 || id || sig_count:u16 ||
//!                 sig_count * ( pool:u8 || action_index:u32 || signature:64 ) )
//!   pool: 0 = Orchard, 1 = Ironwood (matches `pczt::orchard::ValuePool`).

use sha2::{Digest, Sha256};

pub const PRELUDE: u8 = 0x53;
pub const CRYPTO_TYPE_ZCASH: u8 = 0x04;
pub const TX_TYPE_PCZT_SINGLE: u8 = 0x03;
pub const TX_TYPE_PCZT_BATCH: u8 = 0x04;
/// Compact signatures-only request variants (same bodies as 0x03/0x04, but
/// the device answers with signature contributions instead of full PCZTs).
pub const TX_TYPE_PCZT_SINGLE_COMPACT: u8 = 0x05;
pub const TX_TYPE_PCZT_BATCH_COMPACT: u8 = 0x06;
/// Compact signatures-only response types (emitted by the device only).
pub const TX_TYPE_PCZT_SINGLE_COMPACT_RESPONSE: u8 = 0x07;
pub const TX_TYPE_PCZT_BATCH_COMPACT_RESPONSE: u8 = 0x08;

/// Compares against request tx_types (0x03..0x06); response tx_types
/// (0x07/0x08) are NEVER accepted as a request and fail closed.
pub fn is_request_tx_type(tx_type: u8) -> bool {
    matches!(
        tx_type,
        TX_TYPE_PCZT_SINGLE
            | TX_TYPE_PCZT_BATCH
            | TX_TYPE_PCZT_SINGLE_COMPACT
            | TX_TYPE_PCZT_BATCH_COMPACT
    )
}

/// Upstream Keystone caps a batch at 40 PCZTs (ZCASH_BATCH_MAX_PCZTS); relaxed
/// from the earlier vizor-35 so the Ironwood migration moves more transactions
/// per QR exchange. Old zigner firmware still fails closed above its own cap.
pub const BATCH_MAX_MESSAGES: usize = 40;

/// Wire version of the compact signatures-only response. Bump (additively) if
/// the response gains fields; the wallet parses the version prefix so it can
/// refuse a response shape it cannot merge.
pub const COMPACT_RESPONSE_VERSION: &str = "1";

/// Value pool tag inside a [`SignatureContribution`]; mirrors
/// `pczt::orchard::ValuePool` so signatures can ride any transport.
pub const POOL_ORCHARD: u8 = 0;
pub const POOL_IRONWOOD: u8 = 1;

/// One spend-auth signature for one action of one PCZT.
///
/// The compact-response atom: the device produced a RedPallas spend-auth
/// signature over the shielded sighash for the action at `action_index` in
/// the `pool` bundle of some PCZT. The wallet matches it back to a PCZT by
/// position (request order) and merges it with the upstream
/// `pczt::roles::signer::Signer::apply_orchard_spend_auth_signature`.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SignatureContribution {
    /// [`POOL_ORCHARD`] or [`POOL_IRONWOOD`].
    pub pool: u8,
    pub action_index: u32,
    /// 64-byte RedPallas spend authorization signature.
    pub signature: [u8; 64],
}

/// One message of a compact response: the signatures the device produced for
/// the request message with the same `id` (empty for single requests).
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompactResponseMessage {
    pub id: Vec<u8>,
    pub signatures: Vec<SignatureContribution>,
}

/// A parsed compact response.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CompactResponse {
    pub version: String,
    pub messages: Vec<CompactResponseMessage>,
}

#[derive(Debug)]
pub enum EnvelopeError {
    TooShort,
    WrongPrelude,
    WrongCryptoType(u8),
    UnknownTxType(u8),
    BatchCount(usize),
    Truncated(&'static str),
}

impl core::fmt::Display for EnvelopeError {
    fn fmt(&self, f: &mut core::fmt::Formatter<'_>) -> core::fmt::Result {
        match self {
            EnvelopeError::TooShort => write!(f, "payload shorter than prelude"),
            EnvelopeError::WrongPrelude => write!(f, "not a zigner payload"),
            EnvelopeError::WrongCryptoType(t) => {
                write!(f, "not a zcash payload (crypto 0x{t:02x})")
            }
            EnvelopeError::UnknownTxType(t) => write!(f, "unknown zcash tx type 0x{t:02x}"),
            EnvelopeError::BatchCount(n) => {
                write!(f, "batch count {n} outside 1..={BATCH_MAX_MESSAGES}")
            }
            EnvelopeError::Truncated(what) => write!(f, "truncated payload at {what}"),
        }
    }
}

/// One message of a signing request.
#[derive(Debug, Clone, PartialEq)]
pub struct RequestMessage {
    /// Wallet-chosen opaque id, echoed in the response (batch only; empty
    /// for single requests).
    pub id: Vec<u8>,
    pub pczt_bytes: Vec<u8>,
}

/// A parsed signing request.
#[derive(Debug, Clone, PartialEq)]
pub enum SignRequest {
    Single(RequestMessage),
    Batch(Vec<RequestMessage>),
}

impl SignRequest {
    pub fn messages(&self) -> &[RequestMessage] {
        match self {
            SignRequest::Single(m) => core::slice::from_ref(m),
            SignRequest::Batch(v) => v,
        }
    }
}

/// A parsed payload plus the tx_type flavour it used.
#[derive(Debug, Clone, PartialEq)]
pub struct ParsedRequest {
    pub request: SignRequest,
    /// True for the compact tx_types (0x05/0x06): the device must answer with
    /// a signatures-only response (0x07/0x08) instead of full signed PCZTs.
    pub compact: bool,
}

/// Parse a scanned payload into a signing request plus its COMPACT flag.
pub fn parse_request_full(payload: &[u8]) -> Result<ParsedRequest, EnvelopeError> {
    let [p, crypto, tx_type, rest @ ..] = payload else {
        return Err(EnvelopeError::TooShort);
    };
    if *p != PRELUDE {
        return Err(EnvelopeError::WrongPrelude);
    }
    if *crypto != CRYPTO_TYPE_ZCASH {
        return Err(EnvelopeError::WrongCryptoType(*crypto));
    }
    let (request, compact) = match *tx_type {
        TX_TYPE_PCZT_SINGLE | TX_TYPE_PCZT_SINGLE_COMPACT => {
            let compact = *tx_type == TX_TYPE_PCZT_SINGLE_COMPACT;
            if rest.is_empty() {
                return Err(EnvelopeError::Truncated("pczt body"));
            }
            (
                SignRequest::Single(RequestMessage {
                    id: Vec::new(),
                    pczt_bytes: rest.to_vec(),
                }),
                compact,
            )
        }
        TX_TYPE_PCZT_BATCH | TX_TYPE_PCZT_BATCH_COMPACT => {
            let compact = *tx_type == TX_TYPE_PCZT_BATCH_COMPACT;
            let (&count, mut rest) = rest
                .split_first()
                .ok_or(EnvelopeError::Truncated("count"))?;
            let count = count as usize;
            if count == 0 || count > BATCH_MAX_MESSAGES {
                return Err(EnvelopeError::BatchCount(count));
            }
            let mut messages = Vec::with_capacity(count);
            for _ in 0..count {
                let (&id_len, r) = rest
                    .split_first()
                    .ok_or(EnvelopeError::Truncated("id len"))?;
                let id_len = id_len as usize;
                if r.len() < id_len + 4 {
                    return Err(EnvelopeError::Truncated("id"));
                }
                let (id, r) = r.split_at(id_len);
                let (len_bytes, r) = r.split_at(4);
                let pczt_len = u32::from_le_bytes(len_bytes.try_into().unwrap()) as usize;
                if r.len() < pczt_len {
                    return Err(EnvelopeError::Truncated("pczt body"));
                }
                let (pczt, r) = r.split_at(pczt_len);
                messages.push(RequestMessage {
                    id: id.to_vec(),
                    pczt_bytes: pczt.to_vec(),
                });
                rest = r;
            }
            (SignRequest::Batch(messages), compact)
        }
        other => return Err(EnvelopeError::UnknownTxType(other)),
    };
    Ok(ParsedRequest { request, compact })
}

/// Parse a scanned payload, discarding the COMPACT flag. Retained for
/// wallet-side helpers that only need the request content.
pub fn parse_request(payload: &[u8]) -> Result<SignRequest, EnvelopeError> {
    parse_request_full(payload).map(|parsed| parsed.request)
}

/// Wallet-side helper: encode a request with optional compact flag.
/// When `compact=true`, emits tx_type 0x05 (single) or 0x06 (batch);
/// the device responds with 0x07/0x08 signatures-only instead of full PCZTs.
pub fn encode_request_full(request: &SignRequest, compact: bool) -> Result<Vec<u8>, EnvelopeError> {
    let mut out = vec![PRELUDE, CRYPTO_TYPE_ZCASH];
    match request {
        SignRequest::Single(m) => {
            out.push(if compact {
                TX_TYPE_PCZT_SINGLE_COMPACT
            } else {
                TX_TYPE_PCZT_SINGLE
            });
            out.extend_from_slice(&m.pczt_bytes);
        }
        SignRequest::Batch(messages) => {
            if messages.is_empty() || messages.len() > BATCH_MAX_MESSAGES {
                return Err(EnvelopeError::BatchCount(messages.len()));
            }
            out.push(if compact {
                TX_TYPE_PCZT_BATCH_COMPACT
            } else {
                TX_TYPE_PCZT_BATCH
            });
            out.push(messages.len() as u8);
            for m in messages {
                out.push(m.id.len() as u8);
                out.extend_from_slice(&m.id);
                out.extend_from_slice(&(m.pczt_bytes.len() as u32).to_le_bytes());
                out.extend_from_slice(&m.pczt_bytes);
            }
        }
    }
    Ok(out)
}

/// Wallet-side helper: encode a request (non-compact adapter).
/// Calls `encode_request_full` with `compact=false`.
pub fn encode_request(request: &SignRequest) -> Result<Vec<u8>, EnvelopeError> {
    encode_request_full(request, false)
}

/// One signed message of a response.
#[derive(Debug, Clone, PartialEq)]
pub struct ResponseMessage {
    pub id: Vec<u8>,
    /// sha256 of `signed_pczt` - integrity checksum, Keystone parity.
    pub digest: [u8; 32],
    pub signed_pczt: Vec<u8>,
}

/// Device-side: encode signed results back for the wallet's camera.
pub fn encode_response(
    messages: &[ResponseMessage],
    batch: bool,
) -> Result<Vec<u8>, EnvelopeError> {
    let mut out = vec![PRELUDE, CRYPTO_TYPE_ZCASH];
    if batch {
        if messages.is_empty() || messages.len() > BATCH_MAX_MESSAGES {
            return Err(EnvelopeError::BatchCount(messages.len()));
        }
        out.push(TX_TYPE_PCZT_BATCH);
        out.push(messages.len() as u8);
        for m in messages {
            out.push(m.id.len() as u8);
            out.extend_from_slice(&m.id);
            out.extend_from_slice(&m.digest);
            out.extend_from_slice(&(m.signed_pczt.len() as u32).to_le_bytes());
            out.extend_from_slice(&m.signed_pczt);
        }
    } else {
        let [m] = messages else {
            return Err(EnvelopeError::BatchCount(messages.len()));
        };
        out.push(TX_TYPE_PCZT_SINGLE);
        out.extend_from_slice(&m.digest);
        out.extend_from_slice(&(m.signed_pczt.len() as u32).to_le_bytes());
        out.extend_from_slice(&m.signed_pczt);
    }
    Ok(out)
}

/// Wallet-side: parse a device response.
pub fn parse_response(payload: &[u8]) -> Result<Vec<ResponseMessage>, EnvelopeError> {
    let [p, crypto, tx_type, rest @ ..] = payload else {
        return Err(EnvelopeError::TooShort);
    };
    if *p != PRELUDE {
        return Err(EnvelopeError::WrongPrelude);
    }
    if *crypto != CRYPTO_TYPE_ZCASH {
        return Err(EnvelopeError::WrongCryptoType(*crypto));
    }
    let read_one = |r: &[u8], with_id: bool| -> Result<(ResponseMessage, usize), EnvelopeError> {
        let mut pos = 0usize;
        let id = if with_id {
            let id_len = *r.first().ok_or(EnvelopeError::Truncated("id len"))? as usize;
            pos += 1;
            if pos.checked_add(id_len).is_none_or(|end| r.len() < end) {
                return Err(EnvelopeError::Truncated("id"));
            }
            let id = r[pos..pos + id_len].to_vec();
            pos += id_len;
            id
        } else {
            Vec::new()
        };
        // checked_add throughout: `len` below is a u32 straight off the
        // wire, and this module is compiled to wasm32 where usize is 32-bit
        // ALWAYS - so `pos + len` wraps, `r.len() < pos + len` then passes,
        // and the slice is built with start > end. A bounds check that stops
        // being one is worse than none, because it reads as covered.
        if pos.checked_add(36).is_none_or(|end| r.len() < end) {
            return Err(EnvelopeError::Truncated("digest"));
        }
        let digest: [u8; 32] = r[pos..pos + 32].try_into().unwrap();
        pos += 32;
        let len = u32::from_le_bytes(r[pos..pos + 4].try_into().unwrap()) as usize;
        pos += 4;
        let end = pos
            .checked_add(len)
            .ok_or(EnvelopeError::Truncated("signed pczt"))?;
        if r.len() < end {
            return Err(EnvelopeError::Truncated("signed pczt"));
        }
        let signed_pczt = r[pos..end].to_vec();
        pos = end;
        Ok((
            ResponseMessage {
                id,
                digest,
                signed_pczt,
            },
            pos,
        ))
    };
    match *tx_type {
        TX_TYPE_PCZT_SINGLE => {
            let (m, _) = read_one(rest, false)?;
            Ok(vec![m])
        }
        TX_TYPE_PCZT_BATCH => {
            let (&count, mut r) = rest
                .split_first()
                .ok_or(EnvelopeError::Truncated("count"))?;
            let count = count as usize;
            if count == 0 || count > BATCH_MAX_MESSAGES {
                return Err(EnvelopeError::BatchCount(count));
            }
            let mut out = Vec::with_capacity(count);
            for _ in 0..count {
                let (m, used) = read_one(r, true)?;
                r = &r[used..];
                out.push(m);
            }
            Ok(out)
        }
        other => Err(EnvelopeError::UnknownTxType(other)),
    }
}

/// Compute the Keystone-parity integrity digest for a signed PCZT.
pub fn integrity_digest(signed_pczt: &[u8]) -> [u8; 32] {
    Sha256::digest(signed_pczt).into()
}

// ── COMPACT signatures-only responses (tx_type 0x07 / 0x08) ────────────────

/// Device-side: encode signature contributions for the wallet's camera.
///
/// `single` selects the single (0x07) vs batch (0x08) framing; for a batch,
/// every message carries an `id` echoed from the request. The response opens
/// with the compact version prefix so a wallet can refuse a shape it cannot
/// merge.
pub fn encode_compact_response(
    messages: &[CompactResponseMessage],
    version: &str,
    single: bool,
) -> Result<Vec<u8>, EnvelopeError> {
    let ver = version.as_bytes();
    if ver.len() > u8::MAX as usize {
        return Err(EnvelopeError::TooShort);
    }
    let mut out = vec![PRELUDE, CRYPTO_TYPE_ZCASH];
    let push_contributions =
        |out: &mut Vec<u8>, sigs: &[SignatureContribution]| -> Result<(), EnvelopeError> {
            if sigs.len() > u16::MAX as usize {
                return Err(EnvelopeError::TooShort);
            }
            out.extend_from_slice(&(sigs.len() as u16).to_le_bytes());
            for s in sigs {
                if !matches!(s.pool, POOL_ORCHARD | POOL_IRONWOOD) {
                    return Err(EnvelopeError::UnknownTxType(s.pool));
                }
                out.push(s.pool);
                out.extend_from_slice(&s.action_index.to_le_bytes());
                out.extend_from_slice(&s.signature);
            }
            Ok(())
        };
    if single {
        let [m] = messages else {
            return Err(EnvelopeError::BatchCount(messages.len()));
        };
        out.push(TX_TYPE_PCZT_SINGLE_COMPACT_RESPONSE);
        out.push(ver.len() as u8);
        out.extend_from_slice(ver);
        push_contributions(&mut out, &m.signatures)?;
    } else {
        if messages.is_empty() || messages.len() > BATCH_MAX_MESSAGES {
            return Err(EnvelopeError::BatchCount(messages.len()));
        }
        out.push(TX_TYPE_PCZT_BATCH_COMPACT_RESPONSE);
        out.push(ver.len() as u8);
        out.extend_from_slice(ver);
        out.push(messages.len() as u8);
        for m in messages {
            out.push(m.id.len() as u8);
            out.extend_from_slice(&m.id);
            push_contributions(&mut out, &m.signatures)?;
        }
    }
    Ok(out)
}

/// Wallet-side: parse a compact device response (tx_type 0x07/0x08).
pub fn parse_compact_response(payload: &[u8]) -> Result<CompactResponse, EnvelopeError> {
    let [p, crypto, tx_type, rest @ ..] = payload else {
        return Err(EnvelopeError::TooShort);
    };
    if *p != PRELUDE {
        return Err(EnvelopeError::WrongPrelude);
    }
    if *crypto != CRYPTO_TYPE_ZCASH {
        return Err(EnvelopeError::WrongCryptoType(*crypto));
    }
    let single = match *tx_type {
        TX_TYPE_PCZT_SINGLE_COMPACT_RESPONSE => true,
        TX_TYPE_PCZT_BATCH_COMPACT_RESPONSE => false,
        other => return Err(EnvelopeError::UnknownTxType(other)),
    };

    let (&ver_len, mut rest) = rest
        .split_first()
        .ok_or(EnvelopeError::Truncated("version len"))?;
    let ver_len = ver_len as usize;
    if rest.len() < ver_len {
        return Err(EnvelopeError::Truncated("version"));
    }
    let (version, r) = rest.split_at(ver_len);
    let version = String::from_utf8_lossy(version).into_owned();
    rest = r;

    let mut messages = Vec::new();
    if single {
        let (sigs, _) = read_contributions(rest)?;
        messages.push(CompactResponseMessage {
            id: Vec::new(),
            signatures: sigs,
        });
    } else {
        let (&count, r) = rest
            .split_first()
            .ok_or(EnvelopeError::Truncated("count"))?;
        let count = count as usize;
        if count == 0 || count > BATCH_MAX_MESSAGES {
            return Err(EnvelopeError::BatchCount(count));
        }
        let mut r = r;
        for _ in 0..count {
            let (&id_len, rr) = r.split_first().ok_or(EnvelopeError::Truncated("id len"))?;
            let id_len = id_len as usize;
            if rr.len() < id_len {
                return Err(EnvelopeError::Truncated("id"));
            }
            let (id, rr) = rr.split_at(id_len);
            let (sigs, used) = read_contributions(rr)?;
            messages.push(CompactResponseMessage {
                id: id.to_vec(),
                signatures: sigs,
            });
            r = &rr[used..];
        }
    }
    Ok(CompactResponse { version, messages })
}

fn read_contributions(r: &[u8]) -> Result<(Vec<SignatureContribution>, usize), EnvelopeError> {
    if r.len() < 2 {
        return Err(EnvelopeError::Truncated("sig count"));
    }
    let sig_count = u16::from_le_bytes([r[0], r[1]]) as usize;
    // Reserve against what the buffer could actually hold, not against a
    // declared count: each contribution costs 1 + 4 + 64 bytes, so a payload
    // claiming u16::MAX with nothing behind it would otherwise reserve for
    // 65,535 entries before the first bounds check ran.
    let mut out = Vec::with_capacity(sig_count.min(r.len() / 69 + 1));
    let mut pos = 2;
    for _ in 0..sig_count {
        if r.len() < pos + 1 + 4 + 64 {
            return Err(EnvelopeError::Truncated("signature"));
        }
        let pool = r[pos];
        if !matches!(pool, POOL_ORCHARD | POOL_IRONWOOD) {
            return Err(EnvelopeError::UnknownTxType(pool));
        }
        let action_index = u32::from_le_bytes(r[pos + 1..pos + 5].try_into().unwrap());
        let signature: [u8; 64] = r[pos + 5..pos + 5 + 64].try_into().unwrap();
        out.push(SignatureContribution {
            pool,
            action_index,
            signature,
        });
        pos += 1 + 4 + 64;
    }
    Ok((out, pos))
}

#[cfg(test)]
mod tests {
    use super::*;

    // ── Request encoding and parsing ────────────────────────────────────────

    #[test]
    fn test_encode_single_non_compact() {
        let msg = RequestMessage {
            id: Vec::new(),
            pczt_bytes: vec![1, 2, 3],
        };
        let req = SignRequest::Single(msg);
        let encoded = encode_request(&req).unwrap();
        assert_eq!(encoded[0], PRELUDE);
        assert_eq!(encoded[1], CRYPTO_TYPE_ZCASH);
        assert_eq!(encoded[2], TX_TYPE_PCZT_SINGLE);
        assert_eq!(&encoded[3..], &[1, 2, 3]);
    }

    #[test]
    fn test_encode_single_compact() {
        let msg = RequestMessage {
            id: Vec::new(),
            pczt_bytes: vec![4, 5, 6],
        };
        let req = SignRequest::Single(msg);
        let encoded = encode_request_full(&req, true).unwrap();
        assert_eq!(encoded[0], PRELUDE);
        assert_eq!(encoded[1], CRYPTO_TYPE_ZCASH);
        assert_eq!(encoded[2], TX_TYPE_PCZT_SINGLE_COMPACT);
        assert_eq!(&encoded[3..], &[4, 5, 6]);
    }

    #[test]
    fn test_encode_batch_non_compact() {
        let messages = vec![
            RequestMessage {
                id: b"msg1".to_vec(),
                pczt_bytes: vec![1, 2],
            },
            RequestMessage {
                id: b"msg2".to_vec(),
                pczt_bytes: vec![3, 4],
            },
        ];
        let req = SignRequest::Batch(messages);
        let encoded = encode_request(&req).unwrap();
        assert_eq!(encoded[0], PRELUDE);
        assert_eq!(encoded[1], CRYPTO_TYPE_ZCASH);
        assert_eq!(encoded[2], TX_TYPE_PCZT_BATCH);
        assert_eq!(encoded[3], 2u8); // count
    }

    #[test]
    fn test_encode_batch_compact() {
        let messages = vec![
            RequestMessage {
                id: b"id1".to_vec(),
                pczt_bytes: vec![7, 8],
            },
            RequestMessage {
                id: b"id2".to_vec(),
                pczt_bytes: vec![9, 10],
            },
        ];
        let req = SignRequest::Batch(messages);
        let encoded = encode_request_full(&req, true).unwrap();
        assert_eq!(encoded[0], PRELUDE);
        assert_eq!(encoded[1], CRYPTO_TYPE_ZCASH);
        assert_eq!(encoded[2], TX_TYPE_PCZT_BATCH_COMPACT);
        assert_eq!(encoded[3], 2u8); // count
    }

    #[test]
    fn test_compact_single_request_roundtrip() {
        let msg = RequestMessage {
            id: Vec::new(),
            pczt_bytes: vec![11, 12, 13, 14],
        };
        let req = SignRequest::Single(msg.clone());
        let encoded = encode_request_full(&req, true).unwrap();
        let parsed = parse_request_full(&encoded).unwrap();
        assert!(parsed.compact);
        assert_eq!(parsed.request, SignRequest::Single(msg));
    }

    #[test]
    fn test_compact_batch_request_roundtrip() {
        let messages = vec![
            RequestMessage {
                id: b"m1".to_vec(),
                pczt_bytes: vec![20, 21],
            },
            RequestMessage {
                id: b"m2".to_vec(),
                pczt_bytes: vec![22, 23],
            },
            RequestMessage {
                id: b"m3".to_vec(),
                pczt_bytes: vec![24, 25],
            },
        ];
        let req = SignRequest::Batch(messages.clone());
        let encoded = encode_request_full(&req, true).unwrap();
        let parsed = parse_request_full(&encoded).unwrap();
        assert!(parsed.compact);
        assert_eq!(parsed.request, SignRequest::Batch(messages));
    }

    #[test]
    fn test_non_compact_flag_reported() {
        let msg = RequestMessage {
            id: Vec::new(),
            pczt_bytes: vec![30, 31],
        };
        let req = SignRequest::Single(msg);
        let encoded = encode_request(&req).unwrap();
        let parsed = parse_request_full(&encoded).unwrap();
        assert!(!parsed.compact);
    }

    #[test]
    fn test_batch_cap_is_40() {
        // 40 messages should succeed
        let mut messages_40 = Vec::new();
        for i in 0..40 {
            messages_40.push(RequestMessage {
                id: format!("msg{}", i).into_bytes(),
                pczt_bytes: vec![i as u8],
            });
        }
        let req_40 = SignRequest::Batch(messages_40);
        assert!(encode_request(&req_40).is_ok());

        // 41 messages should fail
        let mut messages_41 = Vec::new();
        for i in 0..41 {
            messages_41.push(RequestMessage {
                id: format!("msg{}", i).into_bytes(),
                pczt_bytes: vec![i as u8],
            });
        }
        let req_41 = SignRequest::Batch(messages_41);
        assert!(encode_request(&req_41).is_err());
    }

    // ── Rejecting response tx_types as requests ─────────────────────────────

    #[test]
    fn test_reject_compact_response_as_request_single() {
        let payload = vec![
            PRELUDE,
            CRYPTO_TYPE_ZCASH,
            TX_TYPE_PCZT_SINGLE_COMPACT_RESPONSE,
            1,    // ver_len
            b'1', // version
        ];
        let result = parse_request_full(&payload);
        assert!(result.is_err());
        match result {
            Err(EnvelopeError::UnknownTxType(0x07)) => (),
            other => panic!("expected UnknownTxType(0x07), got {:?}", other),
        }
    }

    #[test]
    fn test_reject_compact_response_as_request_batch() {
        let payload = vec![
            PRELUDE,
            CRYPTO_TYPE_ZCASH,
            TX_TYPE_PCZT_BATCH_COMPACT_RESPONSE,
            1,    // ver_len
            b'2', // version
            0,    // count
        ];
        let result = parse_request_full(&payload);
        assert!(result.is_err());
        match result {
            Err(EnvelopeError::UnknownTxType(0x08)) => (),
            other => panic!("expected UnknownTxType(0x08), got {:?}", other),
        }
    }

    // ── Truncated compact response parsing ───────────────────────────────────

    #[test]
    fn test_truncated_compact_response_version() {
        // Missing version bytes
        let payload = vec![
            PRELUDE,
            CRYPTO_TYPE_ZCASH,
            TX_TYPE_PCZT_SINGLE_COMPACT_RESPONSE,
            5, // ver_len says 5 but no bytes follow
        ];
        let result = parse_compact_response(&payload);
        assert!(result.is_err());
        match result {
            Err(EnvelopeError::Truncated("version")) => (),
            other => panic!("expected Truncated(version), got {:?}", other),
        }
    }

    #[test]
    fn test_truncated_compact_response_sig_count() {
        // Version present but sig count truncated
        let payload = vec![
            PRELUDE,
            CRYPTO_TYPE_ZCASH,
            TX_TYPE_PCZT_SINGLE_COMPACT_RESPONSE,
            1, // ver_len
            b'1', // version
               // missing sig_count (needs 2 bytes)
        ];
        let result = parse_compact_response(&payload);
        assert!(result.is_err());
        match result {
            Err(EnvelopeError::Truncated("sig count")) => (),
            other => panic!("expected Truncated(sig count), got {:?}", other),
        }
    }

    #[test]
    fn test_truncated_compact_response_signature() {
        // Has sig_count but incomplete signature
        let mut payload = vec![
            PRELUDE,
            CRYPTO_TYPE_ZCASH,
            TX_TYPE_PCZT_SINGLE_COMPACT_RESPONSE,
            1,    // ver_len
            b'1', // version
            1,
            0,            // sig_count = 1
            POOL_ORCHARD, // pool
            0,
            0,
            0,
            0, // action_index = 0
               // only 30 bytes of signature instead of 64
        ];
        payload.extend_from_slice(&[0u8; 30]);
        let result = parse_compact_response(&payload);
        assert!(result.is_err());
        match result {
            Err(EnvelopeError::Truncated("signature")) => (),
            other => panic!("expected Truncated(signature), got {:?}", other),
        }
    }

    #[test]
    fn test_truncated_compact_batch_response_id() {
        // Batch with truncated id
        let payload = vec![
            PRELUDE,
            CRYPTO_TYPE_ZCASH,
            TX_TYPE_PCZT_BATCH_COMPACT_RESPONSE,
            1,    // ver_len
            b'1', // version
            1,    // count = 1
            3,    // id_len = 3
            // id truncated
            b'a',
            b'b',
        ];
        let result = parse_compact_response(&payload);
        assert!(result.is_err());
        match result {
            Err(EnvelopeError::Truncated("id")) => (),
            other => panic!("expected Truncated(id), got {:?}", other),
        }
    }

    // ── Compact response encode-parse roundtrip ──────────────────────────────

    #[test]
    fn test_compact_single_response_roundtrip() {
        let mut sig = [0u8; 64];
        sig[0] = 42;
        sig[63] = 99;
        let messages = vec![CompactResponseMessage {
            id: Vec::new(),
            signatures: vec![
                SignatureContribution {
                    pool: POOL_ORCHARD,
                    action_index: 5,
                    signature: sig,
                },
                SignatureContribution {
                    pool: POOL_IRONWOOD,
                    action_index: 10,
                    signature: sig,
                },
            ],
        }];
        let version = "1";
        let encoded = encode_compact_response(&messages, version, true).unwrap();
        let parsed = parse_compact_response(&encoded).unwrap();

        assert_eq!(parsed.version, version);
        assert_eq!(parsed.messages.len(), 1);
        let msg = &parsed.messages[0];
        assert_eq!(msg.id, Vec::new());
        assert_eq!(msg.signatures.len(), 2);
        assert_eq!(msg.signatures[0].pool, POOL_ORCHARD);
        assert_eq!(msg.signatures[0].action_index, 5);
        assert_eq!(msg.signatures[0].signature, sig);
        assert_eq!(msg.signatures[1].pool, POOL_IRONWOOD);
        assert_eq!(msg.signatures[1].action_index, 10);
        assert_eq!(msg.signatures[1].signature, sig);
    }

    #[test]
    fn test_compact_batch_response_roundtrip() {
        let mut sig1 = [0u8; 64];
        sig1[0] = 1;
        let mut sig2 = [0u8; 64];
        sig2[0] = 2;

        let messages = vec![
            CompactResponseMessage {
                id: b"batch_id_1".to_vec(),
                signatures: vec![SignatureContribution {
                    pool: POOL_ORCHARD,
                    action_index: 0,
                    signature: sig1,
                }],
            },
            CompactResponseMessage {
                id: b"batch_id_2".to_vec(),
                signatures: vec![SignatureContribution {
                    pool: POOL_IRONWOOD,
                    action_index: 3,
                    signature: sig2,
                }],
            },
        ];
        let version = "2";
        let encoded = encode_compact_response(&messages, version, false).unwrap();
        let parsed = parse_compact_response(&encoded).unwrap();

        assert_eq!(parsed.version, version);
        assert_eq!(parsed.messages.len(), 2);

        assert_eq!(parsed.messages[0].id, b"batch_id_1");
        assert_eq!(parsed.messages[0].signatures.len(), 1);
        assert_eq!(parsed.messages[0].signatures[0].pool, POOL_ORCHARD);
        assert_eq!(parsed.messages[0].signatures[0].action_index, 0);
        assert_eq!(parsed.messages[0].signatures[0].signature, sig1);

        assert_eq!(parsed.messages[1].id, b"batch_id_2");
        assert_eq!(parsed.messages[1].signatures.len(), 1);
        assert_eq!(parsed.messages[1].signatures[0].pool, POOL_IRONWOOD);
        assert_eq!(parsed.messages[1].signatures[0].action_index, 3);
        assert_eq!(parsed.messages[1].signatures[0].signature, sig2);
    }

    #[test]
    fn test_compact_response_preserves_action_indices() {
        let mut sigs = Vec::new();
        for i in 0..5 {
            let mut sig = [0u8; 64];
            sig[0] = i as u8;
            sigs.push(SignatureContribution {
                pool: if i % 2 == 0 {
                    POOL_ORCHARD
                } else {
                    POOL_IRONWOOD
                },
                action_index: i as u32 * 100,
                signature: sig,
            });
        }
        let messages = vec![CompactResponseMessage {
            id: Vec::new(),
            signatures: sigs.clone(),
        }];
        let version = "3";
        let encoded = encode_compact_response(&messages, version, true).unwrap();
        let parsed = parse_compact_response(&encoded).unwrap();

        assert_eq!(parsed.messages[0].signatures.len(), 5);
        for (i, sig) in parsed.messages[0].signatures.iter().enumerate() {
            assert_eq!(sig.action_index, (i as u32) * 100);
            assert_eq!(
                sig.pool,
                if i % 2 == 0 {
                    POOL_ORCHARD
                } else {
                    POOL_IRONWOOD
                }
            );
        }
    }

    #[test]
    fn test_compact_response_version_variations() {
        // Test different version strings
        for version_str in &["1", "2", "10", "100", "rc1", "1.0.0-alpha"] {
            let messages = vec![CompactResponseMessage {
                id: Vec::new(),
                signatures: vec![],
            }];
            let encoded = encode_compact_response(&messages, version_str, true).unwrap();
            let parsed = parse_compact_response(&encoded).unwrap();
            assert_eq!(parsed.version, *version_str);
        }
    }

    #[test]
    fn test_batch_compact_response_multiple_ids() {
        let mut messages = Vec::new();
        for id_num in 0..5 {
            let id = format!("id_{:03}", id_num).into_bytes();
            let mut sig = [0u8; 64];
            sig[0] = id_num as u8;
            messages.push(CompactResponseMessage {
                id,
                signatures: vec![SignatureContribution {
                    pool: POOL_ORCHARD,
                    action_index: id_num as u32,
                    signature: sig,
                }],
            });
        }
        let version = "1";
        let encoded = encode_compact_response(&messages, version, false).unwrap();
        let parsed = parse_compact_response(&encoded).unwrap();

        assert_eq!(parsed.messages.len(), 5);
        for (i, msg) in parsed.messages.iter().enumerate() {
            assert_eq!(msg.id, format!("id_{:03}", i).into_bytes());
            assert_eq!(msg.signatures[0].action_index, i as u32);
        }
    }
}

#[cfg(test)]
mod width_tests {
    use super::*;

    /// A declared length near u32::MAX must be refused, not wrapped.
    ///
    /// This matters most where it is least visible: the module is compiled to
    /// wasm32, so `usize` is 32-bit there ALWAYS, regardless of the phone's
    /// ABI. `if r.len() < pos + len` looks like a bounds check and stops being
    /// one when `pos + len` wraps - the comparison then passes and the slice
    /// is built with start > end.
    ///
    /// Run this on a 32-bit target to exercise the real path:
    ///
    ///     cargo test -p pczt_signing --lib --target i686-unknown-linux-gnu
    ///
    /// On x86_64 it can only document the intent, which is why the target is
    /// named here rather than left to whoever reads the failure.
    #[test]
    fn huge_declared_pczt_length_is_refused() {
        for declared in [u32::MAX, u32::MAX - 1, 0xffff_ff00, 0x8000_0000] {
            let mut payload = vec![PRELUDE, CRYPTO_TYPE_ZCASH, TX_TYPE_PCZT_SINGLE];
            payload.extend_from_slice(&[0xab; 32]); // digest
            payload.extend_from_slice(&declared.to_le_bytes());
            payload.extend_from_slice(&[0xcd; 8]); // far less than declared

            match parse_response(&payload) {
                Ok(msgs) => {
                    // Accepting is only defensible if nothing was fabricated.
                    for m in msgs {
                        assert!(
                            m.signed_pczt.len() <= payload.len(),
                            "returned a pczt longer than the buffer it came from"
                        );
                    }
                }
                Err(_) => {}
            }
        }
    }
}
