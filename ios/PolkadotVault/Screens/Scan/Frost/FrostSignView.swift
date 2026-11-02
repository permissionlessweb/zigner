//
//  FrostSignView.swift
//  Zigner
//
//  FROST signing — per-round handler. Nonces persist via CameraView.ViewModel.

import SwiftUI

struct FrostSignView: View {
    @StateObject var viewModel: ViewModel

    var body: some View {
        VStack(spacing: 0) {
            NavigationBarView(
                viewModel: .init(
                    title: .title(viewModel.round == 1 ? "FROST Sign — Commitments" : "FROST Sign — Shares"),
                    leftButtons: [.init(type: .xmark, action: viewModel.onDismiss)],
                    rightButtons: [.init(type: .empty)],
                    backgroundColor: .backgroundPrimary
                )
            )
            switch viewModel.state {
            case .loading:
                VStack {
                    Spacer()
                    ProgressView()
                    Text(viewModel.round == 1 ? "Generating commitments..." : "Signing...")
                        .font(PrimaryFont.titleS.font)
                        .foregroundColor(.textAndIconsPrimary)
                        .padding(.top, Spacing.medium)
                    Spacer()
                }
            case let .displayQr(data):
                displayView(qrData: data)
            case let .error(message):
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
                    ActionButton(action: viewModel.onDismiss, text: "Dismiss", style: .secondary())
                        .padding(.horizontal, Spacing.large)
                        .padding(.bottom, Spacing.large)
                }
            }
        }
        .background(.backgroundPrimary)
    }

    private func displayView(qrData: String) -> some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(spacing: Spacing.medium) {
                    Text("Show this to the coordinator")
                        .font(PrimaryFont.labelM.font)
                        .foregroundColor(.textAndIconsTertiary)
                    if let qrImage = viewModel.encodeQR(qrData) {
                        AnimatedQRCodeView(
                            viewModel: Binding<AnimatedQRCodeViewModel>.constant(.init(qrCodes: [qrImage]))
                        )
                        .padding(Spacing.stroke)
                        .containerBackground()
                    }
                    if viewModel.round == 1 {
                        Text("After coordinator collects all commitments, scan the sign request QR")
                            .font(PrimaryFont.captionM.font)
                            .foregroundColor(.textAndIconsTertiary)
                            .multilineTextAlignment(.center)
                    }
                }
                .padding(.horizontal, Spacing.medium)
                .padding(.top, Spacing.medium)
            }
            Divider()
            VStack(spacing: Spacing.small) {
                if viewModel.round == 1 {
                    ActionButton(action: viewModel.onScanNext, text: "Scan Sign Request", style: .primary())
                } else {
                    ActionButton(action: viewModel.onDismiss, text: "Done", style: .primary())
                }
            }
            .padding(.horizontal, Spacing.large)
            .padding(.vertical, Spacing.medium)
        }
    }
}

extension FrostSignView {
    enum State {
        case loading
        case displayQr(String)
        case error(String)
    }

    final class ViewModel: ObservableObject {
        @Published var state: State = .loading
        let round: Int
        private let walletId: String
        private let sighashHex: String
        private let alphasJson: String
        private let commitmentsJson: String
        private let previousNonces: String
        private let previousKeyPackage: String
        private let onStateUpdated: (String, String) -> Void  // (nonces, keyPackage)
        private let onCompletion: () -> Void
        private let onScanNextRound: () -> Void

        init(
            round: Int,
            walletId: String = "",
            sighashHex: String = "",
            alphasJson: String = "[]",
            commitmentsJson: String = "[]",
            previousNonces: String = "",
            previousKeyPackage: String = "",
            onStateUpdated: @escaping (String, String) -> Void = { _, _ in },
            onScanNext: @escaping () -> Void = {},
            onCompletion: @escaping () -> Void
        ) {
            self.round = round
            self.walletId = walletId
            self.sighashHex = sighashHex
            self.alphasJson = alphasJson
            self.commitmentsJson = commitmentsJson
            self.previousNonces = previousNonces
            self.previousKeyPackage = previousKeyPackage
            self.onStateUpdated = onStateUpdated
            self.onScanNextRound = onScanNext
            self.onCompletion = onCompletion
            compute()
        }

        private func compute() {
            DispatchQueue.global(qos: .userInitiated).async { [weak self] in
                guard let self else { return }
                do {
                    switch self.round {
                    case 1:
                        // Signing needs the sealed share, opened with the seed;
                        // not ported to iOS yet (round 2 is refused as well).
                        DispatchQueue.main.async {
                            self.state = .error("FROST signing is not available on iOS yet.")
                        }
                    case 2:
                        // Round 2 signed every action with one nonce, which
                        // leaks this device's share. Refused until iOS takes
                        // one nonce per action and checks the PCZT on-device,
                        // as Android does.
                        DispatchQueue.main.async {
                            self.onStateUpdated("", "")
                            self.state = .error("FROST signing is not available on iOS yet.")
                        }
                    default:
                        DispatchQueue.main.async { self.state = .error("Invalid round") }
                    }
                } catch {
                    DispatchQueue.main.async { self.state = .error(error.localizedDescription) }
                }
            }
        }

        func onScanNext() { onScanNextRound() }
        func onDismiss() { onCompletion() }
        func encodeQR(_ text: String) -> [UInt8]? {
            let bytes = Array(text.utf8).map { UInt8($0) }
            return try? encodeToQr(payload: bytes, isDanger: false)
        }
    }
}
