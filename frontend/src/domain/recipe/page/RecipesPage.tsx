// [AGENT] recipe(기까) 레시피 탭 — 3차: 등록(URL 개별/재생목록 일괄) + 분석 대기열 + 검수용 결과 시트
// 정식 레시피 목록·상세는 4차에서 이 화면을 대체·확장한다.
// 저장소는 registrationRepository 인터페이스만 사용 (지금은 목 — 백엔드 연결 시 구현체 교체).
// 진행 중(대기/분석 중) 항목이 있으면 폴링으로 목록을 갱신 — 백엔드(DB 대기열+단일 워커)에서도 같은 방식.
// 조작은 공용 실행기(useMutation) — 실패 문구·연타 방지·재동기화 한 곳 (PLAYBOOK 관례 5).
// 등록 분기(영상 우선)는 registerLink 공용 모듈 — 홈·공유 수신과 같은 단일 원본.
// 붙여넣기(버튼·입력창 둘 다)는 즉시 등록으로 이어진다 — pasteAndRegister 한 곳.
import { useCallback, useEffect, useState } from 'react';
import { useLocation, useNavigate } from 'react-router-dom';
import type {
    GikkaVideo, RegistrationItem, RegistrationStatus, SearchResults, VideoCategory,
} from '../data/registrationTypes';
import { DuplicateVideoError } from '../data/registrationTypes';
import { registrationRepository } from '../data/registrationRepository';
import { fridgeRepository } from '../data/fridgeRepository';
import { reportRepository } from '../data/reportRepository';
import { registerLink } from '../data/registerLink';
import { analysisQualityWarning, needsReview } from '../data/analysisQuality';
import { parseYoutubePlaylistId, parseYoutubeVideoId } from '../data/videoUrl';
import { clipboardReadSupported, readClipboardText } from '../data/clipboard';
import { useMutation } from './useMutation';
import { useQuery } from './useQuery';
import type { RcpBadgeVariant } from '../ui/RcpBadge';
import RcpBadge from '../ui/RcpBadge';
import RcpButton from '../ui/RcpButton';
import RcpBottomSheet from '../ui/RcpBottomSheet';
import RcpConfirm from '../ui/RcpConfirm';
import RcpFab from '../ui/RcpFab';
import RcpInlineError from '../ui/RcpInlineError';
import RcpLoadError from '../ui/RcpLoadError';
import RcpVideoRow from '../ui/RcpVideoRow';

const POLL_MS = 2500;

// 에러 계약(CONTEXT.md): 사용자 문구는 전부 프론트 소유
const LOAD_ERROR_TEXT = '목록을 불러오지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const loadMessage = () => LOAD_ERROR_TEXT;
const MUTATION_ERROR_TEXT = '등록하지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';
const INVALID_URL_TEXT = '유튜브 링크를 인식하지 못했어요 — 영상·쇼츠·재생목록 링크를 붙여넣어 주세요';
const DUPLICATE_TEXT = '이미 등록된 영상이에요';
const UNAVAILABLE_TEXT = '비공개이거나 삭제된 영상이에요 — 다른 링크를 넣어 주세요';
const PASTE_FAIL_TEXT = '클립보드를 읽지 못했어요 — 직접 붙여넣어 주세요';

const STATUS_LABEL: Record<Exclude<RegistrationStatus, 'DONE'>, string> = {
    WAITING: '분석대기',
    ANALYZING: '분석중',
    TOO_LONG: '긴 영상',
    FAILED: '분석실패',
    REMOVED: '삭제됨',
};
// 분석 완료(DONE)는 상태 대신 분류를 보여준다 (2026-07-12 확정: 요리/유틸/기타)
const CATEGORY_LABEL: Record<VideoCategory, string> = {
    RECIPE: '레시피',
    TIP: '유틸',
    ETC: '기타',
};
/** 카테고리 → 배지 색 슬롯 (2026-07-14 확정, dataviz 스킬 8색 고정 순서 팔레트).
    새 카테고리가 늘면 이 순서 그대로 cat-4 부터 이어 쓴다 — 순서 변경 금지(색맹 구분성 근거) */
const CATEGORY_BADGE: Record<VideoCategory, RcpBadgeVariant> = {
    RECIPE: 'cat-1',
    TIP: 'cat-2',
    ETC: 'cat-3',
};
/** 배지 문구: 완료 항목은 분류(레시피/유틸/기타), 나머지는 진행 상태.
    모든 항목이 항상 배지를 갖는다 — 일부만 비면 카드 오른쪽 칼럼이 들쭉날쭉해져
    레이아웃 일관성이 깨짐 (2026-07-14 재확정: "완료" 대신 "레시피"로 문구만 교체) */
// 검색 보완 항목(GikkaVideo)에도 그대로 쓰이므로 registeredAt 을 안 보는 헬퍼는 GikkaVideo 로 넓힌다
// (RegistrationItem 은 그 상위이므로 그대로 넘길 수 있다). registeredAt 을 보는 것(resultMetaText 등)만 mine 전용.
const itemLabel = (item: GikkaVideo): string =>
    item.status === 'DONE' ? CATEGORY_LABEL[item.category ?? 'ETC'] : STATUS_LABEL[item.status];
/** 상태 → 배지 색 (2026-07-15: if-체인에서 표로 — 아래 STATUS_DETAIL 과 같은 이유).
    카테고리 팔레트와 절대 안 겹치는 예약색만 쓴다 (2026-07-14 재설계, dataviz CVD 검증) */
const STATUS_BADGE: Record<Exclude<RegistrationStatus, 'DONE'>, RcpBadgeVariant> = {
    WAITING: 'neutral',
    ANALYZING: 'analyzing',
    TOO_LONG: 'warning',
    FAILED: 'critical',
    REMOVED: 'serious',
};
/** 배지 색: 완료 항목은 카테고리 팔레트, 나머지는 상태 팔레트 — 서로 절대 안 겹치는
    두 체계 (2026-07-14 재설계, dataviz 스킬 CVD 검증. 예전 "채움/테두리" 구분은 폐지) */
const itemBadge = (item: GikkaVideo): RcpBadgeVariant =>
    item.status === 'DONE' ? CATEGORY_BADGE[item.category ?? 'ETC'] : STATUS_BADGE[item.status];
/** 상태 → 결과 시트의 설명 문구.
    (2026-07-15: if-체인에서 표로 바꿈 — 체인은 끝에 기본값이 있어서 상태가 새로 생기면
    조용히 빈 설명·회색 배지가 됐다. Record 로 두면 새 상태의 문구·색을 정하지 않는 한
    컴파일이 안 된다 — STATUS_LABEL·CATEGORY_* 가 이미 쓰던 방식과 같게 맞춤) */
const STATUS_DETAIL: Record<Exclude<RegistrationStatus, 'DONE'>, string> = {
    WAITING: '차례를 기다리고 있어요. 분석이 끝나면 자동으로 바뀌어요.',
    ANALYZING: '영상을 분석하고 있어요. 잠시만요.',
    TOO_LONG: '7분이 넘는 영상이라 분석하지 않았어요 (기록만 남아요).',
    FAILED: '분석에 여러 번 실패했어요.',
    REMOVED: '이 영상은 시스템에서 제거됐어요.',
};
/** 결과 시트의 상태 설명 — 완료된 요리는 추출 내용을 보여주므로 설명 불필요(null) */
const itemDetail = (item: RegistrationItem): string | null => {
    if (item.status !== 'DONE') return STATUS_DETAIL[item.status];
    if (item.category !== 'RECIPE') {
        return `요리가 아니라 ${CATEGORY_LABEL[item.category ?? 'ETC']}(으)로 분류했어요.`;
    }
    return null;
};
const TOO_LONG_NOTICE = '7분이 넘는 영상이라 분석하지 않아요 — 목록에 기록만 남겼어요';

/** 품질 경고가 붙은 항목의 배지·필터 이름 (2026-07-16 확정 — 사용자 제보: "레시피가 많아져서
    하나하나 눌러서 확인하기는 힘들어". 시트를 열어야만 보이던 경고를 목록까지 끌어올린 것).
    분류·상태 라벨과 겹치지 않는 이름이어야 한다 — 같은 필터 자리를 쓰므로 */
const REVIEW_LABEL = '확인 필요';

// 보관함 검색 (2026-07-16 5차 — CONTEXT.md "5차 확장" 2번). others 표시 개수 상한은 프론트 상수 하나가 원본.
const SEARCH_OTHERS_LIMIT = 10;
const SEARCH_DEBOUNCE_MS = 300; // 타이핑 중 매 글자 요청을 막는다 (디바운스)
const SEARCH_PLACEHOLDER = '보관함·기까 전체에서 검색 (요리 이름·태그)';
const SEARCH_MINE_LABEL = '내 보관함';
const SEARCH_OTHERS_LABEL = '이런 것도 있어요';
const SEARCH_MINE_EMPTY = '검색어와 맞는 내 영상이 없어요';
const SEARCH_OTHERS_EMPTY = '기까 전체에서도 더 찾지 못했어요';
const SEARCHING_TEXT = '검색 중…';
const ADD_TO_LIBRARY_TEXT = '내 보관함에 담기';
const EMPTY_SEARCH: SearchResults = { mine: [], others: [] };
// 검색 보완 항목의 시트 상단 줄 — 문구/데이터 분리(품질 기본선 7): 어순은 여기 한 곳에만
const otherMetaText = (category: VideoCategory | null) =>
    `${CATEGORY_LABEL[category ?? 'ETC']} · 아직 내 보관함에 없어요`;

// 재료 신고 (2026-07-18 — CONTEXT.md "재료 신고" 절): 일반 사용자 기능이지만 공개 전이라
// canReport(=canViewMonitor)로만 노출. 재료 칩 탭 → 확인 → 접수(즉시 응답), 처리는 서버 백그라운드.
const reportConfirmText = (name: string) =>
    `"${name}" 재료가 이상한가요? 영상을 다시 살펴보게 신고할까요?`;
const reportedChipText = (name: string) => `${name} · 신고됨`;
const reportChipLabel = (name: string) => `"${name}" 재료 신고`;
const REPORT_FAIL_TEXT = '신고하지 못했어요 — 네트워크 확인 후 다시 시도해 주세요';

// 카드 제목: AI가 추출한 요리 이름이 원본 영상 제목보다 "무슨 요리인지" 더 잘 드러남
// (2026-07-14 확정 — 이전엔 저장만 하고 목록엔 안 보여주고 있었음)
const itemTitle = (item: GikkaVideo): string => item.recipe?.name || item.title || item.url;
const cookMinutesText = (minutes: number) => `조리 약 ${minutes}분`;
const playlistAddedText = (count: number) => `재생목록에서 ${count}개를 대기열에 넣었어요`;
// 문구/데이터 분리 (품질 기본선 7): 조합 문구는 템플릿 한 곳에
// 필터로 선택된 배지는 체크 표시를 붙인다 — 색만으로 구분하지 않기 위한 보조 신호(색맹 대비)
const summaryBadgeText = (label: string, count: number, active: boolean) =>
    active ? `${label} ${count} ✓` : `${label} ${count}`;
const justAddedTagText = (label: string) => `${label} ✓`;
const resultMetaText = (item: RegistrationItem) =>
    `${itemLabel(item)} · ${formatRegisteredAt(item.registeredAt)}`;
// 실패 문구 결정 — useMutation 에 모듈 레벨로 넘긴다 (렌더마다 재생성 방지)
const mutationMessage = (e: unknown) =>
    e instanceof DuplicateVideoError ? DUPLICATE_TEXT : MUTATION_ERROR_TEXT;

const formatRegisteredAt = (iso: string) => {
    const d = new Date(iso);
    return `${d.getMonth() + 1}월 ${d.getDate()}일 등록`;
};

const hasActive = (items: RegistrationItem[]) =>
    items.some((i) => i.status === 'WAITING' || i.status === 'ANALYZING');

interface RecipesPageProps {
    /** 재료 신고 노출 조건 — 일반 기능의 공개 전 게이트(서버 판정 canViewMonitor 재사용) */
    canReport?: boolean;
}

export default function RecipesPage({ canReport = false }: RecipesPageProps) {
    const [registerOpen, setRegisterOpen] = useState(false);
    const [urlInput, setUrlInput] = useState('');
    const [justRegistered, setJustRegistered] = useState<string[]>([]); // 이번 시트에서 넣은 것 (확인줄)
    const [registerNotice, setRegisterNotice] = useState<string | null>(null); // 긴 영상 즉시 차단 안내 등
    const [selected, setSelected] = useState<RegistrationItem | null>(null); // 항목 탭 → 결과 시트
    // 요약 배지 탭 → 필터 (2026-07-14 확정). 지금은 전체 로드 + 클라이언트 필터로 충분
    // (개인용, 1000건도 부담 없는 규모) — 페이징 도입 시 서버 쿼리 파라미터 방식으로 교체 예정.
    const [activeFilter, setActiveFilter] = useState<string | null>(null);
    // 상세 시트의 재료별 있음/없음 표시용 (2026-07-14 확정) — 정확 매칭, 정규화 없음
    const [fridgeNames, setFridgeNames] = useState<Set<string> | null>(null);
    // 보관함 검색 (2026-07-16 5차). searchTerm=입력값, debouncedTerm=실제 조회에 쓰는 값(디바운스).
    // selectedOther=검색 보완(gikka 전체) 항목의 상세 시트 (내 것 시트 selected 와 분리 — 조작 버튼이 다름)
    const [searchTerm, setSearchTerm] = useState('');
    const [debouncedTerm, setDebouncedTerm] = useState('');
    const [selectedOther, setSelectedOther] = useState<GikkaVideo | null>(null);

    const load = useCallback(() => registrationRepository.list(), []);
    const query = useQuery<RegistrationItem[]>(load, loadMessage);
    const { data: items, setData: setItems, error: loadError, reload, refresh } = query;

    // 홈 섬네일 탭 → 해당 영상의 결과 시트 자동 열기 (2026-07-18 확정 — navigation state 로 전달).
    // 소진 후 state 를 비워 뒤로가기·폴링 재렌더에서 다시 열리지 않게 한다.
    const location = useLocation();
    const navigate = useNavigate();
    useEffect(() => {
        const openVideoId = (location.state as { openVideoId?: string } | null)?.openVideoId;
        if (!openVideoId || !items) return;
        const target = items.find((i) => i.videoId === openVideoId);
        if (target) setSelected(target);
        navigate(location.pathname, { replace: true, state: null });
    }, [items, location, navigate]);

    useEffect(() => {
        fridgeRepository.list().then((list) => setFridgeNames(new Set(list.map((i) => i.name)))).catch(() => undefined);
    }, []);

    // 재료 신고 (2026-07-18) — 열린 시트의 영상에 대해 내가 접수해 둔 재료를 조회해 버튼 상태로.
    // 조회 실패는 조용히 — 신고는 보조 기능이라 시트 표시를 막지 않는다.
    const reportMutation = useMutation(() => REPORT_FAIL_TEXT);
    const [reportedNames, setReportedNames] = useState<Set<string>>(new Set());
    const reportVideoId = selected?.videoId ?? selectedOther?.videoId ?? null;
    useEffect(() => {
        setReportedNames(new Set());
        if (!canReport || !reportVideoId) return;
        reportRepository.myActive(reportVideoId)
            .then((names) => setReportedNames(new Set(names)))
            .catch(() => undefined);
    }, [canReport, reportVideoId]);
    // 확인은 킷 다이얼로그(RcpConfirm)로 — 시스템 창 금지 (2026-07-19 확정)
    const [confirmReport, setConfirmReport] = useState<{ videoId: string; name: string } | null>(null);
    const handleReport = (videoId: string, name: string) => setConfirmReport({ videoId, name });
    const submitReport = () => {
        const target = confirmReport;
        if (target === null) return;
        setConfirmReport(null);
        // 낙관적 표시 (2026-07-19 냉장고와 같은 전환) — 확인 즉시 "신고됨", 실패 시에만 되돌림.
        // 'already' 응답도 접수돼 있는 상태라는 뜻이라 같은 표시로 수렴
        setReportedNames((prev) => new Set(prev).add(target.name));
        void reportMutation.run(async () => {
            try {
                await reportRepository.report(target.videoId, target.name);
            } catch (e) {
                setReportedNames((prev) => {
                    const next = new Set(prev);
                    next.delete(target.name);
                    return next;
                });
                throw e; // 실패 문구는 실행기가 표시
            }
        });
    };

    // 조작 실행기 (공용 useMutation — 실패 문구·연타 방지·재동기화 한 곳, PLAYBOOK 관례 5)
    const mutation = useMutation(mutationMessage, reload);

    // 검색어 디바운스 — 타이핑이 멈춘 뒤에만 실제 조회 (매 글자 요청 방지)
    useEffect(() => {
        const timer = window.setTimeout(() => setDebouncedTerm(searchTerm.trim()), SEARCH_DEBOUNCE_MS);
        return () => window.clearTimeout(timer);
    }, [searchTerm]);

    // 검색 조회 — 공용 useQuery (3상태 손으로 만들지 않음, AGENTS "가져다 쓸 것"). 검색어가 비면
    // 서버를 안 부르고 빈 결과. debouncedTerm 이 바뀔 때마다 load 정체성이 바뀌어 자동 재조회된다.
    const searchLoad = useCallback(
        () => (debouncedTerm ? registrationRepository.search(debouncedTerm, SEARCH_OTHERS_LIMIT)
            : Promise.resolve(EMPTY_SEARCH)),
        [debouncedTerm],
    );
    const search = useQuery<SearchResults>(searchLoad, loadMessage);
    const searching = debouncedTerm.length > 0;
    const searchPending = searchTerm.trim() !== debouncedTerm; // 디바운스 대기 중(입력 반영 전)
    const searchData = search.data ?? EMPTY_SEARCH;

    // 진행 중 항목이 있는 동안만 폴링 (분석이 다 끝나면 조용해짐).
    // 의존성은 "폴링을 켤지"의 판정(active)뿐 — items 를 그대로 넣으면 응답이 올 때마다
    // 인터벌이 재생성돼 주기가 "응답 후 2.5초"로 밀린다 (2026-07-15 수정한 실제 버그).
    const active = !!items && hasActive(items);
    useEffect(() => {
        if (!active) return undefined;
        const timer = window.setInterval(() => {
            void refresh().catch(() => undefined); // 폴링 실패는 조용히 — 다음 턴에 다시
        }, POLL_MS);
        return () => window.clearInterval(timer);
    }, [active, refresh]);

    const handleRegister = (rawUrl: string) => {
        const url = rawUrl.trim();
        if (!url) return;
        setRegisterNotice(null);
        void mutation.run(async () => {
            // 영상 우선 규칙은 registerLink 공용 모듈이 판정 (홈·공유 수신과 단일 원본)
            const outcome = await registerLink(url);
            if (outcome.kind === 'invalid') {
                mutation.setError(INVALID_URL_TEXT);
                return;
            }
            if (outcome.kind === 'duplicate') {
                mutation.setError(DUPLICATE_TEXT); // 이 화면에서는 안내 문구로 (홈·공유는 정상 통과)
                return;
            }
            if (outcome.kind === 'unavailable') {
                mutation.setError(UNAVAILABLE_TEXT);
                return;
            }
            setUrlInput(''); // 성공했을 때만 비움 (실패 시 입력 유지 — 재시도 배려)
            if (outcome.kind === 'playlist') {
                setJustRegistered((prev) => [...prev, playlistAddedText(outcome.added)]);
                setRegisterOpen(false); // 등록 성공하면 팝업도 닫는다 (2026-07-14 확정)
            } else {
                setJustRegistered((prev) => [...prev, '영상 1개']);
                // 길이 컷은 등록 순간 바로 알림 (2026-07-12 확정 — 기록은 남되 막힌 걸 즉시 알게).
                // 이 경우엔 팝업을 닫지 않고 경고를 보여준다 — 자동 닫힘과 즉시 안내가 충돌하는
                // 유일한 경로 (2026-07-14 확정).
                if (outcome.item.status === 'TOO_LONG') {
                    setRegisterNotice(TOO_LONG_NOTICE);
                } else {
                    setRegisterOpen(false);
                }
            }
            await reload();
        });
    };

    /** 붙여넣기 = 바로 등록 (홈 최종 UX "1탭" 지향). 붙여넣기 버튼과 입력창 직접 붙여넣기의 단일 원본 —
        어느 쪽으로 들어와도 입력창엔 붙여넣은 링크 전체가 남고(실패 시 재시도 배려) 즉시 분석이 시작된다 */
    const pasteAndRegister = (text: string) => {
        setUrlInput(text);
        handleRegister(text);
    };

    // 클립보드 읽기는 https 에서만 존재 — 없으면 버튼 자체를 숨김 (품질 기본선 6: 비보안 컨텍스트 폴백)
    const canPaste = clipboardReadSupported();
    const handlePaste = async () => {
        try {
            pasteAndRegister(await readClipboardText());
        } catch {
            mutation.setError(PASTE_FAIL_TEXT);
        }
    };

    // 내 목록에서 지우기 — 내 연결만 삭제 (다른 사용자·video 자체는 무관, 확인 팝업 없이 즉시 —
    // 냉장고 삭제와 동일한 개인 데이터 관리 패턴, 2026-07-14 확정)
    const handleUnregister = (item: RegistrationItem) => mutation.run(async () => {
        await registrationRepository.unregister(item.videoId);
        setSelected(null);
        // 낙관적 업데이트 (2026-07-14 확정): 성공을 이미 아니까 전체 재조회 대신 로컬에서
        // 바로 제거 — 재조회 왕복이 없어져 삭제 직후 체감 지연이 사라진다.
        // 실패 시엔 이 줄까지 오지 않고 useMutation 의 자동 재동기화(reload)가 서버 상태로
        // 복구한다 (삭제 안 된 항목이 다시 나타남 — 별도 복구 로직 불필요).
        setItems((prev) => prev?.filter((i) => i.videoId !== item.videoId) ?? prev);
    });

    const openRegisterSheet = () => {
        setJustRegistered([]);
        mutation.setError(null);
        setRegisterNotice(null);
        setRegisterOpen(true);
    };

    // 검색 보완 항목을 내 보관함에 담기 — video_id 로 연결만(재분석 없음). 성공하면 others→mine 으로
    // 옮겨가야 하므로 검색·메인 목록을 함께 재조회 (mutation 실패 시 자동 재동기화는 메인 목록 담당)
    const handleRegisterOther = (video: GikkaVideo) => mutation.run(async () => {
        await registrationRepository.registerByVideoId(video.videoId);
        setSelectedOther(null);
        await search.reload();
        await reload();
    });

    // DONE 결과 상세(요약·재료·조리순서·태그·원본 링크) — 내 것 시트와 검색 보완 시트가 공유.
    // registeredAt 을 안 보므로 GikkaVideo 로 받는다(RegistrationItem 도 그대로 넘어감). status 가
    // DONE 이 아니면(분석 전 내 항목) 내용 없이 원본 링크만 — 시트별 상태 설명은 각자 담당.
    const renderDoneDetail = (item: GikkaVideo) => (
        <>
            {item.status === 'DONE' && item.summary && (
                <>
                    <h3 className="rcp-section-label">요점 요약</h3>
                    <p className="rcp-sheet-detail">{item.summary}</p>
                </>
            )}
            {item.status === 'DONE' && item.recipe && (
                <>
                    <h3 className="rcp-section-label">재료 (영상에 나온 그대로)</h3>
                    <div className="rcp-chip-group">
                        {item.recipe.ingredients.map((name) => {
                            const haveClass = fridgeNames === null ? 'rcp-sticker'
                                : `rcp-chip ${fridgeNames.has(name) ? 'rcp-chip-on' : 'rcp-chip-off'}`;
                            if (!canReport) {
                                return <span key={name} className={haveClass}>{name}</span>;
                            }
                            // 재료 신고 (2026-07-18) — 이상한 재료를 탭해 접수. 접수돼 있으면 비활성
                            const reported = reportedNames.has(name);
                            return (
                                <button
                                    key={name}
                                    type="button"
                                    className={haveClass}
                                    disabled={reported}
                                    aria-label={reportChipLabel(name)}
                                    onClick={() => handleReport(item.videoId, name)}
                                >
                                    {reported ? reportedChipText(name) : name}
                                </button>
                            );
                        })}
                    </div>
                    {item.recipe.cookMinutes !== null && (
                        <p className="rcp-sheet-meta">{cookMinutesText(item.recipe.cookMinutes)}</p>
                    )}
                    <h3 className="rcp-section-label">조리 순서 요약</h3>
                    <ol className="rcp-step-list">
                        {item.recipe.steps.map((step) => (
                            <li key={step}>{step}</li>
                        ))}
                    </ol>
                </>
            )}
            {item.status === 'DONE' && item.tags && item.tags.length > 0 && (
                <>
                    <h3 className="rcp-section-label">검색 태그</h3>
                    <div className="rcp-chip-group">
                        {item.tags.map((tag) => (
                            <span key={tag} className="rcp-sticker">{tag}</span>
                        ))}
                    </div>
                </>
            )}
            <a className="rcp-btn rcp-btn-ghost" href={item.url} target="_blank" rel="noreferrer">
                원본 영상 보기
            </a>
        </>
    );

    // 내 항목 행 — 일반 목록과 검색 "내 보관함" 결과가 공유 (품질 경고 배지 + 분류 배지 + 등록일)
    const renderMineRow = (item: RegistrationItem) => (
        <RcpVideoRow
            key={item.videoId}
            title={itemTitle(item)}
            thumbnailUrl={item.thumbnailUrl}
            badge={(
                <>
                    {needsReview(item) && <RcpBadge variant="warning">{REVIEW_LABEL}</RcpBadge>}
                    <RcpBadge variant={itemBadge(item)}>{itemLabel(item)}</RcpBadge>
                </>
            )}
            meta={formatRegisteredAt(item.registeredAt)}
            onClick={() => setSelected(item)}
        />
    );

    // 검색 보완 행 — gikka 전체(등록 무관) 완료 영상. 분류 배지만, 등록일 없음(내 것이 아님)
    const renderOtherRow = (video: GikkaVideo) => (
        <RcpVideoRow
            key={video.videoId}
            title={itemTitle(video)}
            thumbnailUrl={video.thumbnailUrl}
            badge={<RcpBadge variant={itemBadge(video)}>{itemLabel(video)}</RcpBadge>}
            onClick={() => setSelectedOther(video)}
        />
    );

    const errorLine = <RcpInlineError message={mutation.error ?? reportMutation.error} />;

    // 요약줄: 표시 문구(레시피·유틸·기타·분석대기…) 기준으로 집계, 0인 것은 숨김
    const summary = items
        ? items.reduce<{ label: string; variant: RcpBadgeVariant; count: number }[]>((acc, item) => {
            const label = itemLabel(item);
            const found = acc.find((s) => s.label === label);
            if (found) found.count += 1;
            else acc.push({ label, variant: itemBadge(item), count: 1 });
            return acc;
        }, [])
        : [];
    // "확인 필요"는 분류·상태와 다른 축이라(레시피이면서 동시에 확인 필요일 수 있음) 따로 센다.
    // 맨 앞에 둔다 — 사용자가 가장 먼저 처리해야 할 것이므로
    const reviewCount = items?.filter(needsReview).length ?? 0;
    if (reviewCount > 0) summary.unshift({ label: REVIEW_LABEL, variant: 'warning', count: reviewCount });
    // 요약 배지 필터 적용 — summary 자체는 항상 전체 items 기준으로 집계되므로(위),
    // 필터가 걸려도 활성 배지는 그대로 남아 다시 탭하면 해제할 수 있다
    const matchesFilter = (item: RegistrationItem, filter: string) =>
        filter === REVIEW_LABEL ? needsReview(item) : itemLabel(item) === filter;
    const displayedItems = (items && activeFilter) ? items.filter((item) => matchesFilter(item, activeFilter)) : items ?? [];

    return (
        <main className="rcp-screen rcp-screen-with-fab" id="rcp-recipes-page">
            <header className="rcp-screen-header">
                <h1 className="rcp-screen-title">보관함</h1>
                <p className="rcp-screen-subtitle">쇼츠 링크를 넣으면 재료와 조리법을 꺼내드려요</p>
            </header>
            {errorLine}

            <RcpLoadError message={loadError} onRetry={() => void reload()} />
            {!loadError && items === null && <p className="rcp-empty">불러오는 중…</p>}

            {items !== null && (
                <>
                    {/* 검색바 (2026-07-16 5차) — 등록 폼과 같은 rcp-input-row 구조 재사용(새 CSS 없음).
                        검색어가 비면 아래는 기존 대기열/목록 그대로, 있으면 검색 결과로 전환 */}
                    <div className="rcp-input-row" id="rcp-search">
                        <input
                            className="rcp-input"
                            id="rcp-search-input"
                            type="search"
                            inputMode="search"
                            placeholder={SEARCH_PLACEHOLDER}
                            value={searchTerm}
                            onChange={(e) => setSearchTerm(e.target.value)}
                            aria-label="보관함 검색"
                        />
                        {searchTerm && (
                            <RcpButton variant="ghost" onClick={() => setSearchTerm('')}>지우기</RcpButton>
                        )}
                    </div>

                    {searching ? (
                        <section id="rcp-search-results" aria-label="검색 결과">
                            <RcpLoadError message={search.error} onRetry={() => void search.reload()} />
                            {searchPending && !search.error && <p className="rcp-empty">{SEARCHING_TEXT}</p>}
                            {!searchPending && !search.error && (
                                <>
                                    <h2 className="rcp-section-label">{SEARCH_MINE_LABEL}</h2>
                                    {searchData.mine.length === 0
                                        ? <p className="rcp-empty">{SEARCH_MINE_EMPTY}</p>
                                        : searchData.mine.map(renderMineRow)}
                                    {/* 결과 부족 여부와 무관하게 항상 보완 섹션 자리를 지킨다
                                        (임계값 없이 — CONTEXT.md "5차 확장" 2번, 임의 기준 금지) */}
                                    <h2 className="rcp-section-label">{SEARCH_OTHERS_LABEL}</h2>
                                    {searchData.others.length === 0
                                        ? <p className="rcp-empty">{SEARCH_OTHERS_EMPTY}</p>
                                        : searchData.others.map(renderOtherRow)}
                                </>
                            )}
                        </section>
                    ) : (
                        <>
                            {summary.length > 0 && (
                                <div className="rcp-summary-row" id="rcp-queue-summary" aria-label="분석 진행 요약 · 필터">
                                    {summary.map(({ label, variant, count }) => (
                                        <button
                                            key={label}
                                            type="button"
                                            className="rcp-summary-badge-btn"
                                            aria-pressed={activeFilter === label}
                                            onClick={() => setActiveFilter((prev) => (prev === label ? null : label))}
                                        >
                                            <RcpBadge variant={variant}>
                                                {summaryBadgeText(label, count, activeFilter === label)}
                                            </RcpBadge>
                                        </button>
                                    ))}
                                </div>
                            )}

                            <section id="rcp-queue-list" aria-label="분석 대기열">
                                {displayedItems.length === 0 && (
                                    <p className="rcp-empty">
                                        {items.length === 0
                                            ? '아직 등록한 영상이 없어요. 오른쪽 아래 [+ 영상 등록]으로 시작해 보세요.'
                                            : '이 필터에 해당하는 항목이 없어요.'}
                                    </p>
                                )}
                                {displayedItems.map(renderMineRow)}
                            </section>
                        </>
                    )}

                    {/* 목록 끝이 아니라 떠 있는 자리 — 영상이 몇 개든 위치가 안 변한다
                        (2026-07-16 확정: 목록이 길어질수록 등록 버튼을 못 찾겠다는 실사용 제보) */}
                    <RcpFab id="rcp-register-button" onClick={openRegisterSheet}>
                        + 영상 등록
                    </RcpFab>
                </>
            )}

            <RcpBottomSheet open={registerOpen} title="영상 등록" onClose={() => setRegisterOpen(false)}>
                {errorLine}
                <div className="rcp-just-added" id="rcp-just-registered">
                    {justRegistered.length === 0
                        ? <span className="rcp-just-added-hint">방금 등록한 영상이 여기 표시돼요</span>
                        : justRegistered.map((label, i) => (
                            // eslint-disable-next-line react/no-array-index-key
                            <span key={`${label}-${i}`} className="rcp-just-added-tag">{justAddedTagText(label)}</span>
                        ))}
                </div>
                {registerNotice && (
                    <p className="rcp-sheet-detail" id="rcp-register-notice" role="status">{registerNotice}</p>
                )}
                {canPaste && (
                    <RcpButton id="rcp-register-paste" onClick={() => void handlePaste()}>
                        복사한 링크 붙여넣기
                    </RcpButton>
                )}
                <form
                    id="rcp-register-form"
                    className="rcp-input-row"
                    onSubmit={(e) => {
                        e.preventDefault();
                        handleRegister(urlInput);
                    }}
                >
                    <input
                        className="rcp-input"
                        id="rcp-register-input"
                        placeholder="유튜브 링크 (영상·쇼츠·재생목록)"
                        value={urlInput}
                        onChange={(e) => setUrlInput(e.target.value)}
                        // 붙여넣는 순간 바로 분석 (2026-07-16 확정) — 앱이 클립보드를 몰래 읽는 게 아니라
                        // 사용자가 붙여넣은 것을 브라우저가 알려주는 이벤트라, 읽기 API 가 없는
                        // http 개발 서버(붙여넣기 버튼이 사라지는 그 환경)에서도 동작한다.
                        // 붙여넣은 링크로 입력창 전체를 갈아끼운다 — 버튼 경로와 같은 규칙.
                        // (직접 타이핑·부분 수정은 [등록] 버튼이 그대로 담당)
                        onPaste={(e) => {
                            const text = e.clipboardData.getData('text');
                            if (!text.trim()) return;
                            e.preventDefault();
                            pasteAndRegister(text);
                        }}
                        inputMode="url"
                    />
                    <RcpButton type="submit" disabled={!urlInput.trim()}>등록</RcpButton>
                </form>
                {!parseYoutubeVideoId(urlInput) && parseYoutubePlaylistId(urlInput) && (
                    <p className="rcp-sheet-meta" id="rcp-register-playlist-hint">
                        재생목록 링크예요 — 안의 영상 전체를 대기열에 넣어요
                    </p>
                )}
            </RcpBottomSheet>

            <RcpBottomSheet
                open={selected !== null}
                title={selected?.recipe?.name ?? selected?.title ?? '분석 결과'}
                onClose={() => setSelected(null)}
            >
                {selected && (
                    <>
                        {errorLine}
                        <p className="rcp-sheet-meta">{resultMetaText(selected)}</p>

                        {analysisQualityWarning(selected) && (
                            <p className="rcp-quality-warning">{analysisQualityWarning(selected)}</p>
                        )}

                        {itemDetail(selected) && (
                            <p className="rcp-sheet-detail">{itemDetail(selected)}</p>
                        )}

                        {/* 요약·재료·조리순서·태그·원본 링크는 검색 보완 시트와 공유 (renderDoneDetail) */}
                        {renderDoneDetail(selected)}

                        <RcpButton variant="ghost" onClick={() => void handleUnregister(selected)}>
                            내 목록에서 지우기
                        </RcpButton>
                    </>
                )}
            </RcpBottomSheet>

            {/* 검색 보완(gikka 전체) 항목 상세 — 내 것이 아니므로 등록일 대신 분류 줄, 조작은 "담기"
                (2026-07-16 5차). 상세 본문은 내 것 시트와 같은 renderDoneDetail 을 공유 */}
            <RcpBottomSheet
                open={selectedOther !== null}
                title={selectedOther?.recipe?.name ?? selectedOther?.title ?? '분석 결과'}
                onClose={() => setSelectedOther(null)}
            >
                {selectedOther && (
                    <>
                        {errorLine}
                        <p className="rcp-sheet-meta">{otherMetaText(selectedOther.category)}</p>

                        {analysisQualityWarning(selectedOther) && (
                            <p className="rcp-quality-warning">{analysisQualityWarning(selectedOther)}</p>
                        )}

                        {renderDoneDetail(selectedOther)}

                        <RcpButton onClick={() => void handleRegisterOther(selectedOther)}>
                            {ADD_TO_LIBRARY_TEXT}
                        </RcpButton>
                    </>
                )}
            </RcpBottomSheet>

            {/* 재료 신고 확인 — 결과 시트(z 50) 위에 뜬다 (RcpConfirm z 60) */}
            <RcpConfirm
                open={confirmReport !== null}
                message={confirmReport !== null ? reportConfirmText(confirmReport.name) : ''}
                confirmLabel="신고"
                onConfirm={submitReport}
                onCancel={() => setConfirmReport(null)}
            />
        </main>
    );
}
