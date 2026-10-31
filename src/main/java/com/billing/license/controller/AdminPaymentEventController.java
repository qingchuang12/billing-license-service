package com.billing.license.controller;

import com.billing.license.annotation.Audit;
import com.billing.license.dto.PaymentEventView;
import com.billing.license.repository.PaymentEventRepository;
import com.billing.license.service.payment.PaymentEventReplayService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/** 管理端支付事件日志（Webhook 事件、处理状态、原始 payload 预览）。 */
@Tag(name = "支付事件日志", description = "Webhook 事件与处理状态（需 ROLE_ADMIN）")
@RestController
@RequestMapping("/api/admin/payment-events")
@RequiredArgsConstructor
public class AdminPaymentEventController {

    private static final int MAX_PAGE_SIZE = 200;

    private final PaymentEventRepository paymentEventRepository;
    private final PaymentEventReplayService replayService;

    @Operation(summary = "查询支付事件日志", description = "按渠道/处理状态过滤，按时间倒序分页。")
    @Audit(action = "LIST_PAYMENT_EVENTS", target = "#provider")
    @GetMapping
    public ResponseEntity<Page<PaymentEventView>> events(
            @RequestParam(required = false) String provider,
            @RequestParam(required = false) Boolean processed,
            @RequestParam(required = false, defaultValue = "0") int page,
            @RequestParam(required = false, defaultValue = "20") int size) {
        int safeSize = Math.max(1, Math.min(size, MAX_PAGE_SIZE));
        PageRequest pageable = PageRequest.of(Math.max(0, page), safeSize,
            Sort.by(Sort.Direction.DESC, "createdAt"));
        String normalized = provider == null || provider.isBlank()
            ? null : provider.trim().toUpperCase();
        return ResponseEntity.ok(paymentEventRepository.search(normalized, processed, pageable)
            .map(PaymentEventView::from));
    }

    @Operation(summary = "人工重放支付事件",
            description = "仅允许验签通过且未处理的事件；重放复用 Webhook 的幂等/金额/订单校验。")
    @Audit(action = "REPLAY_PAYMENT_EVENT", target = "#id")
    @PostMapping("/{id}/replay")
    public ResponseEntity<String> replay(@PathVariable UUID id) {
        return replayService.replay(id);
    }
}
