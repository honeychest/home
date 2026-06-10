import asyncio
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _run(coro):
    return asyncio.run(coro)


class FakeTokenStore:
    def __init__(self):
        self.saved = {}

    async def set(self, token, page_id, ttl=86400):
        self.saved[token] = (page_id, ttl)

    async def get(self, token):
        value = self.saved.get(token)
        return value[0] if value else None


class TestInboxActionToken(unittest.TestCase):
    def test_create_item_actions_saves_page_and_builds_callbacks(self):
        from services.inbox_action_token import InboxActionToken

        tokens = InboxActionToken(FakeTokenStore(), token_factory=lambda: "abc123")

        action = _run(tokens.create_item_actions("page-1"))

        self.assertEqual(action.short_key, "abc123")
        self.assertEqual(action.done_callback, "inbox:done:abc123")
        self.assertEqual(action.postpone_callback, "inbox:postpone:abc123")
        self.assertEqual(_run(tokens.resolve("abc123")), "page-1")

    def test_postpone_date_callback_keeps_token_and_date_together(self):
        from services.inbox_action_token import InboxActionToken

        tokens = InboxActionToken(FakeTokenStore(), token_factory=lambda: "abc123")

        self.assertEqual(
            tokens.postpone_date_callback("abc123", "2026-05-10"),
            "inbox:postpone_date:abc123:2026-05-10",
        )


if __name__ == "__main__":
    unittest.main()
