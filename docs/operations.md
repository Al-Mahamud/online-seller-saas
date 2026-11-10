# Operations

How to run Mojud once it is on a server (implementation-plan.md 13c). Setting up a server, and the full steps for updating and restoring, are in [deploy/README.md](../deploy/README.md). This page is the day-to-day runbook. Commands run in the folder that holds `compose.yml` on the server.

## At a glance

| What | Where |
|---|---|
| Is it up? | `https://<APP_DOMAIN>/healthz` answers `{"status":"UP"}`. The Uptime workflow and any external monitor watch it. |
| Errors | Sentry (with `SENTRY_DSN` set). Otherwise `docker compose logs --since 1h app`. |
| Backups | Nightly at 02:30 Dhaka to the `mojud-backups` R2 bucket. `docker compose logs backup` shows each night. |
| Updates | `docker compose pull app caddy && docker compose up -d app caddy` |
| Restore drill | GitHub Actions, "Deploy check". It runs monthly, plus every change. |
| Known vulnerabilities | GitHub Actions, "Dependencies" (weekly), and Dependabot pull requests. |

## Deploying and updating

- **Every push to `main`:** if CI passes, new images are published (`ghcr.io/al-mahamud/mojud-backend` and `mojud-web`, each tagged with the commit and `latest`).
- **Updating the server:**
  ```sh
  docker compose exec backup backup.sh          # a backup first, if the update changes the database
  docker compose pull app caddy && docker compose up -d app caddy
  docker compose logs -f app                    # "Started MojudApplication"; database changes apply on start
  ```
- **Choosing a version:** pin one with `APP_IMAGE=ghcr.io/al-mahamud/mojud-backend:<commit>` and `WEB_IMAGE=ghcr.io/al-mahamud/mojud-web:<commit>` in `.env`.
- **Going back:**
  - Without a database change in between, pin the previous commit.
  - With one, restore the backup taken just before.

## Backups and restoring

- **Setup and full steps:** see deploy/README.md, "Backups" and "Restoring".
- **What to remember:**
  - **A restore never overwrites the live database.** It restores into a new one, and you switch with the app stopped.
  - **A restore needs two things that are not on the server:**
    - the backup key (`mojud-backup-key.txt`);
    - the `ENCRYPTION_KEY` that was current when the backup was taken. Keep old ones while backups made with them still exist (30 days).
  - **Images are not in the backups.** They are in the `mojud-files` R2 bucket, which Cloudflare keeps.
- **Monthly:** check that the "Deploy check" workflow's `latest-backup` job passed. It restores the newest real backup. Until its secrets are set, restore one by hand instead.

## Background jobs

They run inside the app (at most one instance at a time), in Dhaka time.

| Job | When | What |
|---|---|---|
| stock-reconciliation | 02:30 | Every cached stock level must equal its ledger. A difference is logged as an error (Sentry). Nothing is changed. |
| idempotency-cleanup | 03:00 | Forgets old idempotency keys. |
| business-deletion | 03:30 | Deletes businesses whose 7-day undo window has passed, with their files. |
| low-stock-digest | 08:00 | Emails managers and owners what is running low. |
| subscription-lifecycle | 09:00 | Trials and plans move along; reminders. |
| data-deletion | 09:30 | Businesses read-only for 90 days. **Off** (`mojud.billing.data-deletion.enabled`) until the warnings' wording is approved. |
| import-sweeper | Every minute | Picks up and resumes product imports. |
| pending-payments | Every 15 minutes | Asks SSLCommerz about payments whose callback never came (and whose browser didn't come back either; see docs/plans-and-payment.md, "sandbox"). |
| backup (separate container) | 02:30 (`BACKUP_AT=20:30` UTC) | Encrypted database backup to R2. |

## Rotating secrets

Rotate a secret when someone who knew it leaves, when it may have leaked, and once a year anyway. Each one lives in `.env` on the server, and nowhere else except where noted.

| Secret | How to rotate | Effect |
|---|---|---|
| `DB_APP_PASSWORD`, `DB_SYSTEM_PASSWORD`, `DB_OWNER_PASSWORD` | **1.** Generate: `openssl rand -hex 24`. **2.** Set it: `docker compose exec db psql -U postgres -c "ALTER ROLE mojud_app PASSWORD '<new>'"` (`mojud_system` or `mojud_owner` for the others). **3.** Change `.env`. **4.** `docker compose up -d app`. | A few seconds of errors while the app restarts. |
| `POSTGRES_PASSWORD` (superuser, used by backups) | `ALTER ROLE postgres PASSWORD '<new>'` as above, then change `.env` and run `docker compose up -d backup`. | None. |
| `ENCRYPTION_KEY` | **1.** Generate a new one (`openssl rand -base64 32`). **2.** Move the current one to `ENCRYPTION_KEY_PREVIOUS` and put the new one in `ENCRYPTION_KEY`. **3.** `docker compose up -d app`. The log says "Re-sealed N authenticator secret(s)". **4.** Empty `ENCRYPTION_KEY_PREVIOUS` and run `docker compose up -d app` again. **5.** Keep the old key with the backup key for 30 days: backups from before need it. | None for users. |
| Backup key (`AGE_RECIPIENT`) | **1.** On your computer: `age-keygen -o mojud-backup-key-<year>.txt`. **2.** Put its public key in `AGE_RECIPIENT`. **3.** `docker compose up -d backup`. **4.** Keep the old private key for 30 days, since older backups need it. **5.** If the restore drill is set up, update the `BACKUP_AGE_IDENTITY` GitHub secret. | New backups use the new key. |
| R2 tokens (`S3_*`, `BACKUP_S3_*`) | **1.** Cloudflare R2: create a new token for the same bucket. **2.** Change `.env`. **3.** `docker compose up -d app backup`. **4.** Delete the old token. Also update the drill's `BACKUP_S3_*` GitHub secrets if set. | None. |
| `SMTP_PASSWORD`, `SSLCOMMERZ_STORE_PASSWORD`, `SENTRY_DSN` | **1.** New value from the provider. **2.** Change `.env`. **3.** `docker compose up -d app`. **4.** Revoke the old value at the provider. | None. |
| Image pull token (GitHub, `read:packages`) | **1.** Create a new token. **2.** `docker login ghcr.io -u Al-Mahamud`. **3.** Delete the old token on GitHub. | None. |

**Session cookies:** there's no key to rotate. Session ids are random and live in the database. To sign everyone out, see below.

## When something is wrong

**The app does not start.** `docker compose logs app`:
- `Unsafe production settings: ...` lists every missing or unsafe value in `.env`.
- Database errors usually mean the `db` container is not healthy: run `docker compose ps`.

**A seller sends an error with a code** ("কোড: 261110222032-9ed7970"):
- Every API request gets an ID that starts with its Dhaka time: `261110222032` is Nov 10, 2026, 22:20:32. The app shows it under a server error's message and on the error page. It is also the `X-Request-Id` header and the `requestId` of every error body.
- Find that request's lines, its `[CLIENT-REQ]` and `[CLIENT-RES]` and any `EXCEPTION:` between: `docker compose logs --since 24h app | grep 261110222032-9ed7970`. Older ones are in the hourly files (docs/logging.md): `docker compose exec app sh -c 'grep -h 261110222032-9ed7970 /data/logs/mojud-2026111022-*.log'`.
- In Sentry, search `requestId:261110222032-9ed7970`.
- What the request started in the background (an SMS, an email, an import) logs with the same ID.
- A well-formed ID sent by a proxy in `X-Request-Id` is kept, so the proxy's log and the app's match.
- Each line shows the ID as `T[…]` and the signed-in user as `U[…]` (docs/logging.md).

**Errors in Sentry.**
- Each event has the request path, the request's ID (tag `requestId`) and the error, but no customer details.
- "Stock cache differs from the ledger" is a bug, not bad luck. Keep the event and report it. Do not edit stock by hand.

**A night's backup is missing.**
- Check `docker compose logs backup`. Then run `docker compose exec backup backup.sh` to see the error.
- Usually it's an expired R2 token or a full bucket.

**Suspected break-in (a leaked password, a stolen laptop, strange admin activity):**
1. **Look at what admins did:**
   ```sh
   docker compose exec db psql -U postgres -d mojud -c "select created_at, admin_id, action, target_business_id from admin_audit order by created_at desc limit 50"
   ```
2. **Take away someone's admin rights:**
   ```sh
   docker compose exec db psql -U postgres -d mojud -c "update users set is_platform_admin = false where email = '<email>'"
   ```
3. **Block an account:** set `status = 'DISABLED'` on the user. Their sign-ins and codes stop.
4. **Sign everyone out:**
   ```sh
   docker compose exec db psql -U postgres -d mojud -c "delete from spring_session"
   ```
5. **Rotate every secret** that could have been seen (table above), starting with the database passwords and `ENCRYPTION_KEY`.
6. **Tell affected sellers** if their data may have been read. Bangladesh's data protection rules may set a deadline: ask the lawyer.

## Routine

| How often | What |
|---|---|
| Weekly | Merge Dependabot pull requests whose CI passes. Check the "Dependencies" run: a known vulnerability fails it and names the fixed version. |
| Monthly | Check the restore drill passed (or restore one by hand). Read Sentry's top errors. Check the server's disk: `df -h`. |
| Yearly | Rotate the secrets above. Renew the domain. |

## Load test

- **How to run it:**
  ```sh
  ./mvnw test -Dtest=OrderLoadTests -Dload.minutes=10 -Dload.staff=50
  ```
  50 staff create and confirm orders on six shared variants for 10 minutes. The latest results are in [load-test-report.md](load-test-report.md).
- **Where:** it starts its own app and a throwaway database, so it never touches a server. Run it on a machine the size of the server before big changes to orders or stock, for numbers that match production.
