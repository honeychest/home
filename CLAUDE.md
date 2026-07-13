Apply Always : /chs/chs-rules.md

# 인수인계 (모든 AI 에이전트)
- `docs/HANDOFF.md` 가 비어 있지 않으면 먼저 읽고 이어서 작업한다 (일회성 작업의 세션 간 인계).

# 폴더별 코드 규칙 (모든 AI 에이전트)
- `frontend/AGENTS.md`, `springboot/AGENTS.md` — 해당 폴더의 파일을 수정하기 전에 읽는다.
  (공용 훅·순수 모듈 등 "가져다 쓸 것" 모범 패턴과 금지 사항이 정리돼 있음.
  각 폴더의 CLAUDE.md 가 @AGENTS.md 로 임포트하므로 그 폴더 작업 시 자동 로딩됨)
- recipe(기까) 도메인 작업은 `docs/recipe/CONTEXT.md` 를 읽는 것으로 시작한다.

<!-- gitnexus:start -->
# GitNexus — Code Intelligence

This project is indexed by GitNexus as **lab** (11383 symbols, 23274 relationships, 300 execution flows). Use the GitNexus MCP tools to understand code, assess impact, and navigate safely.

> If any GitNexus tool warns the index is stale, run `npx gitnexus analyze` in terminal first.

## Always Do

- **MUST run impact analysis before editing any symbol.** Before modifying a function, class, or method, run `gitnexus_impact({target: "symbolName", direction: "upstream"})` and report the blast radius (direct callers, affected processes, risk level) to the user.
- **MUST run `gitnexus_detect_changes()` before committing** to verify your changes only affect expected symbols and execution flows.
- **MUST warn the user** if impact analysis returns HIGH or CRITICAL risk before proceeding with edits.
- When exploring unfamiliar code, use `gitnexus_query({query: "concept"})` to find execution flows instead of grepping. It returns process-grouped results ranked by relevance.
- When you need full context on a specific symbol — callers, callees, which execution flows it participates in — use `gitnexus_context({name: "symbolName"})`.

## Never Do

- NEVER edit a function, class, or method without first running `gitnexus_impact` on it.
- NEVER ignore HIGH or CRITICAL risk warnings from impact analysis.
- NEVER rename symbols with find-and-replace — use `gitnexus_rename` which understands the call graph.
- NEVER commit changes without running `gitnexus_detect_changes()` to check affected scope.

## Resources

| Resource | Use for |
|----------|---------|
| `gitnexus://repo/lab/context` | Codebase overview, check index freshness |
| `gitnexus://repo/lab/clusters` | All functional areas |
| `gitnexus://repo/lab/processes` | All execution flows |
| `gitnexus://repo/lab/process/{name}` | Step-by-step execution trace |

## CLI

| Task | Read this skill file |
|------|---------------------|
| Understand architecture / "How does X work?" | `.claude/skills/gitnexus/gitnexus-exploring/SKILL.md` |
| Blast radius / "What breaks if I change X?" | `.claude/skills/gitnexus/gitnexus-impact-analysis/SKILL.md` |
| Trace bugs / "Why is X failing?" | `.claude/skills/gitnexus/gitnexus-debugging/SKILL.md` |
| Rename / extract / split / refactor | `.claude/skills/gitnexus/gitnexus-refactoring/SKILL.md` |
| Tools, resources, schema reference | `.claude/skills/gitnexus/gitnexus-guide/SKILL.md` |
| Index, status, clean, wiki CLI commands | `.claude/skills/gitnexus/gitnexus-cli/SKILL.md` |

<!-- gitnexus:end -->
