FROM python:3.12-slim

WORKDIR /app
RUN pip install uv

COPY pyproject.toml .
RUN uv pip install --system -r pyproject.toml

COPY atlas/ atlas/

EXPOSE 8004
CMD ["uvicorn", "atlas.main:app", "--host", "0.0.0.0", "--port", "8004"]
