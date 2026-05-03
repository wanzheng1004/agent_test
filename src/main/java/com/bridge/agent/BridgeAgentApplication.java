package com.bridge.agent;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.cache.annotation.EnableCaching;

/**
 * 桥梁智能检测助手 Agent 主入口
 *
 * <p>技术栈：Spring AI 1.0.0 + Spring Boot 3.3 + GPT-4o + Qdrant + MySQL + Redis
 *
 * <p>架构要点：
 * <ul>
 *   <li>自定义 ReAct 执行引擎（显式 T-A-O 循环，不依赖框架自动 function calling）</li>
 *   <li>自定义 Plan & Execute 引擎（三阶段：Plan → Execute → Synthesize）</li>
 *   <li>ToolRegistry 统一工具管理（每个 Agent 只开放自己需要的工具子集）</li>
 *   <li>四阶段 RAG 管道（BM25 + 向量 + RRF + LLM Reranker）</li>
 *   <li>三级记忆架构（会话级 Redis / 桥梁级 MySQL / 用户级 MySQL）</li>
 *   <li>Advisor 链横切关注点（Logging + Memory + Guardrail）</li>
 * </ul>
 *
 * <p>启动前提：
 * <pre>
 * # 启动基础设施（Qdrant + Redis + MySQL）
 * docker-compose up -d
 *
 * # 设置环境变量
 * export OPENAI_API_KEY=sk-xxx
 * </pre>
 */
@SpringBootApplication
public class BridgeAgentApplication {

    public static void main(String[] args) {
        SpringApplication.run(BridgeAgentApplication.class, args);
    }
}
