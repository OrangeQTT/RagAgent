package com.ryankew.infinitechatagent.config;

import dev.langchain4j.mcp.McpToolProvider;
import dev.langchain4j.mcp.client.DefaultMcpClient;
import dev.langchain4j.mcp.client.McpClient;
import dev.langchain4j.mcp.client.transport.McpTransport;
import dev.langchain4j.mcp.client.transport.http.StreamableHttpMcpTransport;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Configuration
public class McpToolConfig {

    @Value("${bigmodel.api-key}")
    private String apiKey;

    @Bean
    public McpToolProvider mcpToolProvider() {
        // key 没配（空串或占位符 "-"）时，不初始化 MCP 客户端。
        // 否则 DefaultMcpClient 会在启动阶段同步连 BigModel，鉴权失败会拖 30s 超时、直接让应用/测试启动失败。
        if (apiKey == null || apiKey.isBlank() || "-".equals(apiKey.trim())) {
            log.warn("bigmodel.api-key 未配置，跳过 BigModel 联网搜索 MCP 工具");
            return McpToolProvider.builder().mcpClients(List.of()).build();
        }

        try {
            McpTransport searchTransport = new StreamableHttpMcpTransport.Builder()
                    .url("https://open.bigmodel.cn/api/mcp/web_search/mcp")  // 用能响应的那个端点，去掉 ?Authorization
                    .customHeaders(ctx -> Map.of("Authorization", apiKey.trim()))
                    .logRequests(true)
                    .logResponses(true)
                    .build();

            McpClient searchClient = new DefaultMcpClient.Builder()
                    .key("BigModelSearchMcpClient")
                    .transport(searchTransport)
                    .initializationTimeout(Duration.ofSeconds(10)) // 无效 key 时快速失败，避免拖 30s 才降级
                    .build();

//        McpTransport timeTransport = new StdioMcpTransport.Builder()
//                .command(Arrays.asList("uvx", "mcp-server-time", "--local-timezone=Asia/Shanghai"))
//                .build();
//
//        McpClient timeClient = new DefaultMcpClient.Builder()
//                .key("timeClient")
//                .transport(timeTransport)
//                .build();
//
//
//        return McpToolProvider.builder()
//                .mcpClients(Arrays.asList(timeClient, searchClient))
//                .build();

            return McpToolProvider.builder()
                    .mcpClients(searchClient)
                    .build();
        } catch (Exception e) {
            // key 无效 / BigModel 服务不可用时降级：不加载该工具，但不阻塞应用启动。
            log.warn("BigModel MCP 联网搜索初始化失败，已降级为不加载该工具: {}", e.getMessage());
            return McpToolProvider.builder().mcpClients(List.of()).build();
        }
    }
}