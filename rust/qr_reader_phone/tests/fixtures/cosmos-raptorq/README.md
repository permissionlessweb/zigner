# Zigner multi-frame Cosmos request fixture (raptorq)

Test data only: a session-key operator-exec amino sign doc
(MsgExec{MsgExec{MsgExecuteContract create_svg_collection}}) on the throwaway local chain
`deploy-rehearsal-1`, using mock addresses. There are no real keys and no mainnet addresses.

- `payload.hex`: the full reassembled Zigner request (prefix `530510`, account index 0,
  chain name `terp`, then the sign doc), byte for byte as the cw-orch-kms approval page encodes it (`zigner_qr_hex`).
- `sign_doc.json`: the canonical amino JSON inside the payload.
- `frames/frames.hex` (one hex frame per line, in emission order): each frame is a
  4-byte BE header (top bit set, low 31 bits = payload length) followed by a raptorq 1.8 `EncodingPacket::serialize()`
  (4-byte PayloadId + symbol).
- `meta.json`: lengths, symbol size, source/repair counts, header value, raptorq OTI, sha256 values and self-check results.

The sets:
- `frames-1000/`: the default, 33 frames (17 source + 16 repair) of 1008 bytes.
- `frames-300/`: `--frame-bytes 300`. raptorq rounds the symbol down to its 8-byte alignment, so the symbol is 296 bytes
  and there are 108 frames (55 source + 53 repair) of 304 bytes.

The payload is 16192 bytes, sha256 `edf6dc68cb0bfc77c61c26a8d7a217feda865299c01753f99e907e3fff1b2051` (the same in both sets).

Decode as Zigner does: `ObjectTransmissionInformation::with_defaults(len_from_header, frame_len - 8)`.
Any `source_frames` distinct frames decode the payload, and `source_frames - 1` frames fail.

## Regenerate (on groot-wan)
```
cd ~/abstract/terp-core/crates/cw-orchestrator/packages/cw-orch-kms
O=~/abstract/zigner-fixtures/cosmos-raptorq
CARGO_TARGET_DIR=~/.cache/grokbot-target cargo run --offline --example qr_fixture -- --out $O/frames-1000
CARGO_TARGET_DIR=~/.cache/grokbot-target cargo run --offline --example qr_fixture -- --out $O/frames-300 --frame-bytes 300
```
Optional: `--msg-file F` swaps in a different contract msg (default `examples/fixtures/create_svg_collection.json`).
Each run self-checks (all frames, the first 5 skipped, reversed, too few frames) and aborts on mismatch.
The output is deterministic.
