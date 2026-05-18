package com.bridge.agent.tool;

import com.bridge.agent.core.ToolRegistry;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * 工具注册器，在应用启动时把所有工具注册到 ToolRegistry。
 *
 * <p>面试要点：
 * “为什么手动注册，而不是直接用 Spring AI 的 @Tool 自动扫描？”
 * 因为这里不是把工具完全交给框架做 function calling，
 * 而是配合自定义编排和 ReAct 循环，让每次工具调用都经过我们自己的可观测、可调试代码路径。</p>
 */
@Component
public class ToolInitializer {

    private static final Logger log = LoggerFactory.getLogger(ToolInitializer.class);

    private final ToolRegistry registry;
    private final BridgeProfileTool bridgeProfileTool;
    private final DefectHistoryTool defectHistoryTool;
    private final RepairRecordTool repairRecordTool;
    private final StandardRetrieveTool standardRetrieveTool;
    private final StandardRetrieveMarkdownToolV2 standardRetrieveMarkdownToolV2;
    private final StandardRetrieveMarkdownToolV3 standardRetrieveMarkdownToolV3;
    private final DefectNormalizeTool defectNormalizeTool;
    private final RepairCaseTool repairCaseTool;
    private final DefectClusterTool defectClusterTool;

    public ToolInitializer(ToolRegistry registry,
                           BridgeProfileTool bridgeProfileTool,
                           DefectHistoryTool defectHistoryTool,
                           RepairRecordTool repairRecordTool,
                           StandardRetrieveTool standardRetrieveTool,
                           StandardRetrieveMarkdownToolV2 standardRetrieveMarkdownToolV2,
                           StandardRetrieveMarkdownToolV3 standardRetrieveMarkdownToolV3,
                           DefectNormalizeTool defectNormalizeTool,
                           RepairCaseTool repairCaseTool,
                           DefectClusterTool defectClusterTool) {
        this.registry = registry;
        this.bridgeProfileTool = bridgeProfileTool;
        this.defectHistoryTool = defectHistoryTool;
        this.repairRecordTool = repairRecordTool;
        this.standardRetrieveTool = standardRetrieveTool;
        this.standardRetrieveMarkdownToolV2 = standardRetrieveMarkdownToolV2;
        this.standardRetrieveMarkdownToolV3 = standardRetrieveMarkdownToolV3;
        this.defectNormalizeTool = defectNormalizeTool;
        this.repairCaseTool = repairCaseTool;
        this.defectClusterTool = defectClusterTool;
    }

    @PostConstruct
    public void initTools() {
        log.info("Initializing tool registry...");

        // ===== 巡检前工具 =====
        registry.register(
                "query_bridge_profile",
                "查询桥梁基础档案，包括桥型、建造年份、设计使用年限、荷载等级等基本信息。" +
                        "适用：巡检前了解桥梁概况，或通用查询桥梁基本信息时。",
                "{\"bridgeId\": \"桥梁编号，如 BRG-001\"}",
                bridgeProfileTool::execute
        );

        registry.register(
                "search_defect_history",
                "查询指定桥梁在指定时间范围内的历史病害记录列表，按时间倒序返回。" +
                        "适用：巡检前了解历史病害情况，或追溯某类病害的发展历史时。",
                "{\"bridgeId\": \"桥梁编号\", \"months\": \"查询最近几个月，默认 6\"}",
                defectHistoryTool::execute
        );

        registry.register(
                "query_repair_records",
                "查询指定桥梁的历史维修和加固记录，按时间倒序返回。" +
                        "适用：巡检前了解近期维修情况，评估构件维修后的状态时。",
                "{\"bridgeId\": \"桥梁编号\"}",
                repairRecordTool::execute
        );

        registry.register(
                "cluster_defects",
                "对指定桥梁的历史病害记录进行语义聚类，输出高频病害排行。" +
                        "适用：巡检前分析哪类病害最频繁出现，需要重点关注时。" +
                        "不适用：查询单次检测的病害详情。",
                "{\"bridgeId\": \"桥梁编号\", \"months\": \"分析最近几个月，默认 6\"}",
                defectClusterTool::execute
        );

        // ===== 巡检中工具 =====
        registry.register(
                "retrieve_standard",
                "根据病害描述检索对应的规范条款和病害等级评定标准。" +
                        "适用：检测员描述了病害现象，需要查找对应规范要求和评级依据时。" +
                        "不适用：查询桥梁档案、查询历史维修记录。",
                "{\"defectQuery\": \"包含病害类型、位置、尺寸等关键特征的描述文本\"}",
                standardRetrieveTool::execute
        );

        registry.register(
                "retrieve_standard_markdown_v2",
                "基于 Markdown 文件库做 grep 风格规范检索，不依赖 MySQL 或向量库。" +
                        "适用：规范条款已整理成 markdown 文件，想用轻量、可解释的方式直接全文检索时。",
                "{\"defectQuery\": \"包含病害类型、构件、位置等关键词的描述文本\"}",
                standardRetrieveMarkdownToolV2::execute
        );

        registry.register(
                "retrieve_standard_markdown_v3",
                "参考 OpenClaw 的 Markdown hybrid search：先切片入 SQLite，" +
                        "再走 FTS5/BM25 和 embedding/vector 两路召回并融合。" +
                        "适用：规范文档规模不大，但希望检索粒度、索引结构和双路召回更稳定时。",
                "{\"defectQuery\": \"包含病害类型、构件、位置等关键词的描述文本\"}",
                standardRetrieveMarkdownToolV3::execute
        );

        registry.register(
                "normalize_defect",
                "将检测员的自由文本病害描述转为符合 JTG/T H21 规范格式的标准化记录，" +
                        "包含规范化描述、病害等级、规范条款引用和定级依据。" +
                        "适用：信息已经足够完整，需要生成正式病害记录时。" +
                        "不适用：信息仍不完整时，应先追问。",
                "{\"rawDescription\": \"原始描述\", \"supplementInfo\": \"补充信息\", " +
                        "\"bridgeId\": \"桥梁编号\", \"component\": \"具体构件位置\"}",
                defectNormalizeTool::execute
        );

        // ===== 巡检后工具 =====
        registry.register(
                "search_repair_cases",
                "检索历史同类病害的处置方案，包括修复工艺、处置时限等，为本次病害处置提供参考案例。" +
                        "适用：巡检后生成处置建议时，需要参考历史做法。",
                "{\"defectType\": \"病害类型\", \"bridgeType\": \"桥型，可选\", \"grade\": \"病害等级，可选\"}",
                repairCaseTool::execute
        );

        log.info("Tool registry initialized with {} tools", 9);
    }
}
