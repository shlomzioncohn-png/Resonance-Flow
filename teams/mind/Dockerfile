FROM python:3.11-slim AS base

WORKDIR /app

# System deps for OCR/ASR (extend as needed once tools are decided)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    ca-certificates \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml README.md ./
COPY src ./src

RUN pip install --no-cache-dir .

COPY . .

EXPOSE 8000

CMD ["uvicorn", "mind.api.main:app", "--host", "0.0.0.0", "--port", "8000"]
