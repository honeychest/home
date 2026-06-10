"""
도메인별 Redis 세션 클래스.
핸들러는 이 모듈만 import하면 되고, redis / 키 상수 / TTL 계산은 내부에서 처리한다.
"""
import json

from redis_client import redis as _redis
from timeutil import seconds_until_kst_midnight


def _ttl() -> int:
    """KST 자정까지 남은 초. 자동퀴즈 카운트·세션이 KST 하루 단위로 만료되게 한다."""
    return seconds_until_kst_midnight()


# ── 베이스 ─────────────────────────────────────────────────────────────────────

class RedisJsonStore:
    """dict/list 를 JSON 직렬화하여 Redis 에 저장하는 도메인 클래스의 공통 부모.

    서브클래스는 키와 ttl() 만 정의하면 된다. TTL 누락은 NotImplementedError 로 차단.
    """

    def __init__(self, key: str):
        self._key = key

    def ttl(self) -> int:
        raise NotImplementedError

    async def get(self):
        raw = await _redis.get(self._key)
        return json.loads(raw) if raw else None

    async def set(self, data) -> None:
        await _redis.set(self._key, json.dumps(data), ex=self.ttl())

    async def clear(self) -> None:
        await _redis.delete(self._key)


class RedisStringStore:
    """문자열 값을 Redis 에 저장하는 도메인 클래스의 공통 부모."""

    def __init__(self, key: str):
        self._key = key

    def ttl(self) -> int:
        raise NotImplementedError

    async def get(self) -> str | None:
        return await _redis.get(self._key)

    async def set(self, value: str) -> None:
        await _redis.set(self._key, value, ex=self.ttl())

    async def clear(self) -> None:
        await _redis.delete(self._key)


# ── QuizSession (그대로 유지) ──────────────────────────────────────────────────

class QuizSession:
    """quiz:* 키 6개를 단일 인터페이스로 관리."""

    DAILY_QUIZ_LIMIT = 20

    def __init__(self, chat_id: int):
        c = chat_id
        self._session_k  = f"nexus:quiz:session:{c}"
        self._state_k    = f"nexus:quiz:state:{c}"
        self._count_k    = f"nexus:quiz:count:{c}"
        self._total_k    = f"nexus:quiz:total:{c}"
        self._prefetch_k = f"nexus:quiz:prefetch:{c}"

    # ── 세션 (현재 문제 데이터) ─────────────────────────────────────────────────

    async def get_session(self) -> dict | None:
        raw = await _redis.get(self._session_k)
        return json.loads(raw) if raw else None

    async def set_session(self, data: dict) -> None:
        await _redis.set(self._session_k, json.dumps(data), ex=_ttl())

    # ── 상태 (quiz / word / None) ───────────────────────────────────────────────

    async def get_state(self) -> str | None:
        return await _redis.get(self._state_k)

    async def set_state(self, state: str) -> None:
        await _redis.set(self._state_k, state, ex=_ttl())

    async def clear_state(self) -> None:
        await _redis.delete(self._state_k)

    # ── Prefetch ────────────────────────────────────────────────────────────────

    async def pop_prefetch(self) -> dict | None:
        raw = await _redis.getdel(self._prefetch_k)
        return json.loads(raw) if raw else None

    async def set_prefetch(self, data: dict) -> None:
        await _redis.set(self._prefetch_k, json.dumps(data), ex=_ttl())

    async def clear_prefetch(self) -> None:
        await _redis.delete(self._prefetch_k)

    # ── 카운트 (자동퀴즈 진행도) ────────────────────────────────────────────────

    async def get_count(self) -> int | None:
        raw = await _redis.get(self._count_k)
        return int(raw) if raw else None

    async def reset_count(self) -> None:
        """DAILY_QUIZ_LIMIT으로 초기화 (/quiz 명령)."""
        ttl = _ttl()
        await _redis.set(self._count_k, self.DAILY_QUIZ_LIMIT, ex=ttl)
        await _redis.set(self._total_k, self.DAILY_QUIZ_LIMIT, ex=ttl)

    async def init_count(self, n: int) -> None:
        """오늘의 due words 수로 count + total 동시 초기화 (09시 스케줄러)."""
        ttl = _ttl()
        await _redis.set(self._count_k, n, ex=ttl)
        await _redis.set(self._total_k, n, ex=ttl)

    async def set_count(self, n: int) -> None:
        await _redis.set(self._count_k, n, ex=_ttl())

    async def consume_count(self) -> tuple[int, int] | None:
        """COUNT를 1 차감하고 (remaining, total) 반환. 출제 불가면 None."""
        ttl = _ttl()
        count_str = await _redis.get(self._count_k)
        if not count_str or int(count_str) <= 0:
            return None
        total_str = await _redis.get(self._total_k)
        if not total_str or int(total_str) <= 0:
            total = int(count_str)
            await _redis.set(self._total_k, total, ex=ttl)
        else:
            total = int(total_str)
        remaining = await _redis.decr(self._count_k)
        await _redis.expire(self._count_k, ttl)
        await _redis.expire(self._total_k, ttl)
        return remaining, total

    @staticmethod
    def format_progress(remaining: int, total: int | None = None) -> str:
        if total and total > 0:
            completed = max(0, min(total - remaining, total))
            return f"[{completed}/{total}]"
        return f"[남은 퀴즈 {remaining}개]"

    # ── 생명주기 ────────────────────────────────────────────────────────────────

    async def clear_active(self) -> None:
        """퀴즈 완료/종료 — state + session + prefetch 삭제."""
        await _redis.delete(self._state_k, self._session_k, self._prefetch_k)

    async def clear_all(self) -> None:
        """/exit 전체 초기화 — 4개 키 삭제."""
        await _redis.delete(self._session_k, self._state_k, self._prefetch_k)


# ── 도메인별 서브클래스 ────────────────────────────────────────────────────────

class WordPending(RedisJsonStore):
    def __init__(self, chat_id: int):
        super().__init__(f"nexus:word:pending:{chat_id}")

    def ttl(self) -> int:
        return _ttl()


class GrammarPending(RedisJsonStore):
    def __init__(self, chat_id: int):
        super().__init__(f"nexus:grammar:pending:{chat_id}")

    def ttl(self) -> int:
        return _ttl()


class InboxPending(RedisJsonStore):
    def __init__(self, chat_id: int):
        super().__init__(f"nexus:inbox:pending:{chat_id}")

    def ttl(self) -> int:
        return 600


class ScheduleTracker(RedisJsonStore):
    def __init__(self, chat_id: int):
        super().__init__(f"nexus:schedule:msg_id:{chat_id}")

    def ttl(self) -> int:
        return 86400

    async def get_message_ids(self) -> list[str]:
        data = await self.get()
        return data if isinstance(data, list) else []

    async def set_message_ids(self, ids: list[str]) -> None:
        await self.set(ids)


class InboxCallback(RedisStringStore):
    """short_key → page_id 조회용 (chat_id 기반 아님)."""

    def __init__(self, short_key: str):
        super().__init__(f"nexus:inbox:cb:{short_key}")

    def ttl(self) -> int:
        return 86400


class LawState(RedisStringStore):
    """법령 검색 모드 토글. 10분 무활동 시 자동 만료. 검색어 입력마다 갱신."""

    def __init__(self, chat_id: int):
        super().__init__(f"nexus:law:state:{chat_id}")

    def ttl(self) -> int:
        return 600

    async def is_active(self) -> bool:
        return await self.get() == "law"

    async def activate(self) -> None:
        await self.set("law")
