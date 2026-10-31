# Deployment

One server with Docker runs everything (implementation-plan.md 13b):

| Service | What it does |
|---|---|
| `db` | PostgreSQL 18. Its data lives in the `db-data` volume. No port is open to the internet. |
| `app` | The Mojud backend image, built by CI from `main` (`ghcr.io/al-mahamud/mojud-backend`). |
| `caddy` | The web app's image (`ghcr.io/al-mahamud/mojud-web`: Caddy plus the built files, by CI from `frontend/`). HTTPS for `APP_DOMAIN`: gets and renews the certificate, sends `/api` and `/healthz` to the app, and serves the web app with its own security headers. |
| `backup` | Every night, an encrypted copy of the database goes to a Cloudflare R2 bucket. |

Uploaded images go to their own private R2 bucket, so the server's disk holds nothing that the backups miss. Losing the server loses nothing that cannot be restored.

The whole setup, backup and restore included, runs on every change in CI (`.github/workflows/deploy.yml`). So these steps are known to work, apart from the parts that need real accounts (domain, R2, email).

## What you need first

- **A server:** Ubuntu 24.04 with 2 GB of memory or more, in Singapore or Mumbai for speed from Bangladesh.
- **A domain** (`app.example.com`): its DNS A record pointing to the server.
- **Cloudflare R2:** two private buckets, `mojud-files` (images) and `mojud-backups`, each with its own API token limited to that bucket (Object Read & Write).
- **An email provider's SMTP details** (Brevo, Amazon SES, ...), with the sending domain verified.
- **Optional:**
  - a Sentry project (errors);
  - a healthchecks.io check (missed backups);
  - an uptime monitor.

## Setting up the server

1. **Install Docker**, then copy this `deploy/` folder to the server (for example to `/opt/mojud`):
   ```sh
   curl -fsSL https://get.docker.com | sh
   ```
2. **Make the backup key on your own computer, not the server.** The server only gets the public half, so a stolen server cannot read its own backups. Keep `mojud-backup-key.txt` offline in two places, such as a password manager and a USB stick. Without it, backups cannot be read.
   ```sh
   age-keygen -o mojud-backup-key.txt     # prints: Public key: age1...
   ```
3. **Create the settings file**, then fill in every value. The comments in `.env.example` say how to generate each password and key.
   ```sh
   cp .env.example .env && chmod 600 .env
   ```
   - Put the backup key's public half (`age1...`) in `AGE_RECIPIENT`.
   - Keep a copy of `ENCRYPTION_KEY` with the backup key. A restored database needs it for two-step sign-in.
4. **Let the server pull the images.** They are private, like the repository. Use a GitHub token with only `read:packages`:
   ```sh
   echo <token> | docker login ghcr.io -u Al-Mahamud --password-stdin
   ```
5. **Start:**
   ```sh
   docker compose up -d --build
   docker compose logs -f app      # wait for "Started MojudApplication"
   curl https://app.example.com/healthz   # {"status":"UP",...}
   ```
   **If the app stops at once,** its log lists every missing or unsafe setting (`Unsafe production settings: ...`). Fix `.env` and run `docker compose up -d` again.

   **Warnings are expected until those accounts exist:** no email provider, no SSLCommerz, no Sentry.
6. **Firewall:** allow only SSH, 80 and 443.
   ```sh
   ufw allow OpenSSH && ufw allow 80 && ufw allow 443 && ufw enable
   ```

## Updating

Every push to `main` that passes CI publishes new images (backend and web app), tagged `latest` and with their commit.

```sh
docker compose pull app caddy && docker compose up -d app caddy
```

- **Database changes:** they apply on start (Flyway).
- **Short downtime:** requests in progress get 25 seconds to finish.
- **Choosing a version:** to pin one, set `APP_IMAGE=ghcr.io/al-mahamud/mojud-backend:<commit>` and `WEB_IMAGE=ghcr.io/al-mahamud/mojud-web:<commit>`, usually the same commit.
- **The web app updates at once:** browsers check `index.html` on every visit; the files it names are new names, cached for a year.
- **Going back to an older version:** this works only if no database change came in between. Otherwise restore the backup taken before the update.

## Backups

- **When:** nightly at `BACKUP_AT`, in UTC (20:30 UTC is 02:30 in Dhaka).
- **What:** `pg_dump`, encrypted with `age` to `AGE_RECIPIENT`, uploaded with `rclone` to `BACKUP_REMOTE` as `mojud-<time>.dump.age`.
- **How long they're kept:** backups older than `BACKUP_KEEP_DAYS` are removed.
- **A missed night:** with `BACKUP_PING_URL` set, the monitor alerts you when a night's backup does not arrive.

```sh
docker compose exec backup backup.sh                       # one now, before anything risky
docker compose exec backup rclone lsl r2:mojud-backups/db  # list them
docker compose logs backup                                 # what happened at night
```

## Restoring

A restore always goes into a **new** database. The live one is left alone until you switch.

1. **Copy the backup key into the container** for the restore only:
   ```sh
   docker compose exec -T backup sh -c 'umask 077; cat > /tmp/key.txt' < mojud-backup-key.txt
   ```
2. **Restore the newest backup** (or a named one) into `mojud_restore`, then delete the copied key:
   ```sh
   docker compose exec backup restore.sh latest /tmp/key.txt mojud_restore
   docker compose exec backup rm /tmp/key.txt
   ```
   It prints the schema version and how many businesses, users and orders it holds.
3. **Switch,** with the app stopped:
   ```sh
   docker compose stop app
   docker compose exec db psql -U postgres -c "ALTER DATABASE mojud RENAME TO mojud_before_restore" \
                                          -c "ALTER DATABASE mojud_restore RENAME TO mojud"
   docker compose start app
   ```
   Drop `mojud_before_restore` once you are sure.

**On a brand-new server:**
- Set it up as above with the **same** `.env` values: the database passwords and `ENCRYPTION_KEY`.
- Then restore and switch.
- Images are safe in R2 and need nothing.

## Restore drill

- **Every change:** CI builds this whole setup, backs up, restores into a new database, and checks every table is identical.
- **Monthly:** the `latest-backup` job also restores the newest **real** backup from R2. It checks that the backup is at most 30 hours old and that it restores. The job is skipped until these repository secrets exist:

| Secret | Value |
|---|---|
| `BACKUP_AGE_IDENTITY` | The contents of `mojud-backup-key.txt`. |
| `BACKUP_S3_ENDPOINT` | The same value as in `.env`. |
| `BACKUP_S3_ACCESS_KEY_ID` | A **read-only** token for `mojud-backups`. |
| `BACKUP_S3_SECRET_ACCESS_KEY` | The secret for that read-only token. |

Storing the private key in GitHub is the price of an automatic drill. Without it, run the restore above by hand once a month.

## Monitoring

- **Errors:** set `SENTRY_DSN`. Every error the app logs (failed requests, jobs, emails) becomes a Sentry event, without customers' details.
- **Errors in the web app:** a separate Sentry project is tidiest. Set its DSN as the `WEB_SENTRY_DSN` variable in the GitHub repository (Settings > Secrets and variables > Actions), and the DSN's host as `WEB_SENTRY_ORIGIN` in `.env`, then rebuild. Addresses and phone numbers are taken out before anything is sent (`frontend/src/lib/reporting.ts`).
- **Uptime:**
  - **Built in:** set the repository variable `HEALTHZ_URL` to `https://<APP_DOMAIN>/healthz`. `.github/workflows/uptime.yml` checks it every 10 minutes and GitHub emails a failure.
  - **Faster:** a dedicated monitor (UptimeRobot, Better Stack) on the same URL alerts sooner.
- **Logs:**
  ```sh
  docker compose logs --since 1h app
  ```
  The app writes JSON lines.
