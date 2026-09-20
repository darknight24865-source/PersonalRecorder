# PersonalRecorder dashboard — container image.
#
# Build:  docker build -t personalrecorder-dashboard .
# Run:    docker run --rm -p 8765:8765 -v dash-data:/data \
#           -e TOKEN='pick-a-strong-token' personalrecorder-dashboard
# Compose: docker compose -f deploy/docker-compose.yml up -d
FROM python:3.12-slim

# Keep images small: no build toolchain needed for a pure-Python service.
ENV PYTHONUNBUFFERED=1 \
    PIP_NO_CACHE_DIR=1

WORKDIR /srv

COPY server/requirements.txt /srv/requirements.txt
RUN pip install --no-cache-dir -r /srv/requirements.txt

COPY server/ /srv/
COPY deploy/entrypoint.sh /srv/entrypoint.sh
RUN chmod +x /srv/entrypoint.sh

# Dashboard listens on the ws/http port (plain HTTP/wss-terminated behind a
# proxy, or TLS directly when CERT/KEY envs are set).
EXPOSE 8765

# Data dir: devices.json, db.json, locations.jsonl and media/ uploads.
ENV DATA_DIR=/data \
    PORT=8765

VOLUME ["/data"]

ENTRYPOINT ["/srv/entrypoint.sh"]