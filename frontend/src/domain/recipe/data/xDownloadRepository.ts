// [AGENT] X(트위터) 영상 다운로드 저장소 (2026-07-20 확정) — recipe 의 등록·분석 기능과 완전히
// 별개, 기록 없는 1회성 조회. 링크를 주면 해상도별 직접 다운로드 주소만 받아온다 — 서버는
// 영상 바이트를 만지지 않고, 실제 다운로드는 이 주소로 폰 브라우저가 직접 한다.
import { jsonBody, request } from './http';

export interface XVideoOption {
    height: number;
    url: string;
}

export interface XResolveResult {
    title: string;
    thumbnail: string;
    options: XVideoOption[];
}

export interface XDownloadRepository {
    /** 오너 전용(서버가 403으로 게이트) — X 링크가 아니면 400, 영상을 못 찾으면 404 */
    resolve(url: string): Promise<XResolveResult>;
}

export function createApiXDownloadRepository(): XDownloadRepository {
    return {
        resolve(url) {
            return request<XResolveResult>('/api/recipe/x-download/resolve',
                { method: 'POST', ...jsonBody({ url }) });
        },
    };
}

export const xDownloadRepository: XDownloadRepository = createApiXDownloadRepository();
