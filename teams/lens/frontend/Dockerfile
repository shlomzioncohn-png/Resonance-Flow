FROM node:22-alpine AS build
WORKDIR /app

COPY package*.json ./
RUN npm ci

COPY . .
RUN npm run build

FROM node:22-alpine AS runtime
WORKDIR /app

ENV NODE_ENV=production

COPY --from=build /app/package*.json ./
RUN npm ci --omit=dev
COPY --from=build /app/.next ./.next

EXPOSE 3000
CMD ["npm", "run", "start"]

# Development image with Playwright for E2E testing
FROM node:22-alpine AS development
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