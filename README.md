# MY CHATTING PRO

**MY CHATTING PRO** is a native Android messaging and real-time calling application built with **Kotlin + Jetpack Compose (Material 3, MVVM + Clean Architecture)** and a **Supabase (PostgreSQL + Private Storage) + Cloudflare Workers + Cloudflare Durable Objects** backend.

**Developer & Owner:** `TAWHID SHAKH`

---

## Architecture Overview

### Android App (`app/`)
- **UI:** 100% Jetpack Compose with Material 3, Emerald/Teal Light & Dark palettes, local `Space Grotesk`, `Plus Jakarta Sans`, and `JetBrains Mono` typography (`res/font/`), and edge-to-edge `WindowSizeClass` responsive layouts (`NavigationBar` on compact screens, `NavigationRail` on tablets/foldables).
- **Localization & RTL:** Full support for **বাংলা (`bn`)**, **English (`en`)**, and **العربية (`ar`, RTL)** using `LocaleManager` & `LayoutDirection.Rtl`.
- **Auth (No OTP / No Firebase):** International country picker (`libphonenumber` E.164 normalization) + **5-digit numeric PIN** (2-step Login, 4-step Create Account). Because OTP is intentionally omitted, lost-PIN SMS recovery is unavailable.
- **Offline-First Storage & Security:**
  - `Room` (`MyChattingProDatabase`) caches conversations and messages locally.
  - `EncryptedTokenStore` wraps Android Keystore `AES/GCM/NoPadding` to encrypt access & refresh tokens and optional Owner Monitor Key.
  - `DataStore` stores language, theme, recent countries, and background WebSocket sync preference.
  - **Zero Supabase Secrets in APK:** Android talks exclusively to the Cloudflare Workers API Gateway (`BuildConfig.CLOUDFLARE_API_BASE_URL`). `SUPABASE_SERVICE_ROLE_KEY` never enters the APK.
- **Media & Calls:**
  - Zero-permission `ActivityResultContracts.PickVisualMedia` for images/videos and `OpenDocument` for documents.
  - `Media3 ExoPlayer` for private video playback and `MediaPlayer` for M4A voice notes via short-lived Supabase Storage signed URLs issued by Cloudflare Workers.
  - `stream-webrtc-android` P2P Audio & Video calling signaled through Cloudflare Durable Objects WebSockets.
- **Owner Monitor (`Settings -> অ্যাপ কে চালাচ্ছে দেখুন`):**
  - Protected by authenticated JWT + server-side owner identity check (`users.role = 'owner'` or `OWNER_USER_IDS`) + constant-time `X-Owner-Key` (`OWNER_MONITOR_KEY`) + `ip_and_user` rate limiting + search & pagination.
- **Monetization (Google AdMob Interstitial Only):**
  - App ID: `ca-app-pub-9716395280477689~1970779488`
  - Uses Google's official Test Interstitial ID (`ca-app-pub-3940256099942544/1033173712`) in `BuildConfig.DEBUG` builds and the production Interstitial Ad Unit ID (`ca-app-pub-9716395280477689/4017609898`) in release builds, triggered with a 20s cooldown strictly on Settings item clicks and call hangup. (Banner Ad Unit is not configured.)

### Supabase + Cloudflare Backend (`backend/`)
- **Primary Database (`backend/supabase/migrations/`):** **Supabase PostgreSQL** (`0001_initial_schema.sql`, `0002_rls_and_storage.sql`, `0003_rpc_functions.sql`) with strict Row Level Security (RLS) denying direct `anon`/`authenticated` PostgREST access. Cloudflare D1 is **not** used.
- **Private Media Storage:** **Supabase Storage** private bucket `my-chatting-pro-media` (no Cloudflare R2 card/subscription required).
- **API Gateway:** Cloudflare Workers (`Hono` + `TypeScript`) validating requests, hashing PINs (`PBKDF2-SHA512`, 100k iterations + random 16-byte salt + HMAC server pepper), rotating JWT refresh tokens, rate limiting, and issuing authorized signed media URLs.
- **Realtime Coordinator:** Cloudflare Durable Objects (`UserRealtimeCoordinator` & `ConversationCoordinator`) for WebSocket Hibernation, online presence synced to Supabase, typing indicators, message delivery, reconnection replay, and WebRTC signaling.

---

## Setup & Deployment Guide

See **[backend/SUPABASE_CLOUDFLARE_SETUP.md](backend/SUPABASE_CLOUDFLARE_SETUP.md)** and **[backend/README.md](backend/README.md)** for complete step-by-step instructions (including which steps run in **Supabase Dashboard**, **Cloudflare Dashboard**, and your **PC Terminal**).

1. **Apply Supabase SQL Migrations:** Run `0001_initial_schema.sql`, `0002_rls_and_storage.sql`, and `0003_rpc_functions.sql` in Supabase SQL Editor.
2. **Promote Owner Account in Supabase:** Set `UPDATE public.users SET role = 'owner' WHERE phone_e164 = '+880...';` (or configure `OWNER_USER_IDS`).
3. **Configure Worker Secrets & Deploy:**
   ```bash
   cd backend
   npm install
   npx wrangler login
   npx wrangler secret put SUPABASE_URL
   npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY
   npx wrangler secret put JWT_ACCESS_SECRET
   npx wrangler secret put JWT_REFRESH_SECRET
   npx wrangler secret put PIN_PEPPER
   npx wrangler secret put MEDIA_SIGNING_SECRET
   npx wrangler secret put OWNER_MONITOR_KEY
   npx wrangler secret put OWNER_USER_IDS
   npm run deploy:prod
   ```
4. **Point Android App to Worker URL:** Set `CLOUDFLARE_API_BASE_URL` in `.env` / Secrets panel before building.

---

## Android Build & Testing Commands

> Note: Always run `gradle` (do not create `local.properties`).

- **Unit & Robolectric Tests:** `gradle :app:testDebugUnitTest`
- **Debug APK:** `gradle assembleDebug`
- **Release Play Store AAB:** `gradle bundleRelease`
- **Backend Typecheck & Tests (requires `npm install` in `backend/`):** `npm run typecheck && npm test`

---

## Free Plan Limits & Cost Truth

Backend infrastructure is **not** "100% unlimited free":
- **Supabase Free Plan:** Includes 500 MB PostgreSQL database, 1 GB Storage (`my-chatting-pro-media`), 5 GB cached/uncached egress limits per plan terms, and project pausing after 7 days of inactivity. Moving beyond those requires Supabase Pro ($25/mo).
- **Cloudflare Workers Free Plan:** Includes 100,000 Worker requests/day and basic testing limits. **Durable Objects WebSocket Hibernation (`UserRealtimeCoordinator`, `ConversationCoordinator`) requires the Cloudflare Workers Paid Plan ($5/month base) for production deployment.**
- **Cloudflare R2 / D1:** Neither is required in this architecture (no credit card for R2 needed).
