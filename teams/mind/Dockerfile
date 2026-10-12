FROM python:3.12-slim AS builder

WORKDIR /app

# Build tools (a C compiler etc.) live only in this stage -- needed to
# install today's dependencies and any future OCR/ASR package that
# ships a C extension. None of this reaches the runtime image below.
RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

COPY pyproject.toml README.md ./
COPY proto ./proto
COPY --from=shared proto ./shared/proto
COPY scripts/gen_proto.py ./scripts/gen_proto.py

# Generate the proto classes with the system Python, before the venv
# below exists, so grpcio-tools never ends up in it. See scripts/gen_proto.py
# -- same script used by `make proto` and tests/conftest.py.
RUN pip install --no-cache-dir grpcio-tools==1.71.0 \
    && python scripts/gen_proto.py

# Install into an isolated venv (not system site-packages) so the
# runtime stage can copy over exactly the installed packages -- and
# nothing else (no pip cache, no build tool leftovers, no grpcio-tools).
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

# sentence-transformers pulls in torch; PyPI's default torch wheel is the
# GPU (CUDA) build -- ~3.3GB of CUDA libraries that will never run here
# and just slow the image and every CI pull. Install the CPU wheel into
# the venv first, from PyTorch's own CPU index, so the install below
# finds it already satisfied instead of replacing it.
RUN pip install --no-cache-dir torch --index-url https://download.pytorch.org/whl/cpu

# Install dependencies before the real source ever shows up in a layer
# -- pyproject.toml/hatchling still needs *a* src/mind/ to build the
# wheel, so give it an empty placeholder. Editing any file under the
# real src/ later won't invalidate this layer, so fastapi/uvicorn/etc.
# don't get re-downloaded and rebuilt on every code change.
RUN pip install --no-cache-dir .

# Whisper base.en weights for src/mind/multimodal/asr.py (the #135 decision),
# baked into the image so a pod starts without downloading a model and needs no
# egress to Hugging Face (team-lead decision on #478). The build runs in CI, which
# reaches Hugging Face (only the dev network's NetFree filter blocks it -- locally
# the weights come from models/whisper/, see models/README.md). Pinned to a
# revision and checked against the sha256 Hugging Face publishes for model.bin,
# the same file the benchmark measured; asr.py checks the same hash again on every
# load. These three values must match PINNED_WHISPER_MODELS in asr.py
# (tests/test_asr_settings_sync.py fails if they don't). huggingface_hub comes in
# with faster-whisper, installed just above. Placed under /opt, not /app/models,
# so the local ./models:/app/models mount cannot hide it.
#
# KEEP THIS LAYER BEFORE `COPY src` BELOW. Docker reuses a cached layer only while
# every layer above it is unchanged, and CI builds with a layer cache
# (.github/actions/deploy-service, cache-from: type=gha). Here, above the source,
# the 145 MB download is cached and reruns only when these ARGs or the
# dependencies change. Moved below `COPY src`, it would rerun on every code change.
ARG WHISPER_REPO=Systran/faster-whisper-base.en
ARG WHISPER_REVISION=3d3d5dee26484f91867d81cb899cfcf72b96be6c
ARG WHISPER_MODEL_SHA256=2a166925539a16005f14ff328359f9b9adb9dc4fb631bb3b227526862e93e2ef
RUN python -c "from huggingface_hub import snapshot_download; snapshot_download('${WHISPER_REPO}', revision='${WHISPER_REVISION}', local_dir='/opt/models/whisper/base.en', allow_patterns=['config.json', 'model.bin', 'tokenizer.json', 'vocabulary.txt'])" \
    && rm -rf /opt/models/whisper/base.en/.cache \
    && echo "${WHISPER_MODEL_SHA256}  /opt/models/whisper/base.en/model.bin" | sha256sum -c -

# Now bring in the real source (plus the generated proto classes and
# scripts/) and reinstall over the placeholder.
# --no-deps: dependencies are already installed above; this step is
# just copying mind's own (pure-Python) files into the venv, so it's
# fast even though it reruns on every code change.
COPY src ./src
COPY scripts ./scripts
RUN pip install --no-cache-dir --no-deps .

# ---- runtime ----
# Lean image: only the venv built above crosses the stage boundary.
# No compiler, no dev dependencies, and only the runtime apt packages below -- pip install .
# above only pulls in [project.dependencies], never the [dev] extra
# (pytest/ruff/mypy), so those were never in this image regardless.
FROM python:3.12-slim AS runtime

WORKDIR /app

# tesseract-ocr is a system binary, not pip-installable (see pyproject.toml)
# -- src/mind/multimodal/ocr.py shells out to it via PATH. tesseract-ocr-eng
# is named explicitly rather than relying on --install-recommends, since
# Tesseract's own English traineddata is a separate package on Debian.
# Installed as root, before the switch to the non-root user below.
RUN apt-get update && apt-get install -y --no-install-recommends \
    tesseract-ocr \
    tesseract-ocr-eng \
    && rm -rf /var/lib/apt/lists/*

# Don't run as root -- standard hardening, matters more once this
# handles uploaded files / talks to other services in production.
RUN useradd --create-home --uid 1000 --shell /usr/sbin/nologin mind

COPY --from=builder --chown=mind:mind /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"
COPY --from=builder --chown=mind:mind /app/scripts ./scripts
# Read-only for the app (owned by root): weights are never written at run time.
COPY --from=builder /opt/models /opt/models
# asr.py loads <WHISPER_MODELS_DIR>/<WHISPER_MODEL>/ (see ASRSettings in config.py).
ENV WHISPER_MODELS_DIR=/opt/models/whisper

# alembic.ini/migrations/ aren't part of the pip-installed package (they
# live beside pyproject.toml, not under src/mind/), so the venv copy
# above doesn't carry them -- needed here so the `migrate` service
# (docker-compose.yml) can run `alembic upgrade head` from this same image.
COPY --chown=mind:mind alembic.ini ./
COPY --chown=mind:mind migrations ./migrations

USER mind

EXPOSE 8000

# Docker-native readiness signal for Relay -- `docker compose ps` /
# any orchestrator can see "healthy" without a custom external probe.
# Uses the stdlib (urllib) instead of curl/wget so the runtime image
# doesn't need either installed.
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request; urllib.request.urlopen('http://localhost:8000/health', timeout=2)"

CMD ["uvicorn", "mind.api.main:app", "--host", "0.0.0.0", "--port", "8000"]
