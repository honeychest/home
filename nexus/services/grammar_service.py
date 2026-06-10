import logging
from notion_client import AsyncClient
from config import settings
from constants import GRAMMAR_STAGE_DAYS as STAGE_DAYS
from services import review_deck as rd

logger = logging.getLogger(__name__)
client = AsyncClient(auth=settings.NOTION_API_KEY)

# 문법 복습덱 설정 — 단어와 동일한 review_deck을 쓰고, 설정만 다르다(졸업 없음, 최대 3단계).
_GRAMMAR_CONFIG = rd.DeckConfig(
    data_source_id=settings.NOTION_GRAMMAR_DATABASE_ID,
    advance=rd.capped_advance(STAGE_DAYS, cap=3),
    register_interval=STAGE_DAYS[1],
    graduated_at=None,
)


def _grammar_deck() -> rd.ReviewDeck:
    return rd.ReviewDeck(_GRAMMAR_CONFIG, rd.NotionAdapter(client))


async def save_grammar_error(error_type: str, expression: str, wrong_sentence: str, error_detail: str) -> str:
    """문법 오류를 Notion grammar DB에 저장하고 page_id 반환."""
    page_id = await _grammar_deck().register({
        "오류유형": {"title": [{"text": {"content": error_type}}]},
        "표현":     {"rich_text": [{"text": {"content": expression}}]},
        "틀린문장": {"rich_text": [{"text": {"content": wrong_sentence}}]},
        "오류상세": {"rich_text": [{"text": {"content": error_detail}}]},
    })
    logger.info(f"문법 오류 저장 완료 - type: {error_type}, expression: {expression}, page_id: {page_id}")
    return page_id


async def get_grammar_due() -> list:
    """오늘 리뷰할 grammar 항목 반환.

    [예정 기능] 문법 복습 루프 미배선 — 현재 호출처 없음(문법은 등록만 운영).
    review_deck 위 복습 골격으로 보존. 방침: CONTEXT.md §5.
    """
    return await _grammar_deck().due_pages()


def parse_grammar_page(page: dict) -> dict | None:
    """Notion grammar 페이지에서 속성 추출.

    [예정 기능] 문법 복습 루프 미배선 — 현재 호출처 없음. 방침: CONTEXT.md §5.
    """
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
    """퀴즈 결과에 따라 단계와 다음리뷰일 업데이트.

    [예정 기능] 문법 복습 루프 미배선 — 현재 호출처 없음. 방침: CONTEXT.md §5.
    """
    next_stage = await _grammar_deck().grade(page_id, correct)
    logger.info(f"문법 단계 업데이트 - page_id: {page_id}, stage→{next_stage}, correct: {correct}")
