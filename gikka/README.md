# gikka-local — 로컬 모델 페일오버 호스트 서비스

Spring 앱(chs-app-1/2)은 도커 컨테이너(Alpine Linux)라 yt-dlp·ffmpeg·whisper-cli 를 직접
실행할 수 없다. 이 서비스는 mac-mini 호스트(macOS)에서 상시 도는 작은 HTTP 서버로,
Spring 의 `LocalRecipeExtractor` 가 `host.docker.internal:8765` 로 호출한다
(LM Studio 와 동일한 host.docker.internal 패턴 — 자세한 배경은
`docs/recipe/CONTEXT.md` "로컬 모델 페일오버" 절 참고).

이 폴더(`gikka/`)는 recipe(기까) 도메인 전용 최상위 폴더다 — Spring(`springboot/`)·
프론트(`frontend/`)와 마찬가지로 recipe 코드베이스의 일부이며, 2단계에서 별도 앱으로
분리될 때 이 폴더도 함께 옮겨간다. (배포 스크립트 백업 폴더인 `chs/`와는 무관 — 그 폴더에
잘못 두었다가 `.gitignore`(`/chs/`)에 걸려 커밋 자체가 안 되는 걸 확인하고 이리로 옮김.)

## 사전 준비 (mac-mini, Homebrew — 2026-07-14 설치 완료)
```
brew install yt-dlp ffmpeg whisper-cpp
```

## 배포 — 이제 자동 (2026-07-16 변경)
`server.py` 는 **복사하지 않는다.** launchd 가 저장소 체크아웃
(`/Users/honey/devcontext/project/lab/gikka/server.py`)을 직접 돌리고, Jenkins 의
`Deploy Gikka Local` stage 가 `gikka/` 변경을 감지해 재기동한다 → **푸시하면 반영된다.**

> 왜 바꿨나: 예전엔 `cp server.py ~/gikka-local/server.py` 로 뜬 **사본**을 돌렸다. 저장소만
> 갱신되고 사본은 그대로 남아, `transcriptChars`(품질 경고의 근거)를 안 보내는 옛 코드가 계속
> 돌았고 **DONE 125건 전부 품질 경고가 한 번도 작동하지 않았다**(2026-07-16 발견). 사본이라는
> 개념 자체를 없애 이 사고 유형을 원천 차단했다. 사본을 다시 만들지 말 것.

### 최초 1회만 (mac-mini 에서 손으로 — `~/Library/LaunchAgents` 는 홈이라 Jenkins 밖)
```bash
mkdir -p ~/gikka-local/models   # 모델·로그 자리 (코드는 여기 두지 않는다)
cp com.gikka.local-extractor.plist ~/Library/LaunchAgents/
# whisper 모델(turbo, ~1.6GB) — huggingface.co/ggerganov/whisper.cpp 에서 받아 models/ 로
curl -sL -o ~/gikka-local/models/ggml-large-v3-turbo.bin \
  https://huggingface.co/ggerganov/whisper.cpp/resolve/main/ggml-large-v3-turbo.bin

launchctl bootout  gui/$(id -u)/com.gikka.local-extractor 2>/dev/null || true
launchctl bootstrap gui/$(id -u) ~/Library/LaunchAgents/com.gikka.local-extractor.plist
```

### 수동 재기동 (plist 를 바꿨을 때만 — 코드 변경은 Jenkins 가 함)
```bash
launchctl kickstart -k gui/$(id -u)/com.gikka.local-extractor
```

## 확인
```bash
curl -X POST http://localhost:8765/extract \
  -H 'Content-Type: application/json' \
  -d '{"videoUrl":"https://www.youtube.com/watch?v=VIDEO_ID","description":null}'
```

## 재기동/중지
```bash
launchctl unload ~/Library/LaunchAgents/com.gikka.local-extractor.plist   # 중지
launchctl load ~/Library/LaunchAgents/com.gikka.local-extractor.plist     # 재시작
tail -f ~/gikka-local/server.log                                          # 로그
```

## 설정 (환경변수로 재정의 가능, 기본값은 server.py 상단 참고)
| 환경변수 | 기본값 | 의미 |
|---|---|---|
| `GIKKA_LOCAL_YT_DLP` | `/opt/homebrew/bin/yt-dlp` | yt-dlp 경로 |
| `GIKKA_LOCAL_FFMPEG` | `/opt/homebrew/bin/ffmpeg` | ffmpeg 경로 |
| `GIKKA_LOCAL_FFPROBE` | `/opt/homebrew/bin/ffprobe` | ffprobe 경로 (길이 측정) |
| `GIKKA_LOCAL_WHISPER_CLI` | `/opt/homebrew/bin/whisper-cli` | whisper.cpp 실행 파일 |
| `GIKKA_LOCAL_WHISPER_MODEL` | `~/gikka-local/models/ggml-large-v3-turbo.bin` | STT 모델 (turbo — medium 은 언어 강건성 부족으로 폐기, 2026-07-14 실측) |
| `GIKKA_LOCAL_LM_STUDIO_URL` | `http://localhost:2345/v1/chat/completions` | LM Studio OpenAI 호환 엔드포인트 |
| `GIKKA_LOCAL_LM_STUDIO_MODEL` | `Mac-mini-LLM` | LM Studio 별칭 (로드된 모델 무관하게 항상 이 이름으로 호출 — 모델 교체 시 재배포 불필요) |
| `GIKKA_LOCAL_PORT` | `8765` | 리스닝 포트 |

## 설계 메모
- 외부 의존 없이 파이썬 표준 라이브러리(`http.server`)만 사용 — 배포 단순화.
- 단일 스레드(`HTTPServer`)로 요청을 직렬화 — RegistrationWorker 가 단일 워커이지만
  앱 2인스턴스가 동시에 호출할 가능성을 대비 (LM Studio·whisper 자원 경합 방지).
- 임시 파일은 `finally` 로 항상 삭제 + 기동 시 이전 실패로 남은 폴더 청소(안전망).
- 응답 JSON은 Spring `RecipeExtractor.ExtractionResult` 와 1:1 (category, name, ingredients,
  cookMinutes, steps, summary, tags).
- 실패(HTTP 500)는 Spring 쪽에서 `LocalUnavailableException` 으로 묶여 Gemini 로 전체 폴백된다.
