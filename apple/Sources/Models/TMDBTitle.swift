import Foundation

/// Display metadata only. Never carry playback URLs or TVBox provider identities here.
struct TMDBTitle: Identifiable, Hashable, Decodable {
    let id: Int
    let title: String?
    let name: String?
    let overview: String?
    let posterPath: String?
    let backdropPath: String?
    let mediaType: String?
    let releaseDate: String?
    let firstAirDate: String?

    enum CodingKeys: String, CodingKey {
        case id, title, name, overview
        case posterPath = "poster_path"
        case backdropPath = "backdrop_path"
        case mediaType = "media_type"
        case releaseDate = "release_date"
        case firstAirDate = "first_air_date"
    }

    var displayName: String { title ?? name ?? "未命名作品" }
    var year: String? { (releaseDate ?? firstAirDate)?.prefix(4).description }

    var posterURL: URL? {
        guard let posterPath, posterPath.hasPrefix("/"),
              !posterPath.contains(".."), !posterPath.contains("//") else { return nil }
        return URL(string: "https://image.tmdb.org/t/p/w500\(posterPath)")
    }
}

struct TMDBPage: Decodable {
    let results: [TMDBTitle]
}

enum HerrinSection: String, CaseIterable, Identifiable {
    case recommended = "推荐"
    case movies = "电影"
    case series = "剧集"
    case search = "搜索"
    case settings = "设置"

    var id: Self { self }

    var symbol: String {
        switch self {
        case .recommended: return "house"
        case .movies: return "film"
        case .series: return "tv"
        case .search: return "magnifyingglass"
        case .settings: return "gearshape"
        }
    }
}
