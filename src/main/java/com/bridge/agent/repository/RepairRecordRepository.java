package com.bridge.agent.repository;

import com.bridge.agent.entity.RepairRecord;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface RepairRecordRepository extends JpaRepository<RepairRecord, Long> {

    /** 查询指定桥梁的所有维修记录，按日期倒序 */
    List<RepairRecord> findByBridgeIdOrderByRepairDateDesc(String bridgeId);
}
