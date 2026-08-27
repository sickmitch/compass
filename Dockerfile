FROM python:3.12.11-slim-bookworm AS runtime

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1 \
    PYTHONPATH=/app/src

WORKDIR /app

RUN groupadd --system --gid 10001 compass \
    && useradd --system --uid 10001 --gid compass --home-dir /nonexistent compass

COPY pyproject.toml README.md ./
COPY src ./src
COPY alembic.ini ./
COPY migrations ./migrations

RUN pip install --no-cache-dir .

USER compass

CMD ["uvicorn", "compass.api.main:app", "--host", "0.0.0.0", "--port", "8000"]

