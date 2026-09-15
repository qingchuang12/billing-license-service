package com.billing.license.repository;

import com.billing.license.entity.VerificationCode;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

/**
 * 邮箱验证码仓储。
 *
 * <p>{@code @Modifying} 方法要求调用方处于事务中（服务层标注 {@code @Transactional}）。
 */
@Repository
public interface VerificationCodeRepository extends JpaRepository<VerificationCode, UUID> {

    /**
     * 取该邮箱 + 用途下所有未消费且未过期的码。
     * 正常至多一条（签发新码前会作废旧的），返回 List 是为便于服务层对异常并存情形做兜底处理。
     */
    @Query("SELECT v FROM VerificationCode v WHERE v.email = :email AND v.purpose = :purpose "
        + "AND v.consumedAt IS NULL AND v.expiresAt > :now ORDER BY v.createdAt DESC")
    List<VerificationCode> findUsable(@Param("email") String email,
                                      @Param("purpose") VerificationCode.CodePurpose purpose,
                                      @Param("now") LocalDateTime now);

    /**
     * 取该邮箱 + 用途下**未消费**的码（含已过期），用于把「验证码错误」与「验证码已过期」
     * 区分开——{@link #findUsable} 会滤掉过期码，失败时无从判断用户是输错还是来晚了。
     */
    @Query("SELECT v FROM VerificationCode v WHERE v.email = :email AND v.purpose = :purpose "
        + "AND v.consumedAt IS NULL ORDER BY v.createdAt DESC")
    List<VerificationCode> findUnconsumed(@Param("email") String email,
                                          @Param("purpose") VerificationCode.CodePurpose purpose);

    /**
     * 签发新码前作废同邮箱同用途的旧未消费码，避免多码并存被撞库。
     *
     * @return 被作废的条数
     */
    @Modifying
    @Query("UPDATE VerificationCode v SET v.consumedAt = :now WHERE v.email = :email "
        + "AND v.purpose = :purpose AND v.consumedAt IS NULL")
    int invalidateUnused(@Param("email") String email,
                         @Param("purpose") VerificationCode.CodePurpose purpose,
                         @Param("now") LocalDateTime now);

    /**
     * 清理已过期或已消费的历史码（供定时任务调用，避免表无限增长）。
     *
     * @param cutoff 早于该时刻的过期/消费记录将被删除
     * @return 删除条数
     */
    @Modifying
    @Query("DELETE FROM VerificationCode v WHERE v.expiresAt < :cutoff OR v.consumedAt < :cutoff")
    int purgeBefore(@Param("cutoff") LocalDateTime cutoff);
}
