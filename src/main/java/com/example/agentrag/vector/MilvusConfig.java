package com.example.agentrag.vector;

import io.milvus.v2.client.ConnectConfig;
import io.milvus.v2.client.MilvusClientV2;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MilvusConfig {

    @Bean(destroyMethod = "close")
    MilvusClientV2 milvusClient(@Value("${app.milvus.uri}") String uri) {
        return new MilvusClientV2(ConnectConfig.builder()
                .uri(uri)
                .connectTimeoutMs(10_000)
                .rpcDeadlineMs(30_000)
                .build());
    }
}
