"""스케줄 퀴즈 카운트 확정 — 9/15/22시 공통.

퀴즈 0 처리의 'I/O 계층'을 한 곳에 모은 모듈.
- 표시(렌더) 계층은 schedule_plan._quiz_message 가 담당한다.
"""
from redis_client import redis, _k, KEY_QUIZ_COUNT
from services import notion_service


async def resolve_quiz_count(chat_id: int) -> int:
    """Redis 퀴즈 카운트를 읽고, 실제 due 단어가 없으면 0으로 보정한다."""
    count_str = await redis.get(_k(KEY_QUIZ_COUNT, chat_id))
    quiz_count = int(count_str) if count_str else 0
    if quiz_count > 0:
        due_words = await notion_service.get_words_due()
        if not due_words:
            quiz_count = 0
    return quiz_count
