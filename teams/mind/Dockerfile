FROM python:3.12-slim AS base

WORKDIR /app

# System deps for OCR/ASR (extend as needed once tools are decided)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml README.md ./
COPY src ./src

RUN pip install --no-cache-dir .

EXPOSE 8000

# Mock HTTP server (to be replaced by Mind team with actual service)
CMD ["python", "-m", "http.server", "8080"]
