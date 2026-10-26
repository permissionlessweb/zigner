//
//  CameraView.swift
//  Polkadot Vault
//
//  Created by Alexander Slesarev on 19.7.2021.
//

import AVFoundation
import SwiftUI

struct CameraView: View {
    @StateObject var model: CameraService = .init()
    @StateObject var viewModel: ViewModel
    @StateObject var progressViewModel: ProgressSnackbarViewModel = .init()
    @Environment(\.safeAreaInsets) private var safeAreaInsets

    var body: some View {
        ZStack {
            CameraPreview(session: model.session)
                .onReceive(model.$payload) { payload in
                    guard let payload else { return }
                    DispatchQueue.main.async {
                        viewModel.didUpdatePayload(payload)
                    }
                }
                .onChange(of: model.total) { total in
                    progressViewModel.total = total
                    if total > 1, viewModel.isPresentingProgressSnackbar == false {
                        viewModel.isPresentingProgressSnackbar = true
                    }
                    if total <= 1 {
                        viewModel.isPresentingProgressSnackbar = false
                    }
                }
                .onChange(of: model.captured) { newValue in
                    progressViewModel.current = newValue
                }
                .onChange(of: model.requestPassword) { newValue in
                    guard newValue else { return }
                    viewModel.presentBananaSplitPassword()
                }
            VStack {
                ZStack(alignment: .bottom) {
                    Rectangle()
                        .background(.regularMaterial)
                    VStack {
                        HStack(spacing: Spacing.small) {
                            CameraButton(
                                action: viewModel.dismissView,
                                icon: Image(.xmarkButton)
                            )
                            Spacer()
                            CameraButton(
                                action: { model.toggleTorch() },
                                icon: Image(.torchOff),
                                isPressed: $model.isTorchOn
                            )
                        }
                        .padding(.horizontal, Spacing.medium)
                        .padding(.top, Spacing.medium + safeAreaInsets.top)
                        Spacer()
                        ZStack {
                            RoundedRectangle(cornerRadius: CornerRadius.qrCodeScanner)
                                .aspectRatio(1.0, contentMode: .fit)
                                .blendMode(.destinationOut)
                                .overlay(
                                    Image(.cameraOverlay)
                                        .resizable(resizingMode: .stretch)
                                        .padding(-Spacing.extraExtraSmall)
                                )
                        }
                        .padding(.horizontal, Spacing.medium)
                        Spacer()
                        VStack(alignment: .center, spacing: Spacing.small) {
                            Text(viewModel.header)
                                .font(PrimaryFont.titleL.font)
                            Text(viewModel.message)
                                .font(PrimaryFont.bodyL.font)
                                .multilineTextAlignment(.center)
                        }
                        .foregroundColor(.accentForegroundText)
                        .frame(width: UIScreen.main.bounds.width * 0.86, alignment: .center)
                        Spacer()
                    }
                }
                .compositingGroup()
            }
            .onAppear {
                viewModel.use(cameraModel: model)
                progressViewModel.title = Localizable.Scanner.Label.multipart.string
                progressViewModel.cancelActionTitle = Localizable.Scanner.Action.cancel.string
                progressViewModel.cancelAction = {
                    model.reset()
                    viewModel.isPresentingProgressSnackbar = false
                }
            }
            .bottomProgressSnackbar(progressViewModel, isPresented: $viewModel.isPresentingProgressSnackbar)
        }
        .ignoresSafeArea(edges: [.top, .bottom])
        .onAppear {
            model.configure()
        }
        .onDisappear {
            model.shutdown()
        }
        .background(.backgroundPrimary)
        .fullScreenModal(
            isPresented: $viewModel.isPresentingTransactionPreview
        ) {
            TransactionPreview(
                viewModel: .init(
                    content: viewModel.transactions,
                    signature: viewModel.signature,
                    onCompletion: viewModel.onTransactionPreviewCompletion(_:)
                )
            )
        }
        .fullScreenModal(
            isPresented: $viewModel.isPresentingEnterBananaSplitPassword,
            onDismiss: {
                model.start()
                viewModel.clearTransactionState()
            }
        ) {
            EnterBananaSplitPasswordView(
                viewModel: .init(
                    seedName: viewModel.bananaSplitQRCodeRecovery?.seedName ?? "",
                    isPresented: $viewModel.isPresentingEnterBananaSplitPassword,
                    qrCodeData: model.bucket,
                    onCompletion: viewModel.onKeySetAddCompletion(_:)
                )
            )
        }
        .fullScreenModal(
            isPresented: $viewModel.isPresentingEnterPassword,
            onDismiss: {
                viewModel.onEnterPasswordDismissal()
                if !viewModel.shouldPresentError, viewModel.signature == nil {
                    // Dismissed by user
                    model.payload = nil
                    model.start()
                }
            }
        ) {
            EnterPasswordModal(
                viewModel: .init(
                    isPresented: $viewModel.isPresentingEnterPassword,
                    isErrorPresented: $viewModel.shouldPresentError,
                    dataModel: $viewModel.enterPassword,
                    signature: $viewModel.signature
                )
            )
            .clearModalBackground()
        }
        .fullScreenModal(
            isPresented: $viewModel.isPresentingError,
            onDismiss: {
                model.payload = nil
                model.start()
                viewModel.clearTransactionState()
            }
        ) {
            ErrorBottomModal(
                viewModel: viewModel.presentableError,
                isShowingBottomAlert: $viewModel.isPresentingError
            )
            .clearModalBackground()
        }
        .fullScreenModal(
            isPresented: $viewModel.isPresentingAddKeysForNetwork,
            onDismiss: viewModel.onAddKeysDismissal
        ) {
            AddKeysForNetworkModal(
                viewModel: .init(
                    networkName: viewModel.networkName,
                    isPresented: $viewModel.isPresentingAddKeysForNetwork,
                    onCompletion: viewModel.onAddKeysCompletion(_:)
                )
            )
            .clearModalBackground()
        }
        .fullScreenModal(
            isPresented: $viewModel.isPresentingKeySetSelection
        ) {
            SelectKeySetsForNetworkKeyView(
                viewModel: selectKeySetsForNetworkViewModel()
            )
            .clearModalBackground()
        }
        .fullScreenModal(
            isPresented: $viewModel.isPresentingAddDerivedKeys
        ) {
            AddDerivedKeysView(
                viewModel: addDerivedKeysView()
            )
            .clearModalBackground()
        }
        .fullScreenModal(
            isPresented: $viewModel.isPresentingPenumbraTransaction,
            onDismiss: {
                model.payload = nil
                model.start()
                viewModel.clearTransactionState()
            }
        ) {
            if let hex = viewModel.penumbraQrHex {
                PenumbraTransactionView(
                    viewModel: .init(
                        qrHex: hex,
                        onCompletion: {
                            viewModel.isPresentingPenumbraTransaction = false
                            viewModel.penumbraQrHex = nil
                            model.payload = nil
                            model.start()
                            viewModel.clearTransactionState()
                        }
                    )
                )
            }
        }
        .fullScreenModal(
            isPresented: $viewModel.isPresentingZcashNoteSync,
            onDismiss: {
                model.payload = nil
                model.start()
                viewModel.clearTransactionState()
            }
        ) {
            if let urFrames = viewModel.zcashNotesUrFrames {
                ZcashNoteSyncView(
                    viewModel: .init(
                        urFrames: urFrames,
                        onCompletion: {
                            viewModel.isPresentingZcashNoteSync = false
                            viewModel.zcashNotesUrFrames = nil
                            model.payload = nil
                            model.start()
                            viewModel.clearTransactionState()
                        }
                    )
                )
            }
        }
        .fullScreenModal(
            isPresented: $viewModel.isPresentingZcashPczt,
            onDismiss: {
                model.payload = nil
                model.start()
                viewModel.clearTransactionState()
            }
        ) {
            if let urFrames = viewModel.zcashPcztUrFrames {
                ZcashPcztView(
                    viewModel: .init(
                        urParts: urFrames,
                        onCompletion: {
                            viewModel.isPresentingZcashPczt = false
                            viewModel.zcashPcztUrFrames = nil
                            model.payload = nil
                            model.start()
                            viewModel.clearTransactionState()
                        }
                    )
                )
            }
        }
        .fullScreenModal(
            isPresented: $viewModel.isPresentingAuth,
            onDismiss: {
                model.payload = nil
                model.start()
                viewModel.clearTransactionState()
            }
        ) {
            if let jsonString = viewModel.authJsonPayload,
               let data = jsonString.data(using: .utf8),
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any] {
                let domain = json["domain"] as? String ?? ""
                let nonce = json["nonce"] as? String ?? ""
                let ts = json["ts"] as? UInt64 ?? 0
                let seedPhrase = viewModel.getFirstSeedPhrase()
                if !seedPhrase.isEmpty {
                    AuthChallengeView(
                        viewModel: .init(
                            domain: domain,
                            nonce: nonce,
                            timestamp: ts,
                            seedPhrase: seedPhrase,
                            onCompletion: {
                                viewModel.isPresentingAuth = false
                                viewModel.authJsonPayload = nil
                                model.payload = nil
                                model.start()
                                viewModel.clearTransactionState()
                            }
                        )
                    )
                }
            }
        }
        .fullScreenModal(
            isPresented: $viewModel.isPresentingFrost,
            onDismiss: {
                model.payload = nil
                model.start()
                viewModel.clearTransactionState()
            }
        ) {
            if let jsonString = viewModel.frostJsonPayload,
               let data = jsonString.data(using: .utf8),
               let json = try? JSONSerialization.jsonObject(with: data) as? [String: Any],
               let frostType = json["frost"] as? String {
                let dismissFrost = {
                    viewModel.isPresentingFrost = false
                    viewModel.frostJsonPayload = nil
                    model.payload = nil
                    model.start()
                    viewModel.clearTransactionState()
                }
                let scanNext = {
                    // Dismiss screen, back to camera for next round
                    viewModel.isPresentingFrost = false
                    viewModel.frostJsonPayload = nil
                    model.payload = nil
                    model.start()
                }
                switch frostType {
                case "dkg1":
                    let _ = {
                        viewModel.frostDkgMaxSigners = UInt16(json["n"] as? Int ?? 3)
                        viewModel.frostDkgMinSigners = UInt16(json["t"] as? Int ?? 2)
                        viewModel.frostDkgLabel = json["label"] as? String ?? ""
                        viewModel.frostDkgMainnet = json["mainnet"] as? Bool ?? true
                    }()
                    FrostDkgView(viewModel: .init(
                        round: 1, maxSigners: viewModel.frostDkgMaxSigners,
                        minSigners: viewModel.frostDkgMinSigners,
                        label: viewModel.frostDkgLabel, mainnet: viewModel.frostDkgMainnet,
                        onSecretUpdated: { viewModel.frostDkgSecret = $0 },
                        onScanNext: scanNext, onCompletion: { dismissFrost(); viewModel.frostDkgSecret = "" }
                    ))
                case "dkg2":
                    FrostDkgView(viewModel: .init(
                        round: 2, maxSigners: viewModel.frostDkgMaxSigners,
                        minSigners: viewModel.frostDkgMinSigners,
                        label: viewModel.frostDkgLabel, mainnet: viewModel.frostDkgMainnet,
                        previousSecret: viewModel.frostDkgSecret,
                        broadcastsJson: jsonArrayString(json, key: "broadcasts"),
                        onSecretUpdated: { viewModel.frostDkgSecret = $0 },
                        onScanNext: scanNext, onCompletion: { dismissFrost(); viewModel.frostDkgSecret = "" }
                    ))
                case "dkg3":
                    FrostDkgView(viewModel: .init(
                        round: 3, maxSigners: viewModel.frostDkgMaxSigners,
                        minSigners: viewModel.frostDkgMinSigners,
                        label: viewModel.frostDkgLabel, mainnet: viewModel.frostDkgMainnet,
                        previousSecret: viewModel.frostDkgSecret,
                        round1Json: jsonArrayString(json, key: "round1"),
                        round2Json: jsonArrayString(json, key: "round2"),
                        onSecretUpdated: { viewModel.frostDkgSecret = $0 },
                        onScanNext: scanNext, onCompletion: { dismissFrost(); viewModel.frostDkgSecret = "" }
                    ))
                case "sign1":
                    FrostSignView(viewModel: .init(
                        round: 1, walletId: json["wallet"] as? String ?? "",
                        onStateUpdated: { n, k in viewModel.frostSignNonces = n; viewModel.frostSignKeyPackage = k },
                        onScanNext: scanNext, onCompletion: { dismissFrost(); viewModel.frostSignNonces = ""; viewModel.frostSignKeyPackage = "" }
                    ))
                case "sign2":
                    FrostSignView(viewModel: .init(
                        round: 2,
                        sighashHex: json["sighash"] as? String ?? "",
                        alphasJson: jsonArrayString(json, key: "alphas"),
                        commitmentsJson: jsonArrayString(json, key: "commitments"),
                        previousNonces: viewModel.frostSignNonces,
                        previousKeyPackage: viewModel.frostSignKeyPackage,
                        onStateUpdated: { n, k in viewModel.frostSignNonces = n; viewModel.frostSignKeyPackage = k },
                        onScanNext: scanNext, onCompletion: { dismissFrost(); viewModel.frostSignNonces = ""; viewModel.frostSignKeyPackage = "" }
                    ))
                default:
                    EmptyView()
                }
            }
        }
        .bottomSnackbar(
            viewModel.snackbarViewModel,
            isPresented: $viewModel.isSnackbarPresented
        )
    }

    func selectKeySetsForNetworkViewModel() -> SelectKeySetsForNetworkKeyView.ViewModel {
        .init(
            networkName: viewModel.networkName,
            isPresented: $viewModel.isPresentingKeySetSelection,
            onCompletion: viewModel.onSelectKeySetsForNetworkCompletion(_:)
        )
    }

    func addDerivedKeysView() -> AddDerivedKeysView.ViewModel {
        .init(
            dataModel: .init(viewModel.dynamicDerivationsPreview),
            dynamicDerivationsPreview: viewModel.dynamicDerivationsPreview,
            isPresented: $viewModel.isPresentingAddDerivedKeys,
            onCompletion: viewModel.onAddDerivedKeyCompletion(_:)
        )
    }
}

extension CameraView {
    final class ViewModel: ObservableObject {
        // Overlay presentation
        @Published var isPresentingProgressSnackbar: Bool = false
        @Published var header: String = Localizable.Scanner.Label.Scan.Main.header.string
        @Published var message: String = Localizable.Scanner.Label.Scan.Main.message.string

        // Modal presentation
        @Published var isPresentingTransactionPreview: Bool = false
        @Published var isPresentingEnterPassword: Bool = false
        @Published var shouldPresentError: Bool = false
        @Published var isPresentingError: Bool = false
        @Published var isInTransactionProgress: Bool = false

        // Penumbra / Zcash transaction modals
        @Published var isPresentingPenumbraTransaction: Bool = false
        @Published var isPresentingZcashNoteSync: Bool = false
        @Published var isPresentingFrost: Bool = false
        @Published var isPresentingZcashPczt: Bool = false
        @Published var isPresentingAuth: Bool = false
        var penumbraQrHex: String?
        var zcashNotesUrFrames: [String]?
        var zcashPcztUrFrames: [String]?
        var frostJsonPayload: String?
        // FROST DKG state persisted across rounds
        var frostDkgSecret: String = ""
        var frostDkgMaxSigners: UInt16 = 0
        var frostDkgMinSigners: UInt16 = 0
        var frostDkgLabel: String = ""
        var frostDkgMainnet: Bool = true
        // FROST sign state persisted across rounds
        var frostSignNonces: String = ""
        var frostSignKeyPackage: String = ""
        var authJsonPayload: String?

        // Banana split flow
        @Published var isPresentingEnterBananaSplitPassword: Bool = false

        // Create Keys for network
        @Published var isPresentingAddKeysForNetwork: Bool = false
        @Published var shouldPresentKeySetSelection: Bool = false
        @Published var isPresentingKeySetSelection: Bool = false
        var networkName: String!

        // Dynamic Derived Keys
        @Published var isPresentingAddDerivedKeys: Bool = false

        // Data models for modals
        @Published var transactions: [MTransaction] = []
        @Published var signature: MSignatureReady?
        @Published var enterPassword: MEnterPassword!
        @Published var dynamicDerivationsPreview: DdPreview!
        @Published var presentableError: ErrorBottomModalViewModel = .signingForgotPassword()
        var snackbarViewModel: SnackbarViewModel = .init(title: "")
        @Published var isSnackbarPresented: Bool = false

        @Binding var isPresented: Bool
        private let scanService: ScanTabService
        private let dynamicDerivationsService: DynamicDerivationsService
        private let seedsMediator: SeedsMediating
        private let runtimePropertiesProvider: RuntimePropertiesProviding
        let bananaSplitQRCodeRecovery: BananaSplitQRCodeRecovery?
        private weak var cameraModel: CameraService?

        init(
            isPresented: Binding<Bool>,
            bananaSplitQRCodeRecovery: BananaSplitQRCodeRecovery? = nil,
            seedsMediator: SeedsMediating = ServiceLocator.seedsMediator,
            scanService: ScanTabService = ScanTabService(),
            dynamicDerivationsService: DynamicDerivationsService = DynamicDerivationsService(),
            runtimePropertiesProvider: RuntimePropertiesProviding = RuntimePropertiesProvider()
        ) {
            _isPresented = isPresented
            self.bananaSplitQRCodeRecovery = bananaSplitQRCodeRecovery
            self.seedsMediator = seedsMediator
            self.scanService = scanService
            self.dynamicDerivationsService = dynamicDerivationsService
            self.runtimePropertiesProvider = runtimePropertiesProvider
        }

        func use(cameraModel: CameraService) {
            self.cameraModel = cameraModel
        }

        func didUpdatePayload(_ payload: DecodedPayload) {
            guard !isInTransactionProgress else { return }
            isInTransactionProgress = true
            switch payload {
            case let .penumbraTransaction(hexData):
                penumbraQrHex = hexData
                isPresentingPenumbraTransaction = true
            case .cosmosSignRequest:
                // No Cosmos signing screen on iOS yet. Refuse explicitly so a
                // 530510 request is never handed to the Substrate parser.
                presentableError = .featureNotAvailable()
                isPresentingError = true
            case let .zcashNotes(urFrames):
                zcashNotesUrFrames = urFrames
                isPresentingZcashNoteSync = true
            case let .frost(jsonString):
                frostJsonPayload = jsonString
                isPresentingFrost = true
            case let .zcashPczt(urFrames):
                zcashPcztUrFrames = urFrames
                isPresentingZcashPczt = true
            case let .auth(jsonString):
                authJsonPayload = jsonString
                isPresentingAuth = true
            case let .dynamicDerivations(data):
                guard runtimePropertiesProvider.dynamicDerivationsEnabled else {
                    presentableError = .featureNotAvailable()
                    isPresentingError = true
                    return
                }
                startDynamicDerivationsFlow(data)
            case let .dynamicDerivationsTransaction(data):
                guard runtimePropertiesProvider.dynamicDerivationsEnabled else {
                    presentableError = .featureNotAvailable()
                    isPresentingError = true
                    return
                }
                startDynamicDerivationsTransactionFlow(data)
            case let .transaction(data):
                startTransactionSigningFlow(data)
            }
        }

        func continueWithSignature() {
            isPresentingTransactionPreview = true
        }

        func clearTransactionState() {
            transactions = []
            signature = nil
            enterPassword = nil
            isPresentingError = false
            shouldPresentError = false
            isInTransactionProgress = false
        }

        func presentBananaSplitPassword() {
            isPresentingProgressSnackbar = false
            isPresentingEnterBananaSplitPassword = true
        }

        func dismissView() {
            isPresented = false
        }

        func onTransactionPreviewCompletion(_ completionAction: TransactionPreview.OnCompletionAction) {
            isPresentingTransactionPreview = false
            switch completionAction {
            case .onImportKeysFailure:
                resumeCamera()
                snackbarViewModel = .init(
                    title: Localizable.ImportKeys.Snackbar.Failure.unknown.string,
                    style: .warning
                )
            case let .onNetworkAdded(network):
                snackbarViewModel = .init(
                    title: Localizable.TransactionSign.Snackbar.networkAdded(network),
                    style: .info
                )
                isSnackbarPresented = true
                networkName = network
                DispatchQueue.main.asyncAfter(deadline: .now() + 1) {
                    self.isPresentingAddKeysForNetwork = true
                }
            case let .onNetworkMetadataAdded(network, metadataVersion):
                resumeCamera()
                snackbarViewModel = .init(
                    title: Localizable.TransactionSign.Snackbar.metadata(network, metadataVersion),
                    style: .info
                )
                isSnackbarPresented = true
            case let .onDerivedKeysImport(derivedKeysCount):
                resumeCamera()
                if derivedKeysCount == 1 {
                    snackbarViewModel = .init(title: Localizable.ImportKeys.Snackbar.Success.single.string)
                } else {
                    snackbarViewModel = .init(
                        title: Localizable.ImportKeys.Snackbar.Success
                            .multiple(derivedKeysCount)
                    )
                }
                isSnackbarPresented = true
            case .onDone:
                resumeCamera()
            case .onDismissal:
                resumeCamera()
            }
        }

        func onKeySetAddCompletion(_ completionAction: CreateKeysForNetworksView.OnCompletionAction) {
            if let onRecoveryComplete = bananaSplitQRCodeRecovery?.onRecoveryComplete {
                DispatchQueue.main.asyncAfter(deadline: .now() + 0.3) {
                    onRecoveryComplete(completionAction)
                    self.dismissView()
                }
                return
            }
            let message: String =
                switch completionAction {
                case let .createKeySet(seedName):
                    Localizable.CreateKeysForNetwork.Snackbar.keySetCreated(seedName)
                case let .recoveredKeySet(seedName):
                    Localizable.CreateKeysForNetwork.Snackbar.keySetRecovered(seedName)
                }
            snackbarViewModel = .init(
                title: message,
                style: .info
            )
            isSnackbarPresented = true
            resumeCamera()
        }

        func onEnterPasswordDismissal() {
            // User forgot password
            if shouldPresentError {
                presentableError = .signingForgotPassword()
                // iOS 15 handling of following .fullscreen presentation after dismissal, we need to dispatch this
                // async
                DispatchQueue.main.async { self.isPresentingError = true }
                return
            }
            // User entered valid password, signature is ready
            if signature != nil {
                // iOS 15 handling of following .fullscreen presentation after dismissal, we need to dispatch this
                // async
                DispatchQueue.main.async { self.continueWithSignature() }
                return
            }
            // Dismissed by user
            clearTransactionState()
            enterPassword = nil
        }

        func onAddKeysCompletion(_ onCompletion: AddKeysForNetworkModal.OnCompletionAction) {
            switch onCompletion {
            case .cancel:
                shouldPresentKeySetSelection = false
                networkName = nil
                resumeCamera()
            case .create:
                shouldPresentKeySetSelection = true
            }
        }

        func onAddKeysDismissal() {
            if shouldPresentKeySetSelection {
                isPresentingKeySetSelection = true
            } else {
                resumeCamera()
            }
        }

        func onSelectKeySetsForNetworkCompletion(_ onComplete: SelectKeySetsForNetworkKeyView.OnCompletionAction) {
            switch onComplete {
            case .onDerivedKeysCreated:
                snackbarViewModel = .init(
                    title: Localizable.SelectKeySetsForNetworkKey.Snackbar.keysCreated.string,
                    style: .info
                )
                isSnackbarPresented = true
                networkName = nil
            case .onCancel:
                ()
            }
            resumeCamera()
        }

        func getFirstSeedPhrase() -> String {
            guard let name = seedsMediator.seedNames.first else { return "" }
            return seedsMediator.getSeed(seedName: name)
        }

        private func resumeCamera() {
            cameraModel?.start()
            clearTransactionState()
        }

        func onAddDerivedKeyCompletion(_ onComplete: AddDerivedKeysView.OnCompletionAction) {
            switch onComplete {
            case .onCancel:
                ()
            case .onDone:
                ()
            }
            resumeCamera()
        }
    }
}

// MARK: - Transaction Signature

private extension CameraView.ViewModel {
    func startTransactionSigningFlow(_ payload: String) {
        switch scanService.performTransaction(with: payload) {
        case let .success(actionResult):
            // Handle transactions with just error payload
            guard case let .transaction(transactions) = actionResult.screenData else { return }
            if transactions.allSatisfy(\.isDisplayingErrorOnly) {
                presentableError = .transactionSigningError(
                    message: transactions
                        .reduce("") { $0 + $1.transactionIssues() + ($1 == transactions.last ? "\n" : "") }
                )
                isPresentingError = true
                scanService.resetNavigationState()
                return
            }
            // Handle rest of transactions with optional error payload
            // Type is assumed based on first error
            let firstTransaction = transactions.first
            switch firstTransaction?.ttype {
            case .sign:
                continueTransactionSignature(transactions)
            case .importDerivations:
                continueImportDerivedKeys(transactions)
            default:
                // Transaction with error
                // Transaction that does not require signing (i.e. adding network or metadata)
                self.transactions = transactions
                isPresentingTransactionPreview = true
            }
        case let .failure(error):
            presentableError = ErrorBottomModalViewModel.transactionError(for: error)
            isPresentingError = true
        }
    }

    func continueTransactionSignature(_ transactions: [MTransaction]) {
        let actionResult = sign(transactions: transactions)
        self.transactions = transactions
        // Password protected key, continue to modal
        if case let .enterPassword(value) = actionResult?.modalData {
            enterPassword = value
            isPresentingEnterPassword = true
        }
        // Transaction ready to sign
        if case let .signatureReady(value) = actionResult?.modalData {
            signature = value
            continueWithSignature()
        }
    }
}

private extension CameraView.ViewModel {
    func startDynamicDerivationsTransactionFlow(
        _ payload: [String]
    ) {
        let seedPhrases = seedsMediator.getAllSeeds()
        dynamicDerivationsService.signDynamicDerivationsTransaction(for: seedPhrases, payload: payload) { result in
            switch result {
            case let .success(signedTransaction):
                if signedTransaction.transaction.allSatisfy(\.isDisplayingErrorOnly) {
                    self.presentableError = .transactionSigningError(
                        message: signedTransaction.transaction
                            .reduce("") {
                                $0 + $1.transactionIssues() + ($1 == signedTransaction.transaction.last ? "\n" : "")
                            }
                    )
                    self.isPresentingError = true
                    self.scanService.resetNavigationState()
                    return
                }
                self.transactions = signedTransaction.transaction
                self.signature = signedTransaction.signature
                self.isPresentingTransactionPreview = true
            case let .failure(error):
                self.presentableError = .transactionError(for: error)
                self.isPresentingError = true
            }
        }
    }
}

// MARK: - Import Derived Keys

extension CameraView.ViewModel {
    func continueImportDerivedKeys(_ transactions: [MTransaction]) {
        scanService.resetNavigationState()
        if let importError = transactions.dominantImportError {
            switch importError {
            case .networkMissing:
                presentableError = .importDerivedKeysMissingNetwork()
            case .keySetMissing:
                presentableError = .importDerivedKeysMissingKeySet()
            case .badFormat:
                presentableError = .importDerivedKeysBadFormat()
            }
            isPresentingError = true
        } else if !transactions.hasImportableKeys {
            presentableError = .allKeysAlreadyExist()
            isPresentingError = true
        } else {
            self.transactions = transactions
            isPresentingTransactionPreview = true
        }
    }
}

// MARK: - Dynamic Derivations

private extension CameraView.ViewModel {
    func startDynamicDerivationsFlow(_ payload: String) {
        let seedPhrases = seedsMediator.getAllSeeds()
        dynamicDerivationsService.getDynamicDerivationsPreview(for: seedPhrases, payload: payload) { result in
            switch result {
            case let .success(preview):
                self.dynamicDerivationsPreview = preview
                self.isPresentingAddDerivedKeys = true
                ()
            case let .failure(error):
                self.presentableError = .alertError(message: error.localizedDescription)
                self.isPresentingError = true
            }
        }
    }
}

private extension CameraView.ViewModel {
    func sign(transactions: [MTransaction]) -> ActionResult? {
        let seedNames = transactions.compactMap { $0.authorInfo?.address.seedName }
        let seedPhrasesDictionary = seedsMediator.getSeeds(seedNames: Set(seedNames))
        return scanService.continueTransactionSigning(seedNames, seedPhrasesDictionary)
    }
}

/// Re-encodes a `[String]` field from a decoded QR JSON payload back into a
/// JSON array string for the uniffi FROST entry points.
private func jsonArrayString(_ json: [String: Any], key: String) -> String {
    guard let items = json[key] as? [String],
          let data = try? JSONSerialization.data(withJSONObject: items)
    else { return "[]" }
    return String(data: data, encoding: .utf8) ?? "[]"
}
