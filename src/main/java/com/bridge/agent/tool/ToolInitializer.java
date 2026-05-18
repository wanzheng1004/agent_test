package com.bridge.agent.tool;

import com.bridge.agent.core.ToolRegistry;
import com.bridge.agent.core.ToolSensitivity;
import com.bridge.agent.core.ToolSpec;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Registers all bridge-domain tools with runtime governance metadata.
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
    private final StandardRetrieveMarkdownToolV4 standardRetrieveMarkdownToolV4;
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
                           StandardRetrieveMarkdownToolV4 standardRetrieveMarkdownToolV4,
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
        this.standardRetrieveMarkdownToolV4 = standardRetrieveMarkdownToolV4;
        this.defectNormalizeTool = defectNormalizeTool;
        this.repairCaseTool = repairCaseTool;
        this.defectClusterTool = defectClusterTool;
    }

    @PostConstruct
    public void initTools() {
        log.info("Initializing governed tool registry...");

        registry.register(readOnly(
                "query_bridge_profile",
                "Query bridge profile data such as bridge type, build year, load grade and maintainer.",
                "{\"bridgeId\":\"BRG-001\"}",
                "PRE_INSPECTION", "POST_INSPECTION", "GENERAL"),
                bridgeProfileTool::execute);

        registry.register(readOnly(
                "search_defect_history",
                "Search historical defects for a bridge within a time window.",
                "{\"bridgeId\":\"BRG-001\",\"months\":36}",
                "PRE_INSPECTION", "GENERAL"),
                defectHistoryTool::execute);

        registry.register(readOnly(
                "query_repair_records",
                "Query historical repair and reinforcement records for a bridge.",
                "{\"bridgeId\":\"BRG-001\"}",
                "PRE_INSPECTION"),
                repairRecordTool::execute);

        registry.register(readOnly(
                "cluster_defects",
                "Cluster historical defect records and return recurring risk themes.",
                "{\"bridgeId\":\"BRG-001\",\"months\":36}",
                "PRE_INSPECTION"),
                defectClusterTool::execute);

        registry.register(readOnly(
                "retrieve_standard",
                "Retrieve bridge inspection standard clauses for a defect description.",
                "{\"defectQuery\":\"pier vertical crack width 0.3mm seepage\"}",
                "DURING_INSPECTION", "GENERAL"),
                standardRetrieveTool::execute);

        registry.register(readOnly(
                "retrieve_standard_markdown_v2",
                "Lightweight markdown full-text search for bridge inspection standards.",
                "{\"defectQuery\":\"pier crack rating\"}",
                "DURING_INSPECTION", "GENERAL"),
                standardRetrieveMarkdownToolV2::execute);

        registry.register(readOnly(
                "retrieve_standard_markdown_v3",
                "Markdown hybrid search using SQLite FTS, dense retrieval, RRF fusion and reranking.",
                "{\"defectQuery\":\"pier crack rating\"}",
                "DURING_INSPECTION", "GENERAL"),
                standardRetrieveMarkdownToolV3::execute);

        registry.register(readOnly(
                "retrieve_standard_markdown_v4",
                "RAG v4 standard retrieval with citations, trace and evaluation-ready metadata.",
                "{\"defectQuery\":\"pier crack rating\"}",
                "DURING_INSPECTION", "GENERAL"),
                standardRetrieveMarkdownToolV4::execute);

        registry.register(ToolSpec.write(
                        "normalize_defect",
                        "Normalize free-form inspection notes into a structured defect record.",
                        "{\"rawDescription\":\"text\",\"supplementInfo\":\"text\",\"bridgeId\":\"BRG-001\",\"component\":\"0# pier\"}")
                .withAllowedScenes("DURING_INSPECTION")
                .withTimeout(Duration.ofSeconds(30))
                .withSensitivity(ToolSensitivity.WRITE)
                .withApproval(true),
                defectNormalizeTool::execute);

        registry.register(readOnly(
                "search_repair_cases",
                "Search similar repair cases and treatment suggestions for inspected defects.",
                "{\"defectType\":\"crack\",\"bridgeType\":\"girder\",\"grade\":3}",
                "POST_INSPECTION"),
                repairCaseTool::execute);

        log.info("Tool registry initialized with {} governed tools", registry.getTools().size());
    }

    private ToolSpec readOnly(String name, String description, String schema, String... scenes) {
        return ToolSpec.readOnly(name, description, schema)
                .withAllowedScenes(scenes)
                .withTimeout(Duration.ofSeconds(20));
    }
}
