# Release privacy and Google Play material — DRAFT (Phase 17J, 2026-11-08)

> **This is an engineering draft, not legal advice and not a published policy.** It describes what the code does today so a
> privacy policy and the Play Console forms can be written from facts. It must be reviewed by the owner (and ideally a
> lawyer familiar with India's DPDP Act 2023 and Play policy), hosted at a public URL, and re-checked against the code
> before every release. Wording marked **[OWNER]** needs a decision or a real value.

## 1. Data the app handles (from the code, not from intent)

| Data | Where it goes | Kept | Notes |
|---|---|---|---|
| Name, e-mail, password | Server (`users`); password only as a BCrypt hash | Until the account is deleted | Sign-in identity |
| Phone number (own) | Server; verified by a texted code | Until deletion | Codes are stored only as an HMAC, never logged |
| Date of birth | **Not stored** — checked on the phone/server for 18+ and discarded | — | D6 |
| Profile: handle, bio, city, interests, photo | Server | Until deletion | Visible per the chosen audience |
| Experiences, perspectives (comments), photos, videos | Server | Until deleted by the person / account deletion | Metadata stripped on the phone and again on the server; places as map cells, times as bands |
| **Safety Circle contacts (other people's names and phone numbers)** | Server (`safety_contacts`) | Until the person removes them / account deletion | **Third-party personal data**: the person adds people who did not sign up. The policy must say so. Only name, time, type and a location link reach the Circle in an alert. |
| Precise location during an SOS or Safe Walk | Server (emergency / journey records); sent to the Circle as a link by SMS | SOS points: no automatic deletion rule was found in the code (kept until the account is deleted) - **to confirm and decide a retention period**; a walk keeps only the last known position | Only while the person starts it; foreground service with a visible notification; no background location permission |
| Approximate/precise location for Nearby, place check, Explore | Phone → server as **cells only**, never coordinates for social features | Nearby signals expire | Private places never leave the phone |
| Audio recorded during an SOS (opt-in) and evidence photos/video | Encrypted **on the phone** per item; the server stores ciphertext only | Until the person deletes it (password; refused during/24 h after an emergency) | Recovery escrow only if the operator sets a vault public key |
| Moderation: reports, content assessments, standing | Server | Report/case retention 90 d, audit 365 d, assessments 90 d | The reported person is never told who reported |
| Device sessions (rotating refresh secret hash, timestamps) | Server | 30 days after end | No device identifier, no IP stored |
| Rate-limit counters | **Memory only**, no IP or e-mail stored | Seconds to hours | |
| Typed Explore request | Words understood **on the phone**; only a structured query is sent. Optional AI understanding (default OFF, per-account consent) sends the typed text to the operator's Python service and from there to a model provider | Not stored | **[OWNER]** If AI understanding is switched on in production, the policy must name the provider and the data sent |
| Analytics, ads, crash reporting, advertising ID | **None** | — | No such SDK is present |

Third-party code in the app: AndroidX/Material, Retrofit/OkHttp/Gson, Coil, Google Play services **Location** (fused provider).
No Firebase, no analytics, no ad SDK.

## 2. Google Play "Data safety" form — proposed answers

Data **collected** (shared = No for all, unless **[OWNER]** enables the AI provider; "processed ephemerally" does not apply to server storage):

| Play category | Type | Collected | Shared | Required | Purpose |
|---|---|---|---|---|---|
| Personal info | Name, E-mail, Phone number, User IDs | Yes | No | Required | Account management, App functionality |
| Personal info | Other (Safety Circle contacts' name and number) | Yes | No | Optional | App functionality |
| Location | Approximate and Precise | Yes | No (the Circle receives a link by SMS **sent by the person's phone**: say so in the policy) | Optional | App functionality |
| Photos and videos | Photos, Videos | Yes | No | Optional | App functionality |
| Audio | Voice or sound recordings | Yes (encrypted) | No | Optional | App functionality |
| Messages | Other in-app messages (perspectives) | Yes | No | Optional | App functionality |
| App activity | Other user-generated content | Yes | No | Optional | App functionality |
| Financial, Health, Contacts (address book), Web browsing, Device IDs, Diagnostics | — | **No** (the app never reads the address book) | — | — | — |

Security practices: data **encrypted in transit** = Yes (only once production HTTPS exists, see PHASE17J §5); users **can request
deletion** = Yes (in-app, 7-day grace) **plus a web link [OWNER]**; independent security review = No.

## 3. Play Console declarations needed

1. **SMS permission (`SEND_SMS`)** — Permissions Declaration, exception "physical safety / emergency alerts". Asked only when the
   person switches on automatic SMS; otherwise the SMS app opens. Be ready with a demo video of that screen.
2. **Foreground service types** `location` and `microphone` — declare the user-initiated SOS / Safe Walk / evidence recording.
3. **`USE_BIOMETRIC`** — normal permission, no form.
4. **Target API 36** — required (see PHASE17J §5). Not met today (35).
5. **Content rating questionnaire** and **target audience 18+**; user-generated content declaration (reporting + blocking exist).
6. **Account deletion** — in-app path and the web resource URL **[OWNER]**.
7. Government/emergency claims: the app **does not** integrate with emergency services; "Call 112" opens the dialer. The store
   text must not imply otherwise, nor that anyone is notified automatically unless the person turned on automatic SMS.

## 4. Privacy policy outline (to be written and hosted)

Who we are **[OWNER: legal entity, contact, grievance officer for DPDP]** · what we collect (§1) · why · who sees it (audiences;
Safety Circle) · what is never collected (no address book, no ads/analytics, no tracking of other people) · third parties
(SMS provider **[OWNER]**, hosting **[OWNER]**, optional AI provider) · location (when, how, cells vs precise) · audio and
evidence (encrypted on the phone) · retention and deletion (§1; 7-day grace; evidence deletion guard) · children (18+ only) ·
security · your rights (access, correction, deletion) · changes · contact.

## 5. Facts the policy must not overstate

SMS "sent" is not "delivered"; the app cannot guarantee a phone stays alive during a walk (OEM battery killers);
"Nearby" signals are community reports, never a statement that a place is safe; backups are disabled; the 24 h access token and
refresh secret are sealed with the phone's Keystore (since 17J) and are never written to disk in plain text: if the Keystore
refuses, they live in memory only and the person signs in again after a restart (J-8).
