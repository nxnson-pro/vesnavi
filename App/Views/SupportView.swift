import SwiftUI
import UIKit

/// Voluntary tips: Ko-fi (cards / PayPal) or a VietQR bank transfer. The app stays free and complete.
enum Support {
    static let koFiURL = URL(string: "https://ko-fi.com/nxnson")
    static let bankName = "Techcombank"
    static let accountNumber = "270288886666"
}

struct SupportView: View {
    @Environment(\.dismiss) private var dismiss
    @State private var copied = false

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(spacing: 20) {
                    Text(L("VesNavi miễn phí và đầy đủ tính năng. Nếu app có ích, bạn có thể mời tác giả một ly cà phê — hoàn toàn tự nguyện.",
                           "VesNavi is free and complete. If it's useful to you, you can buy the author a coffee — entirely optional."))
                        .font(.subheadline)
                        .foregroundStyle(.secondary)
                        .multilineTextAlignment(.center)

                    if let koFiURL = Support.koFiURL {
                        Link(destination: koFiURL) {
                            Label(L("Ủng hộ qua Ko-fi (thẻ / PayPal)", "Support on Ko-fi (card / PayPal)"),
                                  systemImage: "cup.and.saucer.fill")
                                .font(.headline)
                                .frame(maxWidth: .infinity)
                        }
                        .buttonStyle(.borderedProminent)
                        .controlSize(.large)
                    }

                    VStack(spacing: 12) {
                        Text(L("Chuyển khoản ngân hàng (VietQR)", "Bank transfer in Vietnam (VietQR)"))
                            .font(.headline)
                        Image("VietQR")
                            .resizable()
                            .scaledToFit()
                            .frame(maxWidth: 300)
                            .clipShape(RoundedRectangle(cornerRadius: 16, style: .continuous))
                            .accessibilityLabel("VietQR \(Support.bankName) \(Support.accountNumber)")
                        Text(L("Lưu ảnh rồi mở app ngân hàng → Quét QR → chọn ảnh từ thư viện.",
                               "Save the image, then in your banking app choose Scan QR → from photo."))
                            .font(.caption)
                            .foregroundStyle(.secondary)
                            .multilineTextAlignment(.center)
                        HStack {
                            ShareLink(item: Image("VietQR"),
                                      preview: SharePreview("VietQR", image: Image("VietQR"))) {
                                Label(L("Lưu ảnh QR", "Save QR image"), systemImage: "square.and.arrow.down")
                            }
                            Button {
                                UIPasteboard.general.string = Support.accountNumber
                                copied = true
                            } label: {
                                Label(copied ? L("Đã chép", "Copied") : L("Chép số TK", "Copy account no."),
                                      systemImage: copied ? "checkmark" : "doc.on.doc")
                            }
                        }
                        .buttonStyle(.bordered)
                    }
                    .padding()
                    .frame(maxWidth: .infinity)
                    .background(Color(.secondarySystemGroupedBackground),
                                in: RoundedRectangle(cornerRadius: Theme.cardRadius, style: .continuous))
                }
                .padding()
            }
            .background(Color(.systemGroupedBackground))
            .navigationTitle(L("Ủng hộ tác giả", "Support the author"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(L("Xong", "Done")) { dismiss() }
                }
            }
        }
        .tint(Theme.accent)
    }
}
