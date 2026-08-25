FROM python:3.11-slim

WORKDIR /app

# System deps (minimal - skip external Python packages to avoid SSL issues)
RUN apt-get update && apt-get install -y --no-install-recommends ca-certificates && rm -rf /var/lib/apt/lists/*

COPY . .

EXPOSE 8080

# Custom HTTP server with /health endpoint (no external dependencies)
CMD ["python", "server.py"]
