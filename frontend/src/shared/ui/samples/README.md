# Visual Effect Samples

공용 UI 효과 샘플 원장입니다. 다른 페이지에서 효과를 요청할 때는 sample key를 그대로 말합니다.
샘플 CSS는 색상·크기·최종 도형을 확정하지 않고, animation/effect hook만 제공합니다.
적용 대상 페이지가 `currentColor`, 크기, border/background를 정합니다.

열람: admin > "UI 샘플" 카드 → 그룹별 조밀 타일, 타일 탭 → 하단에 의도·사용 코드.

그룹 (visualSamples.js 의 `group` 필드): 진행·대기 / 강조·알림 / 등장 / 흐름 / 연출
- oneShot: true = 유한 재생(실사용 1~2회, 클래스 부착 시 재생) — 카탈로그에서만 무한 반복.

## 조합 규칙 — 원자 효과만 등록하고, 조합은 클래스를 나란히 붙여서 만든다
프리셋(조합 완성품)은 등록하지 않는다. "이거 + 이거 합쳐줘" 가 되려면 채널이 겹치지 않아야 한다:
- **transform 채널** (wiggle·pop·bounce 계열·등장/연출 전부): 동시에 **1개만** — 겹치면 마지막 것만 적용됨.
- **배경 채널** (flash): transform·가상요소와 자유 조합.
- **::before 채널** (ring_sweep·border_comet): ::after 계열과 조합 가능, ::before 끼리는 불가.
- **::after 채널** (shine·shockwave·scan_beam): ::before·배경·transform 과 조합 가능, ::after 끼리는 불가.
- 예: `sample_throw_in sample_shockwave` (transform + ::after) ✓ /
  `sample_wiggle sample_flash sample_border_comet` (transform + 배경 + ::before) ✓ /
  `sample_shine sample_shockwave` (::after 충돌) ✗

예:
- `sample_live_spinner 를 logistics-route-node 진행중 dot에 적용`
- `sample_status_pulse 를 분석 중 배지에 적용`
- `sample_sticker_arrive + sample_shockwave 를 새 항목 카드에 적용`

사용:
```jsx
<span className="sample_live_spinner" aria-hidden="true" />
```

적립 규칙 (frontend/AGENTS.md): 새 효과를 만들면 커밋 전에 이 카탈로그에 등록한다.
등록 = visualSamples.js 에 항목 1개(key·label·intent·group·shape·example) +
visualSamples.css 에 클래스·keyframes. 화면 CSS 에 일회성 keyframes 를 만들면 결함.

샘플 목록은 `visualSamples.js`, 실제 CSS는 `visualSamples.css`에 있습니다.
