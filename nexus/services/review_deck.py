"""복습덱(Review Deck) — 간격반복(SRS)의 단일 구현.

단어·문법은 설정(DeckConfig)만 다른 두 Adapter다. SRS 알고리즘(단계 전진·리뷰일
계산·due 필터·등록 메타)은 여기 한 곳에만 있다.

- 단계 전진 규칙(advance)은 값으로 주입한다: graduating_advance / capped_advance.
- Notion 접근은 NotionAdapter(또는 동일 인터페이스의 Fake)로 분리해 테스트 가능.
"""
import random
from dataclasses import dataclass
from datetime import timedelta
from typing import Any, Callable

from timeutil import now_kst

# advance: (현재 단계, 정답 여부) -> (다음 단계, 다음 리뷰까지 일수)
Advance = Callable[[int, bool], tuple[int, int]]


def graduating_advance(
    intervals: dict[int, int], max_active: int, graduated: int,
    *, random_at: int, random_range: tuple[int, int],
) -> Advance:
    """단어용 — max_active 단계에서 졸업(graduated, 출제 제외). random_at 단계는 간격 랜덤."""
    def advance(stage: int, correct: bool) -> tuple[int, int]:
        if not correct:
            return 1, intervals[1]
        if stage >= max_active:
            return graduated, 9999
        nxt = stage + 1
        if nxt == random_at:
            return nxt, random.randint(*random_range)
        return nxt, intervals[nxt]
    return advance


def capped_advance(intervals: dict[int, int], cap: int) -> Advance:
    """문법용 — cap 단계에서 멈춘다(졸업 없음)."""
    def advance(stage: int, correct: bool) -> tuple[int, int]:
        if not correct:
            return 1, intervals[1]
        nxt = min(stage + 1, cap)
        return nxt, intervals[nxt]
    return advance


@dataclass(frozen=True)
class DeckConfig:
    data_source_id: str
    advance: Advance
    register_interval: int        # 신규 카드의 첫 리뷰까지 일수
    graduated_at: int | None      # 이 단계 이상은 due에서 제외(없으면 None)


class NotionAdapter:
    """notion_client AsyncClient를 복습덱 포트 인터페이스로 감싼다."""

    def __init__(self, client: Any):
        self._c = client

    async def create(self, data_source_id: str, properties: dict) -> str:
        resp = await self._c.pages.create(
            parent={"type": "data_source_id", "data_source_id": data_source_id},
            properties=properties,
        )
        return resp["id"]

    async def query(self, data_source_id: str, *, filter: dict | None = None) -> list[dict]:
        kwargs: dict = {"data_source_id": data_source_id}
        if filter is not None:
            kwargs["filter"] = filter
        resp = await self._c.data_sources.query(**kwargs)
        return resp.get("results", [])

    async def update(self, page_id: str, properties: dict) -> None:
        await self._c.pages.update(page_id=page_id, properties=properties)

    async def retrieve(self, page_id: str) -> dict:
        return await self._c.pages.retrieve(page_id=page_id)


class ReviewDeck:
    """등록·due 조회·채점을 작은 인터페이스로 제공한다. 도메인 필드는 opaque로 통과."""

    def __init__(self, config: DeckConfig, port: Any):
        self._cfg = config
        self._port = port

    async def register(self, domain_props: dict) -> str:
        """새 카드 저장 — 단계1, 다음리뷰일=오늘+register_interval. page_id 반환."""
        today = now_kst()
        next_review = (today + timedelta(days=self._cfg.register_interval)).isoformat()
        props = {
            **domain_props,
            "단계": {"number": 1},
            "등록일": {"date": {"start": today.isoformat()}},
            "다음리뷰일": {"date": {"start": next_review}},
        }
        return await self._port.create(self._cfg.data_source_id, props)

    async def due_pages(self) -> list[dict]:
        """오늘(KST) 리뷰 대상 raw 페이지 목록. 졸업 단계는 graduated_at 설정 시 제외."""
        today = now_kst().date().isoformat()
        f: dict = {"property": "다음리뷰일", "date": {"on_or_before": today}}
        if self._cfg.graduated_at is not None:
            f = {"and": [f, {"property": "단계", "number": {"less_than": self._cfg.graduated_at}}]}
        return await self._port.query(self._cfg.data_source_id, filter=f)

    async def grade(self, page_id: str, correct: bool) -> int:
        """채점 — advance로 단계·리뷰일 갱신. 다음 단계 반환."""
        page = await self._port.retrieve(page_id)
        stage = int(page["properties"]["단계"]["number"])
        next_stage, days = self._cfg.advance(stage, correct)
        next_review = (now_kst() + timedelta(days=days)).isoformat()
        await self._port.update(page_id, {
            "단계": {"number": next_stage},
            "다음리뷰일": {"date": {"start": next_review}},
        })
        return next_stage
