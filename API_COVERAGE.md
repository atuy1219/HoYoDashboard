# API coverage

Reference: genshin.py 1.7.32. See [UPSTREAM.md](UPSTREAM.md).

- Game accounts, Battle Chronicle, notes, rewards and redemption, monthly diaries, transactions and gacha, event calendars and announcements.
- Calculator catalogs, owned equipment, individual and batch enhancement, furnishing materials, lineup schedules, TCG, Wiki, Mimo and companions.
- Web and app password login, SMS, email verification, QR, Geetest requests and game-login clients. Android sign-in uses the official site.
- Overseas history keys, official localization dictionaries and game server time zones.
- Request contracts cover UID and locale overrides, period and banner filters, cursor and offset pagination, and owned-account opt-in for settings changes. Kotlin exposes typed profiles, builds, check-in rewards, warp statistics, diary summaries and calculator materials. Other responses are accessible through the JSON client.
- Android uses Japanese UI and game terminology. Supported pages offer on-device translation of foreign-language content.

Contract fixtures cover representative methods and localization. Authenticated live coverage, some response models and optional arguments, and Python-specific extensions remain open.
