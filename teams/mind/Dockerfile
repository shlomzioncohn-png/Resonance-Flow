FROM python:3.11-slim

WORKDIR /app

# System deps (minimal - skip build-essential to avoid SSL build issues)
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*

COPY . .

EXPOSE 8080

# Simple health check endpoint using built-in HTTP server (no external deps)
CMD ["python", "-m", "http.server", "8080", "--directory", "/app"]
