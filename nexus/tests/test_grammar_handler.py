import asyncio
import sys
import os
import unittest
from unittest.mock import AsyncMock, MagicMock, patch

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _run(coro):
    return asyncio.run(coro)


def _make_query(data: str, chat_id: int = 1):
    query = MagicMock()
    query.answer = AsyncMock()
    query.edit_message_reply_markup = AsyncMock()
    query.edit_message_text = AsyncMock()
    query.data = data
    query.message = MagicMock()
    query.message.chat_id = chat_id
    update = MagicMock()
    update.callback_query = query
    return update, query


class TestGrammarRegisterFailure(unittest.TestCase):
    """grammar:register 저장 실패 시 사용자에게 알림을 보낸다."""

    def test_save_failure_shows_alert_to_user(self):
        update, query = _make_query("grammar:register:0")

        pending_info = {
            "expression": "apple",
            "wrong_sentence": "I apple go.",
            "grammar_errors": [{"type": "동사오류", "detail": "go → went"}],
            "collocation_errors": [],
        }

        with patch("handlers.grammar_handler.GrammarPending") as MockGP, \
             patch("handlers.grammar_handler.grammar_service") as mock_gs:
            MockGP.return_value.get = AsyncMock(return_value=pending_info)
            MockGP.return_value.set = AsyncMock()
            MockGP.return_value.clear = AsyncMock()
            mock_gs.save_grammar_error = AsyncMock(side_effect=Exception("Notion 저장 실패"))

            from handlers import grammar_handler
            _run(grammar_handler.handle_callback(update, MagicMock()))

        # 저장 실패 시 show_alert=True 로 사용자에게 알림
        query.answer.assert_called_with(unittest.mock.ANY, show_alert=True)

    def test_save_success_shows_confirmation(self):
        update, query = _make_query("grammar:register:0")

        pending_info = {
            "expression": "apple",
            "wrong_sentence": "I apple go.",
            "grammar_errors": [{"type": "동사오류", "detail": "go → went"}],
            "collocation_errors": [],
        }

        with patch("handlers.grammar_handler.GrammarPending") as MockGP, \
             patch("handlers.grammar_handler.grammar_service") as mock_gs:
            MockGP.return_value.get = AsyncMock(return_value=pending_info)
            MockGP.return_value.set = AsyncMock()
            MockGP.return_value.clear = AsyncMock()
            mock_gs.save_grammar_error = AsyncMock(return_value="page_id_123")

            from handlers import grammar_handler
            _run(grammar_handler.handle_callback(update, MagicMock()))

        query.answer.assert_called_with("📝 등록됐어요!")


if __name__ == "__main__":
    unittest.main()
