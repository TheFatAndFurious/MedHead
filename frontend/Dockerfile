# Shared base: both targets run as the unprivileged node user with the same health check.
FROM node:24-alpine AS base

WORKDIR /app
RUN chown node:node /app
USER node

EXPOSE 5173

HEALTHCHECK --interval=10s --timeout=5s --start-period=15s --retries=5 \
  CMD node -e "fetch('http://127.0.0.1:5173/robots.txt').then(response => process.exit(response.ok ? 0 : 1)).catch(() => process.exit(1))"

FROM base AS deps

COPY --chown=node:node package.json package-lock.json ./
RUN npm ci

# Vite development server with hot reload, selected by docker-compose.dev.yaml.
FROM deps AS dev

COPY --chown=node:node . .

CMD ["npm", "run", "dev", "--", "--host", "0.0.0.0", "--port", "5173", "--strictPort"]

FROM deps AS build

# Public origin compiled into the application (kit.paths.origin) for its CSRF check.
# It must match MEDHEAD_PUBLIC_ORIGIN at runtime, otherwise form submissions are refused.
ARG MEDHEAD_PUBLIC_ORIGIN=http://localhost:5173

COPY --chown=node:node . .
RUN npm run build && npm prune --omit=dev

# Compiled application started by docker-compose.yaml (default target).
# Files copied here belong to root: the node process can read but not modify them.
FROM base AS runtime

ENV NODE_ENV=production \
    PORT=5173

COPY --from=build /app/package.json ./
COPY --from=build /app/node_modules ./node_modules
COPY --from=build /app/build ./build

CMD ["node", "build"]
