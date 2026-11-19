# MY CHATTING PRO — Backend Documentation (Supabase + Cloudflare)

**Developer / Owner:** `TAWHID SHAKH`  
**Stack:** Supabase PostgreSQL (Primary DB + RLS) + Supabase Storage (Private Bucket) + Cloudflare Workers (Hono Gateway) + Cloudflare Durable Objects (Realtime WebSockets)

---

## 1. Architecture Overview

```text
Android Kotlin App (HTTPS REST + Authenticated WSS)
        │
        ▼
Cloudflare Workers API Gateway (`src/index.ts`)
  ├── Auth, PIN PBKDF2-SHA512 + Pepper, JWT Rotation, Rate Limiting, Owner Monitor
  ├── Durable Objects (`UserRealtimeCoordinator`, `ConversationCoordinator`)
  │     └── WebSocket Hibernation, presence, typing, fanout, WebRTC signaling
  ├── Supabase PostgreSQL (`src/database/supabase.ts`) via Worker Secret `SUPABASE_SERVICE_ROLE_KEY`
  │     └── 16 normalized tables + RLS + atomic SECURITY DEFINER RPCs
  └── Supabase Private Storage (`src/storage/supabaseStorage.ts`, bucket: `my-chatting-pro-media`)
        └── Server-authorized uploads, private Range streaming, short-lived signed URLs
```

- **No Cloudflare D1 / No Cloudflare R2 required:** All user, session, chat, message, receipt, call, block, report, rate-limit, and audit records reside in **Supabase PostgreSQL**. Media files reside in **Supabase Private Storage** (`my-chatting-pro-media`).
- **Zero Secrets in APK:** `SUPABASE_SERVICE_ROLE_KEY`, `PIN_PEPPER`, `JWT_*`, `OWNER_MONITOR_KEY`, and `OWNER_USER_IDS` stay strictly inside Cloudflare Worker Secrets.

---

## 2. Quick Deployment Summary

For full Bengali + step-by-step instructions covering Supabase Dashboard, Cloudflare Dashboard, and PC Terminal commands, see **[SUPABASE_CLOUDFLARE_SETUP.md](SUPABASE_CLOUDFLARE_SETUP.md)**.

```bash
cd backend
npm install
npx wrangler login
npx wrangler secret put SUPABASE_URL --env production
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY --env production
npx wrangler secret put JWT_ACCESS_SECRET --env production
npx wrangler secret put JWT_REFRESH_SECRET --env production
npx wrangler secret put PIN_PEPPER --env production
npx wrangler secret put MEDIA_SIGNING_SECRET --env production
npx wrangler secret put OWNER_MONITOR_KEY --env production
npx wrangler secret put OWNER_USER_IDS --env production
npm run deploy:prod
```

---

## 3. Owner Monitor Authorization (`GET /v1/owner/activity`)

Protected by 5 server-side security layers:
1. Valid Bearer Access JWT & active Supabase session (`authMiddleware`).
2. `ip_and_user` sliding-window rate limiting (`consume_rate_limit` RPC).
3. **Server-Side Owner Identity Verification:** Requires `users.role = 'owner'` OR the authenticated `userId` present in `OWNER_USER_IDS`. Normal users receive `403 owner_access_denied` even if they know `X-Owner-Key`.
4. Constant-time SHA-256 verification of `X-Owner-Key` against `OWNER_MONITOR_KEY`.
5. Audit logging (`owner.activity_viewed` / `owner.activity_denied` / `owner.activity_forbidden_non_owner`), masked phone numbers, server-side search (`?q=`), and pagination (`?page=&limit=`).

---

## 4. Plan Limits & Honest Disclosures

| Component | Free Plan Limits | Production Requirement / Notes |
|---|---|---|
| **Supabase PostgreSQL** | 500 MB DB, shared CPU, pauses after 7 days inactivity | Free works for development/small MVP; Pro ($25/mo) removes inactivity pause & scales DB |
| **Supabase Storage** | 1 GB storage (private bucket `my-chatting-pro-media`) | Media limit enforced cleanly (10MB img / 15MB audio / 50MB video & doc); no R2 card needed |
| **Cloudflare Workers** | 100,000 requests/day, 10ms CPU/invocation | Workers Paid ($5/mo base) adds 10M req/mo |
| **Cloudflare Durable Objects** | Limited free prototyping limits | **Workers Paid Plan ($5/mo)** required for production WebSocket Hibernation / DO classes |
| **OTP & Recovery** | No SMS/Email OTP by design | Phone ownership is not SMS-verified; lost-PIN self-recovery unavailable |
| **Push Notifications** | No Firebase FCM | Relies on DO WebSocket + user-toggleable Android `RealtimeSyncService` or UnifiedPush |

---

## 5. REST & WebSocket Endpoints

- `GET /health` — Service & backend status
- `POST /v1/auth/check-phone`, `POST /v1/auth/register`, `POST /v1/auth/login`, `POST /v1/auth/refresh`, `POST /v1/auth/logout`
- `GET /v1/auth/sessions`, `DELETE /v1/auth/sessions/:sessionId`
- `GET /v1/users/search?phone=...&name=...`
- `GET /v1/profiles/me`, `PATCH /v1/profiles/me`, `POST /v1/profiles/account/delete`
- `GET /v1/conversations`, `POST /v1/conversations/direct`
- `GET /v1/messages/:conversationId`, `POST /v1/messages`, `POST /v1/messages/receipts`, `DELETE /v1/messages/:messageId`
- `POST /v1/media/upload`, `GET /v1/media/:mediaId/signed-url`, `GET /v1/media/:mediaId/content`
- `GET /v1/calls/ice-servers`, `POST /v1/calls`, `POST /v1/calls/:callId/state`
- `GET /v1/privacy/blocked`, `POST /v1/privacy/block`, `DELETE /v1/privacy/block/:targetUserId`
- `POST /v1/reports`
- `POST /v1/notifications/endpoints`
- `GET /v1/owner/activity?q=...&page=1&limit=20`
- `GET /v1/ws` — Authenticated WebSocket upgrade routed to `UserRealtimeCoordinator`
