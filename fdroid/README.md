# Zigner F-Droid Readiness Audit

## Executive Summary

**Status: NOT READY FOR SUBMISSION** — There is one significant blocker that must be resolved before F-Droid can accept this repository.

## Critical Blockers

### 1. **Prebuilt WebAssembly Module (BLOCKER)**

**File:** `android/src/main/assets/modules/module0.wasm` (2.0 MB)

**Problem:** F-Droid's fundamental policy is that all binaries shipped in APKs must be built during their build process from source code. Prebuilt binary artifacts committed to the repository are not permitted, as they prevent verification that the binary is genuinely built from the claimed source.

**Impact:** F-Droid will reject the build without this being resolved.

**Source Location:** `rust/pczt_signing/` is the Rust crate that builds to WebAssembly

**Solution Required:** Two options:

1. **Remove the Checked-In Binary (Recommended)**
   - Delete `android/src/main/assets/modules/module0.wasm` from git
   - Add a gradle prebuild task (or F-Droid `sudo` step) that rebuilds it during build
   - Build command (approximately): `cd rust/pczt_signing && cargo build --target wasm32-unknown-unknown --release`
   - Copy output from `rust/pczt_signing/target/wasm32-unknown-unknown/release/pczt_signing.wasm` → `android/src/main/assets/modules/module0.wasm`
   - This will add about 5-10 minutes to F-Droid's build time (one-time per build)

2. **Alternative: Pre-build in F-Droid Metadata**
   - Write the prebuild step in the F-Droid build metadata with `sudo` directives
   - Requires F-Droid maintainer setup of wasm32 target on their builder

**Difficulty:** Moderate. The wasm target is standard Rust, but requires:
- `rustup target add wasm32-unknown-unknown` (already in the CI workflows)
- `cargo` in the build environment (F-Droid has Rust tooling available)
- The Cargo.lock is already committed, so reproducibility should be good

**Note from Project Memory:** The memory indicates wasm is rebuilt and copied during development, but the committed binary was a deliberate choice, possibly for CI speed. F-Droid will not accept this compromise.

---

## Other Binaries Checked

- **gradle-wrapper.jar** (`gradle/wrapper/gradle-wrapper.jar`): Acceptable to F-Droid (standard gradle wrapper, included in their trust policy)
- **.so native library** (`libsigner.so`): NOT committed; built by rust-android-gradle plugin during build — Correct

---

## Non-Critical Issues (All Clear)

✓ **License:** GPL-3.0-only (SPDX) — acceptable  
✓ **Google Play Services:** Not present  
✓ **Firebase:** Not present  
✓ **Google ML Kit:** Present (`com.google.mlkit:barcode-scanning:17.3.0`) — This is open-source and does NOT require Play Services. Does not trigger AntiFeatures.  
✓ **Product Flavors:** None (single build, no Play vs. FOSS split)  
✓ **Hardcoded Secrets:** None found  
✓ **Git Dependencies:** All point to public repositories:
  - github.com/rotkonetworks/zcli.git (public)
  - github.com/paritytech/substrate (public)
  - github.com/paritytech/banana-recovery-rust (public)
  - github.com/novasamatech/merkleized-metadata.git (public)
  - github.com/penumbra-zone/penumbra.git (public)  
✓ **Gradle Versions:** Up-to-date (Android Gradle Plugin 8.7.3)  
✓ **NDK Version:** Pinned (28.2.13676358)  
✓ **Java/Kotlin:** Java 11 target, Kotlin 1.9.20  
✓ **AppID:** `net.rotko.zigner` — available, clear naming  

---

## Reproducibility

The build is largely reproducible:
- Cargo.lock is committed
- gradle-wrapper.jar pinned
- NDK version pinned
- All Rust targets are stable (no nightly)
- Profile settings are deterministic (release build)

Minor risk: The Rust dependencies pulled from GitHub (frost-spend, substrate) rely on git commits, not versioned releases. F-Droid will clone these at the specified commit, which should remain available if the repos don't have force-push policies.

---

## Rank of Effort to Clear Blockers

**1. Remove Prebuilt Wasm (MUST DO - High Priority)**
   - Effort: 1-2 days (code changes, testing in CI)
   - Risk: Medium (need to verify wasm builds reproducibly, and that F-Droid CI can run it)
   - Impact: Unblocks submission

---

## What F-Droid Expects (Once Blocker is Cleared)

1. A metadata file (`net.rotko.zigner.yml`) in fdroiddata repository with:
   - Repo URL pointing to GitHub
   - Build instructions for the gradle build
   - A prebuild step to compile wasm32 binary
   - MinSdkVersion 23, TargetSdkVersion 35
   - gradle flavor: `yes` (single build, no flavors)

2. All source must remain public and accessible via the Repo URL

3. Builds must be reproducible enough that checksums are deterministic (Rust + Gradle should be fine)

---

## Parity Signer Precedent

Parity Signer is indeed on F-Droid (https://f-droid.org/en/packages/io.parity.signer/). Their approach:
- Full Rust+Android stack similar to Zigner
- Uses rust-android-gradle plugin (as Zigner does)
- No prebuilt binaries in git
- Build process handles all compilation

This confirms the technical feasibility of the solution.

---

## Submission Checklist (Once Blocker Cleared)

- [ ] Remove `android/src/main/assets/modules/module0.wasm` from git
- [ ] Add wasm build step to F-Droid metadata (prebuild with `sudo`)
- [ ] Test build in sandbox environment or CI
- [ ] Verify wasm reproducibility (binary should be identical across builds)
- [ ] Submit metadata file to fdroiddata GitLab repository
- [ ] Address F-Droid maintainer review feedback
- [ ] Allow 2-4 weeks for review and merge

---

## Conclusion

**Zigner cannot be submitted to F-Droid as currently configured** due to the committed prebuilt wasm module. However, the project is otherwise well-structured for F-Droid submission:

- Clean dependency graph (no proprietary SDKs)
- GPL-3.0 license
- Well-documented build process
- Strong security model
- Public git dependencies

Once the wasm module issue is resolved by building it at compile time, this should be a straightforward F-Droid submission. The build is more complex than typical F-Droid apps (Rust + NDK + wasm), but Parity Signer already demonstrates this is achievable and acceptable.
