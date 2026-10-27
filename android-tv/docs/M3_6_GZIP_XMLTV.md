# M3.6 — bounded gzip XMLTV support

The optional, user-configured HTTPS XMLTV endpoint now accepts ordinary XML
and `.xml.gz` compressed guides. The app detects gzip from the first two
payload bytes, not from a file extension or an untrusted content type. It also
accepts `application/gzip` and `application/x-gzip` responses.

Safety and backwards compatibility:

- The HTTPS-only endpoint rule, disabled redirects, connection timeouts and
  refusal to fetch XML external entities/DTDs are unchanged.
- Network payloads are limited to **4,000,000 bytes**, and inflated guide XML
  to **6,000,000 UTF-8 bytes**. This blocks small gzip responses that expand
  uncontrollably. XML parsing additionally rejects more than **8,000**
  programme nodes and malformed schedules.
- Corrupt/truncated gzip, unsupported response types, invalid XML and excess
  payload size all return the existing non-fatal EPG error. If the endpoint is
  unchanged, the Live tab keeps the last successful in-memory guide; channel
  playback is unaffected.
- Nothing automatically discovers or downloads a guide. The user must supply
  the HTTPS address. The code contains no third-party stream or XMLTV fixture
  beyond synthetic test data.
- New tests cover a compressed synthetic programme, regular XML parity,
  inflation bombs, oversize transport, truncated gzip, compressed XXE, a true
  TLS emulator request, redirects and HTTP rejection.

Debug version: `0.3.6-alpha` / code 6. Cloud Debug/unit/lint and API30 Android
TV emulator tests are required. Physical TCL T7L Ultra validation of a real
authorized `.xml.gz` guide, guide refresh and memory/performance is pending.
