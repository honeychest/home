"""시간대 단일 기준 — 앱 전체를 KST(UTC+9)로 통일한다.

날짜·자정·'오늘' 판정은 모두 이 모듈을 통해 얻는다.
(과거 UTC/naive 혼용으로 자정 리셋·due 판정이 어긋나던 문제를 일원화)
"""
from datetime import datetime, timezone, timedelta, date

KST = timezone(timedelta(hours=9))


def now_kst() -> datetime:
    """현재 시각(KST, tz-aware)."""
    return datetime.now(KST)


def today_kst() -> date:
    """오늘 날짜(KST 기준)."""
    return datetime.now(KST).date()


def seconds_until_kst_midnight() -> int:
    """다음 KST 자정까지 남은 초. Redis TTL을 KST 하루 단위로 맞출 때 사용."""
    now = now_kst()
    midnight = (now + timedelta(days=1)).replace(hour=0, minute=0, second=0, microsecond=0)
    return int((midnight - now).total_seconds())
