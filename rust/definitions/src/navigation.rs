use sp_core::H256;

use crate::derivations::SeedKeysPreview;
use crate::{
    crypto::Encryption, history::Event, keyring::NetworkSpecsKey,
    network_specs::OrderedNetworkSpecs,
};

pub use crate::network_specs::NetworkSpecs;

#[derive(PartialEq, Eq, Clone)]
pub struct SeedNameWithIdenticon {
    pub seed_name: String,
    pub identicon: Identicon,
}

/// Network information to use during signing
#[derive(PartialEq, Eq, Debug, Clone)]
pub enum TransactionSignActionNetwork {
    Concrete(Box<OrderedNetworkSpecs>),
    AnyNetwork(Encryption),
}

impl TransactionSignActionNetwork {
    pub fn get_network_spec(&self) -> Option<OrderedNetworkSpecs> {
        match self {
            TransactionSignActionNetwork::Concrete(spec) => Some(spec.as_ref().clone()),
            TransactionSignActionNetwork::AnyNetwork(_) => None,
        }
    }

    pub fn get_encryption(&self) -> Encryption {
        match self {
            TransactionSignActionNetwork::Concrete(spec) => spec.specs.encryption,
            TransactionSignActionNetwork::AnyNetwork(encryption) => *encryption,
        }
    }
}

/// A single transaction signing action.
#[derive(PartialEq, Eq, Debug, Clone)]
pub struct TransactionSignAction {
    /// Parsed contents of the transaction.
    pub content: TransactionCardSet,

    /// If this transaction should be signed with a passworded key.
    pub has_pwd: bool,

    /// Information about the signing key of this transaction.
    pub author_info: MAddressCard,

    /// Info about the network this tx happens on.
    pub network_info: TransactionSignActionNetwork,
}

impl TransactionSignAction {
    pub fn get_network_spec(&self) -> Option<OrderedNetworkSpecs> {
        self.network_info.get_network_spec()
    }

    pub fn get_encryption(&self) -> Encryption {
        self.network_info.get_encryption()
    }
}

/// Enum containing card sets for four different outcomes:
/// importing derivations (Derivations), signing (Sign),
/// accepting (Stub) and reading, for example, in case of an error (Read)
#[derive(PartialEq, Eq, Debug, Clone)]
pub enum TransactionAction {
    Derivations {
        content: Box<TransactionCardSet>,
    },
    Sign {
        actions: Vec<TransactionSignAction>,
        checksum: u32,
    },
    Stub {
        s: Box<TransactionCardSet>,
        u: u32,
        stub: StubNav,
    },
    Read {
        r: Box<TransactionCardSet>,
    },
}

/// Enum describing Stub content.
/// Is used for proper navigation. Variants:
/// `AddSpecs` (with associated `NetworkSpecsKey`), `LoadMeta` (with associated
/// `NetworkSpecsKey` for the first by order network using those metadata),
/// and `LoadTypes`
#[derive(PartialEq, Eq, Debug, Clone)]
pub enum StubNav {
    AddSpecs { n: NetworkSpecsKey },
    LoadMeta { l: NetworkSpecsKey },
    LoadTypes,
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum FooterButton {
    Log,
    Scan,
    Keys,
    Settings,
    Back,
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum RightButton {
    LogRight,
    NewSeed,
    Backup,
    MultiSelect,
    NDMenu,
    TypesInfo,
    KeyMenu,
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum ScreenNameType {
    H1,
    H4,
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum ShieldAlert {
    Past,
}

#[derive(PartialEq, Eq, Debug, Clone)]
pub enum AlertData {
    Shield { f: Option<ShieldAlert> },
    ErrorData { f: String },
    Confirm,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ActionResult {
    pub screen_label: String,
    pub back: bool,
    pub footer: bool,
    pub footer_button: Option<FooterButton>,
    pub right_button: Option<RightButton>,
    pub screen_name_type: ScreenNameType,
    pub screen_data: ScreenData,
    pub modal_data: Option<ModalData>,
    pub alert_data: Option<AlertData>,
}

#[derive(Clone, PartialEq, Eq)]
pub struct LogScreenEntry {
    pub timestamp: String,
    pub events: Vec<Event>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ScreenData {
    Scan,
    Keys { f: String },
    Settings { f: MSettings },
    Log { f: MLog },
    LogDetails { f: MLogDetails },
    Transaction { f: Vec<MTransaction> },
    SeedSelector { f: MSeeds },
    KeyDetails { f: Option<MKeyDetails> },
    NewSeed { f: MNewSeed },
    RecoverSeedName { f: MRecoverSeedName },
    RecoverSeedPhrase { f: MRecoverSeedPhrase },
    DeriveKey { f: MDeriveKey },
    VVerifier { f: MVerifierDetails },
    ManageNetworks { f: MManageNetworks },
    NNetworkDetails { f: MNetworkDetails },
    SignSufficientCrypto { f: MSignSufficientCrypto },
    SelectSeedForBackup { f: MSeeds },
    Documents,
    KeyDetailsMulti { f: MKeyDetailsMulti },
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MKeysCard {
    pub address: Address,
    pub address_key: String,
    pub base58: String,
    pub swiped: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MKeysNew {
    pub root: Option<MAddressCard>,
    pub set: Vec<MKeyAndNetworkCard>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MKeyAndNetworkCard {
    pub key: MKeysCard,
    pub network: MSCNetworkInfo,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MNetworkCard {
    pub title: String,
    pub logo: String,
}

#[derive(Clone, Default, Debug, PartialEq, Eq)]
pub struct MSettings {
    pub public_key: Option<String>,
    pub identicon: Option<Identicon>,
    pub encryption: Option<String>,
    pub error: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct History {
    pub order: u32,
    pub timestamp: String,
    pub events: Vec<Event>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MLog {
    pub log: Vec<History>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MEventMaybeDecoded {
    pub event: Event,
    pub decoded: Option<TransactionCardSet>,
    pub signed_by: Option<MAddressCard>,
    pub verifier_details: Option<MVerifierDetails>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MLogDetails {
    pub timestamp: String,
    pub events: Vec<MEventMaybeDecoded>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum TransactionType {
    Sign,
    Stub,
    Read,
    ImportDerivations,
    Done,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct TransactionCard {
    pub index: u32,
    pub indent: u32,
    pub card: Card,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum BananaSplitRecoveryResult {
    RequestPassword,
    RecoveredSeed { s: String },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DecodeSequenceResult {
    BBananaSplitRecoveryResult { b: BananaSplitRecoveryResult },
    DynamicDerivations { s: String },
    DynamicDerivationTransaction { s: Vec<String> },
    Other { s: String },
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct TransactionCardSet {
    pub author: Option<Vec<TransactionCard>>,
    pub error: Option<Vec<TransactionCard>>,
    pub extensions: Option<Vec<TransactionCard>>,
    pub importing_derivations: Option<Vec<TransactionCard>>,
    pub message: Option<Vec<TransactionCard>>,
    pub meta: Option<Vec<TransactionCard>>,
    pub method: Option<Vec<TransactionCard>>,
    pub new_specs: Option<Vec<TransactionCard>>,
    pub verifier: Option<Vec<TransactionCard>>,
    pub warning: Option<Vec<TransactionCard>>,
    pub types_info: Option<Vec<TransactionCard>>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MTransaction {
    pub content: TransactionCardSet,
    pub ttype: TransactionType,
    pub author_info: Option<MAddressCard>,
    pub network_info: Option<MSCNetworkInfo>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSignedTransaction {
    pub transaction: Vec<MTransaction>,
    pub signature: MSignatureReady,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct SeedNameCard {
    pub seed_name: String,
    pub identicon: Identicon,
    pub used_in_networks: Vec<String>,
    pub derived_keys_count: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSeeds {
    pub seed_name_cards: Vec<SeedNameCard>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MKeyDetails {
    pub qr: QrData,
    pub pubkey: String,
    pub network_info: MSCNetworkInfo,
    pub base58: String,
    pub address: Address,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MNewSeed {
    pub keyboard: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MRecoverSeedName {
    pub keyboard: bool,
    pub seed_name: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MRecoverSeedPhrase {
    pub keyboard: bool,
    pub seed_name: String,
    pub user_input: String,
    pub guess_set: Vec<String>,
    pub draft: Vec<String>,
    pub ready_seed: Option<String>,
}

#[derive(Clone, Default, Debug, PartialEq, Eq)]
pub struct DerivationCheck {
    pub button_good: bool,
    pub where_to: Option<DerivationDestination>,
    pub collision: Option<MAddressCard>,
    pub error: Option<String>,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct Address {
    pub path: String,
    pub has_pwd: bool,
    pub identicon: Identicon,
    pub seed_name: String,
    pub secret_exposed: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MAddressCard {
    pub base58: String,
    pub address_key: String,
    pub address: Address,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum DerivationDestination {
    Pwd,
    Pin,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MDeriveKey {
    pub seed_name: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MVerifierDetails {
    pub public_key: String,
    pub identicon: Identicon,
    pub encryption: String,
}

#[derive(Clone, Debug, Default, PartialEq, Eq)]
pub struct MVerifier {
    pub ttype: String,
    pub details: MVerifierDetails,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MMetadataRecord {
    pub specname: String,
    pub specs_version: String,
    pub meta_hash: String,
    pub meta_id_pic: Identicon,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MNetworkDetails {
    pub base58prefix: u16,
    pub color: String,
    pub decimals: u8,
    pub encryption: Encryption,
    pub genesis_hash: H256,
    pub logo: String,
    pub name: String,
    pub order: String,
    pub path_id: String,
    pub secondary_color: String,
    pub title: String,
    pub unit: String,
    pub current_verifier: MVerifier,
    pub meta: Vec<MMetadataRecord>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MRawKey {
    pub address: Address,
    pub address_key: String,
    pub public_key: String,
    pub network_logo: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSignSufficientCrypto {
    pub identities: Vec<MRawKey>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MKeyDetailsMulti {
    pub key_details: MKeyDetails,
    pub current_number: String,
    pub out_of: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MMNetwork {
    pub key: String,
    pub title: String,
    pub logo: String,
    pub order: u8,
    pub path_id: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MManageNetworks {
    pub networks: Vec<MMNetwork>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ExportedSet {
    All,
    Selected { s: Vec<PathAndNetwork> },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PathAndNetwork {
    pub derivation: String,
    pub network_specs_key: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum MSCContent {
    LoadTypes { types: String, pic: Identicon },
    LoadMetadata { name: String, version: u32 },
    AddSpecs { f: MSCNetworkInfo },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum QrData {
    Regular { data: Vec<u8> },
    Sensitive { data: Vec<u8> },
}

impl QrData {
    /// Get the length of the underlying data
    pub fn len(&self) -> usize {
        match self {
            QrData::Regular { data } | QrData::Sensitive { data } => data.len(),
        }
    }

    /// Get a reference to the underlying data.
    pub fn data(&self) -> &[u8] {
        match self {
            QrData::Regular { data } | QrData::Sensitive { data } => data,
        }
    }

    /// If the underlying data is empty.
    pub fn is_empty(&self) -> bool {
        match self {
            QrData::Regular { data } | QrData::Sensitive { data } => data.is_empty(),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSufficientCryptoReady {
    pub author_info: MAddressCard,
    pub sufficient: Vec<u8>,
    pub content: MSCContent,
    pub network_logo: Option<String>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DerivationEntry {
    pub path: String,
    pub has_pwd: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DerivationPack {
    pub network_title: String,
    pub network_logo: String,
    pub network_order: String,
    pub id_set: Vec<DerivationEntry>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MBackup {
    pub seed_name: String,
    pub derivations: Vec<DerivationPack>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSeedMenu {
    pub seed: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MNewSeedBackup {
    pub seed: String,
    pub seed_phrase: String,
    pub identicon: Identicon,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct Network {
    pub key: String,
    pub logo: String,
    pub order: u32,
    pub selected: bool,
    pub title: String,
}

impl From<OrderedNetworkSpecs> for Network {
    fn from(n: OrderedNetworkSpecs) -> Self {
        let key = hex::encode(
            NetworkSpecsKey::from_parts(&n.specs.genesis_hash, &n.specs.encryption).key(),
        );
        Network {
            key,
            logo: n.specs.logo,
            order: n.order as u32,
            selected: false,
            title: n.specs.title,
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MNetworkMenu {
    pub networks: Vec<Network>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MPasswordConfirm {
    pub pwd: String,
    pub seed_name: String,
    pub cropped_path: String,
}

/// Data about signatures that are ready.
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSignatureReady {
    /// Frames of the animated QR code that should be displayed by the UI.
    pub signatures: Vec<QrData>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MEnterPassword {
    pub author_info: MAddressCard,
    pub network_info: Option<MSCNetworkInfo>,
    pub counter: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MLogRight {
    pub checksum: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MMMNetwork {
    pub title: String,
    pub logo: String,
    pub order: u32,
    pub current_on_screen: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MManageMetadata {
    pub name: String,
    pub version: String,
    pub meta_hash: String,
    pub meta_id_pic: Identicon,
    pub networks: Vec<MMMNetwork>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MTypesInfo {
    pub types_on_file: bool,
    pub types_hash: Option<String>,
    pub types_id_pic: Option<Identicon>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum ModalData {
    SufficientCryptoReady { f: MSufficientCryptoReady },
    Backup { f: MBackup },
    SeedMenu { f: MSeedMenu },
    NewSeedBackup { f: MNewSeedBackup },
    NetworkSelector { f: MNetworkMenu },
    PasswordConfirm { f: MPasswordConfirm },
    SignatureReady { f: MSignatureReady },
    EnterPassword { f: MEnterPassword },
    LogRight { f: MLogRight },
    TypesInfo { f: MTypesInfo },
    NewSeedMenu,
    NetworkDetailsMenu,
    ManageMetadata { f: MManageMetadata },
    KeyDetailsAction,
    LogComment,
    SelectSeed { f: MSeeds },
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSCCall {
    pub method_name: String,
    pub docs: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSCCurrency {
    pub amount: String,
    pub units: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSCEnumVariantName {
    pub name: String,
    pub docs_enum_variant: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSCEraMortal {
    pub era: String,
    pub phase: String,
    pub period: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSCFieldName {
    pub name: String,
    pub docs_field_name: String,
    pub path_type: String,
    pub docs_type: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSCFieldNumber {
    pub number: String,
    pub docs_field_number: String,
    pub path_type: String,
    pub docs_type: String,
}

#[derive(Clone, PartialEq, Eq)]
pub enum Identicon {
    Dots { identity: Vec<u8> },
    Blockies { identity: String },
    Jdenticon { identity: String },
}

impl std::fmt::Debug for Identicon {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Identicon::Dots { identity } => write!(f, "Dots {}", hex::encode(&identity[..32])),
            Identicon::Blockies { identity } => {
                write!(f, "Blockies {identity}")
            }
            Identicon::Jdenticon { identity } => {
                write!(f, "Jdenticon {identity}")
            }
        }
    }
}

impl Default for Identicon {
    fn default() -> Self {
        Self::Dots { identity: vec![] }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSCId {
    pub base58: String,
    pub identicon: Identicon,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSCNameVersion {
    pub name: String,
    pub version: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSCNetworkInfo {
    pub network_title: String,
    pub network_logo: String,
    pub network_specs_key: String,
}

/// Dynamic deprivations model
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DDPreview {
    pub qr: Vec<QrData>,
    pub key_set: DDKeySet,
    pub is_some_already_imported: bool,
    pub is_some_network_missing: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DDKeySet {
    pub seed_name: String,
    pub derivations: Vec<DDDetail>,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct DDDetail {
    pub base58: String,
    pub path: String,
    pub network_logo: String,
    pub network_specs_key: String,
    pub identicon: Identicon,
}

impl From<OrderedNetworkSpecs> for MSCNetworkInfo {
    fn from(o: OrderedNetworkSpecs) -> Self {
        MSCNetworkInfo {
            network_title: o.specs.name,
            network_logo: o.specs.logo,
            network_specs_key: hex::encode(
                NetworkSpecsKey::from_parts(&o.specs.genesis_hash, &o.specs.encryption).key(),
            ),
        }
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSCTip {
    pub amount: String,
    pub units: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MSCTxSpecPlain {
    pub network_genesis_hash: H256,
    pub version: String,
    pub tx_version: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct MKeysInfoExport {
    pub frames: Vec<QrData>,
}

// penumbra transaction types
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PenumbraSpendAction {
    pub note_value: String,
    pub note_asset: String,
    pub note_address: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PenumbraOutputAction {
    pub value: String,
    pub asset: String,
    pub dest_address: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PenumbraSwapAction {
    pub input_value: String,
    pub input_asset: String,
    pub output_asset: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PenumbraDelegateAction {
    pub amount: String,
    pub validator: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PenumbraVoteAction {
    pub proposal_id: u64,
    pub vote: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PenumbraTransactionSummary {
    pub chain_id: String,
    pub expiry_height: Option<u64>,
    pub fee: String,
    pub fee_asset: String,
    pub spend_count: u64,
    pub output_count: u64,
    pub effect_hash: String,
}

/// Penumbra Full Viewing Key export data for watch-only wallet import
///
/// Provides multiple formats for compatibility:
/// - `fvk_bech32m`: Native Penumbra bech32m format ("penumbrafullviewingkey1...")
/// - `wallet_id_bech32m`: Wallet identifier ("penumbrawalletid1...")
/// - `qr_data`: Binary format for Zafu wallet
/// - `ur_string`: UR-encoded string for hardware wallet QR compatibility
///
/// ## UR (Uniform Resource) Format for Penumbra
///
/// We define these UR types for Penumbra (proposed, not yet standardized):
///
/// | UR Type                    | CBOR Tag | Description                    |
/// |----------------------------|----------|--------------------------------|
/// | penumbra-accounts          | 49301    | Container for accounts         |
/// | penumbra-full-viewing-key  | 49302    | Single FVK with metadata       |
///
/// ## CBOR Structure for penumbra-accounts
///
/// ```text
/// PenumbraAccounts = {
///   1: bytes,                ; wallet_id (32 bytes, identifies the wallet)
///   2: [+ #49302(FVK)]       ; accounts array, each tagged with 49302
/// }
///
/// PenumbraFullViewingKey (#49302) = {
///   1: tstr,                 ; fvk - bech32m encoded full viewing key
///   2: uint,                 ; index - account index (0, 1, 2, ...)
///   ? 3: tstr                ; name - optional account label
/// }
/// ```
///
/// Reference: <https://github.com/BlockchainCommons/Research> (UR spec)
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PenumbraFvkExport {
    pub account_index: u32,
    pub label: String,
    /// Bech32m encoded FVK ("penumbrafullviewingkey1...")
    pub fvk_bech32m: String,
    /// Bech32m encoded wallet ID ("penumbrawalletid1...")
    pub wallet_id_bech32m: String,
    /// Binary QR data for Zafu wallet
    pub qr_data: Vec<u8>,
    /// UR-encoded string for hardware wallet QR compatibility
    /// Format: `ur:penumbra-accounts/<bytewords>`
    /// Uses CBOR tag 49301 (penumbra-accounts) containing tag 49302 (penumbra-full-viewing-key)
    pub ur_string: String,
    /// Receive address (index 0 of the account's key), from penumbra-sdk
    pub receive_address: String,
    /// `penumbra:<address>:0x<genesis>`, the text of the receive QR
    pub receive_qr_text: String,
}

/// Cosmos account export data for Zafu wallet import
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CosmosAccountExport {
    pub account_index: u32,
    pub label: String,
    /// Hex-encoded compressed secp256k1 public key (33 bytes)
    pub public_key_hex: String,
    /// Change-level xpub (m/44'/118'/account'/0), base58. The hot wallet derives
    /// burner addresses .../0/i from this watch-only; the device signs each at
    /// the same address_index. This is what makes rotations seed-recoverable
    /// without a cold import per burner.
    pub xpub: String,
    /// Bech32 addresses for each chain
    pub addresses: Vec<CosmosChainAddress>,
    /// Binary QR data for Zafu wallet
    pub qr_data: Vec<u8>,
}

/// A bech32 address on a specific Cosmos chain
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CosmosChainAddress {
    /// Chain identifier (e.g. "osmosis", "noble")
    pub chain_id: String,
    /// Bech32 address (e.g. "osmo1...")
    pub address: String,
    /// Bech32 prefix (e.g. "osmo")
    pub prefix: String,
}

/// a single cosmos message displayed to the user
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CosmosMsgDisplay {
    /// message type (e.g. "Send", "IBC Transfer", "Swap", "Contract Call (BLIND)")
    pub msg_type: String,
    /// target address (recipient, validator, contract, etc.)
    pub recipient: String,
    /// amount string
    pub amount: String,
    /// extra detail (min output, pool id, raw contract msg, etc.)
    pub detail: String,
    /// true if this message requires blind signing (contract calls, unknown types)
    pub blind: bool,
}

/// Cosmos sign request parsed from QR
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct CosmosSignRequest {
    pub account_index: u32,
    /// BIP44 address index (`.../0/index`) - selects a fresh receive address
    pub address_index: u32,
    /// chain name from QR (e.g. "noble", "osmosis")
    pub chain_name: String,
    /// chain_id from amino JSON (e.g. "noble-1", "osmosis-1")
    pub chain_id: String,
    /// ALL messages in the sign doc — must all be shown to the user
    pub msgs: Vec<CosmosMsgDisplay>,
    /// fee string
    pub fee: String,
    /// memo
    pub memo: String,
    /// raw QR hex for re-parsing during signing
    pub raw_qr_hex: String,
}

/// String pair for FFI compatibility
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct StringPair {
    pub first: String,
    pub second: String,
}

/// Generic Penumbra action parsed via schema
/// Used for displaying any action type without hardcoding
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PenumbraGenericAction {
    /// Action type name (e.g., "Create Token")
    pub action_name: String,
    /// Action description
    pub description: String,
    /// Parsed fields as (label, value) pairs
    pub fields: Vec<StringPair>,
    /// Whether this action was recognized by the schema
    pub recognized: bool,
    /// Protobuf field number (for debugging)
    pub field_number: u32,
}

/// Schema update info for display
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PenumbraSchemaInfo {
    pub chain_id: String,
    pub protocol_version: String,
    pub action_count: u32,
    pub schema_version: u32,
}

// ============================================================================
// Zcash types
// ============================================================================

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashTransactionSummary {
    pub mainnet: bool,
    pub fee: String,
    pub spend_count: u64,
    pub output_count: u64,
    pub sighash: String,
    pub anchor: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashOrchardSpend {
    pub nullifier: String,
    pub value: String,
    pub cmx: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashOrchardOutput {
    pub value: String,
    pub recipient: String,
    pub is_change: bool,
}

/// Zcash simple sign request (parsed from QR, exposed via uniffi)
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashSimpleSignRequest {
    pub account_index: u32,
    pub sighash_hex: String,
    pub action_count: u32,
    pub summary: String,
    pub mainnet: bool,
    pub raw_qr_hex: String,
}

/// Zcash Full Viewing Key export data for watch-only wallet import
///
/// Provides multiple formats for maximum compatibility:
/// - `ufvk`: ZIP-316 Unified Full Viewing Key string ("uview1..." or "uviewtest1...")
/// - `qr_data`: Binary format for Zafu wallet
/// - `ur_string`: UR-encoded string for Zashi/Keystone hardware wallet QR compatibility
///
/// ## UR (Uniform Resource) Format
///
/// UR is a standard by Blockchain Commons for encoding data in QR codes.
/// The `ur_string` uses the Keystone SDK's `zcash-accounts` registry type:
///
/// | UR Type       | CBOR Tag | Description                       |
/// |---------------|----------|-----------------------------------|
/// | zcash-accounts| 49201    | Container for multiple accounts   |
/// | zcash-ufvk    | 49203    | Single unified full viewing key   |
///
/// Reference: <https://github.com/KeystoneHQ/keystone-sdk-rust>
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashFvkExport {
    pub account_index: u32,
    pub label: String,
    pub mainnet: bool,
    /// Unified address for receiving funds (orchard only)
    pub address: String,
    /// Raw FVK bytes as hex (96 bytes orchard FVK)
    pub fvk_hex: String,
    /// Unified Full Viewing Key string (ZIP-316 format: "uview1..." or "uviewtest1...")
    pub ufvk: String,
    /// Binary QR data for Zafu wallet
    pub qr_data: Vec<u8>,
    /// UR-encoded string for Zashi/Keystone QR compatibility
    /// Format: `ur:zcash-accounts/<bytewords>`
    /// Uses CBOR tag 49201 (zcash-accounts) containing tag 49203 (zcash-unified-full-viewing-key)
    pub ur_string: String,
}

/// Penumbra sign request parsed from QR
#[derive(Clone, Debug, PartialEq, Eq)]
pub struct PenumbraSignRequest {
    pub chain_id: String,
    pub effect_hash_hex: String,
    pub spend_count: u32,
    pub vote_count: u32,
    pub lqt_vote_count: u32,
    pub raw_qr_hex: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashVerifiedNoteDisplay {
    pub value: u64,
    pub nullifier_hex: String,
    pub block_height: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashNoteSyncResult {
    pub notes_verified: u32,
    pub total_balance: u64,
    pub anchor_hex: String,
    pub anchor_height: u32,
    pub mainnet: bool,
    pub anchor_verified: bool,
    /// Unix seconds (device clock) when these verified notes were stored.
    /// 0 = unknown (older anchor written before the field existed).
    pub synced_at: u64,
    /// Unix seconds of the anchor block's own header (chain truth). 0 = unknown
    /// (bundle produced before the field existed). Lets the UI show the anchor
    /// as a real date toggleable with its height.
    pub anchor_time: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashNotesScanProgress {
    pub have: u32,
    pub needed: i32,
    pub complete: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AuthSignResult {
    pub pubkey_hex: String,
    pub signature_hex: String,
    pub domain: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashSignContext {
    pub verified_balance: u64,
    pub note_count: u32,
    pub anchor_height: u32,
    pub synced_at: u64,
    pub has_notes: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashPcztInspection {
    /// Number of actions in the Orchard bundle.
    pub action_count: u32,
    /// Number of actions in the Ironwood bundle (NU6.3 / V6 pool). `0` for a
    /// V5 transaction. A turnstile migration is exactly the case where this is
    /// non-zero while the Orchard side looks like a pure spend, so a review
    /// screen that only shows `action_count` renders a migration as money
    /// leaving with no destination.
    pub ironwood_action_count: u32,
    /// Orchard spends first, then Ironwood spends.
    pub spends: Vec<ZcashPcztSpend>,
    /// Orchard outputs first, then Ironwood outputs.
    pub outputs: Vec<ZcashPcztOutput>,
    /// True transaction fee = (transparent inputs − transparent outputs)
    ///                     + sapling value_balance
    ///                     + orchard value_balance
    ///                     + ironwood value_balance.
    /// Honest across all bundles. Was previously labeled "Fee" but only
    /// computed the Orchard component, which is wrong when transparent legs
    /// (shielding / deshielding) exist — and, for a turnstile migration, would
    /// report ~the entire migrated amount as the fee.
    pub fee_zat: i64,
    /// Net value of the Orchard bundle alone (kept for diagnostics; for the
    /// canonical fee see `fee_zat`).
    pub orchard_net_value: i64,
    /// Net value of the Ironwood bundle alone (diagnostics; see `fee_zat`).
    /// `0` for a V5 transaction.
    pub ironwood_net_value: i64,
    pub verified_balance: u64,
    pub known_spends: u32,
    /// Transaction version (v5 = 5 for current NU6/NU6.1).
    pub tx_version: u32,
    /// Consensus branch ID as 8-char lowercase hex. Cold device can show this
    /// so the user knows which consensus fork they're authorizing for.
    pub consensus_branch_id_hex: String,
    /// Block height at which this transaction expires if not mined.
    pub expiry_height: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashPcztSpend {
    pub value: u64,
    pub nullifier_hex: String,
    pub known: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashPcztOutput {
    pub value: u64,
    pub recipient: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FrostWalletSummaryFFI {
    pub wallet_id: String,
    pub label: String,
    pub min_signers: u16,
    pub max_signers: u16,
    pub mainnet: bool,
    pub created_at: u64,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AnchorVerifierFFI {
    pub pubkey_hex: String,
    pub label: String,
    pub source: String,
    pub added_at: u64,
    pub enabled: bool,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct AnchorVerifierImport {
    pub pubkey_hex: String,
    pub label: String,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ZcashSyncInfo {
    pub anchor_hex: String,
    pub anchor_height: u32,
    pub mainnet: bool,
    pub synced_at: u64,
    /// Unix seconds of the anchor block's own header (chain truth), 0 = unknown.
    pub anchor_time: u32,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum Card {
    AuthorCard {
        f: MAddressCard,
    },
    AuthorPlainCard {
        f: MSCId,
    },
    AuthorPublicKeyCard {
        f: MVerifierDetails,
    },
    BalanceCard {
        f: MSCCurrency,
    },
    BitVecCard {
        f: String,
    },
    BlockHashCard {
        f: String,
    },
    CallCard {
        f: MSCCall,
    },
    DefaultCard {
        f: String,
    },
    DerivationsCard {
        f: Vec<SeedKeysPreview>,
    },
    EnumVariantNameCard {
        f: MSCEnumVariantName,
    },
    EraImmortalCard,
    EraMortalCard {
        f: MSCEraMortal,
    },
    ErrorCard {
        f: String,
    },
    FieldNameCard {
        f: MSCFieldName,
    },
    FieldNumberCard {
        f: MSCFieldNumber,
    },
    IdCard {
        f: MSCId,
    },
    IdentityFieldCard {
        f: String,
    },
    MetaCard {
        f: MMetadataRecord,
    },
    NameVersionCard {
        f: MSCNameVersion,
    },
    NetworkGenesisHashCard {
        f: String,
    },
    NetworkNameCard {
        f: String,
    },
    NetworkInfoCard {
        f: MSCNetworkInfo,
    },
    NewSpecsCard {
        f: NetworkSpecs,
    },
    NonceCard {
        f: String,
    },
    NoneCard,
    PalletCard {
        f: String,
    },
    TextCard {
        f: String,
    },
    TipCard {
        f: MSCCurrency,
    },
    TipPlainCard {
        f: String,
    },
    TxSpecCard {
        f: String,
    },
    TxSpecPlainCard {
        f: MSCTxSpecPlain,
    },
    TypesInfoCard {
        f: MTypesInfo,
    },
    VarNameCard {
        f: String,
    },
    VerifierCard {
        f: MVerifierDetails,
    },
    WarningCard {
        f: String,
    },
    // penumbra cards
    PenumbraSummaryCard {
        f: PenumbraTransactionSummary,
    },
    PenumbraSpendCard {
        f: PenumbraSpendAction,
    },
    PenumbraOutputCard {
        f: PenumbraOutputAction,
    },
    PenumbraSwapCard {
        f: PenumbraSwapAction,
    },
    PenumbraDelegateCard {
        f: PenumbraDelegateAction,
    },
    PenumbraVoteCard {
        f: PenumbraVoteAction,
    },
    /// Generic action card - displays any action parsed via schema
    PenumbraGenericActionCard {
        f: PenumbraGenericAction,
    },
    /// Schema update info card
    PenumbraSchemaCard {
        f: PenumbraSchemaInfo,
    },
    // zcash cards
    ZcashSummaryCard {
        f: ZcashTransactionSummary,
    },
    ZcashOrchardSpendCard {
        f: ZcashOrchardSpend,
    },
    ZcashOrchardOutputCard {
        f: ZcashOrchardOutput,
    },
}
