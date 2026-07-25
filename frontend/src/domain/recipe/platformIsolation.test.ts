// [AGENT] recipe 화면(page/·ui/)이 브라우저 환경 API 를 직접 만지지 못하게 막는 가드 테스트.
// (2026-07-25 신설 — springboot 의 RecipeIsolationArchTest 와 같은 역할을 프론트에서 한다)
//
// 왜 문서가 아니라 테스트인가: frontend/AGENTS.md 에 같은 취지의 규칙이 이미 글로 있었는데도
// 실제로는 네 곳이 새고 있었다(LoginPage 의 GIS 직접 로딩, RecipeApp 의 서비스워커·설치
// 안내 저장, HomePage 의 window.location 판정). 백엔드 격리가 싸게 끝난 이유는 문서가 아니라
// ArchUnit 이 위반을 빌드에서 막았기 때문이라, 그 장치를 프론트에도 둔다.
//
// 왜 ESLint 가 아닌가: 이 저장소의 eslint 설정은 대상이 '**/*.{js,jsx}' 라서 recipe 의
// .ts/.tsx 는 애초에 린트되지 않는다. 켜려면 typescript-eslint 파서를 새 의존성으로 들여
// frontend 전체 린트 설정을 바꿔야 하는데, 필요한 건 "특정 API 를 쓰면 실패"뿐이고
// npm run test 는 이미 커밋 전 필수 절차라 여기에 붙이는 편이 싸고 확실하다.
//
// 무엇을 지키려는 건가 (TWA → 네이티브 전환 대비): 아래 API 들은 네이티브 셸에서 동작이
// 달라지거나 아예 없어진다. 어댑터 파일(data/*)에 모아 두면 전환 시 그 파일만 갈아끼우면
// 되지만, 화면에 흩어지면 화면 수만큼 비용이 붙는다.
//
// 규칙을 예외 없이 유지할 것. "이번 한 번만" 이 쌓이면 이 테스트는 무의미해진다 —
// 새 환경 API 가 필요하면 data/platform.ts 에 함수를 추가해서 쓴다.
import { describe, it, expect } from 'vitest';
import { readdirSync, readFileSync } from 'node:fs';
import { join, relative } from 'node:path';
import { fileURLToPath } from 'node:url';

const DOMAIN_ROOT = fileURLToPath(new URL('.', import.meta.url));
/** 화면 계층 — 어댑터가 아니라 소비자다. data/** 는 어댑터 자리이므로 검사 대상이 아니다. */
const GUARDED_DIRS = ['page', 'ui'];

interface Rule {
    /** 금지 패턴 */
    pattern: RegExp;
    /** 무엇이 문제인지 */
    api: string;
    /** 대신 쓸 것 */
    use: string;
}

const RULES: Rule[] = [
    {
        pattern: /\b(localStorage|sessionStorage)\b/,
        api: '브라우저 저장소 직접 접근',
        use: '토큰은 data/tokenStorage.ts, 셸 상태는 data/platform.ts',
    },
    {
        pattern: /navigator\s*\.\s*clipboard/,
        api: 'navigator.clipboard 직접 호출',
        use: "data/clipboard.ts 의 clipboardReadSupported()/readClipboardText()",
    },
    {
        pattern: /navigator\s*\.\s*serviceWorker/,
        api: 'navigator.serviceWorker 직접 호출',
        use: 'data/platform.ts 의 registerServiceWorker()',
    },
    {
        pattern: /navigator\s*\.\s*userAgent/,
        api: 'userAgent 직접 판정',
        use: 'data/platform.ts 의 isIOS()',
    },
    {
        pattern: /\(display-mode|\.\s*standalone\b/,
        api: '설치 상태 직접 판정',
        use: 'data/platform.ts 의 isStandaloneDisplay()',
    },
    {
        pattern: /window\s*\.\s*location/,
        api: 'window.location 직접 접근',
        use: 'data/platform.ts (개발 세션 판정은 isDevSession())',
    },
    {
        pattern: /accounts\.google\.com|google\s*\.\s*accounts/,
        api: 'GIS(구글 로그인 SDK) 직접 접촉',
        use: 'data/googleSignIn.ts 의 mountGoogleSignInButton()',
    },
];

/** 주석 안의 설명 문구가 규칙에 걸리지 않게 걷어낸다.
    블록 주석과 "줄 전체가 주석"인 줄만 제거한다 — 코드 뒤에 붙는 꼬리 주석까지 지우려 들면
    문자열 안의 '//'(URL 등)를 잘라 먹어 오히려 오탐이 생긴다. */
function stripComments(source: string): string {
    return source
        .replace(/\/\*[\s\S]*?\*\//g, '')
        .split('\n')
        .filter((line) => !/^\s*\/\//.test(line))
        .join('\n');
}

function collectSourceFiles(dir: string): string[] {
    return readdirSync(dir, { withFileTypes: true }).flatMap((entry) => {
        const full = join(dir, entry.name);
        if (entry.isDirectory()) return collectSourceFiles(full);
        if (!/\.tsx?$/.test(entry.name)) return [];
        if (/\.test\.tsx?$/.test(entry.name)) return [];
        return [full];
    });
}

describe('recipe 화면은 환경 API 를 직접 만지지 않는다 (네이티브 전환 대비)', () => {
    const files = GUARDED_DIRS.flatMap((dir) => collectSourceFiles(join(DOMAIN_ROOT, dir)));

    it('검사 대상 파일을 실제로 찾는다 (경로가 바뀌면 가드가 조용히 무력해지므로)', () => {
        expect(files.length).toBeGreaterThan(10);
    });

    it.each(RULES)('page/·ui/ 에 $api 이 없다', ({ pattern, api, use }) => {
        const violations = files
            .filter((file) => pattern.test(stripComments(readFileSync(file, 'utf8'))))
            .map((file) => relative(DOMAIN_ROOT, file).replace(/\\/g, '/'));

        expect(
            violations,
            `${api} 은 화면에서 금지됩니다. 대신: ${use}\n`
            + '(이유: 네이티브 셸 전환 시 동작이 달라지는 API 라 어댑터(data/*) 한 곳에 모아 둡니다.\n'
            + ' 새 환경 API 가 필요하면 data/platform.ts 에 함수를 추가해서 쓰세요.)',
        ).toEqual([]);
    });
});
