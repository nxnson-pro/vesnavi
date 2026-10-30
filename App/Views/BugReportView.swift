import NavCore
import SwiftUI
import UIKit

/// App and phone details for bug reports and Settings → About.
enum AppInfo {
    /// "1.0.0 (25)": marketing version + CI run number.
    static var version: String {
        let info = Bundle.main.infoDictionary
        let version = info?["CFBundleShortVersionString"] as? String ?? "-"
        let build = info?["CFBundleVersion"] as? String ?? "-"
        return "\(version) (\(build))"
    }

    /// Hardware identifier such as "iPhone16,1" (`UIDevice.model` only says "iPhone").
    static var phoneModel: String {
        var info = utsname()
        uname(&info)
        let machine = withUnsafeBytes(of: &info.machine) { raw in
            String(decoding: raw.prefix { $0 != 0 }, as: UTF8.self)
        }
        return machine.isEmpty ? UIDevice.current.model : machine
    }
}

/// Opens a pre-filled GitHub issue (the rider submits it from their own account, no token in the app),
/// with the Bluetooth log redacted for a public page.
struct BugReportView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @Environment(\.openURL) private var openURL
    @State private var details = ""
    @State private var includeLog: Bool

    init(includeLog: Bool = true) {
        _includeLog = State(initialValue: includeLog)
    }

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    TextField(L("Bạn làm gì, mong đợi gì, thực tế thấy gì…", "What you did, what you expected, what you saw…"),
                              text: $details, axis: .vertical)
                        .lineLimit(4...10)
                } header: {
                    Text(L("Mô tả lỗi", "What went wrong"))
                }

                Section {
                    Toggle(L("Đính kèm log Bluetooth", "Attach the Bluetooth log"), isOn: $includeLog)
                } footer: {
                    Text(L("Issue trên GitHub là công khai: địa chỉ Bluetooth, khoá ghép đôi và tên thiết bị khác được ẩn trước khi gửi. Log dài thì chỉ phần mới nhất được điền vào; bản đầy đủ (đã ẩn) được chép sẵn — dán vào bình luận nếu cần.",
                           "GitHub issues are public: Bluetooth addresses, pairing keys and other devices' names are removed first. A long log is cut to its newest part; the full (redacted) log is copied — paste it in a comment if needed."))
                }

                Section {
                    Button {
                        send()
                    } label: {
                        Label(L("Mở GitHub để gửi", "Open GitHub to send"), systemImage: "ladybug")
                    }
                    .disabled(details.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty)
                } footer: {
                    Text(L("Cần tài khoản GitHub (miễn phí). Không có gì được gửi cho tới khi bạn bấm \"Submit new issue\" trên GitHub.",
                           "Needs a (free) GitHub account. Nothing is sent until you tap \"Submit new issue\" on GitHub."))
                }
            }
            .navigationTitle(L("Báo lỗi lên GitHub", "Report a bug on GitHub"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) { Button(L("Huỷ", "Cancel")) { dismiss() } }
            }
        }
        .tint(Theme.accent)
    }

    private func send() {
        let ble = model.vespa
        let text = details.trimmingCharacters(in: .whitespacesAndNewlines)
        let firstLine = text.split(separator: "\n").first.map(String.init) ?? text
        let scooter = [ble.connectedName, ble.linkProtocol?.rawValue, ble.firmwareInfo].compactMap { $0 }.joined(separator: " · ")
        let device = "\(AppInfo.phoneModel), iOS \(UIDevice.current.systemVersion)" + (scooter.isEmpty ? "" : " · \(scooter)")
        let log = includeLog ? ble.exportLog(redacted: true) : nil
        if let log { UIPasteboard.general.string = log }
        guard let url = BugReport.issueURL(title: "[iOS] " + String(firstLine.prefix(70)), description: text,
                                           version: AppInfo.version, device: device, log: log) else { return }
        openURL(url)
        dismiss()
    }
}
