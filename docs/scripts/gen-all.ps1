# 모든 도메인 문서를 순차 생성(fire-and-forget). 실패해도 다음으로 계속.
# 이미 있는 결과물은 건너뜀. 전부 새로 만들려면 -Force.
#
# 실행(저장소 루트에서):  pwsh docs/scripts/gen-all.ps1
# 전체 재생성:            pwsh docs/scripts/gen-all.ps1 -Force
param([switch]$Force)
try { [Console]::OutputEncoding = [System.Text.Encoding]::UTF8 } catch {}

$script = "docs/scripts/gen-domain-doc.ps1"
$outDir = "docs/generated"

$targets = @(
  # 백엔드
  @{ Root="springboot/src/main/java/com/chs/springboot/domain/analysis"; Out="be-analysis.md"; Title="Analysis 도메인" },
  @{ Root="springboot/src/main/java/com/chs/springboot/domain/binance";  Out="be-binance.md";  Title="Binance 수집/집계" },
  @{ Root="springboot/src/main/java/com/chs/springboot/domain/chatbot";  Out="be-chatbot.md";  Title="Chatbot (코드베이스 RAG)" },
  @{ Root="springboot/src/main/java/com/chs/springboot/domain/upbit";    Out="be-upbit.md";    Title="Upbit 도메인" },
  @{ Root="springboot/src/main/java/com/chs/springboot/domain/weather";  Out="be-weather.md";  Title="Weather 도메인" },
  # 프론트 도메인
  @{ Root="frontend/src/domain/binance";   Out="fe-domain-binance.md";   Title="프론트 도메인: binance" },
  @{ Root="frontend/src/domain/logistics"; Out="fe-domain-logistics.md"; Title="프론트 도메인: logistics" },
  @{ Root="frontend/src/domain/support";   Out="fe-domain-support.md";   Title="프론트 도메인: support" },
  @{ Root="frontend/src/domain/weather";   Out="fe-domain-weather.md";   Title="프론트 도메인: weather" },
  # 프론트 페이지
  @{ Root="frontend/src/page/admin";     Out="fe-page-admin.md";     Title="프론트 페이지: admin" },
  @{ Root="frontend/src/page/analysis";  Out="fe-page-analysis.md";  Title="프론트 페이지: analysis" },
  @{ Root="frontend/src/page/binance";   Out="fe-page-binance.md";   Title="프론트 페이지: binance" },
  @{ Root="frontend/src/page/logistics"; Out="fe-page-logistics.md"; Title="프론트 페이지: logistics" },
  @{ Root="frontend/src/page/monitor";   Out="fe-page-monitor.md";   Title="프론트 페이지: monitor" },
  @{ Root="frontend/src/page/random";    Out="fe-page-random.md";    Title="프론트 페이지: random" },
  @{ Root="frontend/src/page/signal";    Out="fe-page-signal.md";    Title="프론트 페이지: signal" },
  @{ Root="frontend/src/page/trade";     Out="fe-page-trade.md";     Title="프론트 페이지: trade" },
  # 프론트 교차영역
  @{ Root="frontend/src/shared";     Out="fe-shared.md";     Title="프론트 교차영역: shared" },
  @{ Root="frontend/src/api";        Out="fe-api.md";        Title="프론트 교차영역: api" },
  @{ Root="frontend/src/store";      Out="fe-store.md";      Title="프론트 교차영역: store" },
  @{ Root="frontend/src/components";  Out="fe-components.md"; Title="프론트 교차영역: components" }
)

$ok = 0; $fail = 0; $skip = 0
$start = Get-Date
foreach ($t in $targets) {
    $outPath = Join-Path $outDir $t.Out
    if ((Test-Path $outPath) -and -not $Force) {
        Write-Host "[건너뜀] 이미 존재: $($t.Out)"; $skip++; continue
    }
    Write-Host "`n========== 생성 시작: $($t.Out) =========="
    try {
        & $script -DomainRoot $t.Root -OutFile $outPath -Title $t.Title
        if (Test-Path $outPath) { Write-Host "[성공] $($t.Out)"; $ok++ }
        else { Write-Warning "[실패] 출력 없음: $($t.Out)"; $fail++ }
    }
    catch {
        Write-Warning "[실패] $($t.Out): $($_.Exception.Message)"; $fail++
    }
}
$min = ((Get-Date) - $start).TotalMinutes
Write-Host "`n========== 전체 완료: 성공 $ok / 건너뜀 $skip / 실패 $fail / 소요 $([math]::Round($min))분 =========="
