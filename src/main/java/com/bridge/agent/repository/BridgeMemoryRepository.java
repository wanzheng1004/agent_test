package com.bridge.agent.repository;

import com.bridge.agent.entity.BridgeMemoryEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

@Repository
public interface BridgeMemoryRepository extends JpaRepository<BridgeMemoryEntity, String> {}
