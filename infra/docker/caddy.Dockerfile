FROM node:22.23.4-bookworm-slim@sha256:5ec23b7c622b00547753bc6ffd78085dd868a68aeb41c8e0a1418f033e13d96d AS web
RUN corepack enable
WORKDIR /repo
COPY package.json pnpm-lock.yaml pnpm-workspace.yaml tsconfig.base.json ./
COPY apps/client/package.json apps/client/
COPY packages/contract/package.json packages/contract/
COPY packages/core/package.json packages/core/
COPY packages/native-upload/package.json packages/native-upload/
RUN pnpm install --frozen-lockfile --filter "@kodakiko/client..."
COPY apps/client apps/client
COPY packages packages
COPY scripts scripts
COPY infra/scripts infra/scripts
RUN node scripts/check-licenses.ts --write
RUN pnpm --filter @kodakiko/client build:web
RUN node infra/scripts/csp-hashes.ts apps/client/dist

FROM caddy:2.11.8-builder@sha256:d4eb070c36154bd2d4c94b40359a03c6168346d5af5404963c74691ab77fb63f AS caddybuild
ARG CADDY_VERSION=v2.11.8
ARG DNS_MODULE=github.com/caddy-dns/cloudflare@v0.2.4
RUN xcaddy build ${CADDY_VERSION} --with ${DNS_MODULE}

FROM caddy:2@sha256:9c1b76fb701c1f77383d3124d9f626e38bcf83bc1255ed47475d4f2420701d03 AS runtime
COPY --from=caddybuild /usr/bin/caddy /usr/bin/caddy
COPY --from=web /repo/apps/client/dist /srv/web
RUN mv /srv/web/csp-hashes.env /etc/caddy/csp-hashes.env
COPY infra/caddy/Caddyfile /etc/caddy/Caddyfile
COPY infra/caddy/snippets /etc/caddy/snippets
COPY infra/caddy/entrypoint.sh /usr/local/bin/entrypoint.sh
ENTRYPOINT ["/usr/local/bin/entrypoint.sh"]
