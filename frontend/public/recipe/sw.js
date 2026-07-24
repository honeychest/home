// [AGENT] recipe(기까) 최소 서비스 워커 — PWA 설치 요건 충족용 (2026-07-24 확정)
// 캐싱 전략 없음 — 순수 통과(pass-through)만 한다. 목적은 캐싱이 아니라 "서비스 워커가
// 있어야 크롬이 홈 화면 추가를 완전한 standalone WebAPK로 만들어준다"는 설치 요건 충족.
// 서비스 워커가 없으면 약식 바로가기로 설치되어 상태바 아래 얇은 구분선이 안 사라지는
// 문제가 안드로이드 실사용에서 확인됨 — 그 대응.
self.addEventListener('install', () => {
    self.skipWaiting();
});

self.addEventListener('activate', (event) => {
    event.waitUntil(self.clients.claim());
});

self.addEventListener('fetch', (event) => {
    event.respondWith(fetch(event.request));
});
