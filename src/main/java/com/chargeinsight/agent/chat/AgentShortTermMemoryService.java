package com.chargeinsight.agent.chat;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

/** Redis cache for recent conversation messages; MySQL remains the auditable source of truth. */
@Service
public class AgentShortTermMemoryService {
    private static final Logger log = LoggerFactory.getLogger(AgentShortTermMemoryService.class);
    private static final String KEY_PREFIX = "chargeinsight:chat:short-memory:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final boolean enabled;
    private final int messageLimit;
    private final Duration ttl;

    public AgentShortTermMemoryService(StringRedisTemplate redisTemplate, ObjectMapper objectMapper,
            @Value("${charge.memory.redis-enabled:false}") boolean enabled,
            @Value("${charge.memory.history-limit:12}") int messageLimit,
            @Value("${charge.memory.ttl-hours:24}") long ttlHours) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.enabled = enabled;
        this.messageLimit = Math.max(2, messageLimit);
        this.ttl = Duration.ofHours(Math.max(1, ttlHours));
    }

    public void append(String sessionId, String role, String content) {
        if (!enabled) return;
        try {
            String key = key(sessionId);
            redisTemplate.opsForList().rightPush(key, objectMapper.writeValueAsString(new MemoryMessage(role, content)));
            redisTemplate.opsForList().trim(key, -messageLimit, -1);
            redisTemplate.expire(key, ttl);
        } catch (Exception exception) {
            log.debug("Redis short-term memory append skipped: {}", exception.getMessage());
        }
    }

    public List<MemoryMessage> recent(String sessionId) {
        if (!enabled) return List.of();
        try {
            List<String> values = redisTemplate.opsForList().range(key(sessionId), 0, -1);
            if (values == null) return List.of();
            return values.stream().map(this::deserialize).filter(java.util.Objects::nonNull).toList();
        } catch (Exception exception) {
            log.debug("Redis short-term memory read skipped: {}", exception.getMessage());
            return List.of();
        }
    }

    public void refresh(String sessionId, List<MemoryMessage> messages) {
        if (!enabled || messages.isEmpty()) return;
        try {
            String key = key(sessionId);
            redisTemplate.delete(key);
            List<String> values = messages.stream().skip(Math.max(0, messages.size() - messageLimit))
                    .map(this::serialize).toList();
            redisTemplate.opsForList().rightPushAll(key, values);
            redisTemplate.expire(key, ttl);
        } catch (Exception exception) {
            log.debug("Redis short-term memory refresh skipped: {}", exception.getMessage());
        }
    }

    public void delete(String sessionId) {
        if (!enabled) return;
        try {
            redisTemplate.delete(key(sessionId));
        } catch (Exception exception) {
            log.debug("Redis short-term memory delete skipped: {}", exception.getMessage());
        }
    }

    public MemoryStatus status() {
        if (!enabled) return new MemoryStatus(false, false, messageLimit, ttl.toHours());
        try (var connection = redisTemplate.getConnectionFactory().getConnection()) {
            String pong = connection.ping();
            return new MemoryStatus(true, "PONG".equalsIgnoreCase(pong), messageLimit, ttl.toHours());
        } catch (Exception exception) {
            return new MemoryStatus(true, false, messageLimit, ttl.toHours());
        }
    }

    private String serialize(MemoryMessage message) {
        try {
            return objectMapper.writeValueAsString(message);
        } catch (Exception exception) {
            throw new IllegalStateException("无法序列化短期会话消息", exception);
        }
    }

    private MemoryMessage deserialize(String value) {
        try {
            return objectMapper.readValue(value, MemoryMessage.class);
        } catch (Exception exception) {
            return null;
        }
    }

    private String key(String sessionId) { return KEY_PREFIX + sessionId; }

    public record MemoryMessage(String role, String content) { }
    public record MemoryStatus(boolean enabled, boolean available, int messageLimit, long ttlHours) { }
}
