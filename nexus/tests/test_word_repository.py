import asyncio
import os
import sys
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


def _run(coro):
    return asyncio.get_event_loop().run_until_complete(coro)


class FakeNotionWords:
    def __init__(self):
        self.graded = []

    async def get_words_due(self):
        return [
            {"id": "empty", "properties": {"단어": {"title": []}, "의미": {"rich_text": []}, "단계": {"number": 1}}},
            {
                "id": "word-1",
                "properties": {
                    "단어": {"title": [{"text": {"content": "apple"}}]},
                    "의미": {"rich_text": [{"text": {"content": "사과"}}]},
                    "단계": {"number": 2},
                },
            },
        ]

    async def get_all_words(self):
        return await self.get_words_due()

    async def search_words_containing(self, keyword):
        return await self.get_words_due()

    async def grade_word(self, page_id, correct):
        self.graded.append((page_id, correct))
        return 2


class TestWordRepository(unittest.TestCase):
    def test_due_words_returns_parsed_words_only(self):
        from services.word_repository import WordRepository

        words = _run(WordRepository(FakeNotionWords()).get_due_words())

        self.assertEqual(words, [{
            "page_id": "word-1",
            "word": "apple",
            "meaning_ko": "사과",
            "stage": 2,
        }])

    def test_search_words_containing_returns_parsed_conflicts(self):
        from services.word_repository import WordRepository

        words = _run(WordRepository(FakeNotionWords()).search_words_containing("app"))

        self.assertEqual(words, [{
            "page_id": "word-1",
            "word": "apple",
            "meaning_ko": "사과",
            "stage": 2,
        }])

    def test_update_word_stage_delegates_to_grade_word(self):
        from services.word_repository import WordRepository

        notion = FakeNotionWords()
        _run(WordRepository(notion).update_word_stage("word-1", True))

        self.assertEqual(notion.graded, [("word-1", True)])


if __name__ == "__main__":
    unittest.main()
