//
//  PenumbraTransactionView.swift
//  Zigner
//

import SwiftUI

struct PenumbraTransactionView: View {
    @StateObject var viewModel: ViewModel
    @Environment(\.presentationMode) var presentationMode

    var body: some View {
        VStack(spacing: 0) {
            NavigationBarView(
                viewModel: .init(
                    title: .title("Penumbra Transaction"),
                    leftButtons: [.init(type: .xmark, action: viewModel.onDecline)],
                    rightButtons: [.init(type: .empty)],
                    backgroundColor: .backgroundPrimary
                )
            )
            switch viewModel.state {
            case .seedSelection:
                seedSelectionView
            case .review:
                reviewView
            case .signing:
                signingView
            case let .signature(bytes):
                PenumbraSignatureQrView(
                    signatureBytes: bytes,
                    onDone: viewModel.onDone
                )
            case let .error(message):
                errorView(message: message)
            }
        }
        .background(.backgroundPrimary)
    }

    private var seedSelectionView: some View {
        VStack(alignment: .leading, spacing: 0) {
            Text("Select a key set to sign with:")
                .font(PrimaryFont.bodyL.font)
                .foregroundColor(.textAndIconsSecondary)
                .padding(.horizontal, Spacing.large)
                .padding(.vertical, Spacing.medium)
            ScrollView {
                LazyVStack(spacing: 0) {
                    ForEach(viewModel.seedNames, id: \.self) { seedName in
                        Button(action: { viewModel.selectSeed(seedName: seedName) }) {
                            HStack {
                                Text(seedName)
                                    .font(PrimaryFont.titleS.font)
                                    .foregroundColor(.textAndIconsPrimary)
                                Spacer()
                                Image(.chevronRight)
                                    .foregroundColor(.textAndIconsTertiary)
                            }
                            .padding(.horizontal, Spacing.large)
                            .padding(.vertical, Spacing.medium)
                        }
                    }
                }
            }
        }
    }

    private var reviewView: some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.small) {
                    infoCard(label: "Chain", value: viewModel.request?.chainId ?? "Unknown")
                    infoCard(
                        label: "Effect Hash",
                        value: {
                            let hash = viewModel.request?.effectHashHex ?? ""
                            if hash.count > 16 {
                                return String(hash.prefix(8)) + "..." + String(hash.suffix(8))
                            }
                            return hash.isEmpty ? "N/A" : hash
                        }()
                    )

                    actionsCard
                }
                .padding(.horizontal, Spacing.medium)
                .padding(.top, Spacing.medium)
            }

            Divider()
            VStack(spacing: Spacing.small) {
                ActionButton(
                    action: viewModel.onApprove,
                    text: "Approve",
                    style: .primary()
                )
                ActionButton(
                    action: viewModel.onDecline,
                    text: "Decline",
                    style: .emptyPrimary()
                )
            }
            .padding(.horizontal, Spacing.large)
            .padding(.vertical, Spacing.medium)
        }
    }

    private var actionsCard: some View {
        VStack(alignment: .leading, spacing: Spacing.small) {
            Text("Actions")
                .font(PrimaryFont.titleS.font)
                .foregroundColor(.textAndIconsPrimary)

            if let request = viewModel.request {
                if request.spendCount > 0 {
                    Text("\(request.spendCount) Spend\(request.spendCount > 1 ? "s" : "")")
                        .font(PrimaryFont.bodyL.font)
                        .foregroundColor(.textAndIconsSecondary)
                }
                if request.voteCount > 0 {
                    Text("\(request.voteCount) Delegator Vote\(request.voteCount > 1 ? "s" : "")")
                        .font(PrimaryFont.bodyL.font)
                        .foregroundColor(.textAndIconsSecondary)
                }
                if request.lqtVoteCount > 0 {
                    Text("\(request.lqtVoteCount) LQT Vote\(request.lqtVoteCount > 1 ? "s" : "")")
                        .font(PrimaryFont.bodyL.font)
                        .foregroundColor(.textAndIconsSecondary)
                }
                let total = request.spendCount + request.voteCount + request.lqtVoteCount
                Text("\(total) signature\(total > 1 ? "s" : "") required")
                    .font(PrimaryFont.captionM.font)
                    .foregroundColor(.textAndIconsTertiary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.medium)
        .containerBackground()
    }

    private var signingView: some View {
        VStack {
            Spacer()
            ProgressView()
            Text("Signing...")
                .font(PrimaryFont.titleS.font)
                .foregroundColor(.textAndIconsPrimary)
                .padding(.top, Spacing.medium)
            Spacer()
        }
    }

    private func errorView(message: String) -> some View {
        VStack {
            Spacer()
            Text("Signing Failed")
                .font(PrimaryFont.titleS.font)
                .foregroundColor(.accentRed300)
            Text(message)
                .font(PrimaryFont.bodyL.font)
                .foregroundColor(.textAndIconsSecondary)
                .multilineTextAlignment(.center)
                .padding(.horizontal, Spacing.large)
            Spacer()
            ActionButton(
                action: viewModel.onDecline,
                text: "Dismiss",
                style: .secondary()
            )
            .padding(.horizontal, Spacing.large)
            .padding(.bottom, Spacing.large)
        }
    }

    private func infoCard(label: String, value: String) -> some View {
        VStack(alignment: .leading, spacing: Spacing.extraSmall) {
            Text(label)
                .font(PrimaryFont.labelM.font)
                .foregroundColor(.textAndIconsTertiary)
            Text(value)
                .font(PrimaryFont.bodyL.font)
                .foregroundColor(.textAndIconsPrimary)
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .padding(Spacing.small)
        .containerBackground()
    }
}

extension PenumbraTransactionView {
    enum State {
        case seedSelection
        case review
        case signing
        case signature([UInt8])
        case error(String)
    }

    final class ViewModel: ObservableObject {
        @Published var state: State = .review
        @Published var seedNames: [String] = []
        var request: PenumbraSignRequest?
        private let seedsMediator: SeedsMediating
        private let onCompletion: () -> Void
        private var selectedSeedPhrase: String?
        private var selectedSeedName: String = ""

        init(
            qrHex: String,
            seedsMediator: SeedsMediating = ServiceLocator.seedsMediator,
            onCompletion: @escaping () -> Void
        ) {
            self.seedsMediator = seedsMediator
            self.onCompletion = onCompletion
            self.seedNames = seedsMediator.seedNames
            do {
                self.request = try parsePenumbraSignRequest(qrHex: qrHex)
            } catch {
                self.state = .error(error.localizedDescription)
                return
            }
            if seedNames.count > 1 {
                state = .seedSelection
            } else if let name = seedNames.first {
                let phrase = seedsMediator.getSeed(seedName: name)
                if phrase.isEmpty {
                    state = .error("No seed phrase available")
                } else {
                    selectedSeedPhrase = phrase
                    selectedSeedName = name
                    state = .review
                }
            } else {
                state = .error("No seed phrase available")
            }
        }

        func selectSeed(seedName: String) {
            let phrase = seedsMediator.getSeed(seedName: seedName)
            guard !phrase.isEmpty else {
                state = .error("Failed to retrieve seed phrase")
                return
            }
            selectedSeedPhrase = phrase
            selectedSeedName = seedName
            state = .review
        }

        func onApprove() {
            guard let request, let seedPhrase = selectedSeedPhrase else { return }
            state = .signing
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                do {
                    // search accounts 0..7 of the chosen seed for the key
                    // whose FVK reproduces the effect hash
                    let result = try signPenumbraTransactionAny(
                        seedNames: [self?.selectedSeedName ?? ""],
                        seedPhrases: [seedPhrase],
                        request: request,
                        maxAccounts: 0
                    )
                    let signatureBytes = result.signature
                    DispatchQueue.main.async {
                        self?.state = .signature(signatureBytes)
                    }
                } catch {
                    DispatchQueue.main.async {
                        self?.state = .error(error.localizedDescription)
                    }
                }
            }
        }

        func onDecline() {
            onCompletion()
        }

        func onDone() {
            onCompletion()
        }
    }
}
