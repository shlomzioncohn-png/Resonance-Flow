FROM python:3.11-slim AS base

WORKDIR /app

# System deps for OCR/ASR (extend as needed once tools are decided)
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml ./
COPY src ./src

# sentence-transformers pulls in torch; PyPI's default torch wheel is the
# GPU (CUDA) build -- ~3.3GB of CUDA libraries that will never run here
# and just slow the image and every CI pull. Install the CPU wheel first,
# from PyTorch's own CPU index, so the install below finds it already
# satisfied instead of replacing it.
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu
RUN pip install --no-cache-dir .

COPY . .

EXPOSE 8000

CMD ["uvicorn", "mind.api.main:app", "--host", "0.0.0.0", "--port", "8000"]
