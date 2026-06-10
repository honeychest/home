import logging
from dataclasses import dataclass
from datetime import datetime

from config import settings
from services import notion_service
from timeutil import KST

logger = logging.getLogger(__name__)

RECURRING_REPEATS = {"매일", "Daily", "daily", "평일", "주중", "주말"}
INACTIVE_STATUSES = {"완료", "비활성", "건너뜀", "취소"}


@dataclass(frozen=True)
class ScheduleReminder:
    page_id: str
    name: str
    when: datetime
    repeat: str
    message: str = ""
    category: str = ""

    @property
    def job_id(self) -> str:
        return f"routine_{self.page_id.replace('-', '')}"

    @property
    def is_recurring(self) -> bool:
        return self.repeat in RECURRING_REPEATS


def _plain_text(items: list[dict]) -> str:
    return "".join(item.get("plain_text", "") for item in items).strip()


def _select_name(prop: dict) -> str:
    item = prop.get("select")
    return item.get("name", "") if item else ""


def _parse_time(value: str | None) -> datetime | None:
    if not value or "T" not in value:
        return None
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    if parsed.tzinfo is None:
        parsed = parsed.replace(tzinfo=KST)
    return parsed.astimezone(KST)


def parse_schedule_page(page: dict) -> ScheduleReminder | None:
    props = page.get("properties", {})
    name = _plain_text(props.get("이름", {}).get("title", []))
    time_prop = props.get("시간", {}).get("date") or {}
    when = _parse_time(time_prop.get("start"))
    if not name or when is None:
        return None

    status = _select_name(props.get("상태", {}))
    checked = props.get("확인", {}).get("checkbox", False)
    if checked or status in INACTIVE_STATUSES:
        return None

    return ScheduleReminder(
        page_id=page["id"],
        name=name,
        when=when,
        repeat=_select_name(props.get("반복", {})),
        message=_plain_text(props.get("메시지", {}).get("rich_text", [])),
        category=_select_name(props.get("분류", {})),
    )


def should_register(reminder: ScheduleReminder, *, now: datetime | None = None) -> bool:
    now = (now or datetime.now(KST)).astimezone(KST)
    if reminder.repeat in {"평일", "주중"}:
        return now.weekday() < 5
    if reminder.repeat == "주말":
        return now.weekday() >= 5
    if reminder.is_recurring:
        return True
    return reminder.when >= now


def format_reminder(reminder: ScheduleReminder) -> str:
    text = f"⏰ {reminder.when:%H:%M} {reminder.name}"
    if reminder.message:
        text += f"\n{reminder.message}"
    if reminder.category:
        text += f"\n#{reminder.category}"
    return text


async def load_schedule_reminders(*, now: datetime | None = None) -> list[ScheduleReminder]:
    if not settings.NOTION_SCHEDULE_DATABASE_ID:
        logger.info("NOTION_SCHEDULE_DATABASE_ID 미설정 — 루틴 알림 스킵")
        return []

    try:
        response = await notion_service.client.data_sources.query(
            data_source_id=settings.NOTION_SCHEDULE_DATABASE_ID,
            sorts=[{"property": "시간", "direction": "ascending"}],
        )
    except Exception as e:
        logger.warning(f"Notion Daily 시간표 조회 실패: {e}")
        return []

    reminders = [
        reminder
        for page in response.get("results", [])
        if (reminder := parse_schedule_page(page))
    ]
    return [reminder for reminder in reminders if should_register(reminder, now=now)]
