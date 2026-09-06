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
EXPECTED_IMAGE_TAG="${IMAGE_TAG:-latest}"

health_body() {
  local service="$1"
  local health_url="$2"
  docker compose exec -T "$service" sh -lc "wget -q -O - '$health_url' 2>/dev/null" 2>/dev/null || true
}

verify_runtime_image() {
  local service="$1"
  local container_id
  local actual_revision

  container_id="$(docker compose ps -q "$service")"
  if [ -z "$container_id" ]; then
    echo "Error: $service container was not found after health check."
    return 1
  fi

  actual_revision="$(docker inspect "$container_id" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')"
  if [ "$EXPECTED_IMAGE_TAG" != "latest" ] && [ "$actual_revision" != "$EXPECTED_IMAGE_TAG" ]; then
    echo "Error: $service image revision mismatch. expected=$EXPECTED_IMAGE_TAG actual=$actual_revision"
    return 1
  fi

  if ! docker compose exec -T "$service" sh -lc \
      "jar tf /app/app.jar | grep -q 'BOOT-INF/classes/com/chs/springboot/global/monitor/health/HealthCheckRecorder.class'"; then
    echo "Error: $service executable JAR does not contain HealthCheckRecorder."
    return 1
  fi

  echo "[image][$service] revision=${actual_revision:-unknown} HealthCheckRecorder=present"
}

verify_pulled_image() {
  local image_ref
  local actual_revision

  image_ref="$(docker compose config --images | awk '/chsproject-docker/ { print; exit }')"
  if [ -z "$image_ref" ]; then
    echo "Error: backend image reference was not found in compose config."
    return 1
  fi

  actual_revision="$(docker image inspect "$image_ref" --format '{{index .Config.Labels "org.opencontainers.image.revision"}}')"
  if [ "$EXPECTED_IMAGE_TAG" != "latest" ] && [ "$actual_revision" != "$EXPECTED_IMAGE_TAG" ]; then
    echo "Error: pulled image revision mismatch. expected=$EXPECTED_IMAGE_TAG actual=$actual_revision"
    return 1
  fi

  if ! docker run --rm --entrypoint sh "$image_ref" -lc \
      "jar tf /app/app.jar | grep -q 'BOOT-INF/classes/com/chs/springboot/global/monitor/health/HealthCheckRecorder.class'"; then
    echo "Error: pulled executable JAR does not contain HealthCheckRecorder."
    return 1
  fi

  echo "[image][pulled] ref=$image_ref revision=${actual_revision:-unknown} HealthCheckRecorder=present"
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
      verify_runtime_image "$service"
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
  echo "[Step 1] Pull backend image from registry..."
  docker compose pull app1 app2

  echo "[Step 2] Verify pulled backend image..."
  verify_pulled_image

  echo "[Step 3] Save existing app logs..."
  save_app_logs

  echo "[Step 4] Deploy..."
  deploy_one "$APP1_SERVICE" "$APP_INTERNAL_HEALTH_URL"
  deploy_one "$APP2_SERVICE" "$APP_INTERNAL_HEALTH_URL"

  echo "[Step 5] Cleanup dangling images..."
  docker image prune -f

  echo "Deployment completed successfully."
}

main "$@"
