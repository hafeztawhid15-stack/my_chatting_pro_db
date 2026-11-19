# MY CHATTING PRO — Supabase + Cloudflare Complete Setup Guide (Step-by-Step)

**Developer / Owner:** TAWHID SHAKH  
**Backend Architecture:** **Supabase (PostgreSQL + Private Storage)** + **Cloudflare Workers (API Gateway) + Durable Objects (Realtime WebSockets)**  
*(Cloudflare D1 ও Cloudflare R2 ব্যবহার করা হয়নি — কোনো কার্ড বা R2 ছাড়াই কাজ করবে)*

---

## গুরুত্বপূর্ণ প্রশ্নের উত্তর: "Terminal এ করবো নাকি Cloudflare/Supabase Dashboard এ করবো?"

দুই জায়গাতেই আলাদা আলাদা অংশের কাজ করতে হবে:
1. **Browser (Supabase Dashboard):** অ্যাকাউন্ট খোলা, PostgreSQL SQL Migration রান করা, Private Bucket তৈরি হয়েছে কি না দেখা, `SUPABASE_URL` ও `service_role` key কপি করা এবং আপনার নিজের অ্যাকাউন্টকে `owner` করা।
2. **Browser (Cloudflare Dashboard):** Workers Free / Paid plan চেক করা (আগে বানানো `my_chatting_pro_db` D1-টা আর লাগবে না — চাইলে রেখে দিতে বা ডিলিট করতে পারেন)।
3. **PC Terminal (VS Code / PowerShell / CMD):** `backend/` ফোল্ডারে কোড ডিপ্লয় করা (`npm install`, `wrangler login`, `wrangler secret put ...`, `npm run deploy:prod`)। কারণ **Cloudflare Durable Objects ও TypeScript/Hono কোড ব্রাউজারে কপি-পেস্ট করে সম্পূর্ণ সঠিকভাবে ডিপ্লয় করা যায় না** — Terminal দিয়ে `wrangler deploy` করাই সঠিক ও অফিসিয়াল নিয়ম।

---

## ধাপ ১: Supabase Setup (Browser — Supabase Dashboard)

1. [https://supabase.com](https://supabase.com)-এ যান ও নতুন একটি Project তৈরি করুন (যেমন নাম দিন: `my-chatting-pro`)।
   - একটি শক্তিশালী Database Password দিন এবং Region হিসেবে আপনার কাছাকাছি region (যেমন `ap-south-1` বা `ap-southeast-1`) বেছে নিন।
2. Project চালু হলে বাম দিক থেকে **SQL Editor** খুলুন এবং নিচের **৩টি SQL ফাইল একটার পর একটা** কপি করে **Run** করুন:
   1. `backend/supabase/migrations/0001_initial_schema.sql`
   2. `backend/supabase/migrations/0002_rls_and_storage.sql`
   3. `backend/supabase/migrations/0003_rpc_functions.sql`
3. যাচাই করুন:
   - বাম মেনু **Database -> Tables**: ১৬টি টেবিল (`users`, `user_profiles`, `sessions`, `devices`, `conversations`, `conversation_members`, `media_objects`, `messages`, `message_receipts`, `calls`, `call_participants`, `blocked_users`, `reports`, `notification_endpoints`, `rate_limits`, `audit_logs`) দেখা যাবে।
   - বাম মেনু **Storage -> Buckets**: `my-chatting-pro-media` নামে একটি **Private** bucket তৈরি হয়েছে।
4. **Credentials কপি করুন:**
   - বাম মেনু **Project Settings -> Data API / API Keys** থেকে কপি করুন:
     - **Project URL** (`https://xxxxx.supabase.co`) -> এটাই `SUPABASE_URL`
     - **`service_role` secret key** -> এটাই `SUPABASE_SERVICE_ROLE_KEY`
   - ⚠️ **সতর্কতা:** এই `service_role` key কখনো Android অ্যাপে (`.env` বা Kotlin কোডে) বসাবেন না! শুধু Cloudflare Worker Secrets-এ বসাবেন।

---

## ধাপ ২: PC Terminal থেকে Cloudflare Worker + Durable Objects Deploy

এই প্রোজেক্টটি ZIP হিসেবে ডাউনলোড করে আপনার PC-তে রাখুন, তারপর Terminal / PowerShell ওপেন করে `backend` ফোল্ডারে যান:

```bash
cd backend
npm install
npx wrangler login
```
*(ব্রাউজার ওপেন হবে — আপনার `Hafeztawhid15@gmail.com` Cloudflare অ্যাকাউন্ট দিয়ে **Allow** করুন)*।

এবার সব Worker Secrets সেট করুন (প্রতিটি কমান্ড দেওয়ার পর ভ্যালু পেস্ট করে Enter দিন):

```bash
npx wrangler secret put SUPABASE_URL --env production
npx wrangler secret put SUPABASE_SERVICE_ROLE_KEY --env production
npx wrangler secret put JWT_ACCESS_SECRET --env production
npx wrangler secret put JWT_REFRESH_SECRET --env production
npx wrangler secret put PIN_PEPPER --env production
npx wrangler secret put MEDIA_SIGNING_SECRET --env production
npx wrangler secret put OWNER_MONITOR_KEY --env production
```
> **Secret Generator Tip (PowerShell / Bash):** র‍্যান্ডম ৩২+ অক্ষরের সিক্রেট বানাতে চাইলে Node দিয়ে করতে পারেন:
> `node -e "console.log(require('crypto').randomBytes(32).toString('hex'))"`

তারপর ডিপ্লয় করুন:
```bash
npx wrangler deploy --env production
```

ডিপ্লয় শেষে Terminal-এ আপনার Worker URL দেখতে পাবেন:
`https://my-chatting-pro-api.hafeztawhid15.workers.dev`

Test করুন:
```bash
curl https://my-chatting-pro-api.hafeztawhid15.workers.dev/health
```

---

## ধাপ ৩: Owner Monitor (`অ্যাপ কে চালাচ্ছে দেখুন`) সেটআপ

নিরাপত্তার জন্য এখন শুধু `OWNER_MONITOR_KEY` জানলেই সাধারণ ইউজার Owner Monitor দেখতে পারবে না — সার্ভার যাচাই করবে ইউজারটি সত্যিই **Owner অ্যাকাউন্ট** কি না:

1. Android অ্যাপ ওপেন করে আপনার নিজের ফোন নম্বর দিয়ে **Create Account** করুন।
2. এবার **Supabase Dashboard -> SQL Editor**-এ গিয়ে নিচের SQL-টি আপনার ফোন নম্বর দিয়ে চালান:
   ```sql
   UPDATE public.users
   SET role = 'owner'
   WHERE phone_e164 = '+8801XXXXXXXXX';
   ```
   *(অথবা `users` টেবিল থেকে আপনার `id` UUID কপি করে Cloudflare-এ `npx wrangler secret put OWNER_USER_IDS --env production` সেট করতে পারেন)*।
3. এখন অ্যাপের **Settings -> অ্যাপ কে চালাচ্ছে দেখুন**-এ গিয়ে আপনার `OWNER_MONITOR_KEY` দিয়ে আনলক করুন। সার্চ ও Load More সব কাজ করবে।

---

## ধাপ ৪: Free Plan বনাম Paid Plan (সত্য তথ্য ও সীমা)

| সার্ভিস | Free Plan-এ কী কী পাবেন | বাস্তব সীমা ও Paid Plan কখন লাগবে |
|---|---|---|
| **Supabase PostgreSQL** | 500 MB Database, RLS, RPC Functions | ৭ দিন কোনো ট্রাফিক না থাকলে Free প্রোজেক্ট Pause হয়। ইউজার বাড়লে Supabase Pro ($25/মাস) দরকার হবে। |
| **Supabase Storage** | 1 GB Private Storage (কোনো কার্ড লাগবে না) | R2-এর মতো কার্ড লাগে না। ১ GB পূর্ণ হলে বা বেশি Bandwidth হলে Pro লাগবে। |
| **Cloudflare Workers** | দিনে ১,০০,০০০ Requests ফ্রি | সাধারণ ব্যবহারে যথেষ্ট। বড় স্কেলে Paid ($5/মাস)। |
| **Cloudflare Durable Objects** | — | **গুরুত্বপূর্ণ:** `UserRealtimeCoordinator` ও `ConversationCoordinator` (WebSocket Hibernation) Production-এ চালানোর জন্য Cloudflare **Workers Paid Plan ($5/মাস)** প্রয়োজন হয়। |
| **Cloudflare D1 / R2** | প্রয়োজন নেই | নতুন আর্কিটেকচারে D1 বা R2 ব্যবহার করা হয়নি। |
| **WebRTC TURN Server** | STUN ফ্রি (`stun.cloudflare.com`) | একই Wi-Fi বা সাধারণ নেটওয়ার্কে STUN কাজ করে; কঠিন মোবাইল ক্যারিয়ার NAT-এ অডিও/ভিডিও কলের জন্য Cloudflare Calls TURN বা Metered/Coturn secret সেট করা উচিত। |

---

## ধাপ ৫: Android App কানেক্ট করা

1. প্রোজেক্টের রুট `.env` বা AI Studio Secrets-এ নিশ্চিত করুন:
   ```env
   CLOUDFLARE_API_BASE_URL=https://my-chatting-pro-api.hafeztawhid15.workers.dev
   ```
2. Android APK / Play Store AAB তৈরি করতে:
   ```bash
   gradle assembleDebug
   gradle bundleRelease
   ```
