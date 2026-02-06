package com.chs.springboot;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.HashMap;
import java.util.Map;

@RestController
public class ConnectController {
    @GetMapping("/api/hello") // 브라우저나 프론트엔드가 접속할 주소입니다.
    public String hello() {
        return "백엔드 서버가 정상적으로 작동 중입니다!";
    }

    @GetMapping("/api/data") // 나중에 Vue/React와 주고받을 '데이터' 형태(JSON) 테스트입니다.
    public Map<String, String> getData() {
        Map<String, String> data = new HashMap<>();
        data.put("status", "success");
        data.put("message", "데이터 전달 완료!");
        return data;
    }
}
