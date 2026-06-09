# 도메인 문서 자동 생성 (로컬 LLM)

`gen-domain-doc.ps1` 은 한 도메인의 소스를 읽어 로컬 LLM(LM Studio)으로 개발자 문서(.md)를 생성한다.
**이 파이프라인에는 Claude 가 개입하지 않는다.** 근거 수집(결정적) + 로컬 LLM 3패스(목차→섹션→자가검증)로 자기완결한다.

## 파이프라인

```
Pass 0  근거 수집     도메인 파일들을 읽어 "FILE: 경로 + 소스" 근거 묶음 생성 (LLM 없음)
Pass 1  목차 생성     로컬 LLM 이 책임/흐름 단위 섹션 목차 제안
Pass 2  섹션 채우기   섹션마다 본문 생성(출력 분할로 약한 모델 부담↓)
Pass 3  자가검증      로컬 LLM 이 초안을 소스와 대조해 교정한 완성본 출력
→ 결과 .md 저장
```

## 실행

```powershell
pwsh docs/scripts/gen-domain-doc.ps1 `
  -DomainRoot springboot/src/main/java/com/chs/springboot/domain/chatbot `
  -OutFile    docs/generated/chatbot.md `
  -Title      "Chatbot (코드베이스 RAG)"
```

## 주요 옵션

| 옵션 | 기본값 | 설명 |
| --- | --- | --- |
| `-DomainRoot` | (필수) | 문서화할 도메인 소스 루트 |
| `-OutFile` | (필수) | 출력 .md 경로 |
| `-Title` | 폴더명 | 문서 제목 |
| `-BaseUrl` | `http://100.69.229.3:2345` | LM Studio OpenAI 호환 base-url |
| `-Model` | `gemma-4-26b-a4b-it-mlx` | 사용할 로컬 모델 |
| `-Ext` | `.java .jsx .js .ts .tsx` | 포함 확장자 |
| `-TimeoutSec` | `600` | 호출당 타임아웃(로컬 모델은 느림) |
| `-Temperature` | `0.2` | 낮게 둬 사실성 우선 |

## 여러 도메인 일괄 실행 예

```powershell
$domains = @(
  @{ Root = "springboot/src/main/java/com/chs/springboot/domain/chatbot"; Title = "Chatbot (RAG)" },
  @{ Root = "springboot/src/main/java/com/chs/springboot/domain/binance"; Title = "Binance 수집" }
)
foreach ($d in $domains) {
  $name = Split-Path $d.Root -Leaf
  pwsh docs/scripts/gen-domain-doc.ps1 -DomainRoot $d.Root `
    -OutFile "docs/generated/$name.md" -Title $d.Title
}
```

## 검증(별도 단계)

생성물은 `docs/generated/` 에 모인다. 모든 도메인을 다 돌린 뒤,
필요하면 Claude 에게 "generated 폴더를 코드와 대조 검증해줘" 라고 따로 요청한다.
(검증은 파이프라인 밖의 일회성 작업이며, 생성 로직에는 포함되지 않는다.)

## 전제

- LM Studio 에 `-Model` 이 로드되어 있고 `-BaseUrl` 로 접근 가능해야 한다.
- 저장소 루트에서 실행한다(상대경로 기준).
```
