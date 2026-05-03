package com.bridge.agent.controller;

import com.bridge.agent.rag.ingest.StandardDocIngester;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

/**
 * 规范文档入库 API
 *
 * <p>管理员通过此接口上传桥梁规范 PDF/Word，系统自动解析并写入 Qdrant。
 */
@RestController
@RequestMapping("/api/ingest")
public class IngestController {

    private static final Logger log = LoggerFactory.getLogger(IngestController.class);

    private final StandardDocIngester ingester;

    public IngestController(StandardDocIngester ingester) {
        this.ingester = ingester;
    }

    /**
     * 上传并入库规范文档
     *
     * <p>POST /api/ingest/standard
     * Form-data: file=<PDF/Word>, standardId=JTG/T H21-2011
     */
    @PostMapping("/standard")
    public IngestResult ingestStandard(
            @RequestParam("file") MultipartFile file,
            @RequestParam("standardId") String standardId) {

        log.info("Ingest request: file={}, standardId={}, size={}KB",
                file.getOriginalFilename(), standardId, file.getSize() / 1024);

        try {
            Resource resource = file.getResource();
            int chunkCount = ingester.ingest(resource, standardId);
            return new IngestResult(true, chunkCount,
                    "成功入库 " + chunkCount + " 个条款切片");
        } catch (Exception e) {
            log.error("Ingest failed: {}", e.getMessage());
            return new IngestResult(false, 0, "入库失败：" + e.getMessage());
        }
    }

    public record IngestResult(boolean success, int chunkCount, String message) {}
}
