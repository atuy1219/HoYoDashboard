# API coverage

- [x] Account discovery and real-time notes (Genshin / Star Rail / ZZZ)
- [x] 105 mapped read-only upstream operations (not all live-verified)
- [x] Native request transport, fixed host validation, overseas/CN routing
- [x] Game details, raw response inspector, input parameters
- [x] Badge-token login implementation (overseas, live verification pending)
- [x] Cursor / page-number pagination with Kotlin Flow
- [x] Explicit actions: daily sign-in, code redemption, Mimo, profile and community updates
- [ ] End-to-end authenticated tests for each upstream method
- [ ] Pydantic-equivalent typed models and complete feature-level parity
- [ ] Password, QR, phone, CAPTCHA, 2FA and token-refresh auth flows
- [ ] Full upstream write-operation set and post-action results
- [ ] Some CN, HI3, and Tears of Themis features

See [UPSTREAM.md](UPSTREAM.md) for the reference version. API definitions are not proof of live compatibility.
