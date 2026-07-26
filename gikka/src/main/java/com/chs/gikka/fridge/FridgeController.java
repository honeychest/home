// [AGENT] 냉장고 API — 경로 /api/recipe/** 통일 (분리 규율 3), 응답 모양은 프론트 fridgeTypes.ts 계약
// 인증: @GikkaUserId 가 시그니처로 보장 (리졸버가 미로그인 401 처리 — 본문에서 인증 코드 없음)
package com.chs.gikka.fridge;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;

import com.chs.gikka.auth.GikkaUserId;

import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
@RequestMapping("/api/recipe/fridge")
public class FridgeController {

    private final FridgeRepository fridge;

    public FridgeController(FridgeRepository fridge) {
        this.fridge = fridge;
    }

    public record AddRequest(String name) {
    }

    /** name / addedDate / expiring 중 넘어온 것만 갱신 (이름 교정·날짜 스테퍼·임박 토글) */
    public record UpdateRequest(String name, String addedDate, Boolean expiring) {
    }

    @GetMapping("/items")
    public List<FridgeItemResponse> items(@GikkaUserId long userId) {
        return fridge.list(userId);
    }

    @PostMapping("/items")
    public FridgeItemResponse add(@GikkaUserId long userId, @RequestBody AddRequest request) {
        if (request.name() == null || request.name().isBlank()) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "재료 이름이 비어 있습니다");
        }
        return fridge.add(userId, request.name());
    }

    @DeleteMapping("/items/{id}")
    public void remove(@GikkaUserId long userId, @PathVariable long id) {
        if (!fridge.remove(userId, id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "재료를 찾을 수 없습니다");
        }
    }

    @PatchMapping("/items/{id}")
    public void update(@GikkaUserId long userId, @PathVariable long id, @RequestBody UpdateRequest request) {
        boolean updated = fridge.update(
                userId, id,
                Optional.ofNullable(request.name()).filter(n -> !n.isBlank()),
                Optional.ofNullable(request.addedDate()).map(LocalDate::parse),
                Optional.ofNullable(request.expiring()));
        if (!updated) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "재료를 찾을 수 없습니다");
        }
    }

    /** limit 필수 — 표시 개수의 원본은 프론트 FREQUENT_LIMIT 하나뿐 (서버 기본값 이중 정의 금지) */
    @GetMapping("/frequent-ingredients")
    public List<String> frequentIngredients(@GikkaUserId long userId, @RequestParam int limit) {
        return fridge.frequentIngredients(userId, limit);
    }

    @DeleteMapping("/frequent-ingredients/{name}")
    public void removeFrequentIngredient(@GikkaUserId long userId, @PathVariable String name) {
        fridge.removeFrequentIngredient(userId, name);
    }
}
