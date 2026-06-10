import asyncio
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _run(coro):
    return asyncio.run(coro)


class FakeConversationState:
    def __init__(self, *, law_active=False, quiz_state=None):
        self.law_active = law_active
        self.quiz_state = quiz_state

    async def is_law_active(self):
        return self.law_active

    async def get_quiz_state(self):
        return self.quiz_state


class TestConversationRouter(unittest.TestCase):
    def test_law_state_takes_precedence_over_url(self):
        from services.conversation_router import ConversationRouter

        intent = _run(ConversationRouter(FakeConversationState(law_active=True)).route("https://example.com"))

        self.assertEqual(intent.kind, "law_query")
        self.assertEqual(intent.text, "https://example.com")

    def test_active_quiz_takes_precedence_over_word_lookup(self):
        from services.conversation_router import ConversationRouter

        intent = _run(ConversationRouter(FakeConversationState(quiz_state="quiz")).route("apple"))

        self.assertEqual(intent.kind, "quiz_answer")
        self.assertEqual(intent.text, "apple")

    def test_korean_short_text_routes_to_short_inbox_confirm(self):
        from services.conversation_router import ConversationRouter

        intent = _run(ConversationRouter(FakeConversationState()).route("밥"))

        self.assertEqual(intent.kind, "inbox_short_confirm")
        self.assertEqual(intent.payload, {"short_confirm": "밥"})


if __name__ == "__main__":
    unittest.main()
