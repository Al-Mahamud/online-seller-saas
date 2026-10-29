# Mojud (working name)

Multi-tenant SaaS for Bangladesh online sellers: inventory, orders, COD delivery tracking and billing, with an AI inbox for Messenger and WhatsApp in release 2.

- [Product plan](docs/product-plan.md)
- [Backend plan](docs/backend-plan.md)
- [Implementation plan](docs/implementation-plan.md) (phases and status)

Layout: `backend/` (Spring Boot, Java 17), `frontend/` (later), `deploy/`, `docs/`.

## Backend

Needs JDK 17. Maven comes with the wrapper.

```sh
cd backend
./mvnw verify                                             # build and run every test
./mvnw spring-boot:test-run                               # run on http://localhost:8080
```

`spring-boot:test-run` starts an embedded PostgreSQL (no Docker or installed server needed), set up with the same three roles as production (`backend/src/main/resources/db/bootstrap/roles.sql`). The database is fresh on every start.

Against a real PostgreSQL, run `roles.sql` once as a superuser, create the database (`CREATE DATABASE mojud OWNER mojud_owner`), and set `DB_URL`, `DB_OWNER_PASSWORD`, `DB_APP_PASSWORD` and `DB_SYSTEM_PASSWORD`.

With the `local` profile: health at `/healthz`, API docs at `/api/docs`, OpenAPI JSON at `/api/v1/openapi`.

**In IntelliJ IDEA:** open the `backend` folder (it imports as a Maven project), set the project SDK to a JDK 17 (File → Project Structure), then run `TestMojudApplication` from `src/test/java/com/mojud`. Don't run `MojudApplication` itself: it expects a real PostgreSQL and passwords.

**Trying it out locally:**
- **Swagger page:** at `/api/docs`, run `GET /api/v1/auth/csrf` once first, so that POST, PUT and DELETE requests are accepted.
- **Sign-up codes:** these aren't sent. They're printed in the log, and `GET /dev/otp?to=01712345678` returns the last one.

Errors are RFC 9457 problem responses with a `code` (see `ErrorCode`) and a `detail` in English or Bangla, chosen by `Accept-Language`.
