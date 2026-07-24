// [AGENT] 로컬 추출기 상태 판정 — 순수 모듈 (PLAYBOOK 관례 6: 판정은 화면 밖 + vitest)
// 호스트 서비스(/health)는 사실만 보고하고, "정상인가 / 뭐라고 보여줄까"는 여기가 정한다
// (springboot/AGENTS.md 의 pattern-raw-signal 과 같은 사상 — analysisQuality.ts 와 같은 구조).
//
// 왜 만들었나 (2026-07-16, 같은 날 두 번 데인 뒤):
//  1. 저장소는 최신인데 launchd 가 손으로 뜬 사본을 돌고 있었다 → 품질 경고가 125건 내내 죽어 있었음
//  2. deno 는 설치돼 있는데 launchd 의 최소 PATH 라 yt-dlp 가 못 찾았다 → deprecated 경로로 유튜브 접근
// 둘 다 "설정은 맞는데 실제 도는 환경이 다르다"라 설정 파일만 봐서는 영영 안 보였다.
import type { LocalExtractorHealth } from './monitorTypes';

/** 서비스가 여기서 돌아야 정상 — 사본이 아니라 Jenkins 가 git pull 하는 체크아웃 (recipe - 24) */
export const EXPECTED_SERVER_PATH = '/Users/honey/devcontext/project/lab/gikka-extractor/server.py';

export type LocalExtractorLevel = 'ok' | 'warning' | 'down';

export interface LocalExtractorStatus {
    level: LocalExtractorLevel;
    /** 배지에 쓸 짧은 라벨 */
    label: string;
    /** 문제일 때 무엇이 문제인지 — 정상이면 빈 배열 */
    problems: string[];
}

const DOWN_LABEL = '로컬 추출기 꺼짐';
const OK_LABEL = '로컬 추출기 정상';
const WARNING_LABEL = '로컬 추출기 주의';
const DOWN_PROBLEM = '지금 등록되는 영상은 전부 Gemini 로 갑니다 (무료 한도를 씁니다).';
const framesText = (n: number) => `프레임 ${n}장`;

/**
 * 사실 → 판정. health 가 null 이면 "못 씀"(죽었거나 /health 없는 옛 버전).
 * down = 로컬을 아예 못 쓴다 / warning = 돌긴 도는데 품질·수명에 문제 /  ok = 이상 없음.
 */
export function localExtractorStatus(health: LocalExtractorHealth | null): LocalExtractorStatus {
    if (health === null) {
        return { level: 'down', label: DOWN_LABEL, problems: [DOWN_PROBLEM] };
    }
    const problems: string[] = [];
    // 사본 사고(recipe - 24)의 재발 감지 — 경로가 다르면 저장소가 아닌 무언가를 돌고 있다
    if (health.serverPath !== EXPECTED_SERVER_PATH) {
        problems.push(`저장소가 아닌 파일을 돌고 있어요: ${health.serverPath}`);
    }
    // deno 사고(recipe - 28)의 재발 감지
    if (!health.denoOnPath) {
        problems.push('deno 를 못 찾아요 — yt-dlp 가 폐기 예정 경로로 유튜브를 받고 있어요.');
    }
    if (!health.ytDlpExists) problems.push('yt-dlp 가 없어요.');
    if (!health.ffmpegExists) problems.push('ffmpeg 가 없어요.');
    if (!health.whisperCliExists) problems.push('whisper-cli 가 없어요.');
    if (!health.whisperModelExists) problems.push('whisper 모델 파일이 없어요 — 음성 인식이 안 됩니다.');

    if (problems.length === 0) {
        return { level: 'ok', label: `${OK_LABEL} · ${framesText(health.targetFrames)}`, problems };
    }
    return { level: 'warning', label: WARNING_LABEL, problems };
}
