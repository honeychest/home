// [AGENT] 운영 알림(텔레그램) 설정 seam — 분리 규율 5 (recipe 전용 설정 그룹)
package com.chs.springboot.domain.recipe.registration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * gikka.notify.* — 페일오버 같은 "사람이 알아야 하는 사건"을 알리는 채널.
 * (2026-07-26 GikkaMediaProperties 분할로 신설)
 *
 * <p>기존 home 봇 값을 recipe 설정 그룹으로 <b>복사해 소유</b>한다 (분리 규율 5·7 — 앱 분리 시
 * 함께 들어내기 위해 공용 참조를 만들지 않는다). token 이 비면 알림을 조용히 생략한다.
 */
@ConfigurationProperties(prefix = "gikka.notify")
public class GikkaNotifyProperties {

    private String telegramToken = "";
    private String telegramChatId = "";

    public String getTelegramToken() {
        return telegramToken;
    }

    public void setTelegramToken(String telegramToken) {
        this.telegramToken = telegramToken;
    }

    public String getTelegramChatId() {
        return telegramChatId;
    }

    public void setTelegramChatId(String telegramChatId) {
        this.telegramChatId = telegramChatId;
    }
}
