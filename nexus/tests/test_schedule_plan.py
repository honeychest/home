import os
import sys
import unittest
from datetime import date

sys.path.insert(0, os.path.dirname(os.path.dirname(os.path.abspath(__file__))))


class TestSchedulePlanClosing(unittest.TestCase):
    def test_22_plan_includes_done_tomorrow_pending_and_quiz(self):
        from services.schedule_plan import ScheduleInputs, build_schedule_plan

        plan = build_schedule_plan(
            ScheduleInputs(
                hour=22,
                today=date(2026, 5, 9),
                pending=[
                    {"text": "보고서 제출", "short_key": "todo1"},
                ],
                done=[
                    {"text": "독서"},
                ],
                tomorrow=[
                    {"text": "운동"},
                ],
                quiz_count=3,
            )
        )

        self.assertEqual(plan[0].text, "📋 오늘 마무리\n~~독서~~ ✔\n\n📅 내일 예정\n• 운동")
        self.assertIsNone(plan[0].action)
        self.assertEqual(plan[1].text, "📋 보고서 제출")
        self.assertEqual(plan[1].action, {
            "kind": "inbox_item",
            "done_callback": "inbox:done:todo1",
            "postpone_callback": "inbox:postpone:todo1",
        })
        self.assertEqual(plan[2].text, "🔤 퀴즈 3개 남음")
        self.assertEqual(plan[2].action, {"kind": "quiz_start"})


class TestSchedulePlanDaytime(unittest.TestCase):
    def test_09_empty_day_still_nudges_with_status_message(self):
        from services.schedule_plan import ScheduleInputs, build_schedule_plan

        plan = build_schedule_plan(
            ScheduleInputs(
                hour=9,
                today=date(2026, 5, 9),
                pending=[],
                done=[],
                tomorrow=[],
                quiz_count=0,
            )
        )

        self.assertEqual(len(plan), 2)
        self.assertEqual(plan[0].text, "📋 오늘 할일 없음")
        self.assertEqual(plan[1].text, "🔤 퀴즈 ✔ 완료")
        self.assertIsNone(plan[1].action)

    def test_15_and_09_share_identical_empty_day_output(self):
        from services.schedule_plan import ScheduleInputs, build_schedule_plan

        def empty(hour):
            return build_schedule_plan(
                ScheduleInputs(
                    hour=hour,
                    today=date(2026, 5, 9),
                    pending=[],
                    done=[],
                    tomorrow=[],
                    quiz_count=0,
                )
            )

        self.assertEqual(
            [(m.text, m.action) for m in empty(15)],
            [(m.text, m.action) for m in empty(9)],
        )

    def test_daytime_plan_appends_quiz_after_tomorrow_when_today_is_empty(self):
        from services.schedule_plan import ScheduleInputs, build_schedule_plan

        plan = build_schedule_plan(
            ScheduleInputs(
                hour=9,
                today=date(2026, 5, 9),
                pending=[],
                done=[],
                tomorrow=[{"text": "운동"}, {"text": "병원"}],
                quiz_count=0,
            )
        )

        self.assertEqual(len(plan), 2)
        self.assertEqual(plan[0].text, "📅 내일 예정\n• 운동\n• 병원")
        self.assertEqual(plan[1].text, "🔤 퀴즈 ✔ 완료")


if __name__ == "__main__":
    unittest.main()
