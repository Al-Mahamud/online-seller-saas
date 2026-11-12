# The server log

The backend writes one text format everywhere: on local runs, in tests and on servers. Al Mahamud chose it on Nov 10, 2026, after a sample log from another Spring service.

```
2026-11-10 22:20:32.340.http-nio-8080-exec-9> INFO  - T[261110222032-9ed7970] U[] - [CLIENT-REQ] GET /api/v1/me
2026-11-10 22:20:32.359.http-nio-8080-exec-9> INFO  - T[261110222032-9ed7970] U[01a1c642-d9a1-73d0-8b4b-122dbad600e7] - [CLIENT-RES] GET /api/v1/me 200 19ms	{"id":"01a1c642-…","userId":"rahima01",…}
```

## Parts of a line

| Part | What it is |
|---|---|
| `2026-11-10 22:20:32.340` | When, in Dhaka time, on servers too. |
| `http-nio-8080-exec-9>` | The thread. Jobs run on their own threads (`db-scheduler-…`), and emails and SMS on `after-commit-…`. |
| `INFO ` | The level: TRACE, DEBUG, INFO, WARN or ERROR. |
| `T[261110222032-9ed7970]` | The request's ID: the Dhaka time to the second, then 7 hex digits. The app shows it to sellers as "কোড: …". Empty for a job's lines. |
| `U[01a1c642-…]` | The signed-in user's id. It is empty until Spring Security has read the session, so a `[CLIENT-REQ]` line has none. |
| The message | It starts with a tag in brackets when it is one of the lines below. |

## Tags

| Tag | Written |
|---|---|
| `[CLIENT-REQ]` | When an API call arrives: method, path with query, then a tab and the body. |
| `[CLIENT-RES]` | When its answer has been sent: method, path, status, time taken, then a tab and the body. |
| `[REQ]` / `[RES]` | The same for each call the server makes to another service, such as SSLCommerz. |
| `EXCEPTION:` | An unexpected error, with its stack trace on the lines after. It also goes to Sentry. |
| `[DEV-SMS]` / `[DEV-EMAIL]` | On local runs and in tests, a message that was logged instead of sent. It includes the one-time code. |
| `[JOB-…]` | A scheduled job: `JOB-LOW-STOCK`, `JOB-SUBSCRIPTIONS`, `JOB-STOCK-CHECK`, `JOB-PENDING-PAYMENTS`, `JOB-BUSINESS-DELETE`, `JOB-IDEMPOTENCY`. |
| Others | `[IMPORT]`, `[IMPORT-RESUME]`, `[BULK-ACTION]`, `[SSLCOMMERZ-IPN]`, `[SSLCOMMERZ-RETURN]`, `[RATE-LIMIT]`, `[EMAIL]`, `[FILES]`, `[BACKGROUND-TASK]`, `[KEY-ROTATION]`. |

## Bodies

- **Secrets are masked everywhere, as `***`.** This covers every password, the one-time codes, two-step secrets and recovery codes, tokens, SSLCommerz's store password and session key, and a gateway callback's `verify_sign`. `LogText` in `shared/logging` holds the list, and `LogTextTest` checks it.
- **Only JSON, form fields and plain text are written out.** Anything else is named by its type and size, such as `<image/png 22.9 KB>`.
- **Line breaks inside a body become `\n`.** A body cannot start a line of its own that looks like a log line.
- **Length:**
  - Servers show the first 4 KB of each body (`mojud.logging.body-limit`). The rest is counted, as in `…(+1520 bytes)`.
  - Local runs show up to 64 KB.
- **Customers' details are in server logs:** names, phones and addresses in order and customer bodies. The log files stay on the server.
- **Sentry gets only the method, path, status and time of these lines,** with no body or query (`SentryBreadcrumbs`).
- **`/healthz` is not logged.** The container checks it every few seconds.

## Levels

| Where | What is written |
|---|---|
| Servers (`prod`) | INFO and above. |
| Local runs (`local`) | Everything, for debugging: Mojud's own DEBUG lines, each SQL statement and its values, Spring's web, security and transaction steps, and the connection pool's size every 30 seconds. Set in `application-local.yml`. |
| Tests | INFO and above. |

To see more of one part on a server for a while, set its level and restart. For example, add `LOGGING_LEVEL_ORG_HIBERNATE_SQL: DEBUG` to the app's `environment` in `compose.yml`.

## Files on servers

- **Where:** the console, which `docker compose logs app` shows, and hourly files in the `app-logs` volume (`/data/logs`).
- **Names:** `mojud-2026111014-0.log` holds 14:00–15:00 Dhaka time. If an hour passes 100 MB, it continues in `-1`, `-2`, and so on.
- **Kept:** 14 days, and at most 5 GB in all. The oldest go first.
- **Set in:** `logback-spring.xml`.

Finding a seller's request from the code they send:

```sh
docker compose exec app sh -c 'grep -h "T\[261110222032-9ed7970\]" /data/logs/mojud-2026111022-*.log'
```

The first 10 digits of the code name the hour's file: `2611102220…` is `mojud-2026111022-*.log`.

## Change log

- **Nov 10, 2026:** the sample's text format replaced JSON lines on servers. Request IDs now start with the time, and every API call and gateway call is logged with its body. Local runs log at DEBUG. Servers write hourly files.
