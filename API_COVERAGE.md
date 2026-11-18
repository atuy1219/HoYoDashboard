# API coverage

## Implemented

- [x] 117 mapped read-only upstream operations across Genshin Impact, Honkai: Star Rail, Zenless Zone Zero, Honkai Impact 3rd, and shared HoYoLAB APIs
- [x] Native HTTPS request transport with official-host validation
- [x] Overseas and mainland-China routing where the upstream API exposes a route
- [x] Cookie storage encrypted with Android Keystore AES-GCM
- [x] WebView login for HoYoLAB and 米游社
- [x] Cookie refresh for supported legacy/global and v2 sessions
- [x] Badge-token acquisition for Star Rail and ZZZ
- [x] Authkey generation for supported overseas gacha and transaction endpoints
- [x] Cursor and page-number pagination with Kotlin Flow
- [x] Aggregation of Genshin, Star Rail, and ZZZ gacha pools
- [x] Aggregation of Genshin transaction categories
- [x] Explicit write actions for daily check-in, code redemption, profile settings, Mimo, and selected community operations
- [x] Typed core models for profiles, character previews, gacha records, diary summaries, rewards, and transaction rows

## Remaining compatibility work

- [ ] Live authenticated verification of every mapped operation and region
- [ ] Typed Kotlin models for every upstream response (raw JSON is still retained for less common APIs)
- [ ] Dedicated UI for every game feature and every HoYoLAB endpoint
- [ ] Full parity for upstream authentication helpers (password, QR, phone, email, Geetest and other challenge flows); the app currently delegates login to official WebView pages
- [ ] Remaining write-operation parity and UI confirmation flows
- [ ] China-region gaps where the upstream service has no supported route, including selected announcement, ZZZ ledger, and teapot endpoints
- [ ] Contract tests against captured, sanitized upstream responses

API definitions are not proof of live compatibility. A successful GitHub Actions build verifies compilation and unit tests, not authenticated access to HoYoverse services.
