package com.billing.license.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;

/**
 * 操作审计日志服务（H11）。
 *
 * 管理后台的敏感操作（退款、作废 License、换机重发等）必须留痕：
 * 记录「谁（密钥哈希，不记明文）、做了什么、作用于谁、结果如何、时间」。
 * 通过独立命名 logger "AUDIT" 输出，便于接入独立日志管道 / 合规留存，
 * 与业务日志（com.billing.license）分离。
 */
@Service
public class AuditLogService {

    private static final Logger auditLog = LoggerFactory.getLogger("AUDIT");

    /**
     * 记录一条管理操作审计
     *
     * @param actorKeyHash 操作者管理员密钥的 SHA-256 哈希前缀（不记明文密钥）
     * @param action       操作类型（如 REFUND_ORDER / REVOKE_LICENSE / REISSUE_LICENSE / LIST_LICENSES）
     * @param target       作用对象（如订单号、License Key）
     * @param success      操作是否成功
     * @param detail       补充信息（如失败原因、退款渠道返回）
     */
    public void audit(String actorKeyHash, String action, String target, boolean success, String detail) {
        String ts = LocalDateTime.now().toString();
        // 结构化单行，便于日志采集与检索
        auditLog.info("AUDIT ts={} actor={} action={} target={} result={} detail={}",
            ts, actorKeyHash, action, target, success ? "SUCCESS" : "FAIL", detail != null ? detail : "");
    }
}
