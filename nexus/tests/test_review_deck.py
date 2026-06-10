import asyncio
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))

from services.review_deck import (
    DeckConfig, ReviewDeck, graduating_advance, capped_advance,
)


def _run(coro):
    return asyncio.run(coro)


WORD_INTERVALS = {1: 1, 2: 3, 3: 7, 4: 30}
GRAMMAR_INTERVALS = {1: 1, 2: 3, 3: 7}

word_advance = graduating_advance(
    WORD_INTERVALS, max_active=5, graduated=6, random_at=5, random_range=(60, 120))
grammar_advance = capped_advance(GRAMMAR_INTERVALS, cap=3)


class TestAdvanceStrategies(unittest.TestCase):
    def test_word_correct_advances_one_stage(self):
        self.assertEqual(word_advance(2, True), (3, 7))

    def test_word_random_interval_entering_max_active(self):
        next_stage, days = word_advance(4, True)
        self.assertEqual(next_stage, 5)
        self.assertTrue(60 <= days <= 120)

    def test_word_graduates_at_max_active(self):
        self.assertEqual(word_advance(5, True), (6, 9999))

    def test_word_wrong_resets_to_stage_one(self):
        self.assertEqual(word_advance(4, False), (1, 1))

    def test_grammar_caps_at_three(self):
        self.assertEqual(grammar_advance(3, True), (3, 7))
        self.assertEqual(grammar_advance(2, True), (3, 7))

    def test_grammar_wrong_resets(self):
        self.assertEqual(grammar_advance(3, False), (1, 1))


class _FakePort:
    def __init__(self, page_stage: int = 1):
        self.created = None
        self.updated = None
        self.query_filter = None
        self._page_stage = page_stage

    async def create(self, data_source_id, properties):
        self.created = (data_source_id, properties)
        return "page-x"

    async def query(self, data_source_id, *, filter=None):
        self.query_filter = filter
        return [{"id": "p1"}]

    async def update(self, page_id, properties):
        self.updated = (page_id, properties)

    async def retrieve(self, page_id):
        return {"properties": {"단계": {"number": self._page_stage}}}


WORD_CFG = DeckConfig("word-db", word_advance, register_interval=1, graduated_at=6)
GRAMMAR_CFG = DeckConfig("grammar-db", grammar_advance, register_interval=1, graduated_at=None)


class TestReviewDeck(unittest.TestCase):
    def test_register_sets_stage_one_and_passes_domain_props(self):
        port = _FakePort()
        page_id = _run(ReviewDeck(WORD_CFG, port).register({"단어": {"title": []}}))
        self.assertEqual(page_id, "page-x")
        ds, props = port.created
        self.assertEqual(ds, "word-db")
        self.assertEqual(props["단계"], {"number": 1})
        self.assertIn("단어", props)            # 도메인 필드 통과
        self.assertIn("다음리뷰일", props)        # 메타 주입

    def test_due_excludes_graduated_when_configured(self):
        port = _FakePort()
        _run(ReviewDeck(WORD_CFG, port).due_pages())
        self.assertIn("and", port.query_filter)   # 졸업 제외 조건 결합

    def test_due_simple_filter_when_no_graduation(self):
        port = _FakePort()
        _run(ReviewDeck(GRAMMAR_CFG, port).due_pages())
        self.assertNotIn("and", port.query_filter)
        self.assertEqual(port.query_filter["property"], "다음리뷰일")

    def test_grade_updates_stage_from_current(self):
        port = _FakePort(page_stage=2)
        next_stage = _run(ReviewDeck(WORD_CFG, port).grade("p1", correct=True))
        self.assertEqual(next_stage, 3)
        _, props = port.updated
        self.assertEqual(props["단계"], {"number": 3})


if __name__ == "__main__":
    unittest.main()
