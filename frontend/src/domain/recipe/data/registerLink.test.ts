// [AGENT] 영상 우선 등록 규칙 고정 — 화면 3곳이 공유하는 단일 원본의 판정 테스트
import { describe, expect, it } from 'vitest';
import { registerLink } from './registerLink';
import type { RegistrationRepository } from './registrationRepository';
import type { RegistrationItem } from './registrationTypes';
import { DuplicateVideoError } from './registrationTypes';

const item = (over: Partial<RegistrationItem> = {}): RegistrationItem => ({
    videoId: 'aaaaaaaaaaa',
    url: 'https://www.youtube.com/watch?v=aaaaaaaaaaa',
    platform: 'YOUTUBE',
    category: null,
    status: 'WAITING',
    title: null,
    thumbnailUrl: null,
    durationSeconds: null,
    recipe: null,
    summary: null,
    tags: null,
    registeredAt: '2026-07-12T00:00:00Z',
    ...over,
});

/** 호출 기록용 가짜 저장소 — 네트워크 없이 규칙만 검증 */
function fakeRepo(over: Partial<RegistrationRepository> = {}): RegistrationRepository & { calls: string[] } {
    const calls: string[] = [];
    return {
        calls,
        list: async () => [],
        register: async (url: string) => { calls.push(`register:${url}`); return item(); },
        registerPlaylist: async (url: string) => { calls.push(`playlist:${url}`); return 3; },
        reanalyze: async () => undefined,
        recentDone: async () => [],
        ...over,
    };
}

describe('registerLink — 영상 우선 등록 규칙', () => {
    it('영상+재생목록 혼합 링크(list= 동반)는 영상 1개 우선', async () => {
        const repo = fakeRepo();
        const outcome = await registerLink(
            'https://www.youtube.com/watch?v=aaaaaaaaaaa&list=PLxxxx', repo);

        expect(outcome.kind).toBe('video');
        expect(repo.calls).toHaveLength(1);
        expect(repo.calls[0]).toMatch(/^register:/);
    });

    it('재생목록 자체 링크(v= 없음)만 일괄 등록 — 추가 수를 돌려준다', async () => {
        const repo = fakeRepo();
        const outcome = await registerLink('https://www.youtube.com/playlist?list=PLxxxx', repo);

        expect(outcome).toEqual({ kind: 'playlist', added: 3 });
        expect(repo.calls[0]).toMatch(/^playlist:/);
    });

    it('유튜브 링크가 아니면 invalid — 저장소를 호출하지 않는다', async () => {
        const repo = fakeRepo();
        const outcome = await registerLink('https://example.com/abc', repo);

        expect(outcome.kind).toBe('invalid');
        expect(repo.calls).toHaveLength(0);
    });

    it('이미 등록된 영상은 duplicate — 화면이 정상/안내를 선택한다', async () => {
        const repo = fakeRepo({
            register: async () => { throw new DuplicateVideoError('u'); },
        });
        const outcome = await registerLink('https://youtu.be/aaaaaaaaaaa', repo);

        expect(outcome.kind).toBe('duplicate');
    });

    it('네트워크 등 진짜 실패는 그대로 던진다 — 실행기(useMutation)가 문구 처리', async () => {
        const repo = fakeRepo({
            register: async () => { throw new Error('boom'); },
        });
        await expect(registerLink('https://youtu.be/aaaaaaaaaaa', repo)).rejects.toThrow('boom');
    });
});
