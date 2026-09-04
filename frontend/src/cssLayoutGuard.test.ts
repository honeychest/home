// [AGENT] CSS 치수 가드 — "화면에 아무것도 안 보이는" 사고를 커밋 전에 막는다 (frontend 전체 대상).
//
// 왜 문서가 아니라 테스트인가 (2026-07-25 신설):
// iOS(사파리·iOS 크롬)에서 추천 카드가 통째로 안 보이는 일이 있었다. 카드에 폭·높이를 둘 다
// 안 적고 "부모가 늘려준 높이(flex stretch) × aspect-ratio" 로 폭을 역산하게 뒀는데, WebKit 은
// 그 역산을 하지 않아 폭이 0 이 됐다. 데이터·API 는 정상인데 화면만 비어 보여 원인 추적에
// 오래 걸렸고, 크롬·PC 에서는 멀쩡해서 개발 중에는 눈으로 잡을 수 없었다.
// 같은 결함이 스켈레톤 카드에도 그대로 복제돼 있었다 — 글로만 적어두면 다음에 또 복제된다.
//
// 규칙: aspect-ratio 를 쓰는 규칙 블록에는 폭이나 높이 중 하나를 명시한다.
// 고치는 법: 그 요소에 width 또는 height 를 함께 적는다(부모가 확정 높이면 height: 100%).
// min-/max- 는 인정하지 않는다 — 그건 크기를 정하는 게 아니라 제한하는 것이라 비율 계산의
// 출발점이 되지 못한다.
// 예외를 추가하지 말 것. "이번 한 번만" 이 쌓이면 이 가드는 무의미해진다.
import { describe, expect, it } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const SRC_ROOT = fileURLToPath(new URL('.', import.meta.url));

/** 폭·높이를 확정하는 선언 (min-/max- 는 제외 — 앞에 '-' 가 붙으면 매칭되지 않는다) */
const DEFINITE_SIZE = /(?:^|[;\s])(?:width|height|flex-basis)\s*:/;
/** flex 단축 표기의 basis 자리에 길이가 들어간 경우 — 예: flex: 0 0 200px (이것도 폭 확정) */
const FLEX_WITH_LENGTH = /(?:^|[;\s])flex\s*:[^;]*\d(?:px|rem|em|%|vw|vh|dvw|dvh)/;
const USES_ASPECT_RATIO = /(?:^|[;\s])aspect-ratio\s*:/;

/** 가장 안쪽 블록만 뽑는다 — @media 같은 중첩이 있어도 실제 선언 뭉치는 여기 들어있다 */
const INNER_BLOCK = /([^{}]*)\{([^{}]*)\}/g;

function collectCssFiles(dir: string): string[] {
    return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) return collectCssFiles(full);
        return entry.name.endsWith('.css') ? [full] : [];
    });
}

/** 주석 안의 설명 문구(aspect-ratio 를 언급하는 해설 등)가 규칙에 걸리지 않게 걷어낸다 */
function stripComments(css: string): string {
    return css.replace(/\/\*[\s\S]*?\*\//g, '');
}

interface Violation {
    file: string;
    line: number;
    selector: string;
}

function findViolations(file: string): Violation[] {
    const source = stripComments(readFileSync(file, 'utf8'));
    const result: Violation[] = [];
    for (const match of source.matchAll(INNER_BLOCK)) {
        const [, selector, declarations] = match;
        if (!USES_ASPECT_RATIO.test(declarations)) continue;
        if (DEFINITE_SIZE.test(declarations) || FLEX_WITH_LENGTH.test(declarations)) continue;
        result.push({
            file: relative(SRC_ROOT, file).replace(/\\/g, '/'),
            line: source.slice(0, match.index).split('\n').length,
            selector: selector.trim().split('\n').pop()?.trim() ?? '',
        });
    }
    return result;
}

describe('CSS 치수 가드 — aspect-ratio 로 치수를 역산하지 않는다 (WebKit 대비)', () => {
    const files = collectCssFiles(SRC_ROOT);

    it('검사 대상 CSS 를 실제로 찾는다 (경로가 바뀌면 가드가 조용히 무력해지므로)', () => {
        expect(files.length).toBeGreaterThan(0);
    });

    it('aspect-ratio 를 쓰는 규칙에는 width 나 height 가 함께 있다', () => {
        const violations = files.flatMap(findViolations)
            .map((v) => `${v.file}:${v.line}  ${v.selector}`);

        expect(
            violations,
            'aspect-ratio 만으로 치수를 정하면 WebKit(iOS 사파리·iOS 크롬)에서 크기가 0 이 되어\n'
            + '요소가 화면에 아예 안 보입니다 (크롬·PC 에서는 멀쩡해 눈으로 못 잡습니다).\n'
            + '해당 규칙에 width 또는 height 를 함께 적으세요 — 부모가 확정 높이면 height: 100%.\n'
            + '(frontend/AGENTS.md "금지" 절 참고)',
        ).toEqual([]);
    });
});
