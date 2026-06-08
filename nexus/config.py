from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    TELEGRAM_BOT_TOKEN: str
    ANTHROPIC_API_KEY: str = ""
    GEMINI_API_KEY: str
    NOTION_API_KEY: str
    NOTION_LINK_DATABASE_ID: str
    NOTION_WORD_DATABASE_ID: str
    NOTION_GRAMMAR_DATABASE_ID: str
    AI_PROVIDER: str = "gemini"
    LMSTUDIO_BASE_URL: str = "http://100.69.229.3:2345/v1"
    LMSTUDIO_API_KEY: str = "lm-studio"
    LMSTUDIO_MODEL: str = "gemma-4-26b-a4b-it-mlx"
    LMSTUDIO_TIMEOUT: float = 60.0
    GITHUB_TOKEN: str = ""
    LAW_OC: str = ""
    GROQ_API_KEY: str = ""
    REDIS_URL: str = "redis://localhost:6379"
    TELEGRAM_CHAT_ID: int  # 퀴즈 알림을 받을 텔레그램 chat_id
    NOTION_INBOX_DATABASE_ID: str  # 할일·아이디어 인박스 DB ID
    NOTION_SCHEDULE_DATABASE_ID: str = ""  # Daily 시간표 DB ID
    DLOG_ENABLED: bool = False  # DRAFT 로그 활성화 (로컬 개발용)
    FIRECRAWL_API_KEY: str = ""

    class Config:
        env_file = ".env"


settings = Settings()
