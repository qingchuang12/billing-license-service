package com.billing.license.controller;

// ============================================================================
// I2（2026-09-14）接口合并简化：本控制器已整体下线，订单查询统一由 AdminController 提供。
//
// 端点迁移映射：
//   GET /api/orders/{orderId}             → GET /api/admin/orders?orderId={uuid}
//   GET /api/orders/number/{orderNumber}  → GET /api/admin/orders?orderNumber={number}
//
// 下线原因：与 /api/admin/orders（列表 + 过滤）职责重叠——同一资源存在 by-id / by-number
// 两个键，同一列表存在"全量 / 按状态"两个端点；对外部客户端查询支付状态请使用
// GET /api/checkout/{checkoutId}/status。
//
// 本文件不含任何类型声明（不产生 class、不暴露端点），待物理删除：
//   Remove-Item 'd:\workspace\billing-license-service\src\main\java\com\billing\license\controller\OrderController.java'
// ============================================================================
