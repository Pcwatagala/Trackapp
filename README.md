# SMS Expenses (Android)

Reads bank SMS on your phone and shows, per month:
- total **Spent** and **Received** (per currency: LKR, USD…)
- **Top spending** places
- every transaction (tap = see the original SMS, long-press = hide that sender)

Works offline. The app has **no internet permission** — your SMS never leave the phone.
Parsing is wording-based, so it handles most Sri Lankan banks and card alerts
(BOC, Commercial, Sampath, HNB, NDB, People's, Seylan, DFCC, NTB…).

## Get the APK — option A: GitHub (no install needed, ~5 min)
1. Create a new repository on github.com (private is fine).
2. Upload everything in this folder (keep the `.github` folder!).
3. Open the **Actions** tab → "Build APK" runs automatically (or press *Run workflow*).
4. When it's green, open the run → **Artifacts** → download `SmsExpenses-apk` → unzip → `app-debug.apk`.

## Option B: Android Studio
Open this folder in Android Studio → let it sync → *Build ▸ Build APK(s)*.

## Installing on the phone
1. Copy `app-debug.apk` to the phone and open it; allow "Install unknown apps" when asked.
2. Open the app → **Allow SMS access**.
3. On Android 13+ the permission may be blocked for side-loaded apps. Then:
   Settings → Apps → SMS Expenses → ⋮ (top-right) → **Allow restricted settings** →
   Permissions → SMS → Allow.

## Tweaking the parser
All rules are in `app/src/main/java/lk/smsexpense/SmsParser.kt`
(keywords for debit/credit, words that mark a message to skip such as OTPs).
If a promo sender sneaks in, just long-press it in the app and choose **Hide**.
