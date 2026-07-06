# 도메인 소스 → 로컬 LLM(LM Studio) 으로 개발자 문서(.md) 자동 생성 파이프라인.
#
# 설계 원칙:
#   - 이 파이프라인에는 Claude 가 개입하지 않는다. 근거 수집(결정적) + 로컬 LLM 으로 자기완결.
#   - 약한 로컬 모델을 보완하려 (1)실제 소스를 근거로 직접 주입 (2)목차→섹션 분할 생성
#     (3)자가검증 1패스 로 구성한다.
#   - 큰 도메인은 한 번에 다 넣으면 모델이 일부 파일을 누락하거나(품질저하) 타임아웃난다.
#     그래서 ChunkChars 를 넘는 도메인은 '맵리듀스'로 처리한다:
#       Map    : 소스를 ChunkChars 단위 청크로 나눠 청크별 '구조 요약'을 만든다.
#                각 요약은 .cache 에 저장되어, 재실행 시 이미 있으면 건너뛴다(체크포인트).
#       Reduce : 청크 요약들 + 파일 경로 인덱스만 모아(작음) 목차→본문→자가검증을 돈다.
#     ChunkChars 이하의 작은 도메인은 종전처럼 소스를 통짜로 주입한다(요약 손실 없음).
#
# 사용 예:
#   pwsh docs/scripts/gen-domain-doc.ps1 `
#     -DomainRoot springboot/src/main/java/com/chs/springboot/domain/chatbot `
#     -OutFile docs/generated/chatbot.md -Title "Chatbot (코드베이스 RAG)"
#
# 재실행: 청크 요약 캐시가 있으면 Map 을 건너뛰고 Reduce 만 다시 돈다.
# 전체 재생성: -Force (캐시 무시).

param(
    [Parameter(Mandatory = $true)] [string]$DomainRoot,
    [Parameter(Mandatory = $true)] [string]$OutFile,
    [string]$Title = "",
    [string]$BaseUrl = "http://100.69.229.3:2345",
    [string]$Model = "Mac-mini-LLM",
    [string[]]$Ext = @(".java", ".jsx", ".js", ".ts", ".tsx"),
    [int]$TimeoutSec = 600,
    [double]$Temperature = 0.2,
    [int]$ChunkChars = 120000,
    [string]$CacheDir = "docs/.cache",
    [switch]$Force
)

$ErrorActionPreference = "Stop"

if (-not (Test-Path $DomainRoot)) {
    throw "DomainRoot 경로를 찾을 수 없습니다: $DomainRoot"
}
if ([string]::IsNullOrWhiteSpace($Title)) {
    $Title = Split-Path $DomainRoot -Leaf
}

# ── Pass 0: 근거 수집 (LLM 없음, 결정적) ──────────────────────────────
Write-Host "[Pass 0] 근거 수집: $DomainRoot"
$files = Get-ChildItem -Path $DomainRoot -Recurse -File |
    Where-Object { $Ext -contains $_.Extension } |
    Sort-Object FullName

if ($files.Count -eq 0) {
    throw "대상 파일이 없습니다(확장자 $($Ext -join ',')): $DomainRoot"
}

$repoRoot = (Get-Location).Path

# 파일별 블록 + 전체 경로 인덱스
$blocks = @()       # 각 원소: @{ Rel=...; Text="===== FILE: rel =====`n내용`n" }
$pathIndex = @()    # 전체 파일 경로 목록(흐름/경로 검증용)
foreach ($f in $files) {
    $rel = $f.FullName.Substring($repoRoot.Length).TrimStart('\', '/').Replace('\', '/')
    $content = Get-Content -Raw -Encoding UTF8 $f.FullName
    $blocks += @{ Rel = $rel; Text = "===== FILE: $rel =====`n$content`n" }
    $pathIndex += $rel
}
$totalChars = ($blocks | ForEach-Object { $_.Text.Length } | Measure-Object -Sum).Sum
Write-Host ("[Pass 0] 파일 {0}개, 근거 {1}자, 임계값 {2}자" -f $files.Count, $totalChars, $ChunkChars)

$pathIndexText = "다음은 이 도메인에 실제로 존재하는 전체 파일 경로 목록이다(이 목록 밖의 경로는 쓰지 마라):`n" +
    (($pathIndex | ForEach-Object { "- $_" }) -join "`n")

# ── 청크 분할 (누적 ChunkChars 기준) ─────────────────────────────────
$chunks = @()
$curSb = New-Object System.Text.StringBuilder
$curLen = 0
foreach ($b in $blocks) {
    if ($curLen -gt 0 -and ($curLen + $b.Text.Length) -gt $ChunkChars) {
        $chunks += $curSb.ToString()
        $curSb = New-Object System.Text.StringBuilder
        $curLen = 0
    }
    [void]$curSb.Append($b.Text)
    $curLen += $b.Text.Length
}
if ($curLen -gt 0) { $chunks += $curSb.ToString() }
Write-Host ("[청크] {0}개로 분할" -f $chunks.Count)

# ── LLM 호출 헬퍼 ────────────────────────────────────────────────────
function Invoke-LLM {
    param([string]$System, [string]$User)
    $body = @{
        model       = $Model
        temperature = $Temperature
        messages    = @(
            @{ role = "system"; content = $System },
            @{ role = "user"; content = $User }
        )
    } | ConvertTo-Json -Depth 8
    $headers = @{ "Authorization" = "Bearer lm-studio" }
    $resp = Invoke-RestMethod -Uri "$BaseUrl/v1/chat/completions" -Method Post `
        -Body $body -ContentType "application/json; charset=utf-8" `
        -Headers $headers -TimeoutSec $TimeoutSec
    return $resp.choices[0].message.content
}

$groundRule = @"
너는 시니어 개발자다. 아래에 제공된 '실제 소스 코드' 또는 '소스 구조 요약'에만 근거해 한국어 개발자 문서를 작성한다.
규칙:
- 제공된 근거에 없는 내용은 절대 추측해 쓰지 않는다. 모르면 '(코드상 미상)' 으로 표기한다.
- 파일 경로는 반드시 근거에 실제로 등장한 경로만 그대로 인용한다. 경로를 지어내거나 디렉토리를 중복/변형하지 않는다.
- 호출 흐름/데이터 흐름을 서술할 때는 '어느 클래스의 어느 메서드가 어느 메서드를 호출하는지'를 근거에서 직접 추적해 확인한 사실만 쓴다. 한쪽의 반환값이 다른 쪽의 인자로 전달되는 경우와, 두 객체가 각자 독립적으로 호출/검색하는 경우를 혼동하지 않는다.
- 과장 없이 사실 위주로, 간결하지만 구체적으로 쓴다.
"@

# ── Map: 청크별 구조 요약 (청크가 2개 이상일 때만) ───────────────────
$key = [System.IO.Path]::GetFileNameWithoutExtension($OutFile)
$cacheSub = Join-Path $CacheDir $key

if ($chunks.Count -le 1) {
    # 작은 도메인: 통짜 주입. 요약 손실 없음.
    $material = $chunks[0]
    $materialLabel = "실제 소스"
    Write-Host "[Map] 단일 청크 → 통짜 주입(요약 생략)"
}
else {
    if (-not (Test-Path $cacheSub)) { New-Item -ItemType Directory -Force -Path $cacheSub | Out-Null }
    $summaries = @()
    $ci = 0
    foreach ($chunk in $chunks) {
        $ci++
        $cf = Join-Path $cacheSub ("{0:D2}.md" -f $ci)
        if ((Test-Path $cf) -and -not $Force) {
            Write-Host ("[Map {0}/{1}] 캐시 사용: {2}" -f $ci, $chunks.Count, $cf)
            $summaries += (Get-Content -Raw -Encoding UTF8 $cf)
            continue
        }
        Write-Host ("[Map {0}/{1}] 요약 생성" -f $ci, $chunks.Count)
        $sum = Invoke-LLM -System $groundRule -User @"
다음은 '$Title' 도메인 소스의 일부다(청크 $ci/$($chunks.Count)).

$chunk

이 청크의 각 파일을 구조 요약하라. 파일마다:
- 파일 경로(FILE 헤더 그대로)
- 한 줄 책임
- 주요 클래스/메서드의 시그니처(이름과 인자/반환 위주)
- 이 파일이 호출하거나 의존하는 다른 클래스/메서드(코드에서 확인되는 것만)
형식은 파일별로 '### 경로' 머리글 + 불릿. 코드에 있는 사실만. 군더더기 설명 금지.
"@
        Set-Content -Path $cf -Value $sum -Encoding UTF8
        $summaries += $sum
    }
    $material = ($summaries -join "`n`n") + "`n`n----`n" + $pathIndexText
    $materialLabel = "소스 구조 요약(+전체 파일 경로 인덱스)"
    Write-Host ("[Map] {0}개 요약 합본({1}자)" -f $chunks.Count, $material.Length)
}

# ── Reduce Pass 1: 목차 생성 ─────────────────────────────────────────
Write-Host "[Pass 1] 목차 생성"
$outlineRaw = Invoke-LLM -System $groundRule -User @"
다음은 '$Title' 도메인의 $materialLabel 이다.

$material

이 도메인을 설명하는 개발자 문서의 '목차'만 만들어라.
- 책임/흐름 단위로 5~8개 섹션을 제안한다(예: 개요, 색인 파이프라인, 검색·근거수집, 답변생성, 설정).
- 출력은 각 섹션 제목을 '## ' 로 시작하는 줄로만, 한 줄에 하나씩. 다른 설명은 쓰지 마라.
"@

$sections = @()
foreach ($line in ($outlineRaw -split "`n")) {
    $t = $line.Trim()
    if ($t.StartsWith("## ")) { $sections += $t.Substring(3).Trim() }
}
if ($sections.Count -eq 0) {
    Write-Warning "[Pass 1] 목차 파싱 실패 → 기본 섹션으로 대체"
    $sections = @("개요", "주요 구성요소", "데이터 흐름", "설정")
}
Write-Host ("[Pass 1] 섹션 {0}개: {1}" -f $sections.Count, ($sections -join ', '))

# ── Reduce Pass 2: 섹션별 본문 채우기 (출력 분할) ────────────────────
$doc = New-Object System.Text.StringBuilder
[void]$doc.AppendLine("# $Title")
[void]$doc.AppendLine("")
[void]$doc.AppendLine("> 이 문서는 로컬 LLM($Model)이 소스 코드를 근거로 자동 생성했다. 검증 전 초안이다.")
[void]$doc.AppendLine("")
[void]$doc.AppendLine("## 목차")
foreach ($s in $sections) { [void]$doc.AppendLine("- $s") }
[void]$doc.AppendLine("")

$idx = 0
foreach ($s in $sections) {
    $idx++
    Write-Host ("[Pass 2] 섹션 {0}/{1}: {2}" -f $idx, $sections.Count, $s)
    $sectionBody = Invoke-LLM -System $groundRule -User @"
다음은 '$Title' 도메인의 $materialLabel 이다.

$material

위 근거에 근거해 '$s' 섹션의 본문만 작성하라.
- 제목 줄은 쓰지 말고 본문만(이미 상위에서 제목을 붙인다).
- 이 섹션 주제와 무관한 내용은 쓰지 마라.
- 근거가 된 파일 경로를 문장에 함께 남겨라.
"@
    [void]$doc.AppendLine("## $s")
    [void]$doc.AppendLine("")
    [void]$doc.AppendLine($sectionBody.Trim())
    [void]$doc.AppendLine("")
}

# ── Reduce Pass 3: 자가검증 1패스 ────────────────────────────────────
Write-Host "[Pass 3] 자가검증"
$draft = $doc.ToString()
$verified = Invoke-LLM -System $groundRule -User @"
아래는 '$Title' 도메인의 '$materialLabel' 과, 그로부터 만든 '문서 초안'이다.
문서 초안을 근거와 대조해 아래 체크리스트로 '엄격히' 검증하고 교정하라.

[검증 체크리스트]
1) 경로 검증: 문서에 등장하는 모든 파일 경로가 [근거]에 똑같이 존재하는가?
   존재하지 않거나 디렉토리가 중복/오타된 경로는 올바른 경로로 고친다.
2) 흐름 검증: "A의 결과가 B로 전달된다", "B가 A를 사용한다" 같은 연결 서술은 근거에서
   확인한다. 어떤 메서드가 다른 메서드의 '반환값을 인자로 받는지', 아니면
   '각자 독립적으로 호출/검색하는지'를 반드시 구분한다. 근거로 확인되지 않는 연결은 단정하지
   말고 교정하거나 '(코드상 미상)' 으로 바꾼다.
3) 사실 검증: 클래스/메서드/설정값/기본값 서술이 근거와 일치하는가? 불일치는 근거에 맞게 고친다.
4) 오타/표기 교정.

형식(제목 구조, 목차)은 유지한 채, 교정한 '완성본 전체'만 그대로 출력하라. 변경 요약이나 설명은 덧붙이지 마라.

[근거]
$material

[문서 초안]
$draft
"@

# ── 출력 저장 ────────────────────────────────────────────────────────
$outDir = Split-Path $OutFile -Parent
if ($outDir -and -not (Test-Path $outDir)) {
    New-Item -ItemType Directory -Force -Path $outDir | Out-Null
}
$final = if ([string]::IsNullOrWhiteSpace($verified)) { $draft } else { $verified.Trim() }
Set-Content -Path $OutFile -Value $final -Encoding UTF8
Write-Host "[완료] 생성: $OutFile ($($final.Length)자)"
