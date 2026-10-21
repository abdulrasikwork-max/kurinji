# Timestamps and time zones (UTC convention)

Written 2026-10-20 (code fixed and existing rows corrected and verified the same day). Status words as in `CLAUDE.md` §3. Resolves the "raw DB timestamps are 5 h 30 m behind UTC" item
found in `PHASE4C.md` §8.1 and listed in `HANDOFF.md`.

## 1. Verdict

It was **a real configuration bug** (not a display issue, not an intentional convention): every `DATETIME(6)` written by
the backend on a PC in India was stored 5 h 30 m behind true UTC. The API looked correct because the same error is
undone when the value is read back, so nothing in the app, the tests or the API ever showed it. Anything reading the
database directly (reports, a future evidence timeline, a server job, a court-facing export) would have been wrong.

## 2. Root cause (reproduced on real MySQL 8.0.46, not assumed)

1. Entities hold **UTC wall-clock** values in `LocalDateTime` fields (`LocalDateTime.now(ZoneOffset.UTC)`), as intended.
2. For a `LocalDateTime` Hibernate 6.6 builds a `java.sql.Timestamp` with `Timestamp.valueOf(...)`, which interprets the
   wall clock in the **JVM's default zone**. On the developer PC that zone is Asia/Kolkata (UTC+05:30), so the instant is
   5 h 30 m *earlier* than the UTC wall clock meant.
3. Connector/J then formats that instant in the connection zone (`serverTimezone=UTC`) → the stored text is
   5 h 30 m behind: `12:00:00 (UTC meant)` is stored as `06:30:00`.
4. Reading reverses both steps, so the Java value round-trips exactly. The bug is invisible through the API.

Probe (`setTimestamp(Timestamp.valueOf(2026-10-19 12:00:00.123456))`, real MySQL, throw-away TEMPORARY table):

| JVM zone | URL option | stored text | read back |
|---|---|---|---|
| Asia/Kolkata | `serverTimezone=UTC` (old) | `06:30:00.123456` ✗ | `12:00` (hides the bug) |
| UTC | `serverTimezone=UTC` | `12:00:00.123456` ✓ | `12:00` |
| Asia/Kolkata | `connectionTimeZone=LOCAL` | `12:00:00.123456` (but only because it stores *local* time; shifts if the zone ever changes) | `12:00` |

`hibernate.jdbc.time_zone=UTC` was also tried and does **not** help for `LocalDateTime` (still `06:30` with an IST JVM),
so it was not adopted.

## 3. What changed

| Change | Why |
|---|---|
| `UtcTimeZone.install()` called first in `main()` | The real fix: with a UTC default zone `Timestamp.valueOf` and the driver agree, whatever PC runs the backend. A server on a UTC host was never affected. |
| JDBC URL `connectionTimeZone=UTC&forceConnectionTimeZoneToSession=true` (replaces `serverTimezone=UTC`) | Explicit, current Connector/J option names; the MySQL session also runs in UTC (`NOW()` etc. agree). `.env.example` updated. |
| `TimestampStorageVerifier` (start-up self-check, `app.time.verify-storage=true`) | Sends a known UTC value through the same Hibernate binding and compares the text the database received. The backend **refuses to start** if it is shifted (wrong `DB_URL`, driver upgrade, different Hibernate behaviour). Read-only. Passes on real MySQL (seen in the start-up log). |
| Entities truncate "now" to microseconds (`EmergencyEvent`, `User`, `SafetyContact`) | `DATETIME(6)` keeps 6 digits; the SOS start response used to carry nanoseconds (`…624354100`) that differed from the value stored/returned later (`…624354`). One precise value everywhere matters for evidence ordering. |
| Surefire runs tests with `-Duser.timezone=Asia/Kolkata` | Tests are deliberately **not** in UTC, so code that depends on the machine's zone fails on every machine instead of hiding on UTC CI. |
| Log lines are now UTC (`…23:07:59.060Z`) | Side effect of the UTC default zone: log chronology matches the database and API. |
| `backend/sql/fix_utc_offset_ist.sql` + backup | One-time correction of existing rows (section 5). |

No schema migration, no API change, no Android change. Phone and API already used ISO-8601 UTC (`…Z`).

## 4. Regression protection (all in `backend/src/test`)

| Test | Protects |
|---|---|
| `config/MySqlTimestampStorageTest` (opt-in, real MySQL) | With the shipped URL + `UtcTimeZone.install()` on an IST JVM the stored text equals the UTC value; a second test reproduces the **old** configuration and asserts the `06:30` shift, so the test provably catches the bug. Run: `SAFESPHERE_MYSQL_TESTS=true`, `DB_PASSWORD`, `mvn -Dtest=MySqlTimestampStorageTest test`. Uses a TEMPORARY table only. |
| `config/TimestampStorageVerifierTest` | Passes on the right value, throws (with both values in the message) on the 5 h 30 m shifted one, does nothing when switched off. |
| `config/TimeConventionsTest` | Source guard: no `LocalDateTime.now()` without `ZoneOffset.UTC`/`clock`, no `ZoneId.systemDefault`, `TimeZone.getDefault`, `new Date()`, `Timestamp.valueOf`, `Calendar.getInstance()` in `src/main`; migrations use `DATETIME(6)` (never `TIMESTAMP`/bare `DATETIME`); the suite really runs in a non-UTC zone; `install()` sets UTC. |
| `emergency/TimestampApiIntegrationTest` | API timestamps are ISO-8601 with `Z` and within seconds of real UTC; the raw column text equals the UTC wall clock the API reports (and is ≥ 5 h away from the machine's local time); `endedAt ≥ startedAt`; error-body `timestamp` is UTC. |

H2 cannot show the original bug (it does no second conversion), which is why the real-MySQL test exists.

## 5. Existing rows: corrected (2026-10-20)

All rows written before the fix came from this PC's backend, so every time column was 5 h 30 m early (users 2,
emergency_events 2, emergency_locations 1, safe_journeys 13, journey_events 36, escalation_events 20; no safety contacts,
no circle alerts, no ACTIVE walk or emergency). They were shifted forward by 330 minutes with
`backend/sql/fix_utc_offset_ist.sql`, **run by the user** (Claude's own run was declined by the permission system and
was not worked around). The first manual attempt failed with MySQL ERROR 1045: the account and `.env` password were
fine (verified on every connection path, nothing exposed); the password typed at the `-p` prompt was not `DB_PASSWORD`.
The second attempt loaded the password into `MYSQL_PWD` from `.env` and succeeded.

Prepared before the run: a full backup `backend/sql/backups/safesphere-before-utc-fix-2026-10-20.sql` (contains personal
data; keep it out of any repository; delete it when no longer needed; **its restore was never tested**), the script
(one transaction, refuses a second run), and a dry run with ROLLBACK on the real database.

Verification after the run (read-only; the live rows were compared with the rows parsed from the backup):

| Check | Result |
|---|---|
| Rows compared | **74** (users 2, emergency_events 2, emergency_locations 1, safe_journeys 13, journey_events 36, escalation_events 20) |
| Time values (non-NULL) shifted by exactly +330 min | **125 of 125** (4 + 8 + 2 + 55 + 36 + 20); 0 wrong |
| Non-time cells changed | 0 |
| NULLs kept NULL; `flyway_schema_history` | unchanged (8 rows, latest v8) |
| Walks 16 / 17 / 19 | start 20:05:03 / 20:28:41 / 21:12:41 UTC, end 20:22:53 / 20:34:26 / 21:56:48: identical to the times recorded in `PHASE4C.md` |
| Chronology | 0 emergencies/walks ending before they start; 0 journey events or escalations before their walk started; 0 events after a walk ended; 0 event times in the future |
| Location 16 vs emergency 9 | its fix is 3.28 s *before* the SOS was recorded: unchanged by the shift and allowed by design (server clock tolerance 5 min) |
| Second run | the script's guard evaluates to REFUSE (newest event 21:56:48 UTC + 330 min = 03:26 the next day, which is later than now + 5 min) |

The guard only works while the newest row is less than about 5 h 25 m old; never run the script again.

## 6. Verification record (2026-10-20)

| Check | Result |
|---|---|
| Root cause probe on real MySQL (JVM IST/UTC × URL variants) | reproduced; table in section 2 |
| `mvn test` (default) | 121 tests, 0 failures, 2 skipped (the opt-in MySQL tests) |
| `mvn test` with `SAFESPHERE_MYSQL_TESTS=true` | 121 run, 0 failures, 0 skipped |
| `mvn -DskipTests package` | OK |
| Fixed backend on real MySQL (Flyway at v8, no migration) | "Timestamp storage check passed"; log times in UTC |
| Live throw-away SOS + location + resolve through the API | API `startedAt 23:08:19.325319Z`, raw DB `2026-10-19 23:08:19.325319` while the PC clock read 04:38 IST ⇒ stored true UTC; user deleted afterwards (users back to 2) |
| Dry run of the old-data correction (ROLLBACK) | consistent with documented times; data unchanged afterwards |
| Old-data correction applied (by the user) | **done and verified**: 74 rows, 125 of 125 time values +330 min, section 5 |
| Backend after the correction | **not started yet** by the user's run at the time of this check; stopped (port 8080 free). First start should log "Timestamp storage check passed" |
| Android | not touched, not re-run (no change needed: it already uses ISO-8601 `Instant`) |

## 7. Limitations and notes

- All existing rows are now true UTC and consistent with new rows. The script is a one-time tool for this database
  only; keep it as a record but do not run it again (a fresh or UTC-host database never needs it).
- The cell-by-cell comparison used the backup dump as the "before" state; the backup itself was not restored into a
  scratch database to prove it restores. The comparison does prove the dump held the pre-correction values.
- Not yet seen after the correction: the fixed backend serving the corrected rows through the API (the earlier live
  check used a throw-away user). The Android app and phone were not re-run.
- Entities `EmergencyEvent`/`User`/`SafetyContact` still take "now" from the system clock rather than the injectable
  `Clock` bean used by the services (always UTC, now microsecond-exact). Unifying them is possible later; not needed for
  correctness.
- `ApiError.timestamp` uses `Instant.now()` (zone-free, fine).
- `flyway_schema_history.installed_on` is a MySQL `TIMESTAMP` managed by Flyway; it shows server-local time. Not ours.
- A database written by a UTC host (e.g. a future cloud server) needs no correction; never run the script on it.
- Phase 5 rule: store evidence times as `DATETIME(6)` UTC, take the server time from the `Clock` bean, and keep the
  phone's capture time as a separate, clearly labelled column (the pattern of `recorded_at` vs `received_at`).

## 8. Viva notes

- *Why did the API hide the bug?* The error is applied once on write and reversed on read; a round trip is lossless.
- *Why not `hibernate.jdbc.time_zone`?* Tested: it does not change how `LocalDateTime` becomes a `Timestamp`.
- *Why not `Instant`/`OffsetDateTime` columns?* A large migration of entities, DTOs and tests for no extra correctness once
  the default zone is UTC; the start-up check and tests now guard the convention.
- *Why run tests in IST?* To make zone assumptions fail everywhere, not only on a machine whose zone differs from UTC.
