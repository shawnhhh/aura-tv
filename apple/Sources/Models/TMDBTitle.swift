import Foundation
#if os(iOS)
import UIKit
#endif

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

    /// TMDB supplies both orientations; layout selection must not change source metadata.
    func artworkURL(for orientation: HerrinArtworkOrientation, detail: Bool = false) -> URL? {
        let preferred = orientation == .portrait ? posterPath : backdropPath
        let fallback = orientation == .portrait ? backdropPath : posterPath
        guard let path = [preferred, fallback].compactMap({ $0 }).first(where: Self.isSafePath) else {
            return nil
        }
        let size: String
        if detail {
            size = orientation == .landscape && path == backdropPath ? "original" : "w780"
        } else {
            size = orientation == .portrait ? "w500" : "w1280"
        }
        return URL(string: "https://image.tmdb.org/t/p/\(size)\(path)")
    }

    /// In the rare case TMDB has no image of the requested orientation,
    /// letterbox the other image rather than stretching it.
    func usesOppositeOrientationImage(for orientation: HerrinArtworkOrientation) -> Bool {
        let preferred = orientation == .portrait ? posterPath : backdropPath
        let fallback = orientation == .portrait ? backdropPath : posterPath
        return !(preferred.map(Self.isSafePath) ?? false)
            && (fallback.map(Self.isSafePath) ?? false)
    }

    private static func isSafePath(_ path: String) -> Bool {
        path.hasPrefix("/") && !path.contains("..") && !path.contains("//")
            && !path.contains("?") && !path.contains("#")
    }
}

/// Device-based image orientation, independent of iPad window size and orientation.
/// Mac and iPad use TMDB landscape backdrops; iPhone uses vertical posters.
enum HerrinArtworkOrientation {
    case portrait
    case landscape

    static var current: Self {
        #if os(macOS)
        return .landscape
        #elseif os(iOS)
        return UIDevice.current.userInterfaceIdiom == .pad ? .landscape : .portrait
        #else
        return .portrait
        #endif
    }

    var aspectRatio: CGFloat {
        self == .portrait ? 2.0 / 3.0 : 16.0 / 9.0
    }
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
