import SwiftUI

struct HerrinSettingsView: View {
    @Binding var token: String
    let onSave: () -> Void
    @State private var draft = ""
    @State private var message = ""

    var body: some View {
        NavigationStack {
            Form {
                Section("TMDB 接口") {
                    Text("首选 api.tmdb.org，网络不可达时尝试 api.themoviedb.org。")
                        .foregroundStyle(.secondary)
                    SecureField("TMDB API Read Access Token", text: $draft)
                        .textContentType(.password)
                    Button("安全保存") {
                        if TMDBTokenStore.write(draft) {
                            token = draft.trimmingCharacters(in: .whitespacesAndNewlines)
                            message = "已保存至当前设备 Keychain"
                            onSave()
                        } else {
                            message = "保存失败，请检查系统 Keychain 权限"
                        }
                    }
                    Text(message).font(.footnote).foregroundStyle(.secondary)
                    Text("Token 只保存在当前设备，不内置于安装包。清空并保存可删除配置。")
                        .font(.footnote).foregroundStyle(.secondary)
                }
                Section("后续计划") {
                    Text("NAS、直播、标准影视源、播放器和三端续播仍处于后续开发阶段。")
                        .foregroundStyle(.secondary)
                }
            }
            .navigationTitle("Herrin 设置")
        }
        .onAppear { draft = token }
    }
}
