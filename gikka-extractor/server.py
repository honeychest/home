#!/usr/bin/env python3
"""
gikka 로컬 모델 페일오버 서비스 (2026-07-14 확정)

Spring 앱(chs-app-1/2)은 도커 컨테이너(Alpine Linux)로 떠 있어 yt-dlp·ffmpeg·whisper-cli 를
직접 실행할 수 없다. 이 스크립트는 mac-mini 호스트에서 상시 도는 작은 HTTP 서비스로,
Spring 의 LocalRecipeExtractor 가 host.docker.internal 로 호출한다 (LM Studio 와 동일 패턴 —
네트워크 호출은 OS/컨테이너 경계를 넘어가도 문제없다).

파이프라인: yt-dlp 다운로드(임시) → ffmpeg 프레임/오디오 추출 → whisper-cli(turbo) STT
           → LM Studio(Mac-mini-LLM 별칭) 호출 → JSON 파싱 → 임시파일 삭제 → 응답.

실행: python3 server.py (포그라운드) 또는 launchd(com.gikka.local-extractor.plist, README 참고).
설정: 아래 상수 또는 환경변수로 재정의 가능 (GIKKA_LOCAL_* 접두).
"""
import base64
import glob
import json
import os
import re
import shutil
import subprocess
import tempfile
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from urllib.request import Request, urlopen

YT_DLP = os.environ.get("GIKKA_LOCAL_YT_DLP", "/opt/homebrew/bin/yt-dlp")
FFMPEG = os.environ.get("GIKKA_LOCAL_FFMPEG", "/opt/homebrew/bin/ffmpeg")
FFPROBE = os.environ.get("GIKKA_LOCAL_FFPROBE", "/opt/homebrew/bin/ffprobe")
WHISPER_CLI = os.environ.get("GIKKA_LOCAL_WHISPER_CLI", "/opt/homebrew/bin/whisper-cli")
WHISPER_MODEL = os.environ.get("GIKKA_LOCAL_WHISPER_MODEL",
                                os.path.expanduser("~/gikka-local/models/ggml-large-v3-turbo.bin"))
LM_STUDIO_URL = os.environ.get("GIKKA_LOCAL_LM_STUDIO_URL", "http://localhost:2345/v1/chat/completions")
LM_STUDIO_MODEL = os.environ.get("GIKKA_LOCAL_LM_STUDIO_MODEL", "Mac-mini-LLM")
PORT = int(os.environ.get("GIKKA_LOCAL_PORT", "8765"))
# X 다운로드 전용 부계정 로그인 세션 (2026-07-20 확정) — "민감한 콘텐츠" 플래그가 붙은 게시물은
# 비로그인 요청엔 X 가 미디어 정보 자체를 안 내려줘서(guest token 한계, 실사용 제보로 확인)
# 로그인 세션이 있어야 조회된다. 개인 계정이 아니라 이 기능 전용 부계정 세션만 사용 — 파일이
# 없거나 만료돼도 비로그인 조회로 조용히 폴백한다(resolve_x_formats 참고).
X_COOKIES_FILE = os.environ.get("GIKKA_LOCAL_X_COOKIES_FILE",
                                 os.path.expanduser("~/gikka-local/x-cookies.txt"))
# X 요청 속도 제한 (2026-07-20 확정) — X 가 실제로 어느 선에서 차단하는지 공개된 기준이 없어
# ("가드레일 있냐"는 질문 계기) Gemini 쪽(GeminiRateLimiter)과 같은 사상으로 보수적으로 시작해
# 429 를 보면 더 쉬는 적응형 방식을 쓴다. 요청 사이 최소 간격 + 429 감지 시 장기 대기.
X_MIN_REQUEST_INTERVAL_SECONDS = 1.5
X_BACKOFF_SECONDS = 60
# 12 → 24 (2026-07-16 실측). 12장이면 53초 영상에서 약 4초 간격이라 "떡을 넣는 순간"이
# 프레임 사이로 빠져 모델이 주재료를 아예 못 보는 일이 있었다("떡볶이인데 떡이 없다" 제보의
# 남은 2건이 정확히 이것). 같은 영상을 전사(음성)는 그대로 둔 채 프레임만 24장으로 올리자
# 2건 다 떡이 잡혔고, 덤으로 표기가 정확해지고(설탕→황설탕, 어묵→사각어묵) 없는 재료를
# 지어내던 것도(후추장) 사라졌다 — 화면을 덜 본 모델이 빈칸을 상상으로 메우고 있었던 셈.
TARGET_FRAMES = 24
MIN_FRAME_INTERVAL_SECONDS = 2
MAX_FRAME_INTERVAL_SECONDS = 30
TEMP_DIR_PREFIX = "gikka-local-"

# 레시피 판정·추출 프롬프트 — GeminiRecipeExtractor.PROMPT 과 동일한 지시 (일관성 유지).
# 설명란(본문)이 있으면 최우선 사용 — 화면/음성은 빈 곳만 보완하는 보조 자료 (2026-07-14 확정,
# "본문에 없는 내용만 화면에서 보완" 조건부 지시라 본문이 레시피와 무관해도 안전).
PROMPT_TEMPLATE = """\
이 영상은 여러 장의 화면 캡처(프레임)로 주어집니다. 프레임과 음성 전사를 보고 판단해 주세요.

1. category: 요리 레시피 영상이면 RECIPE, 생활팁·요령 영상이면 TIP, 둘 다 아니면 ETC.
2. RECIPE 인 경우에만:
   - name: 요리 이름 (짧게)
   - ingredients: 재료 목록. 뒤에 유튜브 설명란 텍스트가 함께 주어지면 그 원문에 적힌
     재료 표기를 최우선으로 사용하세요 (창작자가 직접 적은 텍스트가 가장 정확합니다).
     설명란에 없는 재료만 화면·음성에서 보완하세요. 영상에 나온 이름 그대로 쓰고,
     임의로 바꾸지 마세요. 양념(소금, 간장 등)도 포함. 수량·단위는 빼고 이름만.
     아래 셋은 반드시 지키세요 (2026-07-16 실측에서 전부 어긴 사례가 나왔습니다):
     (1) 설명란에 "재료 : A, B, C" 같은 목록이 있으면 그 항목을 하나도 빠뜨리지 말고
         전부 넣으세요. 설명란에 재료가 적혀 있는데 목록이 비어 있으면 틀린 답입니다.
     (2) steps 에 언급한 재료는 반드시 ingredients 에도 넣으세요. 조리 순서에는
         나오는데 재료 목록에 없으면 그 자체로 틀린 답입니다.
     (3) 요리 이름이 가리키는 주재료를 빠뜨리지 마세요 (예: 떡볶이의 떡,
         단호박 튀김의 단호박). 단, 그 재료를 실제로 안 쓰는 영상이면(예: 양념장만
         만드는 영상) 넣지 마세요 — 영상에 실제로 쓰인 것만 적는 원칙이 우선입니다.
     함께 주어지는 영상 제목·설명란 원문에 상품명·요리명이 적혀 있으면(예: 라면 제품명)
     음성 전사에서 들리는 이름보다 그 표기를 우선하세요 — 음성 전사는 비슷한 발음의 다른
     상품명으로 잘못 인식될 수 있습니다 (실측: "오징어짬뽕"이 전사에서 다른 라면 이름으로
     잘못 잡힌 사례).
   - cookMinutes: 예상 조리 시간(분). 영상에서 알 수 없으면 생략.
   - steps: 조리 순서 요약. 각 단계를 짧은 한 문장으로, 3~7개.
   - confidentSeasonings: 위 ingredients 중 소금·간장·설탕·고춧가루·참기름처럼 명백히 양념·조미료라고
     확신하는 것만 이름 그대로 골라 담으세요. 주재료일 수도 있어 애매하면 넣지 마세요(확실한 것만).
3. RECIPE 가 아닌 경우에만:
   - summary: 영상의 요점 요약 2~3문장. 나중에 다시 찾을 때 내용을 떠올릴 수 있게.
   주의: 화면·음성·설명란에서 명확히 확인되지 않는 고유명사(인물 이름, 지명, 특정 사건·경기
   등)는 절대 단정해서 쓰지 마세요. 확실하지 않으면 "한 선수가", "경기 중" 처럼 일반적인
   표현으로 대체하세요 (2026-07-14 확정, 실측에서 모델이 사전 지식으로 없는 인물명을 지어낸
   사례 발견). 이 요약은 나중에 원본 영상을 찾기 위한 실마리일 뿐이니, 그럴듯하게 지어내는
   것보다 짧고 정확한 편이 낫습니다 — 아는 것만 쓰고 모르는 건 생략하세요.
4. tags: 모든 영상 공통. 이 영상을 검색할 때 쓸 만한 키워드 3~8개. 짧은 명사 위주로.
   이 태그들이 나중에 이 영상을 다시 찾는 핵심 단서이니 summary 보다 중요합니다.
   태그의 철자는 name·ingredients·summary 에 쓴 표기와 정확히 일치시키세요 —
   같은 단어를 다르게 적으면(예: 요약은 "밥간장", 태그는 "밥간정") 검색이 깨집니다.

모든 텍스트는 한국어로, 반드시 JSON으로만 답하세요 (키: category,name,ingredients,cookMinutes,steps,confidentSeasonings,summary,tags).

음성 전사:
{transcript}
"""

TITLE_SUFFIX = "\n\n영상 제목 원문:\n{title}"
DESCRIPTION_SUFFIX = "\n\n영상 설명란 원문:\n{description}"

# 재료 사전 감사 프롬프트 — IngredientAuditor.PROMPT(springboot)과 동일한 지시 (일관성 유지).
# Gemini 가 429/503/타임아웃일 때 IngredientAuditController 가 여기로 폴백한다 (2026-07-18 확정).
# 판정 대상(pendingNames)만 판정하고, allRepresentatives 는 mergeInto 후보를 찾는 참고 자료일
# 뿐이다 — 응답 크기가 사전 전체가 아니라 신규 개수에만 비례하게 만드는 핵심(springboot 쪽과
# 동일 이유, 2026-07-18).
AUDIT_PROMPT = """\
아래 "판정 대상" 목록의 각 이름에 대해서만 두 가지를 판정하세요.
"참고용 전체 대표 목록"은 판정하지 마세요 — mergeInto 값을 고를 때만 참고하는 자료입니다.

[1] tier — BASIC / SEASONING / MAIN 중 하나.
- BASIC: 어느 집에나 늘 있는 상비 양념. 물·소금·설탕·간장·식용유·후추·참기름·통깨·
  다진마늘·고춧가루·식초 같은 것. "장 보러 갈 필요가 없는" 것만.
- SEASONING: 양념·조미료지만 없을 수 있어 사러 가야 하는 것.
  고추장·굴소스·두반장·액젓·물엿·마요네즈 같은 것.
- MAIN: 그 외 실제 식재료(고기·채소·두부·면·떡 등).
확실하지 않으면 MAIN 으로 두세요(안전 기본값 — 양념으로 잘못 빼면 레시피가 추천에서
사라지지만, 주재료로 두면 "부족 재료"로 보일 뿐이라 덜 위험합니다).

[2] mergeInto — 이 이름을 흡수할 대표 이름. 묶을 필요가 없으면 빈 문자열.
같은 것이 여러 이름으로 흩어져 있으면 대표 하나로 묶습니다. 냉장고에 대표가 있으면
멤버도 있는 것으로 칩니다.
- 대표 이름은 반드시 "참고용 전체 대표 목록" 안에 있는 이름이어야 합니다(판정 대상 자기
  자신은 제외 — 자기 자신으로 묶는 건 의미가 없습니다). 새 이름을 만들지 마세요.
- 묶는 예: 수량·괄호 표기만 다른 것("계란 2개" → "계란"), 구성품("라면 건더기스프" →
  "라면", 후레이크·스프도 같음), 상표·세부 변형이라 서로 대체되는 것("신라면" → "라면",
  "밀떡" → "떡", "대파" → "파").
- 절대 묶으면 안 되는 예: 실제로 다른 재료. "진간장"과 "간장", "맛소금"과 "소금",
  "파프리카"와 "파"는 각각 다른 것이라 묶지 마세요.
- 판단 기준: "대표가 냉장고에 있으면 이 재료로 요리할 수 있는가?" 가 확실히 참일 때만
  묶으세요. 조금이라도 애매하면 빈 문자열로 두세요 — 안 묶으면 매칭이 덜 될 뿐이지만,
  잘못 묶으면 없는 재료를 있다고 말하게 됩니다.

판정 대상만 판정하고 표기를 바꾸지 마세요. 반드시 JSON 배열로만 답하세요.
각 항목은 {{"name":..., "tier":..., "mergeInto":...}}.

판정 대상:
{pending_names}

참고용 전체 대표 목록(mergeInto 후보):
{all_representatives}
"""

_lock = threading.Lock()


def run(cmd, timeout=120):
    result = subprocess.run(cmd, capture_output=True, text=True, timeout=timeout)
    if result.returncode != 0:
        raise RuntimeError(f"명령 실패({cmd[0]}): {result.stderr[-2000:]}")
    return result.stdout


def probe_duration_seconds(video_path):
    out = run([FFPROBE, "-v", "error", "-show_entries", "format=duration",
               "-of", "default=noprint_wrappers=1:nokey=1", video_path], timeout=30)
    try:
        return float(out.strip())
    except ValueError:
        return 60.0


def download_video(video_url, work_dir):
    out_template = os.path.join(work_dir, "video.%(ext)s")
    run([YT_DLP, "-f", "bv*[height<=480]+ba/best[height<=480]/best",
         "--merge-output-format", "mp4", "--ffmpeg-location", FFMPEG,
         "-o", out_template, video_url], timeout=180)
    matches = glob.glob(os.path.join(work_dir, "video.*"))
    if not matches:
        raise RuntimeError("yt-dlp 다운로드 결과 파일 없음")
    return matches[0]


def extract_frames(video_path, work_dir, duration):
    interval = max(MIN_FRAME_INTERVAL_SECONDS,
                   min(MAX_FRAME_INTERVAL_SECONDS, round(duration / TARGET_FRAMES) or 1))
    pattern = os.path.join(work_dir, "frame_%03d.jpg")
    run([FFMPEG, "-y", "-i", video_path, "-vf", f"fps=1/{interval}", "-q:v", "3", pattern], timeout=60)
    return sorted(glob.glob(os.path.join(work_dir, "frame_*.jpg")))


def extract_audio(video_path, work_dir):
    audio_path = os.path.join(work_dir, "audio.wav")
    run([FFMPEG, "-y", "-i", video_path, "-ar", "16000", "-ac", "1", "-c:a", "pcm_s16le", audio_path],
        timeout=60)
    return audio_path


def transcribe(audio_path, work_dir):
    prefix = os.path.join(work_dir, "transcript")
    run([WHISPER_CLI, "-m", WHISPER_MODEL, "-f", audio_path, "-l", "ko",
         "--output-txt", "-of", prefix], timeout=180)
    with open(prefix + ".txt", encoding="utf-8") as f:
        return f.read().strip()


def build_payload(frames, transcript, title, description):
    prompt_text = PROMPT_TEMPLATE.format(transcript=transcript or "(음성 없음)")
    if title:
        prompt_text += TITLE_SUFFIX.format(title=title)
    if description:
        prompt_text += DESCRIPTION_SUFFIX.format(description=description)
    content = [{"type": "text", "text": prompt_text}]
    for frame_path in frames:
        with open(frame_path, "rb") as img:
            b64 = base64.b64encode(img.read()).decode()
        content.append({"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + b64}})
    # max_tokens: 800 → 2000 (2026-07-16). 한국어 재료 12개+단계 5문장+태그 8개면 800 이 빠듯해
    # 잘릴 여지가 있었다(잘리면 JSON 이 안 닫혀 parse_model_json 이 예외 → Gemini 로 전체 폴백).
    # 이번에 발견된 "재료 빈 목록" 증상의 원인은 아니었지만(빈 배열은 잘림이 아님) 여유를 둔다.
    return {"model": LM_STUDIO_MODEL, "messages": [{"role": "user", "content": content}], "max_tokens": 2000}


def call_local_model(payload, timeout=120):
    req = Request(LM_STUDIO_URL, data=json.dumps(payload).encode("utf-8"),
                  headers={"Content-Type": "application/json"}, method="POST")
    with urlopen(req, timeout=timeout) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    return body["choices"][0]["message"]["content"]


def parse_model_json(text):
    # 모델이 ```json ... ``` 코드펜스로 감싸는 경우가 있어 벗겨낸다 (실측 확인)
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        raise RuntimeError(f"모델 응답에서 JSON 을 못 찾음: {text[:500]}")
    return json.loads(match.group(0))


def parse_model_json_array(text):
    match = re.search(r"\[.*\]", text, re.DOTALL)
    if not match:
        raise RuntimeError(f"모델 응답에서 JSON 배열을 못 찾음: {text[:500]}")
    return json.loads(match.group(0))


def _x_format_options(formats):
    # X 는 화질별로 두 종류를 같이 준다 — "https"(진짜 통짜 mp4 파일, 소리 포함)와
    # "m3u8_native"(스트리밍 조각 재생목록 — 파일이 아니라 텍스트 매니페스트라 그대로 저장하면
    # 재생이 안 됨, 소리도 별도 트랙으로 빠져 있음). vcodec/acodec 필드로 "소리 있는 쪽"을
    # 고르려 했던 이전 로직은 틀렸다 — https 쪽은 yt-dlp 가 코덱을 프로브하지 않아 vcodec 이
    # None(문자열 "none" 아님)이라 오히려 걸러졌었다(2026-07-20 실사용 다운로드 실패 제보로
    # 원인 확인, avc1+mp4a 코덱이 실제로 들어있는 걸 바이트로 직접 검증). protocol=="https" 인
    # 것만 남기면 된다 — X 플랫폼 특성상 이 진행형(progressive) mp4 는 항상 완성된 파일이다.
    best_by_height = {}
    for f in formats or []:
        height = f.get("height")
        video_url = f.get("url")
        if f.get("protocol") != "https" or not height or not video_url:
            continue
        best_by_height[height] = {"height": height, "url": video_url}
    return sorted(best_by_height.values(), key=lambda o: o["height"], reverse=True)


_x_lock = threading.Lock()
_x_last_request_at = 0.0
_x_blocked_until = 0.0


def _x_throttle():
    """X 로 나가는 요청을 한 번에 하나씩, 최소 간격을 두고 내보낸다 — 락을 요청 전체 동안 쥐고
    있어서 대기 중인 다른 요청은 자연스럽게 순서대로 줄을 선다(2026-07-20 확정, 별도 큐 자료구조
    없이 lock 하나로 같은 효과)."""
    global _x_last_request_at
    now = time.time()
    if now < _x_blocked_until:
        wait_left = int(_x_blocked_until - now)
        raise RuntimeError(f"X 요청이 일시적으로 제한돼 있음 (약 {wait_left}초 후 재시도)")
    wait = X_MIN_REQUEST_INTERVAL_SECONDS - (now - _x_last_request_at)
    if wait > 0:
        time.sleep(wait)
    _x_last_request_at = time.time()


def _x_note_possible_rate_limit(message):
    """429 로 보이는 실패면 한동안 아예 요청을 안 내보낸다 — X 의 실제 차단 기준은 공개돼 있지
    않아(2026-07-20 사용자 질문 계기) 정확한 값 대신 보수적으로 길게 쉬는 쪽을 택한다."""
    global _x_blocked_until
    if "429" in message or "Too Many Requests" in message:
        _x_blocked_until = time.time() + X_BACKOFF_SECONDS


def resolve_x_formats(url):
    """X(트위터) 영상의 직접 CDN 주소만 뽑아낸다 — 다운로드하지 않는다 (2026-07-20 확정).
    recipe 의 분석 파이프라인(extract)과 달리 서버가 영상 바이트를 만지지 않는 게 목적이라
    yt-dlp -J(메타데이터만) 로 포맷 목록을 조회해 twimg.com 주소를 그대로 돌려준다.

    영상이 여러 개인 게시물은 yt-dlp 가 최상위에 formats 가 없는 "playlist" 구조(entries 목록,
    각 항목이 독립된 영상 하나)로 돌려준다 — 응답을 항상 items 목록으로 통일해 1개짜리도
    같은 모양으로 다룬다 (2026-07-20 확정, 3개짜리 게시물 다운로드 실패 제보로 발견).
    """
    cmd = [YT_DLP, "-J", "--no-warnings"]
    if os.path.isfile(X_COOKIES_FILE):
        cmd += ["--cookies", X_COOKIES_FILE]
    cmd.append(url)
    with _x_lock:
        _x_throttle()
        try:
            out = run(cmd, timeout=30)
        except RuntimeError as e:
            # 영상이 아예 없는 게시물(사진·글만 있는 트윗)은 일시적 오류가 아니라 확정된 사실이라
            # 빈 목록(200)으로 응답 — Spring 이 이미 "items 비면 404" 로 처리해 재시도 유도 문구
            # (503) 대신 "못 찾았다"는 정확한 문구가 뜬다 (2026-07-20 실사용 제보로 구분).
            if "No video could be found" in str(e):
                return {"items": []}
            _x_note_possible_rate_limit(str(e))
            raise
    info = json.loads(out)
    raw_entries = info.get("entries") if info.get("_type") == "playlist" else [info]
    items = []
    for entry in (raw_entries or []):
        options = _x_format_options(entry.get("formats"))
        if not options:
            continue  # 이 항목만 다운로드 가능한 형식이 없음 — 나머지 항목은 그대로 살린다
        items.append({
            "title": entry.get("title") or "",
            "thumbnail": entry.get("thumbnail") or "",
            "options": options,
        })
    return {"items": items}


def sweep_orphaned_temp_dirs():
    """실패로 남은 이전 임시 폴더 청소 — 서버 기동 시 1회 (2026-07-14 확정, 안전망)"""
    base = tempfile.gettempdir()
    for name in os.listdir(base):
        if name.startswith(TEMP_DIR_PREFIX):
            shutil.rmtree(os.path.join(base, name), ignore_errors=True)


def extract(video_url, title, description):
    work_dir = tempfile.mkdtemp(prefix=TEMP_DIR_PREFIX)
    try:
        video_path = download_video(video_url, work_dir)
        duration = probe_duration_seconds(video_path)
        frames = extract_frames(video_path, work_dir, duration)
        audio_path = extract_audio(video_path, work_dir)
        transcript = transcribe(audio_path, work_dir)
        payload = build_payload(frames, transcript, title, description)
        result = parse_model_json(call_local_model(payload))
        # 음성 인식이 실제로 얼마나 됐는지 — 백엔드가 분석 품질 신호로 저장 (2026-07-14 확정,
        # 이 자체는 판단이 아니라 사실이라 여기선 개수만 실어 보낸다. 경고 여부 판정은 백엔드 몫)
        result["transcriptChars"] = len((transcript or "").strip())
        return result
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)


def build_audit_payload(pending_names, all_representatives):
    prompt_text = AUDIT_PROMPT.format(
        pending_names="\n".join(pending_names),
        all_representatives="\n".join(all_representatives))
    # max_tokens 는 판정 대상(pending_names) 개수에만 비례 — 응답이 그 개수만큼의
    # {name,tier,mergeInto} 객체이기 때문(2026-07-18, 전체 대표를 판정 대상으로 보내던 예전
    # 방식이 사전 크기(실측 243개)에 비례해 자라다 이 상한을 넘겨 503 이 났던 문제의 근본 수정).
    max_tokens = min(32000, max(500, len(pending_names) * 40))
    return {"model": LM_STUDIO_MODEL, "messages": [{"role": "user", "content": prompt_text}],
            "max_tokens": max_tokens}


def audit_ingredients(pending_names, all_representatives):
    payload = build_audit_payload(pending_names, all_representatives)
    return parse_model_json_array(call_local_model(payload, timeout=180))


def health():
    """지금 도는 프로세스가 스스로 관측한 사실만 돌려준다 (2026-07-16 신설).

    계기: 하루에 같은 종류의 사고가 두 번 났다. (1) 저장소는 최신인데 launchd 는 손으로 뜬
    사본을 돌고 있었고, (2) deno 는 설치돼 있는데 launchd 의 최소 PATH 라 yt-dlp 가 못 찾았다.
    둘 다 "설정상 맞는데 실제 도는 환경은 다르다"라서, 설정 파일을 아무리 봐도 안 보였다.
    그래서 여기서는 설정값이 아니라 **이 프로세스의 실제 상태**를 보고한다 — 실행 중인 파일의
    절대경로, 이 프로세스가 받은 PATH 에서 실제로 찾아지는 도구들.

    판정("정상/비정상")과 문구는 여기서 하지 않는다 — 사실만 나르고 판단은 프론트가 한다
    (springboot/AGENTS.md 의 pattern-raw-signal 과 같은 사상).

    주의: _lock 을 잡지 말 것. /extract 는 영상당 수십 초~수 분 락을 쥐는데, health 가 같은
    락을 기다리면 "분석 중일 때만 상태 조회가 멎는" 최악의 동작이 된다 — 정작 알고 싶은 때다.
    """
    return {
        "serverPath": os.path.realpath(__file__),  # 사본을 돌고 있으면 여기서 드러난다
        "targetFrames": TARGET_FRAMES,
        "lmStudioModel": LM_STUDIO_MODEL,
        "whisperModelExists": os.path.isfile(WHISPER_MODEL),
        # shutil.which 는 이 프로세스가 실제로 받은 PATH 로 찾는다 — deno 사고를 잡는 지점.
        # yt-dlp·ffmpeg 는 절대경로로 부르므로 그 경로의 존재 여부로 본다(찾는 방식이 다르므로).
        "ytDlpExists": os.path.isfile(YT_DLP),
        "ffmpegExists": os.path.isfile(FFMPEG),
        "whisperCliExists": os.path.isfile(WHISPER_CLI),
        "denoOnPath": shutil.which("deno") is not None,
    }


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(f"[gikka-local] {self.address_string()} - {fmt % args}", flush=True)

    def do_GET(self):
        if self.path != "/health":
            self.send_response(404)
            self.end_headers()
            return
        body = json.dumps(health(), ensure_ascii=False).encode("utf-8")
        self.send_response(200)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(body)))
        self.end_headers()
        self.wfile.write(body)

    def _read_chunked_body(self):
        # Spring RestClient(JDK HttpClient)가 Content-Length 없이 chunked 로 보내는
        # 요청을 지원 (2026-07-14 실측 — Content-Length 만 보면 빈 본문으로 읽혀 실패했음).
        chunks = []
        while True:
            line = self.rfile.readline()
            if not line:
                break
            size_str = line.strip().split(b";", 1)[0]
            if not size_str:
                continue
            chunk_size = int(size_str, 16)
            if chunk_size == 0:
                while True:
                    trailer = self.rfile.readline()
                    if trailer in (b"\r\n", b"\n", b""):
                        break
                break
            chunks.append(self.rfile.read(chunk_size))
            self.rfile.readline()  # 청크 데이터 뒤의 CRLF 소비
        return b"".join(chunks)

    def do_POST(self):
        if self.path not in ("/extract", "/audit", "/x-resolve"):
            self.send_response(404)
            self.end_headers()
            return
        if "chunked" in self.headers.get("Transfer-Encoding", "").lower():
            body = self._read_chunked_body()
        else:
            length = int(self.headers.get("Content-Length", 0))
            body = self.rfile.read(length)
        try:
            req = json.loads(body)
            if self.path == "/x-resolve":
                # LM Studio·whisper 를 안 쓰는 순수 조회라 _lock 대상 밖 (2026-07-20 확정)
                result = resolve_x_formats(req["url"])
            else:
                # 단일 워커(RegistrationWorker) 전제이나, 두 앱 인스턴스가 동시에 호출할 가능성을
                # 대비해 직렬화 — LM Studio·whisper 는 한 번에 하나씩만 처리 (2026-07-14 확정)
                with _lock:
                    if self.path == "/extract":
                        result = extract(req["videoUrl"], req.get("title"), req.get("description"))
                    else:
                        result = audit_ingredients(req["pendingNames"], req.get("allRepresentatives", []))
            body = json.dumps(result, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            # flush 필수 — 파일로 리다이렉트되면 블록 버퍼링이라 실패가 로그에 한참 안 뜬다
            # (2026-07-16: 방금 실패한 요청이 로그에 안 보여 "요청이 안 갔나" 하고 헤맸음)
            print(f"[gikka-local] 처리 실패: {e}", flush=True)
            body = json.dumps({"error": str(e)}).encode("utf-8")
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)


def main():
    sweep_orphaned_temp_dirs()
    # ThreadingHTTPServer 로 바꾼 이유 (2026-07-16): 이전엔 HTTPServer(단일 스레드)가 요청을
    # 직렬화했는데, /extract 가 영상당 수십 초~수 분을 쥐고 있어서 그동안 /health 가 통째로
    # 막혔다 — 상태를 알고 싶은 순간이 바로 분석 중일 때이므로 쓸모가 없어진다.
    # 자원 경합(LM Studio·whisper) 방지는 원래부터 _lock 이 담당하고 있어서(직렬화 장치가
    # 두 겹이었다) 스레드로 바꿔도 /extract 는 여전히 한 번에 하나씩만 돈다.
    server = ThreadingHTTPServer(("0.0.0.0", PORT), Handler)
    print(f"[gikka-local] listening on :{PORT} (model={LM_STUDIO_MODEL}, whisper={WHISPER_MODEL})",
          flush=True)
    server.serve_forever()


if __name__ == "__main__":
    main()
