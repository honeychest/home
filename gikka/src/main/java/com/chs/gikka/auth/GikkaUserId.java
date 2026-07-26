// [AGENT] 컨트롤러 파라미터에 현재 사용자 id 주입 — "이 API 는 로그인 필요"를 시그니처로 드러냄
// 사용: `@GikkaUserId long userId`. 미로그인 요청은 리졸버가 401 을 던져 본문에 못 들어온다.
package com.chs.gikka.auth;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface GikkaUserId {
}
