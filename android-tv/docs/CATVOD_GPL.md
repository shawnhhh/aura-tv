# HERRIN CatVod / Spider integration (M4.5)

## Licensing and provenance

The contents of `catvod/src/main` are copied from the `fongmi` branch of
[`FongMi/TV`](https://github.com/FongMi/TV) at commit
`123cc086159b0f13bf54bdf64bacbc259ca47c88` (2026-10-27). Those files
are GPL-3.0. The complete GPL-3.0 license text is at
[`LICENSE-GPL-3.0.md`](../LICENSE-GPL-3.0.md). We preserve upstream names,
source, and structure. Local SDK modifications include a Java 21 pattern switch
in `Prefers.java` rewritten as Java 17 `instanceof` clauses; adapting the
proxy/redirect interceptor and WebSocket timeout calls to OkHttp 4.12; and
**removing the upstream global TLS trust-all/hostname-bypass** in `OkHttp.java`.
The `catvod/build.gradle.kts` file expresses upstream dependency
families and the local Java 17/AGP 8.7 toolchain. OkHttp is pinned to 4.12.0
instead of upstream 5.5.0 to keep Android SDK 35; this requires separate
build and runtime verification.

HERRIN Android sources and binaries incorporating this SDK must be distributed
in compliance with GPL-3.0, including provision of the full corresponding
source, licenses, and build/install information as applicable. This draft PR
does not relicense the separate static design prototype at the repository root.
No third-party Spider plugin binary is bundled into the APK or repository.

## Runtime behavior and security

1. Import a user-chosen TVBox configuration. JPEG+Base64 wrapper, single
   warehouse and multiple warehouses are supported by existing code.
2. **Enable external Spider for this app session**. This is deliberately
   disabled by default and not persisted across app restarts.
3. Choose a Java `csp_*` source. The JAR URL must be HTTPS and carry a
   `;md5;` checksum. Files are downloaded with no redirect, a 16 MB cap,
   checked against the declared MD5, checked for `classes.dex`, made read-only
   and then loaded with `DexClassLoader`. MD5 establishes consistency with the
   provider configuration, **not the trustworthiness of its author**.
4. `Spider.homeContent/homeVideoContent/categoryContent`, `detailContent`, and
   `playerContent` connect TVBox listings and opaque episode identifiers to
   native HERRIN models. `playerContent` with `parse:0` and a valid HTTP(S)
   URL can be played by Media3; URLs requiring web parsing are not falsely
   advertised as playable. HTTP needs separate one-session permission.

**Important limitation:** External DEX currently executes with HERRIN app UID
and permissions. It is *not a security sandbox*. A plugin could access app
storage or network, despite checksum verification; do not enable it for sources
you do not trust. A future iteration should move execution into a dedicated
isolated UID process with explicit brokered network/storage APIs. Some Spider
plugins also rely on private/native modules, proxy APIs, Cloud Drive login,
web parsing, or QuickJS/Python and may not work yet. `parse:1`, encrypted
playlists and DRM are not silently bypassed.

## Concrete test fixture

The user-supplied `http://www.饭太硬.net/tv` responded with a 20,063-byte JPEG
whose tail wraps the TVBox JSON. As of 2026-10-27 it declared
`https://xiuxiu-pro-new.meitudata.com/posters/e05f19091b8106dd81c34becbae3ad1c.jpg;md5;b55978c4fe6d12e2fea9075f17c8a67a`.
We downloaded this exact file for *offline inspection only*: it is an Android
DEX archive with `classes.dex` and its actual MD5 matches the declared value.
It also includes obfuscated/protected auxiliary assets, so even successful
class loading will not prove that real film browsing and playback works.
Never commit or distribute the fetched JAR. Test the end-to-end flow on an
emulator and a user-authorized device; report the exact outcome, not guesses.
