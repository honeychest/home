# 도메인 문서 일괄 생성 런북

`gen-domain-doc.ps1` 로 백엔드/프론트 각 영역의 개발자 문서를 한 번에 생성한다.
생성물은 `docs/generated/` 에 모인다. **생성 로직에 Claude 는 개입하지 않는다**(로컬 LLM 전용).

## 전제
- Mac mini LM Studio 가 켜져 있고 `gemma-4-26b-a4b-it-mlx` 모델이 로드 + 2345 포트 접근 가능.
- **저장소 루트에서** 실행한다(상대경로 기준).
- GitNexus 는 필요 없다. 이 파이프라인은 소스 파일을 직접 읽으므로 `analyze` 선행이 불필요하다.

## 출력 파일명 규칙(이름 충돌 방지)
`binance` 처럼 여러 영역에 같은 이름이 있으므로 접두어를 붙인다.
- 백엔드:        `be-<name>.md`
- 프론트 도메인:  `fe-domain-<name>.md`
- 프론트 페이지:  `fe-page-<name>.md`
- 프론트 교차영역: `fe-<area>.md`

---

## 1) 백엔드 도메인 (5개)

```powershell
$be = @(
  @{ Root="springboot/src/main/java/com/chs/springboot/domain/analysis"; Title="Analysis 도메인" },
  @{ Root="springboot/src/main/java/com/chs/springboot/domain/binance";  Title="Binance 수집/집계" },
  @{ Root="springboot/src/main/java/com/chs/springboot/domain/chatbot";  Title="Chatbot (코드베이스 RAG)" },
  @{ Root="springboot/src/main/java/com/chs/springboot/domain/upbit";    Title="Upbit 도메인" },
  @{ Root="springboot/src/main/java/com/chs/springboot/domain/weather";  Title="Weather 도메인" }
)
foreach ($d in $be) {
  $name = Split-Path $d.Root -Leaf
  pwsh docs/scripts/gen-domain-doc.ps1 -DomainRoot $d.Root `
    -OutFile "docs/generated/be-$name.md" -Title $d.Title
}
```

## 2) 프론트 도메인 (feature-sliced: frontend/src/domain/*)

```powershell
$feDomain = @("binance","logistics","support","weather")
foreach ($n in $feDomain) {
  pwsh docs/scripts/gen-domain-doc.ps1 `
    -DomainRoot "frontend/src/domain/$n" `
    -OutFile "docs/generated/fe-domain-$n.md" -Title "프론트 도메인: $n"
}
```

## 3) 프론트 페이지 (frontend/src/page/*)

> 페이지는 `components/api/store/shared` 등 외부 모듈을 import 하므로, 폴더 안 소스만으로는
> 일부 맥락이 빠질 수 있다(문서에 '(코드상 미상)' 으로 표기됨). 교차영역(아래 4번)도 같이 생성하면 보완된다.
> `forbidden`(1) `weather`(1) `error`(3) 처럼 파일이 매우 적은 페이지는 생략해도 무방하다.

```powershell
$fePage = @("admin","analysis","binance","logistics","monitor","random","signal","trade")
foreach ($n in $fePage) {
  pwsh docs/scripts/gen-domain-doc.ps1 `
    -DomainRoot "frontend/src/page/$n" `
    -OutFile "docs/generated/fe-page-$n.md" -Title "프론트 페이지: $n"
}
```

## 4) 프론트 교차영역 (shared/api/store/components)

```powershell
$feArea = @("shared","api","store","components")
foreach ($n in $feArea) {
  pwsh docs/scripts/gen-domain-doc.ps1 `
    -DomainRoot "frontend/src/$n" `
    -OutFile "docs/generated/fe-$n.md" -Title "프론트 교차영역: $n"
}
```

---

## 주의사항
- **큰 폴더 주의**: `frontend/src/page/logistics`(약 69파일), `frontend/src/domain`(43파일) 등은
  전체 소스를 매 패스마다 모델에 넣으므로 느리거나 컨텍스트 한도에 걸릴 수 있다. 실패/품질저하 시
  그 폴더를 하위 단위로 쪼개 `-DomainRoot` 를 더 좁게 지정해 돌린다.
- **품질 등급**: 산출물은 '검증 전 초안'이다. 흐름/경로는 강화된 자가검증으로 대체로 정확하지만,
  오타 등 미관 결함은 남을 수 있다.
- **느린 생성**: 로컬 26B 모델이라 도메인당 수 분이 걸릴 수 있다. `-TimeoutSec`(기본 600) 안에서 동작한다.

## 생성 후
1. `docs/generated/` 에 산출물이 모인다.
2. 코드와 대조 검증이 필요하면 Claude 에게 "generated 폴더를 검증해줘" 라고 **따로** 요청한다(일회성).
3. 검증·확정 후, 이 문서들을 챗봇이 참조하도록 '적용'하는 작업은 `docs/HANDOFF-apply-docs.md` 참고.
