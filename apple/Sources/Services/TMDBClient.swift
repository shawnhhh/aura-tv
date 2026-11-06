import Foundation

enum TMDBClientError: LocalizedError {
    case missingToken
    case unauthorized
    case rateLimited
    case serverError(Int)
    case invalidResponse
    case networkUnavailable
    case invalidRequest

    var errorDescription: String? {
        switch self {
        case .missingToken: return "请先在设置中配置 TMDB Read Access Token"
        case .unauthorized: return "TMDB Token 认证失败，请检查配置"
        case .rateLimited: return "TMDB 访问频率受限，请稍后再试"
        case .serverError(let code): return "TMDB 服务暂不可用（HTTP \(code)）"
        case .invalidResponse: return "TMDB 返回了无法解析的数据"
        case .networkUnavailable: return "当前网络无法连接 TMDB"
        case .invalidRequest: return "查询条件无效"
        }
    }

    var mayTryBackupEndpoint: Bool {
        switch self {
        case .serverError, .networkUnavailable: return true
        default: return false
        }
    }
}

enum TMDBRoute {
    case trending
    case movieDiscover
    case tvDiscover
    case search(String)

    var path: String {
        switch self {
        case .trending: return "/trending/all/week"
        case .movieDiscover: return "/discover/movie"
        case .tvDiscover: return "/discover/tv"
        case .search: return "/search/multi"
        }
    }

    var extraQueryItems: [URLQueryItem] {
        if case .search(let query) = self { return [URLQueryItem(name: "query", value: query)] }
        return []
    }
}

/// One request source of truth; supports only the fixed TMDB v3 paths above.
/// Authentication is passed exclusively via Authorization header.
actor TMDBClient {
    static let shared = TMDBClient()
    private let domains = ["api.tmdb.org", "api.themoviedb.org"]

    func fetch(_ route: TMDBRoute, token: String) async throws -> [TMDBTitle] {
        let token = token.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !token.isEmpty else { throw TMDBClientError.missingToken }
        if case .search(let text) = route, text.trimmingCharacters(in: .whitespaces).isEmpty {
            return []
        }

        for (index, domain) in domains.enumerated() {
            do {
                guard var components = URLComponents(string: "https://\(domain)/3\(route.path)") else {
                    throw TMDBClientError.invalidRequest
                }
                components.queryItems = [
                    URLQueryItem(name: "language", value: "zh-CN"),
                    URLQueryItem(name: "page", value: "1"),
                    URLQueryItem(name: "include_adult", value: "false")
                ] + route.extraQueryItems
                guard let url = components.url else { throw TMDBClientError.invalidRequest }

                var request = URLRequest(url: url)
                request.timeoutInterval = 8
                request.setValue("application/json", forHTTPHeaderField: "Accept")
                request.setValue("Bearer \(token)", forHTTPHeaderField: "Authorization")
                let (data, rawResponse): (Data, URLResponse)
                do {
                    (data, rawResponse) = try await URLSession.shared.data(for: request)
                } catch let error as URLError {
                    if error.code == .cancelled { throw CancellationError() }
                    throw TMDBClientError.networkUnavailable
                }
                guard let response = rawResponse as? HTTPURLResponse else {
                    throw TMDBClientError.invalidResponse
                }
                switch response.statusCode {
                case 200..<300:
                    guard let page = try? JSONDecoder().decode(TMDBPage.self, from: data) else {
                        throw TMDBClientError.invalidResponse
                    }
                    return page.results
                case 401, 403: throw TMDBClientError.unauthorized
                case 429: throw TMDBClientError.rateLimited
                case 500..<600: throw TMDBClientError.serverError(response.statusCode)
                default: throw TMDBClientError.invalidResponse
                }
            } catch let error as TMDBClientError {
                if error.mayTryBackupEndpoint && index < domains.count - 1 { continue }
                throw error
            }
        }
        throw TMDBClientError.networkUnavailable
    }
}
