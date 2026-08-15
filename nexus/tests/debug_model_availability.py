"""
모델 가용성 및 품질 테스트.
url_context 브라우징 + quiz 함수(explain_word, generate_quiz stage 1/2/3)를 전체 모델 대상으로 테스트합니다.

실행 방법 (nexus/ 디렉토리에서):
    python tests/debug_model_availability.py --list
    python tests/debug_model_availability.py --list-lmstudio
    python tests/debug_model_availability.py --lmstudio-only
    python tests/debug_model_availability.py
    python tests/debug_model_availability.py "https://example.com"
    python tests/debug_model_availability.py "https://example.com" "nexus" "(명사) 연결, 핵심 연결점"
"""
import asyncio
import os
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from google import genai
from google.genai import types as gtypes
from openai import AsyncOpenAI
from config import settings

# ── LM Studio (OpenAI 호환) 설정 ──────────────────────────────────
LMSTUDIO_BASE_URL = "http://100.69.229.3:2345/v1"
LMSTUDIO_API_KEY  = "lm-studio"
LMSTUDIO_TIMEOUT  = 60.0

# ── url_context 테스트 모델 목록 ─────────────────────────────────
URL_MODELS = [
    "gemini-3.1-flash-lite",
    "gemini-2.5-flash-lite",
    "gemini-pro-latest",
    "gemini-flash-latest",
    "gemini-flash-lite-latest"
]

# ── quiz 테스트 모델 목록 ────────────────────────────────────────
QUIZ_MODELS = [
    "gemini-3.1-flash-lite",
    "gemini-2.5-flash-lite",
    "gemini-pro-latest",
    "gemini-flash-latest",
    "gemini-flash-lite-latest"
]
# ────────────────────────────────────────────────────────────────

DEFAULT_URL     = "https://www.google.com"
DEFAULT_WORD    = "nexus"
DEFAULT_MEANING = "(명사) 연결, 핵심 연결점"


async def call_model(client: genai.Client, model: str, prompt: str, use_url_context: bool = False) -> dict:
    config_kwargs = dict(
        automatic_function_calling=gtypes.AutomaticFunctionCallingConfig(disable=True)
    )
    if use_url_context:
        config_kwargs["tools"] = [gtypes.Tool(url_context=gtypes.UrlContext())]

    config  = gtypes.GenerateContentConfig(**config_kwargs)
    timeout = 40.0 if use_url_context else 30.0
    t       = time.time()
    try:
        response = await asyncio.wait_for(
            client.aio.models.generate_content(
                model=model, contents=prompt, config=config,
            ),
            timeout=timeout,
        )
        return {"ok": True, "elapsed": time.time() - t, "response": response.text.strip()}
    except asyncio.TimeoutError:
        return {"ok": False, "elapsed": time.time() - t, "error": "TIMEOUT"}
    except Exception as e:
        return {"ok": False, "elapsed": time.time() - t, "error": str(e)[:120]}


def print_result(model: str, result: dict, response_limit: int = 800) -> None:
    if result["ok"]:
        print(f"  ✔  {model} ({result['elapsed']:.2f}s)")
        for line in result["response"][:response_limit].splitlines():
            print(f"     {line}")
    else:
        print(f"  ✘  {model} ({result['elapsed']:.2f}s) → {result['error']}")


def section(title: str, sub: str = "") -> None:
    print(f"\n{'─'*60}")
    print(f"  {title}")
    if sub:
        print(f"  {sub}")
    print(f"{'─'*60}")


def make_lmstudio_client() -> AsyncOpenAI:
    return AsyncOpenAI(base_url=LMSTUDIO_BASE_URL, api_key=LMSTUDIO_API_KEY)


async def detect_lmstudio_model(client: AsyncOpenAI) -> str | None:
    try:
        models = await client.models.list()
        ids = [m.id for m in models.data]
        if settings.LMSTUDIO_MODEL:
            return settings.LMSTUDIO_MODEL if settings.LMSTUDIO_MODEL in ids else None
        return ids[0] if ids else None
    except Exception as e:
        print(f"  ✘  LM Studio /v1/models 호출 실패: {e}")
        return None


async def call_lmstudio(client: AsyncOpenAI, model: str, prompt: str) -> dict:
    t = time.time()
    try:
        response = await asyncio.wait_for(
            client.chat.completions.create(
                model=model,
                messages=[{"role": "user", "content": prompt}],
                #extra_body={"chat_template_kwargs": {"enable_thinking": False, "thinking_budget": 512}},
            ),
            timeout=LMSTUDIO_TIMEOUT,
        )
        return {"ok": True, "elapsed": time.time() - t, "response": response.choices[0].message.content.strip()}
    except asyncio.TimeoutError:
        return {"ok": False, "elapsed": time.time() - t, "error": "TIMEOUT"}
    except Exception as e:
        return {"ok": False, "elapsed": time.time() - t, "error": str(e)[:120]}


async def list_lmstudio_models() -> None:
    print(f"\n{'='*60}")
    print(f"  LM Studio 로드된 모델 ({LMSTUDIO_BASE_URL})")
    print(f"{'='*60}")
    client = make_lmstudio_client()
    try:
        models = await client.models.list()
        for m in models.data:
            print(f"  {m.id}")
        print(f"\n총 {len(models.data)}개\n")
    except Exception as e:
        print(f"  ✘  실패: {e}\n")


async def list_models(client: genai.Client) -> None:
    print(f"\n{'='*60}")
    print("  사용 가능한 모델 목록")
    print(f"{'='*60}")
    models = [m async for m in await client.aio.models.list()]
    for m in sorted(models, key=lambda x: x.name):
        print(f"  {m.name}")
    print(f"\n총 {len(models)}개\n")


async def run_url_context(client: genai.Client, url: str) -> None:
    prompt = f"이 URL의 제목을 한 줄로 말해줘. 링크: {url}"
    section("[1] url_context", f"URL: {url}")
    for model in URL_MODELS:
        result = await call_model(client, model, prompt, use_url_context=True)
        print_result(model, result)


async def run_quiz(client: genai.Client, word: str, meaning_ko: str, *, gemini_enabled: bool = True, lmstudio_enabled: bool = True) -> None:
    explain_prompt = f"""아래 단어나 문장을 설명해줘. 반드시 아래 형식으로만 답해. 다른 말 붙이지 마.
단어: (핵심 단어 또는 표현)
뜻: (품사별로 주요 의미. 한국어, 한 줄)
예문: (가장 대표적인 용법의 영어 예문 1줄)

입력: {word}"""

    stage1_prompt = """아래 상황 퀴즈에 답해줘. 추론 근거를 한국어로 짧게 설명하고 결론을 한 줄로 내려.
상황: 나는 세차를 하고싶다. 세차장은 25미터 앞에 있다. 걸어가야 할까?, 차로 가야할까?

형식:
근거: (1~2줄)
결론: (걸어간다 / 차로 간다 중 택1)"""

    stage2_prompt = f"""Make an English fill-in-the-blank sentence. Follow all conditions strictly.
Conditions: Only 1 blank. Only "{word}" fits naturally. Elementary school level. No markdown. Output the sentence only 1.
Format: Use _______ for the blank.
Word: {word}"""

    stage3_prompt = f"""너는 영어 단어 학습 퀴즈 출제자야. 학습자는 "{word}"라는 단어를 모르는 상태로 문제를 풀어.
아래 단어를 영어 작문에 정확한 품사로 써야만 자연스러운 한국어 상황 문장한개를 만들어줘.

규칙:
- 뜻({meaning_ko})에 표기된 품사에 맞는 상황.
- "{word}" 외 다른 영단어로는 어색한 상황.
- 30자 이내 짧은 대화체.
- 빈칸·괄호·밑줄 사용 금지.
- 마크다운 금지.
- 한국어 문장에 영단어 절대 포함 금지. (영어 알파벳 사용 금지)

반드시 아래 형식으로만 딱 한문장만 출력해. 다른 말 일절 금지.
상황: (완성된 한국어 문장)

단어: {word} / 뜻: {meaning_ko}"""

    grammar_sentence = "the rumor of that movie was hype, I was very expect that"
    grammar_prompt = f"""아래 영어 문장의 문법 오류를 모두 찾아 정리해줘. 반드시 아래 형식으로만 답해. 다른 말 붙이지 마.
문장: {grammar_sentence}

형식:
1. (오류 부분) → (수정) : (간단 설명)
2. ...
교정문: (전체 문장을 자연스럽게 고친 영어 한 줄)"""

    lm_client = make_lmstudio_client() if lmstudio_enabled else None
    lm_model: str | None = None
    if lmstudio_enabled:
        lm_model = await detect_lmstudio_model(lm_client)
        if lm_model is None:
            print("  (LM Studio 모델 감지 실패 → 스킵)")
            lmstudio_enabled = False

    for label, prompt in [
        ("[2] explain_word", explain_prompt),
        ("[3] situation_quiz — 세차 상황 추론", stage1_prompt),
        ("[4] generate_quiz stage2 — 빈칸 채우기", stage2_prompt),
        ("[5] generate_quiz stage3 — 한국어 상황 문장", stage3_prompt),
        ("[6] grammar_check — 문법 오류 찾기", grammar_prompt),
    ]:
        sub = f"sentence={grammar_sentence}" if label.startswith("[6]") else f"word={word}, meaning={meaning_ko}"
        section(label, sub)
        if gemini_enabled:
            for model in QUIZ_MODELS:
                result = await call_model(client, model, prompt)
                print_result(model, result)
        if lmstudio_enabled:
            result = await call_lmstudio(lm_client, lm_model, prompt)
            print_result(f"lmstudio:{lm_model}", result)


async def main(url: str, word: str, meaning_ko: str, *, lmstudio_only: bool = False) -> None:
    client = None if lmstudio_only else genai.Client(api_key=settings.GEMINI_API_KEY)

    print(f"\n{'='*60}")
    print("  모델 가용성 및 품질 테스트")
    if lmstudio_only:
        print("  (LM Studio only)")
    else:
        print(f"  [URL 모델] {', '.join(URL_MODELS)}")
        print(f"  [QUIZ 모델] {', '.join(QUIZ_MODELS)}")
    lm_probe = make_lmstudio_client()
    lm_id = await detect_lmstudio_model(lm_probe)
    if lm_id:
        print(f"  [LM Studio] {lm_id}")
    print(f"{'='*60}")

    if not lmstudio_only:
        await run_url_context(client, url)
    await run_quiz(client, word, meaning_ko, gemini_enabled=not lmstudio_only, lmstudio_enabled=True)

    print(f"\n{'='*60}\n")


if __name__ == "__main__":
    args = sys.argv[1:]
    if args and args[0] == "--list":
        client = genai.Client(api_key=settings.GEMINI_API_KEY)
        asyncio.run(list_models(client))
    elif args and args[0] == "--list-lmstudio":
        asyncio.run(list_lmstudio_models())
    elif args and args[0] == "--lmstudio-only":
        rest = args[1:]
        url        = rest[0] if len(rest) > 0 else DEFAULT_URL
        word       = rest[1] if len(rest) > 1 else DEFAULT_WORD
        meaning_ko = rest[2] if len(rest) > 2 else DEFAULT_MEANING
        asyncio.run(main(url, word, meaning_ko, lmstudio_only=True))
    else:
        url        = args[0] if len(args) > 0 else DEFAULT_URL
        word       = args[1] if len(args) > 1 else DEFAULT_WORD
        meaning_ko = args[2] if len(args) > 2 else DEFAULT_MEANING
        asyncio.run(main(url, word, meaning_ko))
