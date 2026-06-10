from dataclasses import dataclass
from typing import Any

from utils.strings import levenshtein


@dataclass(frozen=True)
class QuizTurn:
    word: str
    meaning_ko: str
    page_id: str
    stage: int
    progress: str
    body: str


@dataclass(frozen=True)
class NoQuizAvailable:
    message: str


@dataclass(frozen=True)
class QuizComplete:
    message: str


@dataclass(frozen=True)
class QuizAnswerFeedback:
    reply: str
    should_continue: bool
    next_exclude_page_id: str | None = None
    grammar_errors: list[dict] | None = None
    collocation_errors: list[str] | None = None
    vocab_hints: list[str] | None = None
    example_sentence: str | None = None
    usage_hint: str | None = None
    needs_correction: bool = False


class QuizFlow:
    def __init__(
        self,
        session: Any,
        word_deck: Any,
        quiz_generator: Any,
        grammar_pending: Any | None = None,
    ):
        self._session = session
        self._word_deck = word_deck
        self._quiz_generator = quiz_generator
        self._grammar_pending = grammar_pending

    async def start_auto_quiz(self) -> QuizTurn | NoQuizAvailable | QuizComplete:
        words = await self._word_deck.get_due_words()
        if not words:
            if hasattr(self._session, "set_count"):
                await self._session.set_count(0)
            if hasattr(self._session, "clear_state"):
                await self._session.clear_state()
            return NoQuizAvailable("오늘 복습할 단어가 없어요")

        parsed = words[0]

        progress_info = await self._session.consume_count()
        if progress_info is None:
            if hasattr(self._session, "clear_active"):
                await self._session.clear_active()
            return QuizComplete("🎉 오늘 퀴즈 완료! 수고했어요 💪")
        remaining, total = progress_info

        question = await self._quiz_generator.generate_quiz(
            parsed["word"],
            parsed["meaning_ko"],
            parsed["stage"],
        )

        await self._session.set_session({
            "word": parsed["word"],
            "meaning_ko": parsed["meaning_ko"],
            "stage": parsed["stage"],
            "page_id": parsed["page_id"],
            "question": question,
            "mode": "auto",
        })
        await self._session.set_state("quiz")

        progress = _format_progress(remaining, total)
        body = question
        return QuizTurn(
            word=parsed["word"],
            meaning_ko=parsed["meaning_ko"],
            page_id=parsed["page_id"],
            stage=parsed["stage"],
            progress=progress,
            body=body,
        )

    async def start_practice_quiz(self) -> QuizTurn | NoQuizAvailable:
        await self._session.clear_prefetch()
        await self._session.reset_count()

        words = await self._word_deck.get_all_words()
        if not words:
            return NoQuizAvailable("단어장이 비어있어요! 단어를 추가해봐요 😊")

        parsed = words[:100][0]

        question = await self._quiz_generator.generate_quiz(
            parsed["word"],
            parsed["meaning_ko"],
            parsed["stage"],
        )

        await self._session.set_session({
            "word": parsed["word"],
            "meaning_ko": parsed["meaning_ko"],
            "stage": parsed["stage"],
            "page_id": parsed["page_id"],
            "question": question,
            "mode": "quiz",
        })
        await self._session.set_state("quiz")

        body = question
        return QuizTurn(
            word=parsed["word"],
            meaning_ko=parsed["meaning_ko"],
            page_id=parsed["page_id"],
            stage=parsed["stage"],
            progress="[🔄]",
            body=body,
        )

    async def start_next_quiz(
        self,
        *,
        mode: str,
        exclude_page_id: str | None = None,
    ) -> QuizTurn | NoQuizAvailable | QuizComplete:
        if mode == "quiz":
            words = await self._word_deck.get_all_words()
            empty_message = "단어장이 비어있어요!"
        else:
            words = await self._word_deck.get_due_words()
            empty_message = "오늘 복습할 단어가 없어요!"

        if exclude_page_id:
            words = [w for w in words if w["page_id"] != exclude_page_id]

        if not words:
            if mode == "auto":
                if hasattr(self._session, "set_count"):
                    await self._session.set_count(0)
                if hasattr(self._session, "clear_state"):
                    await self._session.clear_state()
            return NoQuizAvailable(empty_message)

        parsed = (words[:100] if mode == "quiz" else words)[0]

        remaining = total = None
        if mode == "auto":
            progress_info = await self._session.consume_count()
            if progress_info is None:
                if hasattr(self._session, "clear_active"):
                    await self._session.clear_active()
                return QuizComplete("🎉 오늘 퀴즈 완료! 수고했어요 💪")
            remaining, total = progress_info

        question = await self._quiz_generator.generate_quiz(
            parsed["word"],
            parsed["meaning_ko"],
            parsed["stage"],
        )

        await self._session.set_session({
            "word": parsed["word"],
            "meaning_ko": parsed["meaning_ko"],
            "stage": parsed["stage"],
            "page_id": parsed["page_id"],
            "question": question,
            "mode": mode,
        })
        await self._session.set_state("quiz")

        progress = _format_progress(remaining, total) if mode == "auto" else "[🔄]"
        body = question
        return QuizTurn(
            word=parsed["word"],
            meaning_ko=parsed["meaning_ko"],
            page_id=parsed["page_id"],
            stage=parsed["stage"],
            progress=progress,
            body=body,
        )

    async def grade_answer(self, text: str) -> QuizAnswerFeedback | NoQuizAvailable:
        session = await self._session.get_session()
        if not session:
            return NoQuizAvailable("진행 중인 퀴즈가 없어요.")

        word = session["word"]
        meaning_ko = session["meaning_ko"]
        stage = session["stage"]
        page_id = session["page_id"]
        question = session.get("question", "")
        mode = session.get("mode", "auto")

        if stage >= 3:
            return await self._grade_writing_answer(
                session=session,
                word=word,
                meaning_ko=meaning_ko,
                question=question,
                answer=text,
                page_id=page_id,
                mode=mode,
            )

        correct = text.strip().lower() == word.lower()
        if correct:
            reply = "✅ 정답!"
        else:
            distance = levenshtein(text, word)
            retry_count = session.get("retry_count", 0)
            if distance <= 2 and retry_count == 0:
                session["retry_count"] = 1
                await self._session.set_session(session)
                return QuizAnswerFeedback(
                    reply="오타인 것 같아요! 다시 한번! 🔄",
                    should_continue=False,
                )
            reply = f"❌ 오답. 정답은 '{word}'예요. 1단계로 돌아갑니다."

        if not (mode == "quiz" and correct):
            await self._word_deck.update_word_stage(page_id, correct)

        return QuizAnswerFeedback(
            reply=reply,
            should_continue=True,
            next_exclude_page_id=page_id,
        )

    async def _grade_writing_answer(
        self,
        *,
        session: dict,
        word: str,
        meaning_ko: str,
        question: str,
        answer: str,
        page_id: str,
        mode: str,
    ) -> QuizAnswerFeedback:
        word_used = word.lower() in answer.lower()
        result = await self._quiz_generator.grade_writing(word, meaning_ko, question, answer)

        grammar_errors = result.get("grammar_errors", [])
        collocation_errors = result.get("collocation_errors", [])
        vocab_hints = result.get("vocab_hints", [])
        example_sentence = result.get("example_sentence", "")
        usage_hint = result.get("usage_hint", "")

        # ── 단어 미포함 ──────────────────────────────────────────
        if not word_used:
            if result["context_ok"]:
                return QuizAnswerFeedback(
                    reply=f"⚠️ 의미는 맞지만 '{word}'를 직접 사용해야 해요. 다시 도전!",
                    should_continue=False,
                )
            await self._word_deck.update_word_stage(page_id, False)
            reply = f"❌ 오답. '{word}'를 사용한 문장을 만들어보세요."
            if example_sentence:
                reply += f"\n💡 모범답안: {example_sentence}"
            return QuizAnswerFeedback(
                reply=reply,
                should_continue=True,
                next_exclude_page_id=page_id,
            )

        # ── 단어 포함 + 의미 전달 O ─────────────────────────────
        if result["used_correctly"]:
            reply = "✅ 정답! 올바르게 사용했어요."
            alternatives = result.get("alternatives", [])
            if alternatives:
                reply += "\n\n💡 비슷한 표현: " + " / ".join(alternatives)

            needs_correction = bool(grammar_errors or collocation_errors)
            if needs_correction:
                if grammar_errors:
                    error_lines = "\n".join(f"⚠️ [{e['type']}] {e['detail']}" for e in grammar_errors)
                    reply += f"\n\n{error_lines}"
                if collocation_errors:
                    reply += f"\n\n💡 연어 등록 추천:\n" + "\n".join(collocation_errors)
                reply += "\n\n📝 한번 더 다듬어서 써볼까요?"

                if self._grammar_pending:
                    await self._grammar_pending.set({
                        "expression": word,
                        "wrong_sentence": answer,
                        "grammar_errors": grammar_errors,
                        "collocation_errors": collocation_errors,
                    })

            if mode != "quiz":
                await self._word_deck.update_word_stage(page_id, True)

            return QuizAnswerFeedback(
                reply=reply,
                should_continue=not needs_correction,
                next_exclude_page_id=page_id if not needs_correction else None,
                grammar_errors=grammar_errors if needs_correction else None,
                collocation_errors=collocation_errors if needs_correction else None,
                needs_correction=needs_correction,
            )

        # ── 단어 포함 + 의미 전달 X → 재시도 (갱신 없음) ────────
        writing_retry = session.get("writing_retry", 0) + 1
        session["writing_retry"] = writing_retry
        await self._session.set_session(session)

        if writing_retry >= 5:
            reply = f"⏰ 여러 번 시도했어요."
            if example_sentence:
                reply += f"\n💡 모범답안: {example_sentence}"
            return QuizAnswerFeedback(
                reply=reply,
                should_continue=True,
                next_exclude_page_id=page_id,
            )

        reply = "💡 단어는 잘 넣었어요! 문장을 다듬어보세요 🔄"
        if usage_hint:
            reply += f"\n📝 용법: {usage_hint}"
        if vocab_hints:
            reply += f"\n📖 보조 어휘: {', '.join(vocab_hints)}"

        return QuizAnswerFeedback(
            reply=reply,
            should_continue=False,
            vocab_hints=vocab_hints,
            example_sentence=example_sentence,
            usage_hint=usage_hint,
        )

    async def fail_current_quiz(self) -> QuizAnswerFeedback | NoQuizAvailable:
        session = await self._session.get_session()
        if not session:
            return NoQuizAvailable("진행 중인 퀴즈가 없어요.")

        page_id = session["page_id"]
        await self._word_deck.update_word_stage(page_id, False)

        return QuizAnswerFeedback(
            reply=f"❌ 실패. 정답은 '{session['word']}'예요. 1단계로 돌아갑니다.",
            should_continue=True,
            next_exclude_page_id=page_id,
        )

def create_quiz_flow(chat_id: int) -> QuizFlow:
    from session import GrammarPending, QuizSession
    from services import ai_service, notion_service
    from services.word_repository import WordRepository

    return QuizFlow(
        session=QuizSession(chat_id),
        word_deck=WordRepository(notion_service),
        quiz_generator=ai_service,
        grammar_pending=GrammarPending(chat_id),
    )


def _format_progress(remaining: int, total: int | None = None) -> str:
    if total and total > 0:
        completed = max(0, min(total - remaining, total))
        return f"[{completed}/{total}]"
    return f"[남은 퀴즈 {remaining}개]"
