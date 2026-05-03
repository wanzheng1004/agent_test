package com.bridge.agent.entity;

import jakarta.persistence.*;
import lombok.Data;

/**
 * 桥梁基础档案实体
 */
@Data
@Entity
@Table(name = "bridge_profile")
public class BridgeProfile {

    @Id
    @Column(name = "bridge_id", length = 50)
    private String bridgeId;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "bridge_type", length = 50)
    private String bridgeType;

    @Column(name = "build_year")
    private Integer buildYear;

    @Column(name = "design_life")
    private Integer designLife;   // 设计使用年限（年）

    @Column(name = "span_desc", length = 200)
    private String spanDesc;

    @Column(name = "load_grade", length = 20)
    private String loadGrade;

    @Column(name = "road_grade", length = 20)
    private String roadGrade;

    @Column(precision = 6, scale = 2)
    private Double width;         // 桥面宽度（m）

    @Column(name = "total_length", precision = 8, scale = 2)
    private Double totalLength;   // 全长（m）

    @Column(name = "location_desc", length = 500)
    private String locationDesc;

    @Column(name = "gps_lat", precision = 10, scale = 7)
    private Double gpsLat;

    @Column(name = "gps_lng", precision = 10, scale = 7)
    private Double gpsLng;

    @Column(columnDefinition = "JSON")
    private String components;    // 构件清单 JSON

    @Column(name = "admin_unit", length = 100)
    private String adminUnit;

    @Column(length = 100)
    private String inspector;     // 责任工程师

    /** 计算当前已使用年限 */
    @Transient
    public int getUsedYears() {
        if (buildYear == null) return 0;
        return java.time.Year.now().getValue() - buildYear;
    }

    /** 计算剩余设计使用年限 */
    @Transient
    public int getRemainingLife() {
        if (designLife == null || buildYear == null) return -1;
        return designLife - getUsedYears();
    }
}
