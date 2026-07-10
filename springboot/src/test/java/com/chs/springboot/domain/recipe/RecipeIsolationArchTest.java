package com.chs.springboot.domain.recipe;

import com.tngtech.archunit.core.importer.ImportOption;
import com.tngtech.archunit.junit.AnalyzeClasses;
import com.tngtech.archunit.junit.ArchTest;
import com.tngtech.archunit.lang.ArchRule;

import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideInAPackage;
import static com.tngtech.archunit.core.domain.JavaClass.Predicates.resideOutsideOfPackage;
import static com.tngtech.archunit.lang.syntax.ArchRuleDefinition.noClasses;

/**
 * [AGENT] recipe(기까) 도메인 격리 감시 — docs/recipe/CONTEXT.md "분리 규율" 1·6·7번.
 *
 * recipe 는 2단계에서 별도 앱으로 들어낼 전제라, 프로젝트 내 다른 코드와
 * 어느 방향으로도 엮이면 안 된다. 이 규율을 사람 기억이 아니라 빌드가 지키게 한다.
 *
 * - 공용 코드 허용 목록: 없음. global/external/features 의 유틸·설정이 필요하면
 *   recipe 패키지 안으로 복사해 소유한다 (규율 7 — 앱 분리 시 함께 들어내기 위해).
 * - 이 테스트가 실패하면 import 를 지우는 것이 수정이다. 규칙을 완화하지 말 것.
 */
@AnalyzeClasses(
        packages = "com.chs.springboot",
        importOptions = ImportOption.DoNotIncludeTests.class
)
class RecipeIsolationArchTest {

    private static final String RECIPE = "com.chs.springboot.domain.recipe..";
    private static final String PROJECT = "com.chs.springboot..";

    @ArchTest
    static final ArchRule recipe_는_프로젝트_내_다른_패키지에_의존하지_않는다 =
            noClasses().that().resideInAPackage(RECIPE)
                    .should().dependOnClassesThat(
                            resideInAPackage(PROJECT).and(resideOutsideOfPackage(RECIPE)))
                    .because("recipe 는 별도 앱 분리 전제 — 다른 도메인·global·external·features 를 "
                            + "import 하지 말고, 필요하면 복사해 소유한다 (분리 규율 6·7)")
                    // recipe 메인 코드가 아직 없어도(2차 착수 시점) 규칙 자체는 유효해야 함
                    .allowEmptyShould(true);

    @ArchTest
    static final ArchRule 다른_패키지는_recipe_에_의존하지_않는다 =
            noClasses().that().resideInAPackage(PROJECT)
                    .and().resideOutsideOfPackage(RECIPE)
                    .should().dependOnClassesThat(resideInAPackage(RECIPE))
                    .because("recipe 를 들어낼 때 다른 코드가 깨지면 안 된다 — 역방향 참조 금지 (분리 규율 1)");
}
