// [AGENT] 로컬 추출기 상태 판정 테스트 (PLAYBOOK 관례 6)
import { describe, expect, it } from 'vitest';
import type { LocalExtractorHealth } from './monitorTypes';
import { EXPECTED_SERVER_PATH, localExtractorStatus } from './localExtractorStatus';

const healthy = (over: Partial<LocalExtractorHealth> = {}): LocalExtractorHealth => ({
    serverPath: EXPECTED_SERVER_PATH,
    targetFrames: 24,
    lmStudioModel: 'Mac-mini-LLM',
    whisperModelExists: true,
    ytDlpExists: true,
    ffmpegExists: true,
    whisperCliExists: true,
    denoOnPath: true,
    ...over,
});

describe('localExtractorStatus', () => {
    it('전부 정상이면 ok — 라벨에 프레임 수를 보여준다(지금 어떤 설정으로 도는지 확인용)', () => {
        const status = localExtractorStatus(healthy());
        expect(status.level).toBe('ok');
        expect(status.label).toContain('24');
        expect(status.problems).toEqual([]);
    });

    it('health 가 null 이면 down — 전부 Gemini 로 간다는 사실을 알린다', () => {
        const status = localExtractorStatus(null);
        expect(status.level).toBe('down');
        expect(status.problems[0]).toContain('Gemini');
    });

    // 2026-07-16 실제 사고 2건의 재발 감지선 — 이 두 테스트가 그날의 교훈이다
    it('저장소가 아닌 사본을 돌고 있으면 경고 — 사본 사고(recipe - 24) 재발 감지', () => {
        const status = localExtractorStatus(healthy({ serverPath: '/Users/honey/gikka-local/server.py' }));
        expect(status.level).toBe('warning');
        expect(status.problems[0]).toContain('/Users/honey/gikka-local/server.py');
    });

    it('deno 를 못 찾으면 경고 — PATH 사고(recipe - 28) 재발 감지', () => {
        const status = localExtractorStatus(healthy({ denoOnPath: false }));
        expect(status.level).toBe('warning');
        expect(status.problems[0]).toContain('deno');
    });

    it('도구가 없으면 각각 경고로 잡힌다', () => {
        expect(localExtractorStatus(healthy({ ytDlpExists: false })).problems[0]).toContain('yt-dlp');
        expect(localExtractorStatus(healthy({ whisperModelExists: false })).problems[0]).toContain('whisper 모델');
    });

    it('문제가 여러 개면 전부 모아서 보여준다', () => {
        const status = localExtractorStatus(healthy({ denoOnPath: false, ffmpegExists: false }));
        expect(status.problems).toHaveLength(2);
    });
});
