import SwiftUI

@MainActor
final class HerrinCatalogModel: ObservableObject {
    @Published private(set) var titles: [TMDBTitle] = []
    @Published private(set) var loading = false
    @Published private(set) var message: String?

    private var activeRequest: Task<Void, Never>?

    func load(section: HerrinSection, token: String, query: String = "") {
        activeRequest?.cancel()
        guard section != .settings else { return }
        guard !token.isEmpty else {
            titles = []
            message = "请在设置中填写 TMDB Read Access Token"
            loading = false
            return
        }
        let route: TMDBRoute
        switch section {
        case .recommended: route = .trending
        case .movies: route = .movieDiscover
        case .series: route = .tvDiscover
        case .search: route = .search(query)
        case .settings: return
        }

        if section == .search && query.trimmingCharacters(in: .whitespaces).isEmpty {
            titles = []
            message = "输入片名后点击搜索"
            loading = false
            return
        }
        loading = true
        message = nil
        activeRequest = Task {
            do {
                let result = try await TMDBClient.shared.fetch(route, token: token)
                guard !Task.isCancelled else { return }
                self.titles = result
                self.message = result.isEmpty ? "暂无匹配内容" : nil
                self.loading = false
            } catch {
                guard !Task.isCancelled else { return }
                self.message = (error as? LocalizedError)?.errorDescription ?? "请求失败，请稍后重试"
                self.loading = false
            }
        }
    }
}

struct HerrinRootView: View {
    @State private var section: HerrinSection = .recommended
    @State private var token = TMDBTokenStore.read()
    @State private var searchText = ""
    @StateObject private var catalog = HerrinCatalogModel()

    var body: some View {
        Group {
            #if os(macOS)
            NavigationSplitView {
                List {
                    ForEach(HerrinSection.allCases) { choice in
                        Button {
                            section = choice
                        } label: {
                            Label(choice.rawValue, systemImage: choice.symbol)
                                .frame(maxWidth: .infinity, alignment: .leading)
                        }
                        .buttonStyle(.plain)
                        .padding(.vertical, 6)
                    }
                }
                .navigationTitle("HERRIN")
                .listStyle(.sidebar)
            } detail: {
                pane(for: section)
            }
            .frame(minWidth: 840, minHeight: 550)
            #else
            TabView(selection: $section) {
                ForEach(HerrinSection.allCases) { choice in
                    pane(for: choice)
                        .tabItem { Label(choice.rawValue, systemImage: choice.symbol) }
                        .tag(choice)
                }
            }
            #endif
        }
        .tint(.white)
        .task { refresh() }
        .onChange(of: section) { _, _ in refresh() }
    }

    @ViewBuilder
    private func pane(for choice: HerrinSection) -> some View {
        if choice == .settings {
            HerrinSettingsView(token: $token, onSave: refresh)
        } else {
            HerrinCatalogView(
                section: choice,
                model: catalog,
                query: $searchText,
                onSearch: refresh
            )
        }
    }

    private func refresh() {
        catalog.load(section: section, token: token, query: searchText)
    }
}

struct HerrinCatalogView: View {
    let section: HerrinSection
    @ObservedObject var model: HerrinCatalogModel
    @Binding var query: String
    let onSearch: () -> Void

    private let columns = [GridItem(.adaptive(minimum: 145, maximum: 230), spacing: 18)]

    var body: some View {
        NavigationStack {
            ScrollView {
                VStack(alignment: .leading, spacing: 20) {
                    Text("HERRIN")
                        .font(.system(size: 34, weight: .semibold, design: .rounded))
                        .tracking(4)
                    Text(section.rawValue)
                        .font(.title2.bold())
                    if section == .search {
                        HStack {
                            TextField("输入电影或剧集名称", text: $query)
                                .textFieldStyle(.roundedBorder)
                                .onSubmit(onSearch)
                            Button("搜索", action: onSearch)
                                .buttonStyle(.borderedProminent)
                        }
                    }
                    if model.loading {
                        ProgressView("正在加载影视资料…")
                            .frame(maxWidth: .infinity)
                            .padding(.vertical, 35)
                    }
                    if let message = model.message {
                        Text(message).foregroundStyle(.secondary)
                            .padding(.vertical, 12)
                    }
                    LazyVGrid(columns: columns, spacing: 24) {
                        ForEach(model.titles) { title in
                            NavigationLink {
                                HerrinTitleDetailView(title: title)
                            } label: {
                                HerrinPosterCard(title: title)
                            }
                            .buttonStyle(.plain)
                        }
                    }
                }
                .padding(24)
                .frame(maxWidth: 1200, alignment: .leading)
                .frame(maxWidth: .infinity)
            }
            .background(Color.black)
        }
    }
}

struct HerrinPosterCard: View {
    let title: TMDBTitle

    var body: some View {
        VStack(alignment: .leading, spacing: 8) {
            AsyncImage(url: title.posterURL) { image in
                image.resizable().scaledToFill()
            } placeholder: {
                Rectangle().fill(.white.opacity(0.08))
                    .overlay { Image(systemName: "film").foregroundStyle(.secondary) }
            }
            .aspectRatio(2 / 3, contentMode: .fit)
            .clipped()
            .clipShape(RoundedRectangle(cornerRadius: 12))
            Text(title.displayName)
                .font(.callout.weight(.medium))
                .lineLimit(2)
            if let year = title.year {
                Text(year).font(.caption).foregroundStyle(.secondary)
            }
        }
        .frame(maxWidth: .infinity, alignment: .leading)
        .accessibilityElement(children: .combine)
    }
}

struct HerrinTitleDetailView: View {
    let title: TMDBTitle

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: 20) {
                HStack(alignment: .top, spacing: 24) {
                    AsyncImage(url: title.posterURL) { image in
                        image.resizable().scaledToFit()
                    } placeholder: {
                        RoundedRectangle(cornerRadius: 12)
                            .fill(.white.opacity(0.1))
                    }
                    .frame(width: 160)
                    .clipShape(RoundedRectangle(cornerRadius: 12))
                    VStack(alignment: .leading, spacing: 12) {
                        Text(title.displayName).font(.largeTitle.bold())
                        if let year = title.year { Text(year).foregroundStyle(.secondary) }
                        Text(title.overview?.isEmpty == false ? (title.overview ?? "") : "暂无简介")
                            .font(.body)
                    }
                    Spacer(minLength: 0)
                }
                Text("M0：本页面仅展示 TMDB 元数据，尚未接入播放。")
                    .font(.footnote).foregroundStyle(.secondary)
            }
            .padding(24)
        }
        .background(Color.black)
        .navigationTitle(title.displayName)
    }
}
