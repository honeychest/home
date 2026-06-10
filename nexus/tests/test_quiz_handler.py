import asyncio
import sys
import os
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _run(coro):
    return asyncio.run(coro)


class TestSendNextQuizRecursionGuard(unittest.TestCase):
    """due 단어가 전부 파싱 불가(빈 단어/의미)면 무한 출제 없이 '없음' 메시지로 끝낸다."""

    def test_all_pages_unparseable_sends_no_more_message(self):
        loading = MagicMock()
        loading.delete = AsyncMock()
        update = MagicMock()
        update.effective_message = MagicMock()
        update.effective_message.reply_text = AsyncMock(return_value=loading)

        # notion이 단어/의미가 빈 페이지만 반환 → WordRepository가 전부 걸러 [] 반환
        class _UnparseableNotion:
            async def get_words_due(self):
                return [
                    {"id": f"p{i}", "properties": {
                        "단어": {"title": []}, "의미": {"rich_text": []}, "단계": {"number": 1},
                    }}
                    for i in range(5)
                ]

            async def get_all_words(self):
                return await self.get_words_due()

        from services.quiz_flow import QuizFlow
        from services.word_repository import WordRepository

        flow_session = MagicMock()
        flow_session.set_count = AsyncMock()
        flow_session.clear_state = AsyncMock()

        with patch("handlers.quiz_handler.QuizSession") as MockQS, \
             patch("handlers.quiz_handler.create_quiz_flow") as mock_cqf:
            qs = MagicMock()
            qs.get_session = AsyncMock(return_value={"mode": "auto"})
            qs.pop_prefetch = AsyncMock(return_value=None)
            MockQS.return_value = qs

            mock_cqf.return_value = QuizFlow(
                session=flow_session,
                word_deck=WordRepository(_UnparseableNotion()),
                quiz_generator=MagicMock(),
            )

            from handlers import quiz_handler
            _run(quiz_handler._send_next_quiz(update, chat_id=1))

        # 무한 출제 없이 종료 — reply_text는 '출제 중' + '없음' 2회 이하
        self.assertLessEqual(update.effective_message.reply_text.call_count, 2)


if __name__ == "__main__":
    unittest.main()
