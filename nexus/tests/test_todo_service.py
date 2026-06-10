import asyncio
import sys
import os
import unittest
from unittest.mock import AsyncMock, patch, call

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from services import todo_service


def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


CHAT_ID = 12345


def _mock_notion(today_pending=None, overdue=None, done=None, tomorrow=None):
    """get_todos 호출 순서에 맞게 side_effect 반환."""
    today_pending = today_pending or []
    overdue = overdue or []
    done = done or []
    tomorrow = tomorrow or []

    async def _get_todos(*, date=None, overdue_before=None, done_on=None):
        if overdue_before is not None:
            return overdue
        if done_on is not None:
            return done
        # date= 호출 — today vs tomorrow는 값으로 구분
        # today_str < tomorrow_str 이므로 첫 번째 date 호출이 today
        if not hasattr(_get_todos, "_date_call_count"):
            _get_todos._date_call_count = 0
        _get_todos._date_call_count += 1
        return today_pending if _get_todos._date_call_count == 1 else tomorrow

    return _get_todos


class TestBuildScheduleContentPending(unittest.TestCase):
    """오늘 pending 할일이 메시지에 포함된다."""

    def test_today_pending_appears_in_09_messages(self):
        todos = [{"page_id": "p1", "text": "운동하기"}]

        with patch("services.todo_service.notion_service") as mock_notion, \
             patch("services.quiz_schedule.redis") as mock_redis:
            mock_notion.get_todos = _mock_notion(today_pending=todos)
            mock_redis.get = AsyncMock(return_value=None)
            mock_redis.set = AsyncMock()

            messages = _run(todo_service.build_schedule_content(CHAT_ID, hour=9))

        texts = [m[0] for m in messages]
        self.assertTrue(any("운동하기" in t for t in texts))

    def test_overdue_pending_appears_in_messages(self):
        overdue = [{"page_id": "p2", "text": "보고서 제출"}]

        with patch("services.todo_service.notion_service") as mock_notion, \
             patch("services.quiz_schedule.redis") as mock_redis:
            mock_notion.get_todos = _mock_notion(overdue=overdue)
            mock_redis.get = AsyncMock(return_value=None)
            mock_redis.set = AsyncMock()

            messages = _run(todo_service.build_schedule_content(CHAT_ID, hour=9))

        texts = [m[0] for m in messages]
        self.assertTrue(any("보고서 제출" in t for t in texts))

    def test_done_today_appears_in_22_messages(self):
        done = [{"page_id": "p3", "text": "독서"}]

        with patch("services.todo_service.notion_service") as mock_notion, \
             patch("services.quiz_schedule.redis") as mock_redis:
            mock_notion.get_todos = _mock_notion(done=done)
            mock_redis.get = AsyncMock(return_value=None)
            mock_redis.set = AsyncMock()

            messages = _run(todo_service.build_schedule_content(CHAT_ID, hour=22))

        texts = [m[0] for m in messages]
        self.assertTrue(any("독서" in t for t in texts))


class TestBuildScheduleContentParallel(unittest.TestCase):
    """Notion 쿼리 4개가 gather로 병렬 실행된다."""

    def test_notion_queries_run_in_parallel(self):
        call_order = []

        async def _get_todos(*, date=None, overdue_before=None, done_on=None):
            call_order.append("start")
            await asyncio.sleep(0)
            call_order.append("end")
            return []

        with patch("services.todo_service.notion_service") as mock_notion, \
             patch("services.quiz_schedule.redis") as mock_redis:
            mock_notion.get_todos = _get_todos
            mock_redis.get = AsyncMock(return_value=None)
            mock_redis.set = AsyncMock()

            _run(todo_service.build_schedule_content(CHAT_ID, hour=9))

        # 병렬이면 start가 연속으로 쌓임: start,start,start,start,end,end,...
        # 순차면 start,end,start,end,...
        self.assertEqual(call_order[:4], ["start", "start", "start", "start"])


class TestSendScheduleMessageTimeout(unittest.TestCase):
    """build_schedule_content가 timeout 초과 시 메시지를 보내지 않는다."""

    def test_timeout_skips_send(self):
        async def slow_build(*args, **kwargs):
            await asyncio.sleep(999)
            return [("텍스트", None)]

        bot = AsyncMock()

        with patch("scheduler.todo_service.build_schedule_content", side_effect=slow_build), \
             patch("scheduler.notion_service.get_words_due", AsyncMock(return_value=[])), \
             patch("scheduler.ScheduleTracker") as MockTracker, \
             patch("scheduler.QuizSession") as MockQuiz:
            MockTracker.return_value.get_message_ids = AsyncMock(return_value=[])
            MockTracker.return_value.set_message_ids = AsyncMock()
            MockQuiz.return_value.init_count = AsyncMock()

            import scheduler
            _run(scheduler.send_schedule_message(bot, CHAT_ID, hour=9, timeout=0.01))

        bot.send_message.assert_not_called()


if __name__ == "__main__":
    unittest.main()
