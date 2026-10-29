# The web app's image, ghcr.io/al-mahamud/mojud-web (frontend-plan.md, F0c): Caddy with the built
# files in /srv/web. deploy/compose.yml runs it with deploy/Caddyfile, which sends /api to the
# backend. The build runs on the builder's own platform: its output is the same files for every
# platform, so the arm64 image needs no emulation.
FROM --platform=$BUILDPLATFORM node:22-slim AS build
WORKDIR /app
COPY package.json package-lock.json ./
RUN npm ci --no-audit --no-fund
COPY . .
RUN npm run build

FROM caddy:2
COPY --from=build /app/dist /srv/web
