import asyncio
import os
import sys
import types
import unittest

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


class _AsyncClientStub:
    def __init__(self, auth=None):
        self.auth = auth


# config.Settings 전체 스키마를 미러링한 가짜 settings.
# discover가 수집 단계에서 모든 test 모듈을 import하므로, 누수돼도 redis_client 등
# 다른 모듈의 import가 깨지지 않도록 실제 필드를 모두 채운다.
_FAKE_SETTINGS = types.SimpleNamespace(
    TELEGRAM_BOT_TOKEN="test-token",
    ANTHROPIC_API_KEY="",
    GEMINI_API_KEY="test-key",
    NOTION_API_KEY="test-key",
    NOTION_LINK_DATABASE_ID="link-db",
    NOTION_WORD_DATABASE_ID="word-db",
    NOTION_GRAMMAR_DATABASE_ID="grammar-db",
    AI_PROVIDER="gemini",
    LMSTUDIO_BASE_URL="http://localhost/v1",
    LMSTUDIO_API_KEY="lm-studio",
    LMSTUDIO_MODEL="test-model",
    LMSTUDIO_TIMEOUT=60.0,
    GITHUB_TOKEN="",
    LAW_OC="",
    GROQ_API_KEY="",
    REDIS_URL="redis://localhost:6379",
    TELEGRAM_CHAT_ID=1,
    NOTION_INBOX_DATABASE_ID="inbox-db",
    NOTION_SCHEDULE_DATABASE_ID="",
    DLOG_ENABLED=False,
    FIRECRAWL_API_KEY="",
)

# 우리가 새로 주입한 모듈만 기록 → tearDownModule에서 그것만 되돌린다(전역 오염 방지).
_INJECTED: list[str] = []
if "notion_client" not in sys.modules:
    sys.modules["notion_client"] = types.SimpleNamespace(AsyncClient=_AsyncClientStub)
    _INJECTED.append("notion_client")
if "config" not in sys.modules:
    sys.modules["config"] = types.SimpleNamespace(settings=_FAKE_SETTINGS)
    _INJECTED.append("config")

from services import notion_service


def tearDownModule():
    for _name in _INJECTED:
        sys.modules.pop(_name, None)


def _run(coro):
    return asyncio.run(coro)


class _PagesStub:
    def __init__(self):
        self.create_kwargs = None

    async def create(self, **kwargs):
        self.create_kwargs = kwargs
        return {"id": "page-1"}


class TestSaveLink(unittest.TestCase):

    def test_save_fills_link_tags_from_explicit_ai_result_tags(self):
        pages = _PagesStub()
        notion_service.client = types.SimpleNamespace(pages=pages)

        _run(notion_service.save(
            "https://github.com/a/b",
            "title",
            "제목: Claude Code 컨텍스트 관리 Skill\n- 요약",
            platform="github",
            tags=["Claude", "클로드", "AI에이전트", "컨텍스트 관리"],
        ))

        self.assertEqual(
            pages.create_kwargs["properties"]["태그"],
            {"multi_select": [
                {"name": "Claude"},
                {"name": "클로드"},
                {"name": "AI에이전트"},
                {"name": "컨텍스트 관리"},
            ]},
        )

    def test_save_fills_link_tags_from_summary_tag_line(self):
        pages = _PagesStub()
        notion_service.client = types.SimpleNamespace(pages=pages)
        summary = (
            "제목: Claude Code 컨텍스트 관리 Skill\n"
            "- Claude Code에서 컨텍스트 낭비를 줄이는 방법\n"
            "태그: Claude, 클로드, AI에이전트, 컨텍스트 관리"
        )

        page_id = _run(notion_service.save("https://github.com/a/b", "title", summary, platform="github"))

        self.assertEqual(page_id, "page-1")
        self.assertEqual(
            pages.create_kwargs["properties"]["태그"],
            {"multi_select": [
                {"name": "Claude"},
                {"name": "클로드"},
                {"name": "AI에이전트"},
                {"name": "컨텍스트 관리"},
            ]},
        )
        saved_text = pages.create_kwargs["children"][0]["paragraph"]["rich_text"][0]["text"]["content"]
        self.assertNotIn("태그:", saved_text)


if __name__ == "__main__":
    unittest.main()
