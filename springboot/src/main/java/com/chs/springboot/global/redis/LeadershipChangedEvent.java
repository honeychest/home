package com.chs.springboot.global.redis;

/**
 * ownerToken·epoch 는 이 리더십 변경을 발급한 lease 의 fence 정보 —
 * telemetry 등 flush 경로가 "지금 내가 쥔 lease 가 여전히 유효한지"를 Redis 원자 검증할 때 쓴다.
 * leader=false 로 넘어갈 때도 epoch 은 방금까지 쥐고 있던(혹은 시도했던) lease 세대를 그대로 담아 보낸다.
 */
public record LeadershipChangedEvent(
        String serverName,
        boolean leader,
        String ownerToken,
        long epoch
) {
    public LeadershipChangedEvent(String serverName, boolean leader) {
        this(serverName, leader, "", 0L);
    }
}
