package com.bridge.agent.repository;

import com.bridge.agent.entity.BridgeProfile;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface BridgeProfileRepository extends JpaRepository<BridgeProfile, String> {

    /** 按桥梁名称模糊搜索（用于未提供精确 ID 时） */
    List<BridgeProfile> findByNameContaining(String name);
}
