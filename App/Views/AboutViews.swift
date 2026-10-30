import SwiftUI

/// OpenStreetMap's licence page (ODbL attribution for routes and speed limits).
let osmCopyrightURL = URL(string: "https://www.openstreetmap.org/copyright")

/// Shown once on first launch (and from Settings): ride safely, data is indicative, unofficial app.
struct SafetyNoticeView: View {
    let onAccept: () -> Void

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                Image(systemName: "exclamationmark.shield.fill")
                    .font(.system(size: 44))
                    .foregroundStyle(Theme.accent)
                Text(L("Trước khi lên xe", "Before you ride"))
                    .font(.largeTitle.weight(.bold))
                point("hand.raised.fill",
                      L("Đặt điểm đến trước khi chạy. Không thao tác điện thoại khi đang lái — luôn chú ý đường và tuân thủ luật giao thông.",
                        "Set your destination before riding. Don't handle the phone while riding — watch the road and follow traffic law."))
                point("signpost.right.fill",
                      L("Chỉ đường và giới hạn tốc độ chỉ để tham khảo, có thể sai hoặc thiếu. Biển báo thực tế luôn được ưu tiên.",
                        "Directions and speed limits are for reference only and can be wrong or missing. Road signs always take priority."))
                point("person.fill.questionmark",
                      L("VesNavi là dự án cá nhân, không liên kết với Piaggio / Vespa. Bạn tự chịu trách nhiệm khi sử dụng.",
                        "VesNavi is an unofficial hobby project, not affiliated with Piaggio / Vespa. Use it at your own risk."))
            }
            .padding(24)
        }
        .safeAreaInset(edge: .bottom) {
            Button(action: onAccept) {
                Text(L("Tôi đã hiểu", "I understand"))
                    .font(.headline)
                    .frame(maxWidth: .infinity)
            }
            .buttonStyle(.borderedProminent)
            .controlSize(.large)
            .padding(24)
        }
        .tint(Theme.accent)
        .interactiveDismissDisabled()
    }

    private func point(_ icon: String, _ text: String) -> some View {
        HStack(alignment: .top, spacing: 14) {
            Image(systemName: icon)
                .font(.title3)
                .foregroundStyle(Theme.accent)
                .frame(width: 28)
            Text(text).font(.body)
        }
    }
}

/// Settings → About: data attribution, the safety notice again, version.
struct AboutSection: View {
    let version: String
    @State private var showSafety = false
    @State private var showSupport = false

    var body: some View {
        Section {
            if let osmCopyrightURL {
                Link(destination: osmCopyrightURL) {
                    Label("© OpenStreetMap contributors", systemImage: "map")
                }
            }
            Button { showSafety = true } label: {
                Label(L("Lưu ý an toàn", "Safety notice"), systemImage: "exclamationmark.shield")
            }
            .sheet(isPresented: $showSafety) {
                SafetyNoticeView { showSafety = false }
            }
            Button { showSupport = true } label: {
                Label(L("Ủng hộ tác giả", "Support the author"), systemImage: "cup.and.saucer")
            }
            .sheet(isPresented: $showSupport) { SupportView() }
            LabeledContent(L("Phiên bản", "Version"), value: version)
        } header: {
            Text(L("Thông tin", "About"))
        } footer: {
            Text(L("Tuyến đường (OSRM, GraphHopper) và giới hạn tốc độ (Overpass) dùng dữ liệu OpenStreetMap theo giấy phép ODbL. Bản đồ và tìm kiếm: Apple Maps. VesNavi là dự án cá nhân, không liên kết với Piaggio / Vespa.",
                   "Routes (OSRM, GraphHopper) and speed limits (Overpass) use OpenStreetMap data under the ODbL. Map and search: Apple Maps. VesNavi is an unofficial hobby project, not affiliated with Piaggio / Vespa."))
        }
    }
}
