// [AGENT] gikka(recipe) DB 접속 설정 seam — docs/recipe/CONTEXT.md 분리 규율 5·8
// chatbot 의 PgVectorProperties 패턴을 복사 소유 (규율 7: 공용 코드 import 금지)
package com.chs.springboot.domain.recipe.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gikka.datasource")
public class GikkaDataSourceProperties {

    private String url;
    private String username;
    private String password;

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        this.username = username;
    }

    public String getPassword() {
        return password;
    }

    public void setPassword(String password) {
        this.password = password;
    }
}
