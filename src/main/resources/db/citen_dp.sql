SET NAMES utf8mb4;
SET FOREIGN_KEY_CHECKS = 0;

DROP TABLE IF EXISTS `tb_reservation`;
DROP TABLE IF EXISTS `tb_reservation_compensation`;
DROP TABLE IF EXISTS `tb_resource_quota`;
DROP TABLE IF EXISTS `tb_resource`;
DROP TABLE IF EXISTS `tb_lab`;
DROP TABLE IF EXISTS `tb_lab_type`;
DROP TABLE IF EXISTS `tb_user_info`;
DROP TABLE IF EXISTS `tb_user`;

CREATE TABLE `tb_user` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '用户 ID',
  `phone` varchar(11) NOT NULL COMMENT '手机号码',
  `password` varchar(128) NOT NULL DEFAULT '' COMMENT '加密后的密码',
  `nick_name` varchar(32) NOT NULL DEFAULT '' COMMENT '昵称',
  `icon` varchar(255) NOT NULL DEFAULT '' COMMENT '头像地址',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_user_phone` (`phone`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户';

CREATE TABLE `tb_user_info` (
  `user_id` bigint UNSIGNED NOT NULL COMMENT '用户 ID',
  `city` varchar(64) NOT NULL DEFAULT '',
  `introduce` varchar(128) DEFAULT NULL,
  `fans` int UNSIGNED NOT NULL DEFAULT 0,
  `followee` int UNSIGNED NOT NULL DEFAULT 0,
  `gender` tinyint UNSIGNED NOT NULL DEFAULT 0,
  `birthday` date DEFAULT NULL,
  `credits` int UNSIGNED NOT NULL DEFAULT 0,
  `level` tinyint UNSIGNED NOT NULL DEFAULT 0,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`user_id`),
  CONSTRAINT `fk_user_info_user` FOREIGN KEY (`user_id`) REFERENCES `tb_user` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='用户资料';

CREATE TABLE `tb_lab_type` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '类型 ID',
  `name` varchar(64) NOT NULL COMMENT '类型名称',
  `icon` varchar(255) NOT NULL DEFAULT '' COMMENT '图标地址',
  `sort` int NOT NULL DEFAULT 0 COMMENT '排序值',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_lab_type_name` (`name`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实验室类型';

CREATE TABLE `tb_lab` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '实验室 ID',
  `name` varchar(128) NOT NULL COMMENT '实验室名称',
  `lab_type_id` bigint UNSIGNED NOT NULL COMMENT '实验室类型 ID',
  `images` varchar(1024) NOT NULL DEFAULT '' COMMENT '图片地址',
  `area` varchar(128) DEFAULT NULL COMMENT '校区或区域',
  `address` varchar(255) NOT NULL COMMENT '位置',
  `x` double DEFAULT NULL COMMENT '经度',
  `y` double DEFAULT NULL COMMENT '纬度',
  `avg_price` bigint UNSIGNED DEFAULT NULL COMMENT '单位资源使用成本',
  `sold` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '累计预约次数',
  `comments` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '使用记录数',
  `score` int UNSIGNED NOT NULL DEFAULT 0 COMMENT '评分，放大十倍保存',
  `open_hours` varchar(64) DEFAULT NULL COMMENT '开放时间',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_lab_type` (`lab_type_id`),
  CONSTRAINT `fk_lab_type` FOREIGN KEY (`lab_type_id`) REFERENCES `tb_lab_type` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='实验室或算力中心';

CREATE TABLE `tb_resource` (
  `id` bigint UNSIGNED NOT NULL AUTO_INCREMENT COMMENT '资源 ID',
  `lab_id` bigint UNSIGNED NOT NULL COMMENT '所属实验室 ID',
  `name` varchar(255) NOT NULL COMMENT '资源名称',
  `description` varchar(255) DEFAULT NULL COMMENT '资源描述',
  `usage_rules` varchar(1024) DEFAULT NULL COMMENT '使用规则',
  `reserve_value` bigint UNSIGNED NOT NULL DEFAULT 1 COMMENT '一次预约占用额度',
  `confirm_value` bigint NOT NULL DEFAULT 0 COMMENT '确认时记录值',
  `resource_mode` tinyint UNSIGNED NOT NULL DEFAULT 0 COMMENT '资源模式',
  `status` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 可用，2 停用',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_resource_lab_status` (`lab_id`, `status`),
  CONSTRAINT `fk_resource_lab` FOREIGN KEY (`lab_id`) REFERENCES `tb_lab` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='可预约资源';

CREATE TABLE `tb_resource_quota` (
  `resource_id` bigint UNSIGNED NOT NULL COMMENT '资源 ID',
  `quota` int NOT NULL COMMENT '同一时间段最大并发预约数',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `begin_time` datetime NOT NULL COMMENT '可预约开始时间',
  `end_time` datetime NOT NULL COMMENT '可预约结束时间',
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`resource_id`),
  CONSTRAINT `fk_quota_resource` FOREIGN KEY (`resource_id`) REFERENCES `tb_resource` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源预约容量';

CREATE TABLE `tb_reservation` (
  `id` bigint NOT NULL COMMENT '预约 ID',
  `user_id` bigint UNSIGNED NOT NULL COMMENT '用户 ID',
  `resource_id` bigint UNSIGNED NOT NULL COMMENT '资源 ID',
  `reservation_date` date NOT NULL COMMENT '预约日期',
  `start_time` time NOT NULL COMMENT '开始时间',
  `end_time` time NOT NULL COMMENT '结束时间',
  `expire_at` datetime NOT NULL COMMENT '待确认过期时间',
  `timeout_message_sent` tinyint NOT NULL DEFAULT 0 COMMENT 'MQ 超时消息是否已发布确认',
  `reserve_type` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 用户预约，2 管理员代约，3 系统调度',
  `status` tinyint UNSIGNED NOT NULL DEFAULT 1 COMMENT '1 待确认，2 已确认，3 已完成，4 已取消，5 已过期',
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `confirm_time` datetime DEFAULT NULL,
  `complete_time` datetime DEFAULT NULL,
  `cancel_time` datetime DEFAULT NULL,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  KEY `idx_reservation_user_status` (`user_id`, `status`),
  KEY `idx_reservation_resource_time` (`resource_id`, `reservation_date`, `status`, `start_time`, `end_time`),
  CONSTRAINT `fk_reservation_user` FOREIGN KEY (`user_id`) REFERENCES `tb_user` (`id`),
  CONSTRAINT `fk_reservation_resource` FOREIGN KEY (`resource_id`) REFERENCES `tb_resource` (`id`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='资源预约';

CREATE TABLE `tb_reservation_compensation` (
  `id` bigint NOT NULL AUTO_INCREMENT,
  `reservation_id` bigint NOT NULL,
  `user_id` bigint UNSIGNED NOT NULL,
  `resource_id` bigint UNSIGNED NOT NULL,
  `reservation_date` date NOT NULL,
  `start_time` time NOT NULL,
  `end_time` time NOT NULL,
  `compensation_type` varchar(32) NOT NULL,
  `status` tinyint NOT NULL DEFAULT 0 COMMENT '0 待处理，1 已完成',
  `retry_count` int NOT NULL DEFAULT 0,
  `last_error` varchar(512) DEFAULT NULL,
  `create_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP,
  `update_time` datetime NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
  PRIMARY KEY (`id`),
  UNIQUE KEY `uk_reservation_compensation` (`reservation_id`, `compensation_type`),
  KEY `idx_compensation_status` (`status`, `update_time`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COMMENT='预约 Redis 补偿任务';

INSERT INTO `tb_user` (`id`, `phone`, `nick_name`) VALUES
  (1, '13800000001', '测试用户');

INSERT INTO `tb_lab_type` (`id`, `name`, `sort`) VALUES
  (1, '计算机实验室', 1),
  (2, '智能算力中心', 2);

INSERT INTO `tb_lab` (`id`, `name`, `lab_type_id`, `area`, `address`, `x`, `y`, `open_hours`) VALUES
  (1, '第一计算机实验室', 1, '主校区', '实验楼 A201', 104.066541, 30.572269, '08:00-22:00'),
  (2, 'GPU 算力中心', 2, '主校区', '信息楼 B305', 104.067120, 30.571850, '00:00-24:00');

INSERT INTO `tb_resource` (`id`, `lab_id`, `name`, `description`, `usage_rules`, `reserve_value`) VALUES
  (1, 1, '工位 A-01', '带显示器的开发工位', '请按预约时间使用并保持整洁', 1),
  (2, 2, 'GPU 节点 G-01', '教学用 GPU 计算节点', '仅用于课程与科研任务', 1);

INSERT INTO `tb_resource_quota` (`resource_id`, `quota`, `begin_time`, `end_time`) VALUES
  (1, 1, '2026-01-01 00:00:00', '2030-12-31 23:59:59'),
  (2, 1, '2026-01-01 00:00:00', '2030-12-31 23:59:59');

SET FOREIGN_KEY_CHECKS = 1;
