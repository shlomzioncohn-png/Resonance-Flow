FROM node:22-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

# Development image (next dev). E2E tests run from the `playwright` stage.
FROM node:22-alpine AS development
WORKDIR /app

RUN apk add --no-cache \
    ca-certificates \
    curl \
    bash \
    python3 \
    make \
    g++

COPY package*.json ./
# Configure npm with retry logic for network issues
RUN npm config set fetch-timeout 120000 && \
    npm config set fetch-retries 5 && \
    npm config set fetch-retry-mintimeout 20000 && \
    npm config set fetch-retry-maxtimeout 120000 && \
    npm install

# No Playwright browser here: `playwright install-deps` needs apt-get, which
# Alpine does not have, so this stage could not be built with it.

COPY . .

EXPOSE 3000
CMD ["npm", "run", "dev"]

# Playwright E2E test runner
FROM node:22-alpine AS playwright
WORKDIR /app

# Install system dependencies for Playwright (Alpine compatible)
RUN apk add --no-cache \
    ca-certificates \
    curl \
    bash \
    python3 \
    make \
    g++

COPY package*.json ./
# Configure npm with retry logic for network issues
RUN npm config set fetch-timeout 120000 && \
    npm config set fetch-retries 5 && \
    npm config set fetch-retry-mintimeout 20000 && \
    npm config set fetch-retry-maxtimeout 120000 && \
    npm install

# Install Playwright browsers and system dependencies
RUN npx playwright install-deps chromium && \
    npx playwright install chromium

COPY . .

CMD ["npm", "run", "test:e2e"]

# Static Storybook build, served by the `storybook` stage below.
FROM node:22-alpine AS storybook-build
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build-storybook

# Storybook as a service (lens-storybook) - built with `--target storybook`.
# The unprivileged image listens on 8080 and runs as a non-root user.
FROM nginxinc/nginx-unprivileged:alpine AS storybook

COPY --from=storybook-build /app/storybook-static /usr/share/nginx/html

EXPOSE 8080

# Must stay the last stage - CD builds the default (last) stage.
FROM node:22-alpine AS runtime
WORKDIR /app

ENV NODE_ENV=production

COPY --from=build /app/package*.json ./
RUN npm ci --omit=dev
COPY --from=build /app/.next ./.next

EXPOSE 3000
CMD ["npm", "run", "start"]