package com.bridge.agent.controller;

import com.bridge.agent.entity.UserPreference;
import com.bridge.agent.memory.UserPreferenceService;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 用户偏好 API（三级记忆架构 — 用户级读写接口）
 */
@RestController
@RequestMapping("/api/user/{userId}/preference")
public class UserPreferenceController {

    private final UserPreferenceService service;

    public UserPreferenceController(UserPreferenceService service) {
        this.service = service;
    }

    /**
     * 获取用户偏好（含常用桥梁列表）
     * GET /api/user/{userId}/preference
     */
    @GetMapping
    public UserPreference getPreference(@PathVariable String userId) {
        return service.getOrCreate(userId);
    }

    /**
     * 获取常用桥梁列表（前端首页快速访问）
     * GET /api/user/{userId}/preference/bridges
     */
    @GetMapping("/bridges")
    public List<String> getPreferredBridges(@PathVariable String userId) {
        return service.getPreferredBridges(userId);
    }

    /**
     * 添加常用桥梁
     * POST /api/user/{userId}/preference/bridges/{bridgeId}
     */
    @PostMapping("/bridges/{bridgeId}")
    public void addPreferredBridge(@PathVariable String userId,
                                    @PathVariable String bridgeId) {
        service.addPreferredBridge(userId, bridgeId);
    }

    /**
     * 设置偏好输出格式
     * PUT /api/user/{userId}/preference/format
     */
    @PutMapping("/format")
    public void setOutputFormat(@PathVariable String userId,
                                 @RequestParam String format) {
        service.setOutputFormat(userId, format);
    }

    /**
     * 添加术语映射（如：裂纹 → 裂缝）
     * POST /api/user/{userId}/preference/terminology
     */
    @PostMapping("/terminology")
    public void addTerminology(@PathVariable String userId,
                                @RequestParam String from,
                                @RequestParam String to) {
        service.addTerminologyMapping(userId, from, to);
    }
}
