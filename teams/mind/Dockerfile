# ---- builder ----
# Build tools (a C compiler etc.) live only in this stage -- needed to
# install today's dependencies and any future OCR/ASR package that
# ships a C extension. None of this reaches the runtime image below.
FROM python:3.12-slim AS builder

WORKDIR /app

RUN apt-get update && apt-get install -y --no-install-recommends \
    build-essential \
    && rm -rf /var/lib/apt/lists/*

# Install into an isolated venv (not system site-packages) so the
# runtime stage can copy over exactly the installed packages -- and
# nothing else (no pip cache, no build tool leftovers).
RUN python -m venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

COPY pyproject.toml README.md ./
COPY src ./src
RUN pip install --no-cache-dir .

# ---- runtime ----
# Lean image: only the venv built above crosses the stage boundary.
# No compiler, no apt packages, no dev dependencies -- pip install .
# above only pulls in [project.dependencies], never the [dev] extra
# (pytest/ruff/mypy), so those were never in this image regardless.
FROM python:3.12-slim AS runtime

WORKDIR /app

COPY --from=builder /opt/venv /opt/venv
ENV PATH="/opt/venv/bin:$PATH"

EXPOSE 8000

# Docker-native readiness signal for Relay -- `docker compose ps` /
# any orchestrator can see "healthy" without a custom external probe.
# Uses the stdlib (urllib) instead of curl/wget so the runtime image
# doesn't need either installed.
HEALTHCHECK --interval=10s --timeout=3s --start-period=5s --retries=3 \
    CMD python -c "import urllib.request as u; import sys; sys.exit(0 if u.urlopen('http://localhost:8000/health', timeout=2).status == 200 else 1)"

CMD ["uvicorn", "mind.api.main:app", "--host", "0.0.0.0", "--port", "8000"]
