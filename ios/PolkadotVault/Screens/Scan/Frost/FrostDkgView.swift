//
//  FrostDkgView.swift
//  Zigner
//
//  FROST DKG — handles one round at a time. Secrets persist across rounds
//  via the CameraView.ViewModel's frostDkgSecret property.

import SwiftUI

struct FrostDkgView: View {
    @StateObject var viewModel: ViewModel

    var body: some View {
        VStack(spacing: 0) {
            NavigationBarView(
                viewModel: .init(
                    title: .title("FROST DKG — Round \(viewModel.round) of 3"),
                    leftButtons: [.init(type: .xmark, action: viewModel.onDismiss)],
                    rightButtons: [.init(type: .empty)],
                    backgroundColor: .backgroundPrimary
                )
            )
            switch viewModel.state {
            case .computing:
                VStack {
                    Spacer()
                    ProgressView()
                    Text("Computing round \(viewModel.round)...")
                        .font(PrimaryFont.titleS.font)
                        .foregroundColor(.textAndIconsPrimary)
                        .padding(.top, Spacing.medium)
                    Spacer()
                }
            case let .displayQr(data):
                displayView(qrData: data)
            case let .complete(walletId):
                completeView(walletId: walletId)
            case let .error(message):
                VStack {
                    Spacer()
                    Text("DKG Failed")
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
                    if viewModel.round < 3 {
                        Text("After the coordinator processes this, scan the round \(viewModel.round + 1) QR")
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
                if viewModel.round < 3 {
                    ActionButton(action: viewModel.onScanNext, text: "Scan Round \(viewModel.round + 1)", style: .primary())
                } else {
                    ActionButton(action: viewModel.onDismiss, text: "Done", style: .primary())
                }
            }
            .padding(.horizontal, Spacing.large)
            .padding(.vertical, Spacing.medium)
        }
    }

    private func completeView(walletId: String) -> some View {
        VStack(spacing: 0) {
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.small) {
                    Text("DKG Complete").font(PrimaryFont.titleS.font).foregroundColor(.textAndIconsPrimary)
                    Text("Key share stored securely").font(PrimaryFont.bodyL.font).foregroundColor(.textAndIconsSecondary)
                    Text("Wallet: \(walletId)").font(PrimaryFont.captionM.font).foregroundColor(.textAndIconsTertiary)
                }
                .frame(maxWidth: .infinity, alignment: .leading)
                .padding(Spacing.medium)
                .containerBackground()
                .padding(.horizontal, Spacing.medium)
                .padding(.top, Spacing.medium)
            }
            Divider()
            ActionButton(action: viewModel.onDismiss, text: "Done", style: .primary())
                .padding(.horizontal, Spacing.large)
                .padding(.vertical, Spacing.medium)
        }
    }
}

extension FrostDkgView {
    enum State {
        case computing
        case displayQr(String)
        case complete(String)
        case error(String)
    }

    final class ViewModel: ObservableObject {
        @Published var state: State = .computing
        let round: Int
        let maxSigners: UInt16
        let minSigners: UInt16
        let label: String
        let mainnet: Bool
        private let previousSecret: String
        private let broadcastsJson: String
        private let round1Json: String
        private let round2Json: String
        private let onSecretUpdated: (String) -> Void
        private let onCompletion: () -> Void
        private let onScanNextRound: () -> Void

        init(
            round: Int,
            maxSigners: UInt16,
            minSigners: UInt16,
            label: String = "",
            mainnet: Bool = true,
            previousSecret: String = "",
            broadcastsJson: String = "[]",
            round1Json: String = "[]",
            round2Json: String = "[]",
            onSecretUpdated: @escaping (String) -> Void = { _ in },
            onScanNext: @escaping () -> Void = {},
            onCompletion: @escaping () -> Void
        ) {
            self.round = round
            self.maxSigners = maxSigners
            self.minSigners = minSigners
            self.label = label
            self.mainnet = mainnet
            self.previousSecret = previousSecret
            self.broadcastsJson = broadcastsJson
            self.round1Json = round1Json
            self.round2Json = round2Json
            self.onSecretUpdated = onSecretUpdated
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
                        let result = try frostDkgPart1(maxSigners: self.maxSigners, minSigners: self.minSigners)
                        let json = try JSONSerialization.jsonObject(with: Data(result.utf8)) as? [String: Any]
                        let secret = json?["secret"] as? String ?? ""
                        let broadcast = json?["broadcast"] as? String ?? ""
                        DispatchQueue.main.async {
                            self.onSecretUpdated(secret)
                            self.state = .displayQr(broadcast)
                        }
                    case 2:
                        let result = try frostDkgPart2(secretHex: self.previousSecret, peerBroadcastsJson: self.broadcastsJson)
                        let json = try JSONSerialization.jsonObject(with: Data(result.utf8)) as? [String: Any]
                        let secret = json?["secret"] as? String ?? ""
                        let packages = json?["peer_packages"] as? [String] ?? []
                        let packagesStr = String(data: try JSONSerialization.data(withJSONObject: packages), encoding: .utf8) ?? "[]"
                        DispatchQueue.main.async {
                            self.onSecretUpdated(secret)
                            self.state = .displayQr(packagesStr)
                        }
                    case 3:
                        // Shares are now sealed under a seed (Rust frost_storage),
                        // which needs the seed unlock flow iOS does not have for
                        // FROST yet. Refused rather than storing an unsealed share.
                        DispatchQueue.main.async {
                            self.onSecretUpdated("")
                            self.state = .error("FROST wallet creation is not available on iOS yet.")
                        }
                    default:
                        DispatchQueue.main.async { self.state = .error("Invalid round: \(self.round)") }
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
