#!/bin/bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "$0")" && pwd)"
cd "$ROOT_DIR"

LOG_DIR="$ROOT_DIR/dockerlogs"
APP1_SERVICE="app1"
APP2_SERVICE="app2"
APP_INTERNAL_HEALTH_URL="http://127.0.0.1:8080/actuator/health"
HEALTH_CHECK_RETRIES=50
HEALTH_CHECK_INTERVAL=3

health_body() {
  local service="$1"
  local health_url="$2"
  docker compose exec -T "$service" sh -lc "wget -q -O - '$health_url' 2>/dev/null" 2>/dev/null || true
}

save_app_logs() {
  mkdir -p "$LOG_DIR"
  docker logs chs-app-1 >> "$LOG_DIR/app1_$(date +%Y%m%d).log" 2>&1 || true
  docker logs chs-app-2 >> "$LOG_DIR/app2_$(date +%Y%m%d).log" 2>&1 || true
}

deploy_one() {
  local service="$1"
  local health_url="$2"
  local attempt
  local body
  local preview

  docker compose stop "$service"
  docker compose up -d "$service"

  for attempt in $(seq 1 $HEALTH_CHECK_RETRIES); do
    body="$(health_body "$service" "$health_url")"
    if printf '%s' "$body" | grep -q '"status":"UP"'; then
      echo "[health][$service][$attempt/$HEALTH_CHECK_RETRIES] status=UP"
      echo "$service is healthy."
      return 0
    fi
    preview="$(printf '%s' "$body" | tr '\n' ' ' | cut -c1-160)"
    echo "[health][$service][$attempt/$HEALTH_CHECK_RETRIES] status=WAIT body=${preview:-<empty>}"
    sleep "$HEALTH_CHECK_INTERVAL"
  done
  echo "Error: $service failed health check."
  return 1
}

main() {
  echo "[Step 1] Pull latest image from registry..."
  docker compose pull app1 app2

  echo "[Step 2] Save existing app logs..."
  save_app_logs

  echo "[Step 3] Deploy..."
  deploy_one "$APP1_SERVICE" "$APP_INTERNAL_HEALTH_URL"
  deploy_one "$APP2_SERVICE" "$APP_INTERNAL_HEALTH_URL"

  echo "[Step 4] Cleanup dangling images..."
  docker image prune -f

  echo "Deployment completed successfully."
}

main "$@"
