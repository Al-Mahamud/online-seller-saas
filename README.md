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
./mvnw spring-boot:run -Dspring-boot.run.profiles=local   # run on http://localhost:8080
```

With the `local` profile: health at `/healthz`, API docs at `/api/docs`, OpenAPI JSON at `/api/v1/openapi`.

Errors are RFC 9457 problem responses with a `code` (see `ErrorCode`) and a `detail` in English or Bangla, chosen by `Accept-Language`.
