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

# Install dependencies for Playwright
RUN apk add --no-cache \
    chromium \
    ca-certificates \
    fonts-noto-cjk \
    fonts-noto-color-emoji \
    fonts-dejavu \
    fonts-dejavu-sans

COPY package*.json ./
RUN npm ci

# Install Playwright browsers
RUN npx playwright install chromium --with-deps

COPY . .

EXPOSE 3000
CMD ["npm", "run", "dev"]