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
from http.server import BaseHTTPRequestHandler, HTTPServer
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
TARGET_FRAMES = 12
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
   - cookMinutes: 예상 조리 시간(분). 영상에서 알 수 없으면 생략.
   - steps: 조리 순서 요약. 각 단계를 짧은 한 문장으로, 3~7개.
3. RECIPE 가 아닌 경우에만:
   - summary: 영상의 요점 요약 2~3문장. 나중에 다시 찾을 때 내용을 떠올릴 수 있게.
   주의: 화면·음성·설명란에서 명확히 확인되지 않는 고유명사(인물 이름, 지명, 특정 사건·경기
   등)는 절대 단정해서 쓰지 마세요. 확실하지 않으면 "한 선수가", "경기 중" 처럼 일반적인
   표현으로 대체하세요 (2026-07-14 확정, 실측에서 모델이 사전 지식으로 없는 인물명을 지어낸
   사례 발견). 이 요약은 나중에 원본 영상을 찾기 위한 실마리일 뿐이니, 그럴듯하게 지어내는
   것보다 짧고 정확한 편이 낫습니다 — 아는 것만 쓰고 모르는 건 생략하세요.
4. tags: 모든 영상 공통. 이 영상을 검색할 때 쓸 만한 키워드 3~8개. 짧은 명사 위주로.
   이 태그들이 나중에 이 영상을 다시 찾는 핵심 단서이니 summary 보다 중요합니다.

모든 텍스트는 한국어로, 반드시 JSON으로만 답하세요 (키: category,name,ingredients,cookMinutes,steps,summary,tags).

음성 전사:
{transcript}
"""

DESCRIPTION_SUFFIX = "\n\n영상 설명란 원문:\n{description}"

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


def build_payload(frames, transcript, description):
    prompt_text = PROMPT_TEMPLATE.format(transcript=transcript or "(음성 없음)")
    if description:
        prompt_text += DESCRIPTION_SUFFIX.format(description=description)
    content = [{"type": "text", "text": prompt_text}]
    for frame_path in frames:
        with open(frame_path, "rb") as img:
            b64 = base64.b64encode(img.read()).decode()
        content.append({"type": "image_url", "image_url": {"url": "data:image/jpeg;base64," + b64}})
    return {"model": LM_STUDIO_MODEL, "messages": [{"role": "user", "content": content}], "max_tokens": 800}


def call_local_model(payload):
    req = Request(LM_STUDIO_URL, data=json.dumps(payload).encode("utf-8"),
                  headers={"Content-Type": "application/json"}, method="POST")
    with urlopen(req, timeout=120) as resp:
        body = json.loads(resp.read().decode("utf-8"))
    text = body["choices"][0]["message"]["content"]
    return parse_model_json(text)


def parse_model_json(text):
    # 모델이 ```json ... ``` 코드펜스로 감싸는 경우가 있어 벗겨낸다 (실측 확인)
    match = re.search(r"\{.*\}", text, re.DOTALL)
    if not match:
        raise RuntimeError(f"모델 응답에서 JSON 을 못 찾음: {text[:500]}")
    return json.loads(match.group(0))


def sweep_orphaned_temp_dirs():
    """실패로 남은 이전 임시 폴더 청소 — 서버 기동 시 1회 (2026-07-14 확정, 안전망)"""
    base = tempfile.gettempdir()
    for name in os.listdir(base):
        if name.startswith(TEMP_DIR_PREFIX):
            shutil.rmtree(os.path.join(base, name), ignore_errors=True)


def extract(video_url, description):
    work_dir = tempfile.mkdtemp(prefix=TEMP_DIR_PREFIX)
    try:
        video_path = download_video(video_url, work_dir)
        duration = probe_duration_seconds(video_path)
        frames = extract_frames(video_path, work_dir, duration)
        audio_path = extract_audio(video_path, work_dir)
        transcript = transcribe(audio_path, work_dir)
        payload = build_payload(frames, transcript, description)
        result = call_local_model(payload)
        # 음성 인식이 실제로 얼마나 됐는지 — 백엔드가 분석 품질 신호로 저장 (2026-07-14 확정,
        # 이 자체는 판단이 아니라 사실이라 여기선 개수만 실어 보낸다. 경고 여부 판정은 백엔드 몫)
        result["transcriptChars"] = len((transcript or "").strip())
        return result
    finally:
        shutil.rmtree(work_dir, ignore_errors=True)


class Handler(BaseHTTPRequestHandler):
    def log_message(self, fmt, *args):
        print(f"[gikka-local] {self.address_string()} - {fmt % args}")

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
        if self.path != "/extract":
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
            # 단일 워커(RegistrationWorker) 전제이나, 두 앱 인스턴스가 동시에 호출할 가능성을
            # 대비해 직렬화 — LM Studio·whisper 는 한 번에 하나씩만 처리 (2026-07-14 확정)
            with _lock:
                result = extract(req["videoUrl"], req.get("description"))
            body = json.dumps(result, ensure_ascii=False).encode("utf-8")
            self.send_response(200)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)
        except Exception as e:
            print(f"[gikka-local] 처리 실패: {e}")
            body = json.dumps({"error": str(e)}).encode("utf-8")
            self.send_response(500)
            self.send_header("Content-Type", "application/json")
            self.end_headers()
            self.wfile.write(body)


def main():
    sweep_orphaned_temp_dirs()
    server = HTTPServer(("0.0.0.0", PORT), Handler)
    print(f"[gikka-local] listening on :{PORT} (model={LM_STUDIO_MODEL}, whisper={WHISPER_MODEL})")
    server.serve_forever()


if __name__ == "__main__":
    main()
