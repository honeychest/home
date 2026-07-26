package com.chs.gikka;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * [AGENT] gikka 내부 패키지 방향 규율 — docs/recipe/CONTEXT.md 19절 9·12번.
 *
 * <p><b>이 파일의 유래</b>: springboot/ 시절 {@code RecipeIsolationArchTest} 에는 규칙이 넷이었다.
 * 앞의 둘은 recipe 와 <i>바깥</i> 사이의 담이었고(recipe ↛ 다른 도메인, 다른 도메인 ↛ recipe),
 * 뒤의 둘은 recipe <i>안쪽</i>의 방향 규칙이었다. 앱을 분리하면서 바깥 담 둘은 물리적으로
 * 보장됐다 — 다른 도메인이 아예 이 프로젝트에 없다. 그래서 여기 남은 것은 안쪽 규칙 둘뿐이고,
 * 이름도 격리(isolation)가 아니라 구조(architecture)로 바꿨다.
 *
 * <p>바깥 담이 사라졌다고 규율 7("공용 코드 허용 목록 없음, 필요하면 복사해 소유")까지 없어진
 * 것은 아니다. 그 규율의 목적이 바로 이 분리였고, 이미 달성됐다.
 */
@AnalyzeClasses(
        packages = "com.chs.gikka",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class GikkaArchitectureTest {

    private static final String ROOT = "com.chs.gikka..";
    private static final String DICTIONARY = "com.chs.gikka.dictionary..";
    private static final String REGISTRATION = "com.chs.gikka.registration..";
    private static final String EXTERNAL = "com.chs.gikka.external..";

    /**
     * 재료 사전은 registration(분석 파이프라인)의 산출물이자 recommend(매칭)의 입력이라 어느 한쪽
     * 소유가 아니다. 그래서 별도 패키지로 뒀는데, <b>양방향이 되면 옮긴 의미가 사라진다</b> —
     * registration → dictionary 는 정상(워커가 사전을 채운다), 그 반대가 생기는 순간 두 패키지는
     * 사실상 한 덩어리이고 "recommend 가 registration 에 의존"하던 예전 모양으로 돌아간다.
     *
     * <p>2026-07-26 해소: 예전엔 이 규칙 때문에 LLM 판정 6개(IngredientJudge·DictionaryJudge·
     * 어댑터들)를 dictionary 로 못 옮기고 registration 에 남겨뒀다 — 그것들이 GeminiJsonClient·
     * GikkaMediaProperties·TransientFailureException·LocalUnavailableException 을 쓰기 때문이었다.
     * 그 넷을 {@code external} 중립 지대로 옮기면서 판정도 함께 사전으로 이사했다.
     */
    @ArchTest
    static final ArchRule dictionary_는_registration_에_의존하지_않는다 =
            noClasses().that().resideInAPackage(DICTIONARY)
                    .should().dependOnClassesThat(resideInAPackage(REGISTRATION))
                    .because("사전 패키지는 한 방향으로만 쓰인다 — 역방향이 생기면 패키지 순환이라 "
                            + "분리한 이득(recommend 가 registration 을 안 봐도 되는 것)이 사라진다");

    /**
     * external 은 <b>중립 지대</b>다 — "밖으로 나가는 접촉면"(Gemini 호출 봉투, mac-mini 호스트
     * 서비스 주소, 그 둘의 실패 타입)만 산다. 중립이라는 말의 뜻이 곧 이 규칙이다: 여기가
     * 다른 패키지를 하나라도 알게 되는 순간, 그 패키지를 쓰는 쪽은 external 을 통해 간접적으로
     * 서로 엮이고 중립 지대는 그냥 "공용 잡동사니"가 된다 (분할 전 GikkaMediaProperties 가
     * 정확히 그 상태였다 — xdownload 가 설정 하나 때문에 registration 을 import 했다).
     *
     * <p>바깥 방향은 자유롭다(registration·dictionary·xdownload 셋 다 external 을 쓴다).
     */
    @ArchTest
    static final ArchRule external_은_다른_패키지를_모른다 =
            noClasses().that().resideInAPackage(EXTERNAL)
                    .should().dependOnClassesThat(
                            resideInAPackage(ROOT).and(resideOutsideOfPackage(EXTERNAL)))
                    .because("중립 지대가 남을 알면 중립이 아니다 — 세 패키지가 여기를 통해 서로 "
                            + "엮이고, 분할 전 GikkaMediaProperties 의 상태로 되돌아간다");
}
