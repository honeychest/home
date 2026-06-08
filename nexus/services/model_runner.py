"""
LLM 실행 계층. 폴백 체인, 타임아웃, provider 선택을 담당.
ai_service.py는 이 모듈을 통해서만 LLM을 호출한다.
"""
import asyncio
import logging
import time

from config import settings
from services.ai_parsers import parse_response

logger = logging.getLogger(__name__)

MODELS = [
    "gemini-flash-lite-latest",
    "gemini-flash-latest",
    "gemini-pro-latest",
    "gemini-3.1-flash-lite",
    "gemini-2.5-flash-lite",
    "gemini-2.5-flash",
]


async def run(prompt: str, timeout: float = 8.0) -> dict:
    """표준 텍스트 생성. AI_PROVIDER에 따라 Gemini, LM Studio, Claude를 선택."""
    if settings.AI_PROVIDER == "gemini":
        try:
            return await _lmstudio(prompt, timeout, use_config_timeout=False)
        except Exception:
            logger.warning("[llm] LM Studio 우선 시도 실패 — Gemini 폴백")
        return await _gemini(prompt, timeout)
    if settings.AI_PROVIDER == "lmstudio":
        return await _lmstudio(prompt, timeout)
    return await _claude(prompt)


async def run_url_context(prompt: str, timeout: float = 30.0) -> dict:
    """url_context 툴로 웹 페이지를 직접 브라우징해 생성."""
    from google import genai
    from google.genai import types as gtypes

    client = genai.Client(api_key=settings.GEMINI_API_KEY)
    config = gtypes.GenerateContentConfig(
        tools=[gtypes.Tool(url_context=gtypes.UrlContext())],
        automatic_function_calling=gtypes.AutomaticFunctionCallingConfig(disable=True),
    )
    for model in MODELS:
        t = time.time()
        try:
            response = await asyncio.wait_for(
                client.aio.models.generate_content(model=model, contents=prompt, config=config),
                timeout=timeout,
            )
            logger.info(f"[url] 사용 모델: {model} ({time.time() - t:.2f}s)")
            return parse_response(response.text)
        except asyncio.TimeoutError:
            logger.warning(f"[url] {model} 타임아웃 ({time.time() - t:.2f}s)")
        except Exception as e:
            logger.warning(f"[url] {model} 실패 ({time.time() - t:.2f}s): {e}")
    raise Exception("모든 url 모델 실패")


async def run_youtube(url: str, prompt: str, timeout: float = 60.0) -> dict:
    """YouTube file_uri를 Gemini에 직접 전달."""
    from google import genai
    from google.genai import types as gtypes

    client = genai.Client(api_key=settings.GEMINI_API_KEY)
    for model in MODELS:
        t = time.time()
        try:
            response = await asyncio.wait_for(
                client.aio.models.generate_content(
                    model=model,
                    contents=[gtypes.Part(file_data=gtypes.FileData(file_uri=url)), prompt],
                ),
                timeout=timeout,
            )
            logger.info(f"[youtube] 사용 모델: {model} ({time.time() - t:.2f}s)")
            return parse_response(response.text)
        except asyncio.TimeoutError:
            logger.warning(f"[youtube] {model} 타임아웃 ({time.time() - t:.2f}s)")
        except Exception as e:
            logger.warning(f"[youtube] {model} 실패 ({time.time() - t:.2f}s): {e}")
    raise Exception("모든 youtube 모델 실패")


async def _gemini(prompt: str, timeout: float) -> dict:
    from google import genai
    from google.genai import types as gtypes

    client = genai.Client(api_key=settings.GEMINI_API_KEY)
    config = gtypes.GenerateContentConfig(
        automatic_function_calling=gtypes.AutomaticFunctionCallingConfig(disable=True)
    )
    for model in MODELS:
        t = time.time()
        try:
            response = await asyncio.wait_for(
                client.aio.models.generate_content(model=model, contents=prompt, config=config),
                timeout=timeout,
            )
            logger.info(f"[llm] 사용 모델: {model} ({time.time() - t:.2f}s)")
            return parse_response(response.text)
        except asyncio.TimeoutError:
            logger.warning(f"[llm] {model} 타임아웃 ({time.time() - t:.2f}s)")
        except Exception as e:
            logger.warning(f"[llm] {model} 실패 ({time.time() - t:.2f}s): {e}")
    raise Exception("모든 모델 실패")


async def _lmstudio(prompt: str, timeout: float, *, use_config_timeout: bool = True) -> dict:
    from openai import AsyncOpenAI

    client = AsyncOpenAI(base_url=settings.LMSTUDIO_BASE_URL, api_key=settings.LMSTUDIO_API_KEY)
    model = settings.LMSTUDIO_MODEL or await _detect_lmstudio_model(client)
    if not model:
        raise Exception("LM Studio 모델 감지 실패")

    effective_timeout = max(timeout, settings.LMSTUDIO_TIMEOUT) if use_config_timeout else timeout
    t = time.time()
    try:
        response = await asyncio.wait_for(
            client.chat.completions.create(
                model=model,
                messages=[{"role": "user", "content": prompt}],
            ),
            timeout=effective_timeout,
        )
        content = response.choices[0].message.content or ""
        logger.info(f"[llm] 사용 모델: lmstudio:{model} ({time.time() - t:.2f}s)")
        return parse_response(content)
    except asyncio.TimeoutError:
        logger.warning(f"[llm] lmstudio:{model} 타임아웃 ({time.time() - t:.2f}s)")
        raise
    except Exception as e:
        logger.warning(f"[llm] lmstudio:{model} 실패 ({time.time() - t:.2f}s): {e}")
        raise


async def lmstudio_health(timeout: float = 8.0) -> tuple[bool, str]:
    """LM Studio 연결 + 채팅 모델 로드 여부를 점검한다.

    반환: (정상 여부, 상세 메시지)
    - 연결 실패: (False, "연결 실패: …")
    - 모델 미로드: (False, "모델 없음: 'gemma…' / 로드된 모델 […]")
    - 정상: (True, "정상: gemma…")
    """
    from openai import AsyncOpenAI

    want = settings.LMSTUDIO_MODEL
    client = AsyncOpenAI(base_url=settings.LMSTUDIO_BASE_URL, api_key=settings.LMSTUDIO_API_KEY)
    try:
        models = await asyncio.wait_for(client.models.list(), timeout=timeout)
        ids = [m.id for m in models.data]
    except asyncio.TimeoutError:
        return False, f"연결 실패: /v1/models 타임아웃 ({timeout}s 초과) — {settings.LMSTUDIO_BASE_URL}"
    except Exception as e:
        return False, f"연결 실패: {e} — {settings.LMSTUDIO_BASE_URL}"

    if not want:
        # 고정 모델이 비어 있으면 자동감지 동작이라 '첫 모델 존재'만 확인
        return (bool(ids), f"정상(자동감지): {ids[0]}" if ids else "모델 없음: 로드된 모델이 없습니다")

    if want in ids:
        return True, f"정상: {want}"
    return False, f"모델 없음: '{want}' 미로드 / 현재 로드된 모델 {ids or '없음'}"


async def _detect_lmstudio_model(client) -> str | None:
    try:
        models = await client.models.list()
        ids = [m.id for m in models.data]
        return ids[0] if ids else None
    except Exception as e:
        logger.warning(f"[llm] LM Studio /v1/models 호출 실패: {e}")
        return None


async def _claude(prompt: str) -> dict:
    from anthropic import AsyncAnthropic

    client = AsyncAnthropic(api_key=settings.ANTHROPIC_API_KEY)
    message = await client.messages.create(
        model="claude-opus-4-6",
        max_tokens=1024,
        messages=[{"role": "user", "content": prompt}],
    )
    return parse_response(message.content[0].text)
