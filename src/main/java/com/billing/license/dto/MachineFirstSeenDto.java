package com.billing.license.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;

/**
 * C8（2026-09-20）：机器码首次出现时间响应。
 *
 * <p>{@code firstSeenAt == null} 表示服务端从没见过这台机器 —— 客户端按全新试用处理。
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "机器码首次被服务端见到的时间；null 表示从未见过")
public record MachineFirstSeenDto(
        @Schema(description = "机器码", example = "5E01-7EB8-3661-E06A")
        String machineCode,

        @Schema(description = "首次见到时间（ISO-8601）；null = 从未见过", example = "2026-09-19T22:10:33")
        LocalDateTime firstSeenAt,

        @Schema(description = "是否见过（firstSeenAt != null 的便捷冗余字段）", example = "true")
        boolean seen
) {
    public static MachineFirstSeenDto of(String machineCode, LocalDateTime firstSeenAt) {
        return new MachineFirstSeenDto(machineCode, firstSeenAt, firstSeenAt != null);
    }
}
