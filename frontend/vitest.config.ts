// [AGENT] vitest 수집 범위 — .test.ts/tsx 만. (src 곳곳의 *.test.mjs 는 vitest 도입 전
// node 로 직접 실행하던 스크립트라 테스트 스위트가 아님 — 수집하면 전부 실패로 집계됨)
import { defineConfig } from 'vitest/config';

export default defineConfig({
    test: {
        include: ['src/**/*.test.{ts,tsx}'],
    },
});
