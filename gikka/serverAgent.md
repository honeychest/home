# gikka — 서버 작업 안내 (서버 agent 전용)

mac-mini 에서 gikka 앱(recipe 백엔드)을 띄우고 운영할 때 확인·수행할 것들.
코드 규칙은 `AGENTS.md`, 분리 진행 상황은 `docs/HANDOFF.md`(lab 저장소)를 본다.

> **이 문서는 `gikka/` 안에 있다.** 이 앱은 지금 lab 모노레포에 살지만 언젠가 별도 git
> 저장소로 갈라져 나갈 예정이다(2026-07-26 현행 유지 결정, 재검토 예정). 그래서 여기에는
> **gikka 자신에 관한 것만** 적고, lab 저장소에 의존하는 부분은 전부 아래 §6 표에 모아 뒀다.
> 분리할 때는 §6 표의 항목만 기계적으로 옮기면 되고, 이 문서의 나머지는 그대로 따라간다.

---

## 1. 서버가 이미 갖추고 있어야 하는 것 (첫 배포 전 확인)

| 확인 대상 | 기대 상태 | 확인 방법 |
|---|---|---|
| PostgreSQL 컨테이너 | `chs-pgvector` (pgvector/pgvector:pg16) 가 `chs-network` 에 떠 있음 | `docker ps --filter name=chs-pgvector` |
| gikka 데이터베이스 | 같은 PG 안에 DB `gikka` + 전용 계정 (그 DB 밖은 권한 없음) | `docker exec chs-pgvector psql -U postgres -lqt \| grep gikka` |
| 스키마 버전 | `flyway_schema_history` 최신 15 (앱이 기동 시 자동 검증) | 앱 로그의 `Current version of schema "public": 15` |
| 도커 네트워크 | `chs-network` (external, infra compose 가 소유) | `docker network ls \| grep chs-network` |
| 로컬 레지스트리 | `localhost:5010` 에 push/pull 가능 | `curl -s localhost:5010/v2/_catalog` |
| 호스트 추출 서비스 | mac-mini launchd `com.gikka.local-extractor` 가 8765 에서 응답 | `curl -s localhost:8765/health` (없으면 Gemini 전량 폴백 — 앱은 뜬다) |

**환경변수** — compose 가 `.env` 에서 보간한다. 이름은 분리 전과 같아 서버 `.env` 를 그대로 쓴다.

| 키 | 없으면 | 비고 |
|---|---|---|
| `SPRING_PROFILES_ACTIVE` | **local 로 떠서 운영 DB 를 못 찾고 죽는다** | 반드시 `prod` |
| `GIKKA_DB_USERNAME` / `GIKKA_DB_PASSWORD` | 기동 실패 (Flyway 가 연결 못 함) | PG 의 gikka 전용 계정 |
| `GIKKA_GEMINI_API_KEY` | 분석 워커가 조용히 휴면 (앱은 정상) | AI Studio 신형 키(`AQ.`) |
| `GIKKA_YOUTUBE_API_KEY` | 메타 조회만 생략 (앱은 정상) | 클라우드 구형 키(`AIza`) |
| `TELEGRAM_TOKEN` / `TELEGRAM_CHATID` | 페일오버 알림만 생략 (앱은 정상) | 기존 봇 값 재사용 |

---

## 2. 첫 배포 (한 번만 — 수동 실행이 필요하다)

```
Jenkins 웹UI → 파이프라인 → Build with Parameters → GIKKA_APP_ONLY 체크 → 실행
```

**왜 수동인가**: Jenkins 는 push 웹훅을 받으면 *그 시점 브랜치의* Jenkinsfile 로 파이프라인을
정의한 뒤 Sync Local(git pull)을 돈다. 그래서 stage 정의가 처음 들어오는 커밋은 옛 정의로
실행되어 새 stage 가 없다(2026-07-16 `Deploy Gikka Local` 신설 때 실측). 이후 `gikka/` 변경부터는
자동으로 잡힌다.

통과해야 할 stage 둘: `Build & Push Gikka App` → `Deploy Gikka App`.

---

## 3. 평소 배포와 확인

`gikka/` 아래가 바뀌면 자동으로 돈다(`DEPLOY_GIKKA_APP`). 배포는 `gikka/deploy-gikka.sh` 가
`pull → 로그 보관 → stop → up -d → 헬스체크(50회 × 3초)` 순으로 수행한다.

- **인스턴스가 1개라 배포 중 수십 초 끊긴다.** 받아들이기로 한 트레이드오프다(서버 메모리).
  2개로 늘릴 때는 이 스크립트를 `springboot/deploy-back-only.sh` 처럼 순차 배포로 바꾼다.
- 상태 확인: `docker compose ps gikka1` / 로그: `docker logs chs-gikka-1`
- 헬스: 컨테이너 안에서 `wget -qO- http://127.0.0.1:8080/actuator/health` → `{"status":"UP"}`
  (호스트에서는 `curl -s 127.0.0.1:8082/actuator/health`)

**기동 실패 시 보는 순서**
1. `SPRING_PROFILES_ACTIVE=prod` 인가 — 빠지면 local 로 떠서 Tailscale 주소를 찾다가 죽는다.
2. `password authentication failed for user` — `GIKKA_DB_*` 확인.
3. `applied migration not resolved locally` — §5 의 Flyway 주의를 어긴 경우다.
4. 포트 8082 충돌 — `lsof -i :8082`.

---

## 4. nginx 전환 (아직 하지 않았다 — 배포 확인 후)

지금은 nginx 가 `/api/recipe/**` 를 app1/app2 로 보낸다. gikka1 이 떠 있어도 **트래픽은 안 간다.**
양쪽이 같은 gikka DB 를 봐도 등록 워커 중복은 `claimNext` 의 `SKIP LOCKED` 가 막으므로 안전하다.

전환할 때 고칠 곳 (upstream 이 단일이라 `$sticky_backend`·`SRV_ID` 쿠키가 필요 없다):
1. `upstream chs_gikka { server 127.0.0.1:8082 max_fails=2 fail_timeout=3s; }` 추가
2. 기존 `location ^~ /api/recipe/llm/` 의 `proxy_pass` → `http://chs_gikka`
   (read_timeout 120s · `proxy_next_upstream off` 는 유지 — 사고 계기가 여전히 유효하다.
   재시도하면 같은 Gemini 호출이 한 번 더 나가 무료 한도를 두 배로 태운다)
3. 새 `location ^~ /api/recipe/` 추가 → `proxy_pass http://chs_gikka`.
   `location /api` 보다 먼저 매칭돼야 한다(`^~` 접두 우선). read_timeout 은 지금과 같은 15s.

`nginx -t` → `nginx -s reload` → 기까 앱에서 보관함·냉장고·추천·등록 1건 확인.

**롤백 = conf 원복 후 reload.** 앱은 건드리지 않는다 — recipe 코드가 app1/app2 에도 아직
살아 있어서 프록시만 되돌리면 즉시 복구된다. 이것이 병행 전환을 택한 이유다.

---

## 5. 운영 중 주의

- **gikka DB 마이그레이션 추가 금지** (분리 4단계 전까지). 두 앱이 같은 DB 에 각자의 Flyway 로
  붙어 있어서, 새 파일을 `gikka/` 에만 넣으면 DB 이력에는 적용되고 `springboot/` 에는 그 파일이
  없어 **다음 백엔드 배포 때 springboot 앱이 기동 실패**한다
  (`applied migration not resolved locally`). 꼭 필요하면 같은 파일을 양쪽에 동시에 넣는다.
- **배포 대상 코드를 손으로 복사한 사본으로 돌리지 말 것.** 2026-07-16 실제 사고: launchd 가
  `~/gikka-local/server.py` 사본을 돌아 품질 경고가 DONE 125건 전부 미작동했다. 프로세스는
  체크아웃 경로를 직접 실행한다.
- Gemini 무료 등급: 일 약 250~1,500요청 · 분당 약 10요청. 호출 간격 하한은 앱이 DB 로 조율한다
  (`gikka.llm.min-interval-seconds=6`). 한도 소진이 잦으면 호스트 추출 서비스가 죽어 전량
  Gemini 폴백 중일 수 있으니 8765 부터 확인한다.
- 로그는 배포 때마다 `springboot/dockerlogs/gikka1_YYYYMMDD.log` 로 보관된다(도커 자체 로그는
  50MB × 3 롤링).

---

## 6. 별도 저장소로 분리할 때 바꿔야 하는 것 (여기만 보면 된다)

지금 gikka 는 lab 모노레포에 있고, 아래 항목들만 lab 쪽 파일·경로에 의존한다.
**분리 작업 = 이 표를 위에서 아래로 처리하는 것이고, 그 외에는 손댈 것이 없다.**

| # | 항목 | 지금 (lab 모노레포) | 분리 후 | 비고 |
|---|---|---|---|---|
| 1 | 체크아웃 경로 | `/Users/honey/devcontext/project/lab` | `.../gikka` (새 클론) | Jenkins `Sync Local` 도 함께 |
| 2 | compose 서비스 정의 | `springboot/docker-compose.yml` 의 `gikka1` | `gikka/docker-compose.yml` 로 이동 | 서비스 블록을 통째로 옮기면 끝. `chs-network` 는 external 이라 그대로 붙는다 |
| 3 | 배포 스크립트 상수 | `deploy-gikka.sh` 의 `COMPOSE_DIR` | 새 compose 위치로 | 이 파일에서 lab 을 아는 곳은 이 상수 **하나뿐**이다 |
| 4 | CI 파이프라인 | lab `Jenkinsfile` 의 `Build & Push / Deploy Gikka App` stage + `DEPLOY_GIKKA_APP` + `GIKKA_APP_ONLY` | gikka 저장소에 `Jenkinsfile` 신설 | 새 저장소는 변경 감지가 필요 없다(전체가 gikka) — `Detect Changes` 없이 단순해진다 |
| 5 | 웹훅 | lab 저장소 훅 하나 | gikka 저장소 훅 추가 | |
| 6 | 환경변수 파일 | `springboot/.env` 를 compose 가 보간 | `gikka/.env` (키 7개만) | 키 이름은 그대로. 로컬용 `gikka/.env` 는 이미 그 7개로 만들어져 있다 |
| 7 | nginx 설정 | lab `chs/server/nginx/devcontext.conf` | **lab 에 남긴다** | 서버 전체의 라우팅이라 gikka 소유가 아니다. gikka 는 8082 를 제공할 뿐 |
| 8 | 문서 | lab `docs/recipe/` (CONTEXT·DECISIONS-LOG·PLAYBOOK·progress) | 함께 옮길지 별도 판단 | recipe 프론트 53개 파일이 lab `frontend/` 에 남으므로, 문서를 옮기면 프론트 작업자가 못 본다. 이 결정이 분리의 실질 쟁점이다 |

**분리 후에도 그대로인 것** (추가 비용 없음): 이미지 이름 `chs-gikka` · 컨테이너 `chs-gikka-1` ·
포트 8082 · DB 접속 정보 · 환경변수 이름 · 도커 네트워크 · 이 문서와 `AGENTS.md`.
