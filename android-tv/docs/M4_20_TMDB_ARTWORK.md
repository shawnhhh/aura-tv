# M4.20 · 高清海报／壁纸元数据匹配

- 使用 TMDB 官方 `/3/search/movie`、`/3/search/tv` API，在用户明确输入 **API Read Access Token** 后才连接 `api.themoviedb.org`。
- `poster_path` 优先加载 `https://image.tmdb.org/t/p/w780/...`，`backdrop_path` 优先加载 `https://image.tmdb.org/t/p/original/...`；不会把小尺寸竖图硬拉成电视全屏壁纸。
- 对影视源的片名去掉常见画质/字幕标记，再严格按完整片名、年份、电影/剧集类别匹配。匹配冲突时不猜测，保持源提供的图。
- 用户原始海报**立即加载并显示**，TMDB 查询在另一路协程中运行，成功后渐变替换为高清图；没有 token、没有匹配、离线或请求失败时，不影响原图加载。
- 元数据查询最多并发 3 个；有效结果本地缓存 14 天；无结果/临时请求失败负缓存 3 小时；401/403 对同一 token 暂停查询 30 分钟。图像缓存限每个 8MB，最多约 100MB 级别。
- 未内置任何 token、影视图片、专有抓取工具。**不能将模拟 JSON 测试误称为真实 TMDB 网络及 TCL 电视验证。**

## API 授权与署名

TMDB 官方要求非商业 API 使用者在应用的 Credits/About 页面放置官方徽标及以下原文：

> This product uses the TMDB API but is not endorsed or certified by TMDB.

批准的官网徽标来源：
`https://www.themoviedb.org/assets/2/v4/logos/v2/blue_square_2-d537fb228cf3ded904ef09b136fe3fec72548ebc1fea3fbbd1ad9e36364db38b.svg`。
转换成 `res/drawable-nodpi/tmdb_official_logo.png` 时未改变形状、颜色或宽高比。官网授权资源说明见
`https://www.themoviedb.org/about/logos-attribution` 和 `https://developer.themoviedb.org/docs/faq`。

TMDB API 对非商业使用免费；商业使用应向 TMDB 咨询授权。影像权利仍归各权利人所有。
