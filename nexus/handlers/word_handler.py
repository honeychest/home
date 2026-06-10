import logging
import time

from telegram import Update, InlineKeyboardButton, InlineKeyboardMarkup
from telegram.ext import ContextTypes

from session import WordPending
from services import ai_service, notion_service
from services.word_repository import WordRepository

logger = logging.getLogger(__name__)


async def handle_word_query(update: Update, chat_id: int, text: str) -> None:
    """단어/문장 설명 후 등록 버튼 표시."""
    wp = WordPending(chat_id)

    if len(text) <= 3:
        await wp.set({"short_word_confirm": text})
        buttons = [[
            InlineKeyboardButton("✅ 맞아요", callback_data="word:short_confirm"),
            InlineKeyboardButton("✖ 아니에요", callback_data="word:cancel"),
        ]]
        await update.message.reply_text(
            f"'{text}' — 올바른 단어인가요?",
            reply_markup=InlineKeyboardMarkup(buttons),
        )
        return

    loading = await update.message.reply_text("분석 중... ⏳")

    t0 = time.time()
    info = await ai_service.explain_word(text)
    t1 = time.time()
    logger.info(f"[word_query] explain_word 소요: {t1 - t0:.2f}s — 입력: {text!r}, 결과: {info.get('word')!r}")

    word = info["word"]
    transformed = word.lower() != text.lower()

    conflict_pages = await WordRepository(notion_service).search_words_containing(text)
    t2 = time.time()
    logger.info(f"[word_query] search_words_containing 소요: {t2 - t1:.2f}s — 결과 {len(conflict_pages)}개")

    await loading.delete()

    await wp.set({
        **info,
        "original_word": text,
        "existing_page_id": None,
        "conflict_pages": conflict_pages,
    })

    msg = f"{word}\n뜻: {info['meaning_ko']}\n\n📌 \"{info['example']}\""

    if conflict_pages:
        await update.message.reply_text(msg)
        await _ask_conflict(update.message, conflict_pages[0])
    else:
        await update.message.reply_text(msg, reply_markup=_register_buttons(transformed))


async def _ask_conflict(message, conflict: dict) -> None:
    buttons = [[
        InlineKeyboardButton("🗑 삭제", callback_data="word:conflict_delete"),
        InlineKeyboardButton("🔒 유지", callback_data="word:conflict_keep"),
        InlineKeyboardButton("⏹ 작업중단", callback_data="word:conflict_stop"),
    ]]
    await message.reply_text(
        f"기존에 '{conflict['word']}' ({conflict['stage']}단계)이 있어요. 삭제할까요?",
        reply_markup=InlineKeyboardMarkup(buttons),
    )


def _register_buttons(transformed: bool) -> InlineKeyboardMarkup:
    if transformed:
        return InlineKeyboardMarkup([[
            InlineKeyboardButton("✅ 추천형태로 등록", callback_data="word:register"),
            InlineKeyboardButton("📝 원어로 등록", callback_data="word:register_original"),
            InlineKeyboardButton("✖ 취소", callback_data="word:cancel"),
        ]])
    return InlineKeyboardMarkup([[
        InlineKeyboardButton("✅ 등록", callback_data="word:register"),
        InlineKeyboardButton("✖ 취소", callback_data="word:cancel"),
    ]])


async def _show_next_conflict_or_register(query, pending: dict) -> None:
    conflict_pages = pending.get("conflict_pages", [])
    if conflict_pages:
        await _ask_conflict(query.message, conflict_pages[0])
    else:
        word = pending["word"]
        transformed = word.lower() != pending.get("original_word", word).lower()
        await query.message.reply_text(
            f"처리 완료! '{word}'를 등록할까요?",
            reply_markup=_register_buttons(transformed),
        )


async def handle_callback(update: Update, context: ContextTypes.DEFAULT_TYPE) -> None:
    """word:* 콜백 처리."""
    query   = update.callback_query
    await query.answer()
    chat_id = query.message.chat_id
    data    = query.data
    wp      = WordPending(chat_id)

    async def _load():
        pending = await wp.get()
        if not pending:
            await query.edit_message_reply_markup(reply_markup=None)
            await query.message.reply_text("등록 정보가 만료됐어요. 다시 입력해주세요.")
        return pending

    if data == "word:conflict_delete":
        pending = await _load()
        if not pending:
            return
        conflict_pages = pending.get("conflict_pages", [])
        current = conflict_pages.pop(0)
        pending["conflict_pages"] = conflict_pages
        await wp.set(pending)
        await notion_service.delete_page(current["page_id"])
        await query.edit_message_text(f"🗑 '{current['word']}' ({current['stage']}단계) 삭제됐어요.")
        await _show_next_conflict_or_register(query, pending)

    elif data == "word:conflict_keep":
        pending = await _load()
        if not pending:
            return
        conflict_pages = pending.get("conflict_pages", [])
        current = conflict_pages.pop(0)
        pending["conflict_pages"] = conflict_pages
        await wp.set(pending)
        await query.edit_message_text(f"🔒 '{current['word']}' ({current['stage']}단계) 유지했어요.")
        await _show_next_conflict_or_register(query, pending)

    elif data == "word:conflict_stop":
        pending = await _load()
        if not pending:
            return
        pending["conflict_pages"] = []
        await wp.set(pending)
        await query.edit_message_reply_markup(reply_markup=None)
        await _show_next_conflict_or_register(query, pending)

    elif data == "word:register":
        pending = await _load()
        if not pending:
            return
        await notion_service.add_word(pending["word"], pending["meaning_ko"])
        await query.edit_message_reply_markup(reply_markup=None)
        await query.message.reply_text(f"✅ '{pending['word']}' 저장됐어요. 내일 첫 퀴즈 나올거에요!")

    elif data == "word:register_original":
        pending = await _load()
        if not pending:
            return
        original_word = pending.get("original_word", pending["word"])
        await notion_service.add_word(original_word, pending["meaning_ko"])
        await query.edit_message_reply_markup(reply_markup=None)
        await query.message.reply_text(f"✅ '{original_word}' 저장됐어요. 내일 첫 퀴즈 나올거에요!")

    elif data == "word:reregister":
        pending = await _load()
        if not pending:
            return
        await WordRepository(notion_service).update_word_stage(pending["existing_page_id"], correct=False)
        await query.edit_message_reply_markup(reply_markup=None)
        await query.message.reply_text(f"🔄 '{pending['word']}' 1단계로 초기화됐어요!")

    elif data == "word:short_confirm":
        pending = await _load()
        if not pending:
            return
        text = pending.get("short_word_confirm", "")
        if not text:
            await query.edit_message_reply_markup(reply_markup=None)
            await query.message.reply_text("등록 정보가 만료됐어요. 다시 입력해주세요.")
            return
        await query.edit_message_reply_markup(reply_markup=None)
        loading = await query.message.reply_text("분석 중... ⏳")
        t0 = time.time()
        info = await ai_service.explain_word(text)
        t1 = time.time()
        logger.info(f"[short_confirm] explain_word 소요: {t1 - t0:.2f}s — 입력: {text!r}, 결과: {info.get('word')!r}")
        word = info["word"]
        transformed = word.lower() != text.lower()
        conflict_pages = await WordRepository(notion_service).search_words_containing(text)
        t2 = time.time()
        logger.info(f"[short_confirm] search_words_containing 소요: {t2 - t1:.2f}s — 결과 {len(conflict_pages)}개")
        await loading.delete()
        await wp.set({**info, "original_word": text, "existing_page_id": None, "conflict_pages": conflict_pages})
        msg = f"{word}\n뜻: {info['meaning_ko']}\n\n📌 \"{info['example']}\""
        if conflict_pages:
            await query.message.reply_text(msg)
            await _ask_conflict(query.message, conflict_pages[0])
        else:
            await query.message.reply_text(msg, reply_markup=_register_buttons(transformed))

    elif data == "word:cancel":
        await query.edit_message_text("취소됐어요.")
