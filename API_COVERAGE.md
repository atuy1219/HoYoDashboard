# API coverage

## Implemented

- [x] 117 mapped read-only upstream operations across Genshin Impact, Honkai: Star Rail, Zenless Zone Zero, Honkai Impact 3rd, and shared HoYoLAB APIs
- [x] Native HTTPS request transport with official-host validation
- [x] Overseas and mainland-China routing where the upstream API exposes a route
- [x] Cookie storage encrypted with Android Keystore AES-GCM
- [x] WebView login for HoYoLAB and 米游社
- [x] Cookie refresh for supported global and mainland legacy/v2 sessions
- [x] Badge-token acquisition for Star Rail and ZZZ
- [x] Authkey generation for supported overseas gacha and transaction endpoints
- [x] Cursor and page-number pagination with Kotlin Flow
- [x] Aggregation of all Genshin, Star Rail, and ZZZ gacha pools before applying history limits
- [x] Aggregation of all Genshin transaction categories before applying history limits
- [x] Typed core models for profiles, character previews, gacha records, diary summaries, rewards, and transaction rows
- [x] Dedicated tabs for account overview, characters and build data, battle records, gacha history, and monthly records
- [x] UI for daily check-in, code redemption, and the currently implemented account write operations
- [x] Confirmation gates for destructive or value-consuming account actions
- [x] Unit tests for catalog coverage, pagination merge ordering, nested diary parsing, and account-scoped write actions

## Remaining compatibility work

- [ ] Live authenticated verification of every mapped operation and region
- [ ] Typed Kotlin models for every upstream response (raw JSON is still retained for less common APIs)
- [ ] Dedicated native UI for every game feature and every HoYoLAB endpoint; the API explorer remains the fallback for less common operations
- [ ] Full parity for upstream authentication helpers (password, QR, phone, email, Geetest and other challenge flows); the app currently delegates login to official WebView pages
- [ ] Additional upstream write-operation parity beyond the implemented action set
- [ ] China-region gaps where the upstream service has no supported route, including selected announcement, ZZZ ledger, and teapot endpoints
- [ ] Contract tests against captured, sanitized upstream responses

API definitions are not proof of live compatibility. A successful GitHub Actions build verifies compilation and unit tests, not authenticated access to HoYoverse services.
