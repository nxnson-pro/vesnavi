import MapKit
import SwiftUI

struct SearchView: View {
    @Environment(AppModel.self) private var model
    @Environment(\.dismiss) private var dismiss
    @State private var query = ""

    var body: some View {
        NavigationStack {
            List {
                if query.isEmpty {
                    SavedPlacesList { place in
                        Task { await model.planRoute(to: place.mapItem) }
                        dismiss()
                    }
                }
                ForEach(model.search.results, id: \.self) { item in
                    Button {
                        Task { await model.select(item) }
                        dismiss()
                    } label: {
                        HStack(spacing: 12) {
                            Image(systemName: "mappin.circle.fill")
                                .font(.title2)
                                .foregroundStyle(Theme.accent)
                            VStack(alignment: .leading, spacing: 2) {
                                Text(item.title).foregroundStyle(.primary)
                                if !item.subtitle.isEmpty {
                                    Text(item.subtitle).font(.caption).foregroundStyle(.secondary)
                                }
                            }
                        }
                    }
                }
            }
            .searchable(text: $query, placement: .navigationBarDrawer(displayMode: .always),
                        prompt: L("Địa chỉ, quán, địa điểm…", "Address, shop, place…"))
            .onChange(of: query) { _, q in
                model.search.update(query: q, near: model.location.lastLocation?.coordinate)
            }
            .navigationTitle(L("Đi đâu?", "Where to?"))
            .navigationBarTitleDisplayMode(.inline)
            .listStyle(.insetGrouped)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(L("Đóng", "Close")) { dismiss() }
                }
            }
        }
        .tint(Theme.accent)
    }
}
