-- ====================================================
-- 桥梁智能检测助手 Agent — 数据库建表脚本
-- ====================================================

CREATE DATABASE IF NOT EXISTS bridge_mgmt
    CHARACTER SET utf8mb4
    COLLATE utf8mb4_unicode_ci;

USE bridge_mgmt;

-- ===================================================
-- 桥梁基础档案表
-- ===================================================
CREATE TABLE IF NOT EXISTS bridge_profile (
    bridge_id       VARCHAR(50)  PRIMARY KEY COMMENT '桥梁编号（如 BRG-001）',
    name            VARCHAR(200) NOT NULL COMMENT '桥梁名称',
    bridge_type     VARCHAR(50)  COMMENT '桥型：梁桥/拱桥/斜拉桥/悬索桥',
    build_year      INT          COMMENT '建造年份',
    design_life     INT          COMMENT '设计使用年限（年）',
    span_desc       VARCHAR(200) COMMENT '跨径组合描述，如：3×20m',
    load_grade      VARCHAR(20)  COMMENT '荷载等级，如：公路-I级',
    road_grade      VARCHAR(20)  COMMENT '公路等级，如：二级公路',
    width           DECIMAL(6,2) COMMENT '桥面宽度（m）',
    total_length    DECIMAL(8,2) COMMENT '桥梁全长（m）',
    location_desc   VARCHAR(500) COMMENT '位置描述',
    gps_lat         DECIMAL(10,7) COMMENT '纬度',
    gps_lng         DECIMAL(10,7) COMMENT '经度',
    components      JSON         COMMENT '构件清单 [{id, name, type, count}]',
    admin_unit      VARCHAR(100) COMMENT '管养单位',
    inspector       VARCHAR(100) COMMENT '责任工程师',
    created_at      DATETIME     DEFAULT NOW(),
    updated_at      DATETIME     DEFAULT NOW() ON UPDATE NOW()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='桥梁基础档案';

-- ===================================================
-- 病害记录表
-- ===================================================
CREATE TABLE IF NOT EXISTS defect_record (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    bridge_id       VARCHAR(50)  NOT NULL COMMENT '桥梁编号',
    session_id      VARCHAR(100) COMMENT '检测会话 ID（关联 Redis 会话）',
    inspection_date DATE         NOT NULL COMMENT '检测日期',
    component       VARCHAR(100) COMMENT '病害构件，如：0#桥墩墩柱',
    defect_type     VARCHAR(50)  COMMENT '病害类型：裂缝/变形/破损/腐蚀/其他',
    description     TEXT         COMMENT '规范化病害描述（由 Agent 生成）',
    grade           TINYINT      COMMENT '病害等级 1-4（参考 JTG/T H21）',
    standard_ref    VARCHAR(100) COMMENT '规范条款引用，如：JTG/T H21-2011 第6.3.2条',
    grade_reason    TEXT         COMMENT '定级依据说明',
    urgency         VARCHAR(20)  COMMENT '处置紧迫度：立即处置/限期处置/持续观察',
    raw_description TEXT         COMMENT '检测员原始输入（存档用）',
    photos          JSON         COMMENT '照片 URL 列表',
    created_by      VARCHAR(50)  COMMENT '操作人员',
    created_at      DATETIME     DEFAULT NOW(),
    INDEX idx_bridge_date (bridge_id, inspection_date),
    INDEX idx_session    (session_id),
    INDEX idx_bridge_type (bridge_id, defect_type),
    FULLTEXT INDEX ft_defect (description, defect_type, component)
        WITH PARSER ngram     -- 中文分词支持，依赖 MySQL 参数 ngram_token_size=2
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='病害记录';

-- ===================================================
-- 维修记录表
-- ===================================================
CREATE TABLE IF NOT EXISTS repair_record (
    id              BIGINT       AUTO_INCREMENT PRIMARY KEY,
    bridge_id       VARCHAR(50)  NOT NULL COMMENT '桥梁编号',
    repair_date     DATE         NOT NULL COMMENT '维修日期',
    repair_type     VARCHAR(50)  COMMENT '维修类型：大修/加固/日常养护/应急处置',
    component       VARCHAR(100) COMMENT '维修构件',
    description     TEXT         COMMENT '维修内容描述',
    repair_method   VARCHAR(200) COMMENT '修复工艺，如：环氧树脂压力注浆',
    contractor      VARCHAR(200) COMMENT '施工单位',
    cost            DECIMAL(12,2) COMMENT '维修费用（元）',
    result          VARCHAR(50)  COMMENT '维修结果：完工/持续中',
    created_at      DATETIME     DEFAULT NOW(),
    INDEX idx_bridge (bridge_id),
    INDEX idx_bridge_date (bridge_id, repair_date)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='维修加固记录';

-- ===================================================
-- 桥梁级记忆表（三级记忆架构 — 桥梁级）
-- 存储历次检测结论和持续追踪病害，支持跨时间维度追溯
-- ===================================================
CREATE TABLE IF NOT EXISTS bridge_memory (
    bridge_id           VARCHAR(50)  PRIMARY KEY COMMENT '桥梁编号',
    last_inspection     DATE         COMMENT '最近一次检测日期',
    health_score        DECIMAL(5,2) COMMENT '综合健康评分 0-100',
    tracking_defects    JSON COMMENT '持续追踪病害列表，含发展数据点（见下方注释）',
    inspection_history  JSON COMMENT '历次检测摘要，保留最近5次',
    updated_at          DATETIME     DEFAULT NOW() ON UPDATE NOW()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='桥梁级持久化记忆';
/*
tracking_defects 字段结构:
[
  {
    "defectId": "D-2024-001",
    "type": "竖向裂缝",
    "component": "0#桥墩墩柱",
    "firstFound": "2022-03",
    "history": [
      {"date": "2022-03", "width": 0.1, "grade": 1},
      {"date": "2024-08", "width": 0.3, "grade": 3}
    ],
    "trend": "扩展中"
  }
]

inspection_history 字段结构:
[
  {
    "date": "2024-08-15",
    "sessionId": "sess-xxx",
    "defectCount": 5,
    "maxGrade": 3,
    "summary": "发现3类病害1处，需重点关注0#桥墩裂缝"
  }
]
*/

-- ===================================================
-- 用户偏好表（三级记忆架构 — 用户级）
-- ===================================================
CREATE TABLE IF NOT EXISTS user_preference (
    user_id             VARCHAR(50)  PRIMARY KEY COMMENT '用户ID',
    preferred_bridges   JSON         COMMENT '常用桥梁列表',
    output_format       VARCHAR(50)  DEFAULT 'standard' COMMENT '偏好输出格式',
    terminology         JSON         COMMENT '用户惯用术语映射',
    created_at          DATETIME     DEFAULT NOW(),
    updated_at          DATETIME     DEFAULT NOW() ON UPDATE NOW()
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户偏好（用户级记忆）';

-- ===================================================
-- 测试数据
-- ===================================================
INSERT INTO bridge_profile (bridge_id, name, bridge_type, build_year, design_life,
    span_desc, load_grade, road_grade, width, total_length, location_desc, admin_unit)
VALUES
('BRG-001', '沿江大桥', '连续梁桥', 2005, 100,
 '3×30m+5×40m+3×30m', '公路-I级', '国道', 18.5, 360.0,
 'XX省XX市沿江路与滨河路交叉口', 'XX市公路局'),
('BRG-002', '南山立交桥', '预应力混凝土梁桥', 2010, 100,
 '2×25m+4×30m', '公路-II级', '省道', 12.0, 170.0,
 'XX省XX市南山路与环城路交叉口', 'XX市公路局'),
('BRG-003', '溪口小桥', '简支梁桥', 1998, 50,
 '3×15m', '公路-II级', '县道', 8.0, 48.0,
 'XX省XX县溪口镇国道K135+200', 'XX县交通局');

INSERT INTO defect_record (bridge_id, session_id, inspection_date, component,
    defect_type, description, grade, standard_ref, grade_reason, urgency)
VALUES
('BRG-001', 'sess-2024-001', '2024-08-15', '0#桥墩墩柱',
 '裂缝',
 '0#桥墩北侧墩柱距墩顶1.2m处发现竖向裂缝，裂缝长约20cm，最大缝宽0.3mm，裂缝贯通，缝内有渗水痕迹。',
 3, 'JTG/T H21-2011 第6.3.2条',
 '缝宽0.3mm超过0.2mm限值，且存在渗水，定为3类。',
 '限期处置'),
('BRG-001', 'sess-2024-001', '2024-08-15', '桥面铺装',
 '坑槽',
 '第3跨桥面铺装发现坑槽，面积约0.8㎡，深度3-5cm，坑槽内有积水。',
 2, 'JTG/T H21-2011 第8.2.1条',
 '坑槽面积超过0.5㎡，深度超过2cm，定为2类。',
 '限期处置'),
('BRG-001', 'sess-2023-001', '2023-05-20', '0#桥墩墩柱',
 '裂缝',
 '0#桥墩北侧墩柱距墩顶1.0m处发现竖向裂缝，裂缝长约15cm，最大缝宽0.1mm，无渗水。',
 1, 'JTG/T H21-2011 第6.3.2条',
 '缝宽0.1mm未超过限值，定为1类。',
 '持续观察'),
('BRG-002', 'sess-2024-002', '2024-06-10', '主梁',
 '裂缝',
 '第2跨主梁下翼缘发现横向裂缝，裂缝长约8cm，最大缝宽0.15mm，无渗水。',
 2, 'JTG/T H21-2011 第6.3.1条',
 '横向裂缝，缝宽0.15mm，定为2类。',
 '持续观察');

INSERT INTO repair_record (bridge_id, repair_date, repair_type, component,
    description, repair_method, contractor, cost, result)
VALUES
('BRG-001', '2022-10-15', '日常养护', '桥面铺装',
 '桥面坑槽修复，面积约2㎡', '热拌沥青混合料填充压实',
 'XX路桥养护公司', 8500.00, '完工'),
('BRG-001', '2021-06-20', '加固', '支座',
 '更换全桥板式橡胶支座12个', '更换盆式橡胶支座',
 'XX桥梁工程公司', 125000.00, '完工'),
('BRG-002', '2023-09-05', '大修', '主梁',
 '主梁裂缝封闭处理', '环氧树脂注浆修复',
 'XX桥梁工程公司', 45000.00, '完工');

INSERT INTO bridge_memory (bridge_id, last_inspection, health_score,
    tracking_defects, inspection_history)
VALUES
('BRG-001', '2024-08-15', 72.5,
 '[{"defectId":"D-2024-001","type":"竖向裂缝","component":"0#桥墩墩柱","firstFound":"2023-05","history":[{"date":"2023-05","width":0.1,"grade":1},{"date":"2024-08","width":0.3,"grade":3}],"trend":"扩展中"}]',
 '[{"date":"2024-08-15","sessionId":"sess-2024-001","defectCount":2,"maxGrade":3,"summary":"发现3类裂缝1处，桥墩北侧病害持续扩展"},{"date":"2023-05-20","sessionId":"sess-2023-001","defectCount":1,"maxGrade":1,"summary":"桥墩轻微裂缝，持续观察"}]');
