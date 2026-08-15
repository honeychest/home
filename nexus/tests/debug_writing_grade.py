"""
3단계 작문 채점 대화형 테스트.
전체 흐름: 단어 입력 → 문제 출제 → 작문 입력 → 채점(보조어휘/교정힌트/모범답안) → 재시도(최대 5회)

실행 방법 (nexus/ 디렉토리에서):
    python tests/debug_writing_grade.py
    python tests/debug_writing_grade.py --lmstudio-only
"""
import asyncio
import os
import re
import sys
import time

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from google import genai
from google.genai import types as gtypes
from openai import AsyncOpenAI
from config import settings

# ── 모델 설정 (model_runner.py 와 동일) ──────────────────────────
MODELS = [
    "gemini-flash-lite-latest",
    "gemini-flash-latest",
    "gemini-pro-latest",
    "gemini-3.1-flash-lite",
    "gemini-2.5-flash-lite",
    "gemini-2.5-flash",
]

LMSTUDIO_BASE_URL = settings.LMSTUDIO_BASE_URL
LMSTUDIO_API_KEY = settings.LMSTUDIO_API_KEY
LMSTUDIO_TIMEOUT = settings.LMSTUDIO_TIMEOUT

MAX_RETRY = 5


# ── 프롬프트 ─────────────────────────────────────────────────────

def build_explain_prompt(word: str) -> str:
    """단어 뜻 조회 프롬프트 (ai_service.explain_word 과 동일)"""
    return f"""아래 단어나 문장을 설명해줘. 반드시 아래 형식으로만 답해. 다른 말 붙이지 마.
단어: (핵심 단어 또는 표현. make/take/have/do/pay/give 같은 light verb와 고정적으로 결합하는 명사라면 대표 동사구로 변환. 예: effort → make an effort, poll → take a poll, attention → pay attention to. 일반 동사·형용사·범용 명사는 원어 그대로.)
뜻: (품사별로 주요 의미 모두 나열. 어려운 개념은 비유나 쉬운 말로 풀어서. 형식: (품사) 의미 / (품사) 의미. 한국어, 한 줄)
예문: (가장 대표적인 용법의 영어 예문 1줄)

입력: {word}"""


def parse_explain_response(text: str) -> dict:
    word = re.search(r'단어:\s*(.*)', text)
    meaning_ko = re.search(r'뜻:\s*(.*)', text)
    example = re.search(r'예문:\s*(.*)', text)
    return {
        "word": word.group(1).strip() if word else "",
        "meaning_ko": meaning_ko.group(1).strip() if meaning_ko else "",
        "example": example.group(1).strip() if example else "",
    }


def build_quiz_prompt(word: str, meaning_ko: str) -> str:
    """stage 3 문제 출제 프롬프트 (ai_service._quiz_prompt stage>=3 과 동일)"""
    return f"""너는 영어 단어 학습 퀴즈 출제자야. 학습자는 "{word}"라는 단어를 모르는 상태로 문제를 풀어.
아래 단어를 영어 작문에 정확한 품사로 써야만 자연스러운 한국어 상황 문장을 만들어줘.

규칙:
- 뜻({meaning_ko})에 표기된 품사에 맞는 상황. 예) 형용사면 명사 수식 상황, 동사면 행동 상황.
- "{word}" 외 다른 영단어로는 어색한 상황.
- 25자 이내 짧은 대화체.
- 빈칸·괄호·밑줄 사용 금지.
- 마크다운 금지.
- 한국어 문장에 영단어 절대 포함 금지. (영어 알파벳 사용 금지)

반드시 아래 형식으로만 출력해. 다른 말 일절 금지.
상황: (완성된 한국어 문장)

단어: {word} / 뜻: {meaning_ko}"""


def build_grade_prompt(word: str, meaning_ko: str, question: str, answer: str) -> str:
    """채점 프롬프트 — 모범답안, 보조어휘, 교정힌트 포함"""
    return f"""아래 영어 작문을 채점해줘. 반드시 아래 형식으로만 답해.
사용여부: (yes/no) — "{word}"를 포함해서 아래 한국어 문장의 의미를 영어로 전달했는지. 시제·관사·단복수 같은 문법 오류가 있어도 의미가 전달되면 yes.
맥락: (yes/no) — 단어 없어도 의미 전달이 됐는지
오류: (명백한 문법 규칙 위반만. 더 자연스러운 표현이나 다른 단어 선택은 오류가 아님. 없으면 "없음". 최대 3개.
  형식: "[유형] 틀린부분 → 고친부분" — 반드시 오류 1개당 1줄. 같은 줄에 여러 오류를 쓰지 마. 전체 문장을 쓰지 말고 오류가 있는 단어·구만 짧게 발췌.
  유형은 반드시 아래 중 하나: 관사 / 단복수 / 전치사 / 동사원형 / 시제 / 어순 / 철자 / 접속사 / 연어
  주어·목적어·필수 보어 누락도 오류로 신고.
  연어는 "{word}"가 포함된 관용적 단어 조합만. 문장 교정이나 문장 보완은 연어에 포함하지 않음.
  예시:
  [관사] bread looks → the bread looks
  [시제] he go → he went
  [전치사] listen music → listen to music)
대안표현: (오류 없을 때만. 작문과 비슷한 의미의 다른 표현 1~2개를 "/" 로 구분. 오류 있으면 "없음".)
보조어휘: (한국어 문장에 나온 핵심 명사·동사를 영단어로 3~5개. 반드시 "한국어=영어" 형식, 쉼표 구분. "{word}" 제외. 예: 논문=paper, 밝히다=clarify, 출처=source)
모범답안: (한국어 문장의 의미를 "{word}"를 사용해서 자연스럽게 영작한 문장 1개.)
교정힌트: (학습자 작문에서 가장 큰 문제 1개를 한국어로 짧게 지적. 10자 이내.)
용법힌트: (학습자 작문을 보고 "{word}"의 올바른 사용법을 구체적으로 안내. 학습자가 모르는 부분에 맞춰서. 예: "retention은 명사입니다. 'focus on retention of ~' 또는 'improve retention' 같은 구조로 사용하세요." 한국어, 1~2문장.)

한국어 문장: {question}
단어: {word}
뜻: {meaning_ko}
작문: {answer}"""


# ── 파싱 ─────────────────────────────────────────────────────────

def parse_quiz_response(text: str) -> str:
    m = re.search(r'상황[:：]\s*(.+)', text)
    return m.group(1).strip() if m else text.strip()


def parse_grade_response(text: str) -> dict:
    used = re.search(r'사용여부:\s*(yes|no)', text, re.IGNORECASE)
    context = re.search(r'맥락:\s*(yes|no)', text, re.IGNORECASE)
    errors_raw = re.search(r'오류:\s*([\s\S]*?)(?=\n대안표현:|$)', text)

    grammar_errors: list[dict] = []
    collocation_errors: list[str] = []

    if errors_raw:
        raw = errors_raw.group(1).strip()
        if raw != "없음":
            for line in raw.splitlines():
                line = line.strip()
                if not line:
                    continue
                m = re.match(r'\[(.+?)\]\s*(.*)', line)
                if m:
                    error_type = m.group(1).strip()
                    detail = m.group(2).strip()
                    if error_type == "연어":
                        parts = detail.split("→")
                        collocation_errors.append(parts[1].strip() if len(parts) > 1 else detail)
                    else:
                        grammar_errors.append({"type": error_type, "detail": detail})
                else:
                    grammar_errors.append({"type": "기타", "detail": line})

    alternatives_raw = re.search(r'대안표현:\s*(.*)', text)
    alternatives: list[str] = []
    if alternatives_raw:
        raw_alt = alternatives_raw.group(1).strip()
        if raw_alt and raw_alt != "없음":
            alternatives = [a.strip() for a in raw_alt.split("/") if a.strip()]

    vocab_raw = re.search(r'보조어휘:\s*(.*)', text)
    vocab_hints: list[str] = []
    if vocab_raw:
        raw_v = vocab_raw.group(1).strip()
        if raw_v and raw_v != "없음":
            vocab_hints = [v.strip() for v in raw_v.split(",") if v.strip()]

    example_raw = re.search(r'모범답안:\s*(.*)', text)
    example_sentence = example_raw.group(1).strip() if example_raw else ""

    correction_raw = re.search(r'교정힌트:\s*(.*)', text)
    correction_hint = correction_raw.group(1).strip() if correction_raw else ""

    usage_raw = re.search(r'용법힌트:\s*(.*)', text)
    usage_hint = usage_raw.group(1).strip() if usage_raw else ""

    return {
        "used_correctly": used.group(1).lower() == "yes" if used else False,
        "context_ok": context.group(1).lower() == "yes" if context else False,
        "grammar_errors": grammar_errors,
        "collocation_errors": collocation_errors,
        "alternatives": alternatives,
        "vocab_hints": vocab_hints,
        "example_sentence": example_sentence,
        "correction_hint": correction_hint,
        "usage_hint": usage_hint,
    }


# ── 모델 호출 ────────────────────────────────────────────────────

async def call_gemini(client: genai.Client, prompt: str) -> dict:
    config = gtypes.GenerateContentConfig(
        automatic_function_calling=gtypes.AutomaticFunctionCallingConfig(disable=True)
    )
    for model in MODELS:
        t = time.time()
        try:
            response = await asyncio.wait_for(
                client.aio.models.generate_content(model=model, contents=prompt, config=config),
                timeout=15.0,
            )
            elapsed = time.time() - t
            print(f"  (모델: {model}, {elapsed:.2f}s)")
            return {"ok": True, "response": response.text.strip()}
        except Exception:
            continue
    return {"ok": False, "error": "모든 Gemini 모델 실패"}


async def call_lmstudio(client: AsyncOpenAI, model: str, prompt: str) -> dict:
    t = time.time()
    try:
        response = await asyncio.wait_for(
            client.chat.completions.create(
                model=model,
                messages=[{"role": "user", "content": prompt}],
            ),
            timeout=LMSTUDIO_TIMEOUT,
        )
        elapsed = time.time() - t
        print(f"  (모델: lmstudio:{model}, {elapsed:.2f}s)")
        return {"ok": True, "response": response.choices[0].message.content.strip()}
    except asyncio.TimeoutError:
        return {"ok": False, "error": "TIMEOUT"}
    except Exception as e:
        return {"ok": False, "error": str(e)[:120]}


async def detect_lmstudio_model(client: AsyncOpenAI) -> str | None:
    try:
        models = await client.models.list()
        ids = [m.id for m in models.data]
        return ids[0] if ids else None
    except Exception:
        return None


# ── LLM 호출 통합 ────────────────────────────────────────────────

class LLMCaller:
    def __init__(self, *, lmstudio_only: bool = False):
        self.lmstudio_only = lmstudio_only
        self.gemini_client = None if lmstudio_only else genai.Client(api_key=settings.GEMINI_API_KEY)
        self.lm_client = AsyncOpenAI(base_url=LMSTUDIO_BASE_URL, api_key=LMSTUDIO_API_KEY)
        self.lm_model: str | None = None

    async def init(self):
        self.lm_model = settings.LMSTUDIO_MODEL or await detect_lmstudio_model(self.lm_client)
        if self.lm_model:
            print(f"  [LM Studio] {self.lm_model}")
        elif self.lmstudio_only:
            print("  ✘ LM Studio 모델 감지 실패")
            sys.exit(1)
        if not self.lmstudio_only:
            print(f"  [Gemini] 폴백 체인 활성")

    async def call(self, prompt: str) -> dict:
        if self.lm_model:
            result = await call_lmstudio(self.lm_client, self.lm_model, prompt)
            if result["ok"]:
                return result
            if self.lmstudio_only:
                return result
        if self.gemini_client:
            return await call_gemini(self.gemini_client, prompt)
        return {"ok": False, "error": "사용 가능한 모델 없음"}


# ── 대화형 루프 ──────────────────────────────────────────────────

async def interactive_loop(llm: LLMCaller) -> None:
    print(f"\n{'='*60}")
    print("  3단계 작문 퀴즈 대화형 테스트")
    print(f"  재시도 최대 {MAX_RETRY}회 / 'q' 입력 시 종료")
    print(f"{'='*60}")

    while True:
        # ── 단어 입력 ────────────────────────────────────────────
        print(f"\n{'─'*60}")
        word = input("  단어 (예: cite): ").strip()
        if word.lower() == "q":
            break
        if not word:
            continue

        # ── 뜻 자동 조회 ────────────────────────────────────────
        print(f"  뜻 조회 중...")
        explain_result = await llm.call(build_explain_prompt(word))
        if not explain_result["ok"]:
            print(f"  ✘ 뜻 조회 실패: {explain_result.get('error')}")
            continue

        explained = parse_explain_response(explain_result["response"])
        word = explained["word"] or word
        meaning_ko = explained["meaning_ko"]
        print(f"  단어: {word}")
        print(f"  뜻:   {meaning_ko}")
        if explained["example"]:
            print(f"  예문: {explained['example']}")

        # ── 문제 출제 ────────────────────────────────────────────
        print(f"\n  문제 생성 중...")
        quiz_prompt = build_quiz_prompt(word, meaning_ko)
        result = await llm.call(quiz_prompt)

        if not result["ok"]:
            print(f"  ✘ 문제 생성 실패: {result.get('error')}")
            continue

        question = parse_quiz_response(result["response"])
        first_letter_hint = " ".join(w[0] + "____" for w in word.split())
        print(f"\n{'─'*60}")
        print(f"  ✏️ 작문 3단계")
        print(f"  {first_letter_hint} {meaning_ko}")
        print(f"  {question}")
        print(f"{'─'*60}")

        # ── 채점 루프 (최대 5회) ─────────────────────────────────
        for attempt in range(1, MAX_RETRY + 1):
            if attempt > 1:
                print(f"\n  {first_letter_hint} {meaning_ko}")
                print(f"  📋 {question}")
            answer = input(f"\n  [{attempt}/{MAX_RETRY}] 작문: ").strip()
            if answer.lower() == "q":
                print("  종료.")
                return
            if not answer:
                continue

            word_used = word.lower() in answer.lower()

            print(f"  채점 중...")
            grade_prompt = build_grade_prompt(word, meaning_ko, question, answer)
            result = await llm.call(grade_prompt)

            if not result["ok"]:
                print(f"  ✘ 채점 실패: {result.get('error')}")
                continue

            print(f"\n  [원본 응답]")
            for line in result["response"].splitlines():
                print(f"    {line}")

            parsed = parse_grade_response(result["response"])

            print(f"\n  [파싱 결과]")
            print(f"    단어포함: {word_used}")
            print(f"    사용여부: {parsed['used_correctly']}")
            print(f"    맥락:     {parsed['context_ok']}")
            print(f"    문법오류: {parsed['grammar_errors']}")
            print(f"    보조어휘: {parsed['vocab_hints']}")
            print(f"    모범답안: {parsed['example_sentence']}")
            print(f"    교정힌트: {parsed['correction_hint']}")
            print(f"    용법힌트: {parsed['usage_hint']}")

            # ── 판정 시뮬레이션 ──────────────────────────────────
            print(f"\n  [봇 응답 시뮬레이션]")

            if not word_used:
                if parsed["context_ok"]:
                    print(f"  ⚠️ 의미는 맞지만 '{word}'를 직접 사용해야 해요. 다시 도전!")
                else:
                    print(f"  ❌ 오답. '{word}'를 사용한 문장을 만들어보세요. 1단계로 돌아갑니다.")
                    print(f"  💡 모범답안: {parsed['example_sentence']}")
                    break
                continue

            if parsed["used_correctly"]:
                has_errors = bool(parsed["grammar_errors"])
                print(f"  ✅ 정답! 올바르게 사용했어요.")
                if parsed["alternatives"] and not all(a.rstrip('.') == "없음" for a in parsed["alternatives"]):
                    print(f"  💡 비슷한 표현: {' / '.join(parsed['alternatives'])}")
                if has_errors:
                    for e in parsed["grammar_errors"]:
                        print(f"  ⚠️ [{e['type']}] {e['detail']}")
                    print(f"  📝 한번 더 다듬어서 써볼까요?")
                    print(f"  → stage 갱신 (승급)")

                    # ── 교정 작문 1회 ────────────────────────────
                    if attempt > 1:
                        print(f"\n  {first_letter_hint} {meaning_ko}")
                        print(f"  📋 {question}")
                    retry_answer = input(f"\n  [교정] 작문: ").strip()
                    if retry_answer and retry_answer.lower() != "q":
                        print(f"  채점 중...")
                        retry_prompt = build_grade_prompt(word, meaning_ko, question, retry_answer)
                        retry_result = await llm.call(retry_prompt)
                        if retry_result["ok"]:
                            print(f"\n  [원본 응답]")
                            for line in retry_result["response"].splitlines():
                                print(f"    {line}")
                            retry_parsed = parse_grade_response(retry_result["response"])
                            if not retry_parsed["grammar_errors"]:
                                print(f"\n  [봇 응답 시뮬레이션]")
                                print(f"  ✅ 깔끔해요! 잘 고쳤어요 👏")
                                if retry_parsed["alternatives"]:
                                    print(f"  💡 비슷한 표현: {' / '.join(retry_parsed['alternatives'])}")
                            else:
                                print(f"\n  [봇 응답 시뮬레이션]")
                                for e in retry_parsed["grammar_errors"]:
                                    print(f"  ⚠️ [{e['type']}] {e['detail']}")
                                print(f"  💡 모범답안: {retry_parsed['example_sentence'] or parsed['example_sentence']}")
                else:
                    print(f"  → stage 갱신 (승급)")
                break

            # 단어 포함 + 어색한 맥락 → 재시도
            if attempt < MAX_RETRY:
                print(f"  💡 단어는 잘 넣었어요! 문장을 다듬어보세요 🔄")
                if parsed["usage_hint"]:
                    print(f"  📝 용법: {parsed['usage_hint']}")
                elif parsed["correction_hint"]:
                    print(f"  📝 힌트: {parsed['correction_hint']}")
                if parsed["vocab_hints"]:
                    print(f"  📖 보조 어휘: {', '.join(parsed['vocab_hints'])}")
            else:
                print(f"  ⏰ {MAX_RETRY}회 시도 완료.")
                print(f"  💡 모범답안: {parsed['example_sentence']}")
                print(f"  → stage 갱신 없음 (강등도 없음, 다음에 다시 출제)")
                break


# ── 메인 ─────────────────────────────────────────────────────────

async def main(lmstudio_only: bool = False) -> None:
    llm = LLMCaller(lmstudio_only=lmstudio_only)
    await llm.init()
    await interactive_loop(llm)
    print(f"\n{'='*60}")
    print("  테스트 종료")
    print(f"{'='*60}\n")


if __name__ == "__main__":
    lmstudio_only = "--lmstudio-only" in sys.argv
    asyncio.run(main(lmstudio_only=lmstudio_only))
