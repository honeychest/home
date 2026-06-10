import logging
from notion_client import AsyncClient
from chs import dlog
from config import settings
from constants import WORD_STAGE_DAYS as STAGE_DAYS, MAX_ACTIVE_STAGE, GRADUATED_STAGE
from timeutil import now_kst
from services import review_deck as rd
from services.ai_parsers import parse_link_tags, strip_link_tag_lines
from services.word_repository import parse_word_page as _parse_word_page

logger = logging.getLogger(__name__)
client = AsyncClient(auth=settings.NOTION_API_KEY)

# 단어 복습덱 설정 — 간격반복 로직은 review_deck 한 곳, 여기선 단어용 설정만 둔다.
_WORD_CONFIG = rd.DeckConfig(
    data_source_id=settings.NOTION_WORD_DATABASE_ID,
    advance=rd.graduating_advance(STAGE_DAYS, MAX_ACTIVE_STAGE, GRADUATED_STAGE,
                                  random_at=MAX_ACTIVE_STAGE, random_range=(60, 120)),
    register_interval=STAGE_DAYS[1],
    graduated_at=GRADUATED_STAGE,
)


def _word_deck() -> rd.ReviewDeck:
    return rd.ReviewDeck(_WORD_CONFIG, rd.NotionAdapter(client))


def parse_word_page(page: dict) -> dict | None:
    """Notion 단어 페이지에서 속성 추출. 단어/의미 비어있으면 None 반환."""
    return _parse_word_page(page)


async def exists(url: str) -> str | None:
    """URL이 Notion DB에 이미 저장되어 있으면 page_id 반환, 없으면 None."""
    try:
        response = await client.data_sources.query(
            data_source_id=settings.NOTION_LINK_DATABASE_ID,
            filter={"property": "원본", "url": {"equals": url}},
        )
        results = response.get("results", [])
        if results:
            return results[0]["id"]
        return None
    except Exception as e:
        logger.warning(f"Notion 중복 확인 실패: {e}")
        return None


async def delete_page(page_id: str) -> None:
    """Notion 페이지를 삭제(archived 처리)."""
    try:
        await client.pages.update(page_id=page_id, archived=True)
        logger.info(f"Notion 페이지 삭제 완료: {page_id}")
    except Exception as e:
        logger.warning(f"Notion 페이지 삭제 실패: {e}")


async def save(url: str, title: str, summary: str, platform: str= "telegram", tags: list[str] | None = None) -> str:
    tags = tags if tags is not None else parse_link_tags(summary)
    clean_summary = strip_link_tag_lines(summary)
    properties = {
        "제목": {
            "title": [{"text": {"content": title}}] # title 만 조작가능한 부분이고 나머지는 다 notion양식이라 변경x
        },
        "원본": {
            "url": url
        },
        "플랫폼": {
            "select": {"name": platform}
        },
        "저장일시": {
            "date": {"start": now_kst().isoformat()}
        },
    }
    if tags:
        properties["태그"] = {
            "multi_select": [{"name": tag} for tag in tags]
        }

    response = await client.pages.create(
        parent={"type": "data_source_id", "data_source_id": settings.NOTION_LINK_DATABASE_ID},
        properties=properties,
        children=[
            {
                "object": "block",
                "type": "paragraph",
                "paragraph":{
                    "rich_text": [{"type": "text", "text": {"content": clean_summary}}]
                }
            }
        ]
    )

    page_id = response["id"]
    logger.info(f"노션 저장 완료  - page_id: {page_id}")
    return page_id


async def exists_word(word: str) -> str | None:
    """단어가 영단어 DB에 이미 있으면 page_id 반환, 없으면 None."""
    try:
        response = await client.data_sources.query(
            data_source_id=settings.NOTION_WORD_DATABASE_ID,
            filter={"property": "단어", "title": {"equals": word}},
        )
        results = response.get("results", [])
        if results:
            return results[0]["id"]
        return None
    except Exception as e:
        logger.warning(f"영단어 중복 확인 실패: {e}")
        return None



async def add_word(word: str, meaning: str) -> str:
    """영단어를 Notion DB에 저장하고 page_id 반환."""
    page_id = await _word_deck().register({
        "단어": {"title": [{"text": {"content": word}}]},
        "의미": {"rich_text": [{"text": {"content": meaning}}]},
    })
    logger.info(f"영단어 저장 완료 - word: {word}, page_id: {page_id}")
    return page_id


async def get_all_words() -> list:
    """단어장 전체 조회 — /quiz 전용. 다음리뷰일 오름차순 (오래된 단어 우선)."""
    response = await client.data_sources.query(
        data_source_id=settings.NOTION_WORD_DATABASE_ID,
        sorts=[{"property": "다음리뷰일", "direction": "ascending"}],
    )
    return response.get("results", [])


async def search_words_containing(keyword: str) -> list:
    """단어 필드에 keyword가 포함된 항목 목록 반환."""
    response = await client.data_sources.query(
        data_source_id=settings.NOTION_WORD_DATABASE_ID,
        filter={"property": "단어", "title": {"contains": keyword}},
    )
    return response.get("results", [])


async def get_words_due() -> list:
    """오늘 리뷰할 단어 목록 반환. 졸업(6단계) 단어 제외."""
    return await _word_deck().due_pages()


async def add_inbox(text: str, kind: str, date: str | None) -> str:
    """Notion Inbox DB에 항목 저장 — 할일 또는 아이디어."""
    props = {
        "내용": {"title": [{"text": {"content": text}}]},
        "종류": {"select": {"name": kind}},
        "상태": {"select": {"name": "대기"}},
    }
    if date:
        props["날짜"] = {"date": {"start": date}}

    response = await client.pages.create(
        parent={"type": "data_source_id", "data_source_id": settings.NOTION_INBOX_DATABASE_ID},
        properties=props
    )
    page_id = response["id"]
    logger.info(f"Inbox 항목 저장 완료 - kind: {kind}, page_id: {page_id}")
    return page_id


def _parse_todo_page(page: dict) -> dict | None:
    text = page.get("properties", {}).get("내용", {}).get("title", [])
    if not text:
        return None
    return {"page_id": page["id"], "text": text[0]["text"]["content"]}


async def get_todos(
    *,
    date: str | None = None,
    overdue_before: str | None = None,
    done_on: str | None = None,
) -> list:
    """Inbox 할일 조회.

    date: 특정 날짜의 대기 중 할일 (date_iso)
    overdue_before: 해당 날짜 이전의 대기 중 할일
    done_on: 특정 날짜에 완료된 항목 (생략 시 오늘 KST)
    세 파라미터는 상호 배타적으로 사용.
    """
    try:
        if done_on is not None or (date is None and overdue_before is None):
            target = done_on or now_kst().date().isoformat()
            f = {"and": [
                {"property": "상태", "select": {"equals": "완료"}},
                {"property": "날짜", "date": {"equals": target}},
            ]}
        elif overdue_before is not None:
            f = {"and": [
                {"property": "종류", "select": {"equals": "할일"}},
                {"property": "상태", "select": {"equals": "대기"}},
                {"property": "날짜", "date": {"before": overdue_before}},
            ]}
        else:
            f = {"and": [
                {"property": "종류", "select": {"equals": "할일"}},
                {"property": "상태", "select": {"equals": "대기"}},
                {"property": "날짜", "date": {"equals": date}},
            ]}

        response = await client.data_sources.query(
            data_source_id=settings.NOTION_INBOX_DATABASE_ID,
            filter=f,
        )
        return [t for p in response.get("results", []) if (t := _parse_todo_page(p))]
    except Exception as e:
        logger.warning(f"Notion 할일 조회 실패: {e}")
        return []


async def update_inbox_status(page_id: str, status: str) -> None:
    """Inbox 항목 상태 업데이트."""
    try:
        await client.pages.update(
            page_id=page_id,
            properties={"상태": {"select": {"name": status}}}
        )
        logger.info(f"Inbox 상태 업데이트 - page_id: {page_id}, status: {status}")
    except Exception as e:
        logger.warning(f"Notion 상태 업데이트 실패: {e}")
        raise


async def update_inbox_date(page_id: str, new_date_iso: str) -> None:
    """Inbox 항목 날짜 업데이트."""
    try:
        await client.pages.update(
            page_id=page_id,
            properties={"날짜": {"date": {"start": new_date_iso}}}
        )
        logger.info(f"Inbox 날짜 업데이트 - page_id: {page_id}, date: {new_date_iso}")
    except Exception as e:
        logger.warning(f"Notion 날짜 업데이트 실패: {e}")
        raise


async def grade_word(page_id: str, correct: bool) -> int:
    """단어 채점 프리미티브 — 복습덱 grade로 단계·다음리뷰일 갱신, 다음 단계 반환.

    호출 입구(로깅 포함)는 WordRepository.update_word_stage 한 곳으로 모은다.
    """
    return await _word_deck().grade(page_id, correct)
