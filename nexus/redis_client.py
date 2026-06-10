import redis.asyncio as aioredis

from chs import dlog
from config import settings

redis = aioredis.from_url(settings.REDIS_URL, decode_responses=True)

DAILY_QUIZ_LIMIT = 20

# ─── 키 상수 ──────────────────────────────────────────────────────────────────
KEY_QUIZ_SESSION    = "nexus:quiz:session:{chat_id}"
KEY_QUIZ_STATE      = "nexus:quiz:state:{chat_id}"
KEY_QUIZ_COUNT      = "nexus:quiz:count:{chat_id}"
KEY_QUIZ_TOTAL      = "nexus:quiz:total:{chat_id}"
KEY_WORD_PENDING    = "nexus:word:pending:{chat_id}"
KEY_GRAMMAR_PENDING = "nexus:grammar:pending:{chat_id}"
KEY_QUIZ_PREFETCH   = "nexus:quiz:prefetch:{chat_id}"
KEY_LAW_STATE       = "nexus:law:state:{chat_id}"
dlog("KEY_QUIZ_TOTAL — 자동퀴즈 전체 출제 수를 자정까지 보관")


# [AGENT]
# KEY_QUIZ_COUNT는 자동퀴즈 남은 출제 수, KEY_QUIZ_TOTAL은 하루 전체 출제 수다.
# 자동퀴즈 헤더는 두 값을 조합해 [완료/전체] 형식으로 표시한다.

# ─── 신규 키 ──────────────────────────────────────────────────────────────────
KEY_INBOX_PENDING   = "nexus:inbox:pending:{chat_id}"    # TTL 600s
KEY_SCHEDULE_MSG_ID = "nexus:schedule:msg_id:{chat_id}"  # TTL 없음, 덮어쓰기
KEY_INBOX_CB        = "nexus:inbox:cb:{short_key}"       # TTL 86400s


def _k(key: str, chat_id: int) -> str:
    return key.format(chat_id=chat_id)
