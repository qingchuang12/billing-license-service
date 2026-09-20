package com.billing.license.controller;

import com.billing.license.dto.MachineFirstSeenDto;
import com.billing.license.service.MachineRegistryService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * C8（2026-09-20）：机器码首次出现时间查询。
 *
 * <p>用于堵住「删掉本地授权存档重装 → 再白嫖一次 60 天试用」：客户端首跑联网问一次，
 * 若服务端早就见过这台机器，就把试用起点回溯到 {@code firstSeenAt}，删档重来拿到的仍是过期试用。
 *
 * <p>公开端点（SecurityConfig 已 permitAll）：只按机器码查时间，不含任何客户信息。
 */
@Tag(name = "机器码账本", description = "机器码首次出现时间（C8 试用防重置），公开只读")
@RestController
@RequestMapping("/api/licenses/machine")
@RequiredArgsConstructor
public class MachineController {

    private final MachineRegistryService machineRegistryService;

    @Operation(summary = "查询机器码首次出现时间",
            description = "返回该机器码首次被服务端见到的时间；未见过则 firstSeenAt 为 null、seen=false")
    @GetMapping("/{machineCode}/first-seen")
    public ResponseEntity<MachineFirstSeenDto> firstSeen(@PathVariable("machineCode") String machineCode) {
        return ResponseEntity.ok(
            MachineFirstSeenDto.of(machineCode, machineRegistryService.firstSeenAt(machineCode).orElse(null)));
    }
}
