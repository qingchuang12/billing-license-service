package com.billing.license.service.risk;

import com.billing.license.config.BillingProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 风控频控服务（架构十七）
 *
 * 覆盖：同一邮箱大量购买、同一机器码频繁换机、同一 IP 高频兑换、兑换码暴力猜测、
 * 单 License 重发次数上限。采用内存滑动窗口计数（适合单机 exe 配套服务；
 * 多实例部署时可替换为 Redis 等共享存储，接口保持不变）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RateLimitService {

    private final BillingProperties billingProperties;

    /** key -> 时间戳环形缓冲（限制窗口内的事件数） */
    private final Map<String, TimestampRing> windows = new ConcurrentHashMap<>();

    /**
     * 检查某 key 在窗口内的事件数是否超过 max；
     * 超过则抛出异常（由调用方捕获转为 BusinessException）。
     *
     * @param namespace 业务命名空间（如 "email-purchase"）
     * @param key       被限流主体（如邮箱、IP、机器码）
     * @param max       窗口内允许的最大事件数
     * @param windowMinutes 窗口分钟数
     * @return 当前窗口内已计数（含本次）
     */
    public int checkAndCount(String namespace, String key, int max, int windowMinutes) {
        if (key == null || key.isEmpty()) {
            return 0;
        }
        String composite = namespace + ":" + key.toLowerCase();
        long now = Instant.now().toEpochMilli();
        long windowMillis = (long) windowMinutes * 60_000L;

        TimestampRing ring = windows.computeIfAbsent(composite, k -> new TimestampRing(max));
        int count = ring.record(now, windowMillis);

        if (count > max) {
            log.warn("风控触发：namespace={}, key={}, count={}, max={}", namespace, key, count, max);
            throw new RateLimitExceededException(namespace, key, count, max);
        }
        return count;
    }

    /**
     * 仅计数（不抛异常），用于记录兑换失败次数。
     */
    public int countOnly(String namespace, String key, int windowMinutes) {
        if (key == null || key.isEmpty()) return 0;
        String composite = namespace + ":" + key.toLowerCase();
        long now = Instant.now().toEpochMilli();
        long windowMillis = (long) windowMinutes * 60_000L;
        TimestampRing ring = windows.computeIfAbsent(composite, k -> new TimestampRing(Integer.MAX_VALUE));
        return ring.record(now, windowMillis);
    }

    // ============ 业务封装方法 ============

    public int checkEmailPurchase(String email) {
        BillingProperties.Risk risk = billingProperties.getRisk();
        return checkAndCount("email-purchase", email, risk.getEmailPurchaseMax(), risk.getEmailPurchaseWindowMinutes());
    }

    public int checkMachineReissue(String machineCode) {
        BillingProperties.Risk risk = billingProperties.getRisk();
        return checkAndCount("machine-reissue", machineCode, risk.getMachineReissueMax(), risk.getMachineReissueWindowMinutes());
    }

    public int checkRedeemIp(String ip) {
        BillingProperties.Risk risk = billingProperties.getRisk();
        return checkAndCount("redeem-ip", ip, risk.getRedeemIpMax(), risk.getRedeemIpWindowMinutes());
    }

    public int recordRedeemFailure(String ip) {
        BillingProperties.Risk risk = billingProperties.getRisk();
        int count = countOnly("redeem-fail", ip, risk.getRedeemFailureWindowMinutes());
        if (count > risk.getRedeemFailureMax()) {
            log.warn("风控触发：兑换暴力猜测 namespace=redeem-fail, ip={}, count={}", ip, count);
            throw new RateLimitExceededException("redeem-fail", ip, count, risk.getRedeemFailureMax());
        }
        return count;
    }

    /** 限流触发异常 */
    public static class RateLimitExceededException extends RuntimeException {
        public RateLimitExceededException(String namespace, String key, int count, int max) {
            super("Rate limit exceeded: " + namespace + " key=" + key + " count=" + count + " max=" + max);
        }
    }

    /**
     * 滑动窗口环形计数：保留窗口内的时间戳，统计窗口内事件数。
     */
    private static class TimestampRing {
        private final java.util.concurrent.atomic.AtomicLongArray stamps;
        private final AtomicLong idx = new AtomicLong(0);

        TimestampRing(int capacity) {
            // 容量+1 作为环形缓冲大小（允许瞬间峰值稍大于 max 再被 check 拦下）
            this.stamps = new java.util.concurrent.atomic.AtomicLongArray(Math.max(8, capacity + 1));
        }

        int record(long now, long windowMillis) {
            long pos = idx.getAndIncrement() % stamps.length();
            stamps.set((int) pos, now);
            // 统计窗口内（now - windowMillis, now] 的时间戳数量
            int count = 0;
            long lower = now - windowMillis;
            for (int i = 0; i < stamps.length(); i++) {
                long t = stamps.get(i);
                if (t > lower) count++;
            }
            return count;
        }
    }
}
