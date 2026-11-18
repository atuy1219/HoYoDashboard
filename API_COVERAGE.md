# API coverage

Reference: genshin.py 1.7.32. See [UPSTREAM.md](UPSTREAM.md).

- Game accounts, Battle Chronicle, real-time notes, reward status and redemption, diaries, transactions and gacha, event calendars and announcements.
- Calculator catalogs, owned character data, individual and batch enhancement, furnishing and replica materials, lineup schedules, TCG, Wiki, Mimo and companions.
- Native authentication clients for web/app password login, SMS, email verification, QR sessions, Geetest requests, and game login. The Android sign-in screen runs the official HoYoLAB or 米游社 WebView flow.
- Android results use Japanese labels, item and character images, grouped details and drill-downs where the API supplies identifiers. Kotlin keeps the original response accessible for endpoints without dedicated data classes.
- Overseas and mainland routes follow the pinned upstream where implemented. Mainland gacha and transaction history can take a caller-supplied authkey. Global-only features retain regional availability checks.

`testDebugUnitTest` includes request and response fixtures for calculator pages and payloads, TCG effects, monthly diaries, lineup schedules, sign-in steps and gacha history. Authenticated live verification is pending. Dedicated Kotlin model coverage and per-method option parity are incomplete.
