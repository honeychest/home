#!/bin/bash
# gikka 배포 — springboot/deploy-back-only.sh 를 본뜨되 인스턴스가 1개라 롤링이 없다.
# 그래서 stop → up 사이 수십 초 끊긴다(2026-07-26 결정: 서버 메모리 여유 때문에 1개로 시작).
# 2개로 늘리면 이 스크립트를 deploy-back-only.sh 처럼 순차 배포로 바꾼다.
#
# compose 파일은 springboot/docker-compose.yml 에 함께 있다 — 이미지·컨테이너는 완전히
# 별개이고 파일만 공유한다(배포 편의). 그래서 이 스크립트도 그 폴더에서 compose 를 부른다.
set -euo pipefail

COMPOSE_DIR="/Users/honey/devcontext/project/lab/springboot"
LOG_DIR="$COMPOSE_DIR/dockerlogs"
SERVICE="gikka1"
CONTAINER="chs-gikka-1"
HEALTH_URL="http://127.0.0.1:8080/actuator/health"
HEALTH_CHECK_RETRIES=50
HEALTH_CHECK_INTERVAL=3

cd "$COMPOSE_DIR"

echo "[Step 1] Pull latest image from registry..."
docker compose pull "$SERVICE"

echo "[Step 2] Save existing logs..."
mkdir -p "$LOG_DIR"
docker logs "$CONTAINER" >> "$LOG_DIR/gikka1_$(date +%Y%m%d).log" 2>&1 || true

echo "[Step 3] Deploy..."
docker compose stop "$SERVICE"
docker compose up -d "$SERVICE"

for attempt in $(seq 1 $HEALTH_CHECK_RETRIES); do
  body="$(docker compose exec -T "$SERVICE" sh -lc "wget -q -O - '$HEALTH_URL' 2>/dev/null" 2>/dev/null || true)"
  if printf '%s' "$body" | grep -q '"status":"UP"'; then
    echo "[health][$attempt/$HEALTH_CHECK_RETRIES] status=UP"
    echo "[Step 4] Cleanup dangling images..."
    docker image prune -f
    echo "Deployment completed successfully."
    exit 0
  fi
  preview="$(printf '%s' "$body" | tr '\n' ' ' | cut -c1-160)"
  echo "[health][$attempt/$HEALTH_CHECK_RETRIES] status=WAIT body=${preview:-<empty>}"
  sleep "$HEALTH_CHECK_INTERVAL"
done

echo "Error: $SERVICE failed health check."
exit 1
