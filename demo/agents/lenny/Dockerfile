FROM python:3.12-slim

WORKDIR /app
RUN pip install uv

COPY pyproject.toml .
RUN uv pip install --system -r pyproject.toml

COPY lenny/ lenny/

EXPOSE 8001
CMD ["uvicorn", "lenny.main:app", "--host", "0.0.0.0", "--port", "8001"]
