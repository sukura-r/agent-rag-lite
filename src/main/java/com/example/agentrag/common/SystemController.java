package com.example.agentrag.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import javax.sql.DataSource;
import java.sql.Connection;
import java.util.LinkedHashMap;
import java.util.Map;
import com.example.agentrag.vector.VectorStore;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;

@RestController
@RequestMapping("/api/system")
public class SystemController {

    private final DataSource dataSource;
    private final JdbcTemplate jdbcTemplate;
    private final String milvusUri;
    private final VectorStore vectorStore;
    private final StringRedisTemplate redisTemplate;

    public SystemController(DataSource dataSource,
                            JdbcTemplate jdbcTemplate,
                            @Value("${app.milvus.uri}") String milvusUri,
                            VectorStore vectorStore,
                            StringRedisTemplate redisTemplate) {
        this.dataSource = dataSource;
        this.jdbcTemplate = jdbcTemplate;
        this.milvusUri = milvusUri;
        this.vectorStore = vectorStore;
        this.redisTemplate = redisTemplate;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() throws Exception {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("application", "UP");
        try (Connection connection = dataSource.getConnection()) {
            result.put("mysql", connection.isValid(2) && jdbcTemplate.queryForObject("SELECT 1", Integer.class) == 1
                    ? "UP" : "DOWN");
        }
        result.put("milvus", Map.of("uri", milvusUri, "status", vectorStore.exists() ? "UP" : "DOWN"));
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            result.put("redis", "PONG".equalsIgnoreCase(connection.ping()) ? "UP" : "DOWN");
        }
        result.put("stage", "DAY_14_PRODUCTION_DELIVERY");
        return ApiResponse.success(result);
    }
}
