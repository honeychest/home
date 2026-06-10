from dataclasses import dataclass
from datetime import date


@dataclass(frozen=True)
class ScheduleInputs:
    hour: int
    today: date
    pending: list[dict]
    done: list[dict]
    tomorrow: list[dict]
    quiz_count: int


@dataclass(frozen=True)
class ScheduleMessage:
    text: str
    action: dict | None = None


def build_schedule_plan(inputs: ScheduleInputs) -> list[ScheduleMessage]:
    if inputs.hour == 22:
        return _build_closing_plan(inputs)
    return _build_daytime_plan(inputs)


def _quiz_message(quiz_count: int) -> ScheduleMessage:
    """퀴즈 라인 — 9/15/22시 동일. 0이어도 한 줄 보낸다(넛지)."""
    if quiz_count > 0:
        return ScheduleMessage(text=f"🔤 퀴즈 {quiz_count}개 남음", action={"kind": "quiz_start"})
    return ScheduleMessage("🔤 퀴즈 ✔ 완료")


def _build_closing_plan(inputs: ScheduleInputs) -> list[ScheduleMessage]:
    messages: list[ScheduleMessage] = []
    header_parts = ["📋 오늘 마무리"]
    for item in inputs.done:
        header_parts.append(f"~~{item['text']}~~ ✔")
    if not inputs.pending and not inputs.done:
        header_parts.append("오늘 마무리할 일 없음")
    if inputs.tomorrow:
        header_parts.append("")
        header_parts.append("📅 내일 예정")
        for item in inputs.tomorrow:
            header_parts.append(f"• {item['text']}")
    messages.append(ScheduleMessage("\n".join(header_parts)))

    for item in inputs.pending:
        messages.append(ScheduleMessage(
            text=f"📋 {item['text']}",
            action={
                "kind": "inbox_item",
                "done_callback": item.get("done_callback", f"inbox:done:{item['short_key']}"),
                "postpone_callback": item.get("postpone_callback", f"inbox:postpone:{item['short_key']}"),
            },
        ))

    messages.append(_quiz_message(inputs.quiz_count))

    return messages


def _build_daytime_plan(inputs: ScheduleInputs) -> list[ScheduleMessage]:
    messages: list[ScheduleMessage] = []
    for item in inputs.pending:
        messages.append(ScheduleMessage(
            text=f"📋 {item['text']}",
            action={
                "kind": "inbox_item",
                "done_callback": item.get("done_callback", f"inbox:done:{item['short_key']}"),
                "postpone_callback": item.get("postpone_callback", f"inbox:postpone:{item['short_key']}"),
            },
        ))

    for item in inputs.done:
        messages.append(ScheduleMessage(f"✔ ~~{item['text']}~~"))

    if not inputs.pending and not inputs.done:
        if inputs.tomorrow:
            text_parts = ["📅 내일 예정"]
            for item in inputs.tomorrow:
                text_parts.append(f"• {item['text']}")
            messages.append(ScheduleMessage("\n".join(text_parts)))
        else:
            messages.append(ScheduleMessage("📋 오늘 할일 없음"))

    messages.append(_quiz_message(inputs.quiz_count))

    return messages
