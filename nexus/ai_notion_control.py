import argparse
import asyncio

from timeutil import now_kst
from services import ai_notion_control as admin


def _parser() -> argparse.ArgumentParser:
    parser = argparse.ArgumentParser(description="AI-facing local Notion data source control tool")
    parser.add_argument("--data-source-id", default=None)
    parser.add_argument("--env-key", default=None)

    sub = parser.add_subparsers(dest="command", required=True)

    sub.add_parser("schema")

    list_parser = sub.add_parser("list")
    list_parser.add_argument("--limit", type=int, default=20)

    create_parser = sub.add_parser("create")
    create_parser.add_argument("--json", default=None)
    create_parser.add_argument("--json-file", default=None)

    update_parser = sub.add_parser("update")
    update_parser.add_argument("--page-id", required=True)
    update_parser.add_argument("--json", default=None)
    update_parser.add_argument("--json-file", default=None)

    upsert_parser = sub.add_parser("upsert")
    upsert_parser.add_argument("--match-property", required=True)
    upsert_parser.add_argument("--match-value", required=True)
    upsert_parser.add_argument("--match-type", default="title")
    upsert_parser.add_argument("--json", default=None)
    upsert_parser.add_argument("--json-file", default=None)

    seed_parser = sub.add_parser("seed-recovery-schedule")
    seed_parser.add_argument("--base-date", default=None)

    return parser


async def _run(args) -> None:
    data_source_id = admin.data_source_id_from(args.data_source_id, args.env_key)

    if args.command == "schema":
        schema = await admin.retrieve_schema(data_source_id)
        for name, kind in schema.items():
            print(f"{name}: {kind}")
        return

    if args.command == "list":
        rows = await admin.list_rows(data_source_id, limit=args.limit)
        for row in rows:
            print(f"{row.page_id} | {row.values}")
        return

    if args.command == "create":
        page_id = await admin.create_row(data_source_id, admin.load_json_arg(args.json, args.json_file))
        print(f"created {page_id}")
        return

    if args.command == "update":
        await admin.update_row(args.page_id, admin.load_json_arg(args.json, args.json_file))
        print(f"updated {args.page_id}")
        return

    if args.command == "upsert":
        action, page_id = await admin.upsert_row(
            data_source_id,
            admin.load_json_arg(args.json, args.json_file),
            match_property=args.match_property,
            match_value=args.match_value,
            match_type=args.match_type,
        )
        print(f"{action} {page_id}")
        return

    if args.command == "seed-recovery-schedule":
        base_date = args.base_date or now_kst().date().isoformat()
        results = await admin.seed_recovery_schedule(data_source_id, base_date=base_date)
        for action, page_id, name in results:
            print(f"{action} {page_id} | {name}")


def main() -> None:
    asyncio.run(_run(_parser().parse_args()))


if __name__ == "__main__":
    main()
