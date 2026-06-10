import logging
from datetime import timedelta
from notion_client import AsyncClient
from config import settings
from constants import GRAMMAR_STAGE_DAYS as STAGE_DAYS
from timeutil import now_kst

logger = logging.getLogger(__name__)
client = AsyncClient(auth=settings.NOTION_API_KEY)


async def save_grammar_error(error_type: str, expression: str, wrong_sentence: str, error_detail: str) -> str:
    """문법 오류를 Notion grammar DB에 저장하고 page_id 반환."""
    today = now_kst()
    next_review = (today + timedelta(days=STAGE_DAYS[1])).isoformat()
    response = await client.pages.create(
        parent={"type": "data_source_id", "data_source_id": settings.NOTION_GRAMMAR_DATABASE_ID},
        properties={
            "오류유형": {"title": [{"text": {"content": error_type}}]},
            "표현":     {"rich_text": [{"text": {"content": expression}}]},
            "틀린문장": {"rich_text": [{"text": {"content": wrong_sentence}}]},
            "오류상세": {"rich_text": [{"text": {"content": error_detail}}]},
            "단계":     {"number": 1},
            "등록일":   {"date": {"start": today.isoformat()}},
            "다음리뷰일": {"date": {"start": next_review}},
        }
    )
    page_id = response["id"]
    logger.info(f"문법 오류 저장 완료 - type: {error_type}, expression: {expression}, page_id: {page_id}")
    return page_id


async def get_grammar_due() -> list:
    """오늘 리뷰할 grammar 항목 반환."""
    today = now_kst().date().isoformat()
    response = await client.data_sources.query(
        data_source_id=settings.NOTION_GRAMMAR_DATABASE_ID,
        filter={"property": "다음리뷰일", "date": {"on_or_before": today}},
    )
    return response.get("results", [])


def parse_grammar_page(page: dict) -> dict | None:
    """Notion grammar 페이지에서 속성 추출."""
    props = page["properties"]
    title_list = props["오류유형"]["title"]
    if not title_list:
        return None
    expression  = props["표현"]["rich_text"]
    wrong       = props["틀린문장"]["rich_text"]
    detail      = props["오류상세"]["rich_text"]
    return {
        "page_id":       page["id"],
        "error_type":    title_list[0]["text"]["content"],
        "expression":    expression[0]["text"]["content"] if expression else "",
        "wrong_sentence": wrong[0]["text"]["content"] if wrong else "",
        "error_detail":  detail[0]["text"]["content"] if detail else "",
        "stage":         int(props["단계"]["number"]),
    }


async def update_grammar_stage(page_id: str, correct: bool) -> None:
    """퀴즈 결과에 따라 단계와 다음리뷰일 업데이트."""
    page = await client.pages.retrieve(page_id=page_id)
    current_stage = int(page["properties"]["단계"]["number"])

    next_stage = min(current_stage + 1, 3) if correct else 1
    days = STAGE_DAYS[next_stage]
    next_review = (now_kst() + timedelta(days=days)).isoformat()

    await client.pages.update(
        page_id=page_id,
        properties={
            "단계":       {"number": next_stage},
            "다음리뷰일": {"date": {"start": next_review}},
        }
    )
    logger.info(f"문법 단계 업데이트 - page_id: {page_id}, stage: {current_stage}→{next_stage}, correct: {correct}")
