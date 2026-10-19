//! Replay REAL ironwood-SEND cold-signing inputs (from the zcli
//! `dump_ironwood_send_fixtures` producer) through the SHIPPED module0.wasm -
//! the exact bytes the device receives, no browser, no phone.
//!
//! For each shape we check TWO paths end-to-end:
//!   FULL (0x03):    summarize -> sign -> every ironwood spend authorized.
//!   COMPACT (0x05): summarize -> sign -> MERGE the returned signatures into the
//!                   retained pczt via apply_signature_contribution. The merge
//!                   verifies each sig against the action's rk over the RETAINED
//!                   tx's sighash, so a device that signed a DIFFERENT sighash
//!                   (e.g. the compact empty-memo hardcode vs a real memo) is
//!                   caught here as a rejected merge - not a false green.
//!
//! Shapes come in two consensus branches built by the SAME wallet code zafu
//! ships (zcli `crates/zcash-wasm`): NU6.3 (`<shape>`) and NU7 (`nu7_<shape>`,
//! branch 0x77190ad9, NU7 expiry). The NU7 set is the cross-repo proof that a
//! wallet-built NU7 PCZT is signed by the shipped module.
//!
//! Regenerate (zcli):
//!   ZIGNER_FIXTURES_DIR=$PWD/rust/pczt_signing/tests/fixtures \
//!   cargo test --release --test dump_ironwood_send_fixtures -- --nocapture

use module_host::ModuleRuntime;
use pczt::Pczt;

const BUNDLED_MODULE_WASM: &str = concat!(
    env!("CARGO_MANIFEST_DIR"),
    "/../../android/src/main/assets/modules/module0.wasm"
);
const FIXTURE_DIR: &str = concat!(env!("CARGO_MANIFEST_DIR"), "/tests/fixtures");
const MNEMONIC: &str =
    "abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon abandon about";

const SHAPES: &[&str] = &[
    "single",
    "memo",
    "zt",
    "multinote",
    "nu7_single",
    "nu7_memo",
    "nu7_zt",
    "nu7_multinote",
];

/// Fixtures are committed, so a missing one is a failure: skipping would turn
/// a deleted or misnamed fixture into a silent green.
fn fixture(shape: &str, kind: &str) -> Vec<u8> {
    let path = format!("{FIXTURE_DIR}/ironwood_{shape}_{kind}.hex");
    let hex = std::fs::read_to_string(&path)
        .unwrap_or_else(|e| panic!("[{shape}] fixture missing: {path}: {e}"));
    hex::decode(hex.trim()).expect("fixture hex")
}

fn runtime() -> ModuleRuntime {
    let wasm =
        std::fs::read(BUNDLED_MODULE_WASM).unwrap_or_else(|e| panic!("module wasm missing: {e}"));
    ModuleRuntime::load(&wasm).expect("instantiate module under wasmi")
}

/// FULL path across every shape - the "get ours working" assertion.
#[test]
fn ironwood_send_full_path_signs_every_shape() {
    let mut rt = runtime();
    for shape in SHAPES {
        let bytes = fixture(shape, "full");
        let mut payload = vec![0x53, 0x04, 0x03];
        payload.extend_from_slice(&bytes);
        rt.summarize_request(&payload)
            .unwrap_or_else(|e| panic!("[{shape}] FULL summarize failed: {e:?}"));
        let resp = rt
            .sign_request(&payload, MNEMONIC, 0, false)
            .unwrap_or_else(|e| panic!("[{shape}] FULL sign failed: {e:?}"));
        let messages =
            pczt_signing::envelope::parse_response(&resp).expect("response envelope parses");
        let signed = Pczt::parse(&messages[0].signed_pczt).expect("signed pczt parses");
        for (i, a) in signed.ironwood().actions().iter().enumerate() {
            assert!(
                a.spend().spend_auth_sig().is_some(),
                "[{shape}] ironwood action {i} left unsigned on FULL path"
            );
        }
        eprintln!("[{shape}] FULL: signed + all ironwood spends authorized");
    }
}

/// COMPACT path across every shape, WITH merge-verification. Reports per shape
/// whether the device's signatures actually merge into the retained tx.
#[test]
fn ironwood_send_compact_signatures_merge_every_shape() {
    let mut rt = runtime();
    let mut failures = Vec::new();

    for shape in SHAPES {
        let (compact, retained) = (fixture(shape, "compact"), fixture(shape, "retained"));
        let mut payload = vec![0x53, 0x04, 0x05];
        payload.extend_from_slice(&compact);

        if let Err(e) = rt.summarize_request(&payload) {
            eprintln!("[{shape}] COMPACT summarize FAILED: {e:?}");
            failures.push(format!("{shape}: summarize {e:?}"));
            continue;
        }
        let resp = match rt.sign_request(&payload, MNEMONIC, 0, false) {
            Ok(r) => r,
            Err(e) => {
                eprintln!("[{shape}] COMPACT sign FAILED: {e:?}");
                failures.push(format!("{shape}: sign {e:?}"));
                continue;
            }
        };
        let parsed = pczt_signing::parse_compact_response(&resp).expect("compact response parses");

        // Merge each returned signature into the RETAINED tx. This is where a
        // sighash mismatch (device signed the wrong bytes) surfaces.
        let mut merged = Pczt::parse(&retained).expect("retained pczt parses");
        let mut applied = 0usize;
        let mut rejected = 0usize;
        for m in &parsed.messages {
            for c in &m.signatures {
                match pczt_signing::apply_signature_contribution(merged.clone(), c) {
                    Ok(next) => {
                        merged = next;
                        applied += 1;
                    }
                    Err(e) => {
                        rejected += 1;
                        eprintln!("[{shape}] COMPACT merge REJECTED a signature: {e:?}");
                    }
                }
            }
        }
        if rejected > 0 || applied == 0 {
            failures.push(format!("{shape}: {applied} merged, {rejected} rejected"));
            eprintln!("[{shape}] COMPACT: {applied} merged, {rejected} REJECTED  <-- BREAK");
        } else {
            eprintln!("[{shape}] COMPACT: {applied} signatures merged clean");
        }
    }

    // GUARANTEE. Compact signing must produce signatures that verify against
    // the retained tx on EVERY send shape. This held once zcli
    // `redact_pczt_compact` was switched from the hand-rolled empty-memo
    // replacement to the canonical `compact_resolvable_fields()` primitive,
    // which clears cmx/enc_ciphertext/cv_net only when the device's
    // resolve_fields reproduces them byte-for-byte (dummies + real memos are
    // retained). A regression here means the compact redaction started clearing
    // a sighash-committed field it cannot losslessly regenerate again.
    assert!(
        failures.is_empty(),
        "compact broke on some shapes - a sighash-committed field is no longer \
         losslessly regenerable: {failures:?}"
    );
    eprintln!(
        "compact: signatures merge clean on all {} shapes",
        SHAPES.len()
    );
}
