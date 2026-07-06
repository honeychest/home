#!/bin/bash
set -euo pipefail

# 대화형 통합 롤백. 백엔드(docker 이미지 sha) / 프론트(nginx 릴리스 심링크).
LAB_DIR="$(cd "$(dirname "$0")" && pwd)"
BACK_DIR="${LAB_DIR}/springboot"
IMAGE_REPO="localhost:5010/chsproject-docker"
APP_SERVICES=(app1 app2)
HEALTH_URL="http://127.0.0.1:8080/actuator/health"
HEALTH_RETRIES=50
HEALTH_INTERVAL=3
KEEP=5

NGINX_BASE="/Users/honey/devcontext/docker-volumes/nginx"
RELEASES_DIR="${NGINX_BASE}/releases"
DIST_LINK="${NGINX_BASE}/dist"
PREVIOUS_LINK="${NGINX_BASE}/previous"

# ---------- 공통 ----------
ask_number() {  # $1=최대값  → 선택 인덱스 반환(stdout)
  local max="$1" n
  while true; do
    read -rp "몇 번? (1-${max}, q=취소) > " n
    [ "$n" = "q" ] && echo "취소" >&2 && return 1
    [[ "$n" =~ ^[0-9]+$ ]] && [ "$n" -ge 1 ] && [ "$n" -le "$max" ] && { echo "$n"; return 0; }
    echo "  잘못된 입력." >&2
  done
}
confirm() { local a; read -rp "$1 (yes/no) > " a; [ "$a" = "yes" ]; }

# ---------- 백엔드 ----------
backend_live_short() { docker inspect chs-app-1 --format '{{.Image}}' 2>/dev/null | sed 's/^sha256://' | cut -c1-12; }

health_ok() {
  local svc="$1" body a
  for a in $(seq 1 "$HEALTH_RETRIES"); do
    body="$(cd "$BACK_DIR" && docker compose exec -T "$svc" sh -lc "wget -q -O - '$HEALTH_URL' 2>/dev/null" 2>/dev/null || true)"
    if printf '%s' "$body" | grep -q '"status":"UP"'; then echo "  [$svc] UP ($a/${HEALTH_RETRIES})"; return 0; fi
    echo "  [$svc] 대기 $a/${HEALTH_RETRIES}"; sleep "$HEALTH_INTERVAL"
  done
  return 1
}

rollback_backend() {
  local live; live="$(backend_live_short)"
  echo "최근 ${KEEP}개 백엔드 빌드:"
  local -a tags=() ; local i=0 tag id created msg mark
  while IFS=$'\t' read -r tag id created; do
    [ "$tag" = "latest" ] && continue
    i=$((i+1)); tags+=("$tag")
    msg="$(git -C "$BACK_DIR" log -1 --format='%s' "$tag" 2>/dev/null || echo '(메시지 없음)')"
    mark=""; [ "$(echo "$id" | cut -c1-12)" = "$live" ] && mark="  ← 현재 LIVE"
    printf "  %d) %-10s %s  \"%s\"%s\n" "$i" "$tag" "$created" "$msg" "$mark"
  done < <(docker images "$IMAGE_REPO" --format '{{.Tag}}\t{{.ID}}\t{{.CreatedAt}}' | awk -F'\t' '!seen[$2]++' | head -n $((KEEP+1)))
  [ "$i" -eq 0 ] && { echo "백엔드 이미지 없음."; return 1; }

  local sel; sel="$(ask_number "$i")" || return 0
  local target="${tags[$((sel-1))]}"
  confirm "${IMAGE_REPO}:${target} 로 롤백(app1→app2 순차). 진행?" || { echo "취소."; return 0; }

  echo ">> retag ${target} → latest (registry 안 건드림, 로컬 한정)"
  docker tag "${IMAGE_REPO}:${target}" "${IMAGE_REPO}:latest"
  local svc
  for svc in "${APP_SERVICES[@]}"; do
    echo ">> ${svc} 재생성"
    (cd "$BACK_DIR" && docker compose up -d --pull never --force-recreate "$svc")
    health_ok "$svc" || { echo "!! ${svc} health 실패 — 중단(다음 서비스 안 건드림)"; return 1; }
  done
  echo "백엔드 롤백 완료 → ${target}"
}

# ---------- 프론트 ----------
rollback_frontend() {
  local live prev; live="$(readlink -f "$DIST_LINK" 2>/dev/null || true)"; prev="$(readlink -f "$PREVIOUS_LINK" 2>/dev/null || true)"
  echo "최근 ${KEEP}개 프론트 릴리스:"
  local -a rels=(); local i=0 rel mark
  while IFS= read -r rel; do
    rel="${rel%/}"; i=$((i+1)); rels+=("$rel"); mark=""
    [ "$rel" = "$live" ] && mark="  ← 현재 LIVE"
    [ "$rel" = "$prev" ] && mark="${mark}  (previous)"
    printf "  %d) %s%s\n" "$i" "$(basename "$rel")" "$mark"
  done < <(ls -1dt "${RELEASES_DIR}"/*/ 2>/dev/null | head -n "$KEEP")
  [ "$i" -eq 0 ] && { echo "프론트 릴리스 없음."; return 1; }

  local sel; sel="$(ask_number "$i")" || return 0
  local target="${rels[$((sel-1))]}"
  confirm "dist → $(basename "$target") 로 롤백. 진행?" || { echo "취소."; return 0; }

  local cur; cur="$(readlink -f "$DIST_LINK" 2>/dev/null || true)"
  [ -n "$cur" ] && [ "$cur" != "$target" ] && ln -sfn "$cur" "$PREVIOUS_LINK"   # 현재를 previous로 보존
  ln -sfn "releases/$(basename "$target")" "$DIST_LINK"
  if nginx -t >/dev/null 2>&1; then nginx -s reload && echo "  nginx reload OK"; else echo "  !! nginx -t 실패 — 설정 점검 필요"; fi
  echo "프론트 롤백 완료 → dist -> $(readlink "$DIST_LINK")"
}

# ---------- main ----------
echo "무엇을 롤백?"
echo "  1) 백엔드   2) 프론트   3) 둘다"
read -rp "선택 (q=취소) > " what
case "$what" in
  1) rollback_backend ;;
  2) rollback_frontend ;;
  3) rollback_backend; echo; rollback_frontend ;;
  q) echo "취소." ;;
  *) echo "잘못된 입력." ;;
esac
