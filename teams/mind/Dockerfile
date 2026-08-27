FROM python:3.11-slim

WORKDIR /app

COPY . .

EXPOSE 8080

# Mock HTTP server (to be replaced by Mind team with actual service)
CMD ["python", "-m", "http.server", "8080"]
