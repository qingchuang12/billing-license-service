package com.billing.license.service;

import com.billing.license.entity.MachineFirstSeen;
import com.billing.license.repository.MachineFirstSeenRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * C8（2026-09-20）：机器码首次出现账本。
 *
 * <p>核心不变量：**first_seen_at 只写一次、永不更新**——它是「这台机器最早何时来过」的权威值，
 * 一旦被后续访问改写，删档重装就能把时间刷新成现在，防重置立刻失效。
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MachineRegistryServiceTest {

    private static final String MID = "5E01-7EB8-3661-E06A";

    @Mock private MachineFirstSeenRepository repository;

    private MachineRegistryService service;

    @BeforeEach
    void setUp() {
        service = new MachineRegistryService(repository);
        when(repository.save(any(MachineFirstSeen.class)))
            .thenAnswer(inv -> inv.getArgument(0));
    }

    @Test
    void touch_firstTime_recordsFirstSeen() {
        when(repository.findByMachineCode(MID)).thenReturn(Optional.empty());

        Optional<LocalDateTime> firstSeen = service.touch(MID, MachineRegistryService.SRC_REDEEM);

        assertTrue(firstSeen.isPresent());
        ArgumentCaptor<MachineFirstSeen> captor = ArgumentCaptor.forClass(MachineFirstSeen.class);
        verify(repository).save(captor.capture());
        assertEquals(MID, captor.getValue().getMachineCode());
        assertEquals(MachineRegistryService.SRC_REDEEM, captor.getValue().getSource());
        assertNotNull(captor.getValue().getFirstSeenAt());
    }

    /** 第二次见到：只刷新 last_seen_at，first_seen_at 必须原样不动 */
    @Test
    void touch_again_doesNotMoveFirstSeen() {
        LocalDateTime original = LocalDateTime.now().minusDays(200);
        when(repository.findByMachineCode(MID)).thenReturn(Optional.of(
            MachineFirstSeen.firstTime(MID, original, MachineRegistryService.SRC_REDEEM)));

        Optional<LocalDateTime> firstSeen = service.touch(MID, MachineRegistryService.SRC_PURCHASE);

        assertEquals(original, firstSeen.orElseThrow(), "first_seen_at 必须保持首次值");
        verify(repository, never()).save(any(MachineFirstSeen.class));
        verify(repository).touchLastSeen(eq(MID), any(LocalDateTime.class));
    }

    @Test
    void touch_blankMachineCode_isIgnored() {
        assertTrue(service.touch(null, MachineRegistryService.SRC_REDEEM).isEmpty());
        assertTrue(service.touch("   ", MachineRegistryService.SRC_REDEEM).isEmpty());
        verifyNoInteractions(repository);
    }

    @Test
    void firstSeenAt_readOnlyDoesNotRegister() {
        LocalDateTime seen = LocalDateTime.now().minusDays(10);
        when(repository.findByMachineCode(MID)).thenReturn(Optional.of(
            MachineFirstSeen.firstTime(MID, seen, MachineRegistryService.SRC_PROBE)));

        assertEquals(seen, service.firstSeenAt(MID).orElseThrow());
        verify(repository, never()).save(any(MachineFirstSeen.class));
        verify(repository, never()).touchLastSeen(anyString(), any());
    }

    @Test
    void firstSeenAt_unknownMachine_returnsEmpty() {
        when(repository.findByMachineCode(MID)).thenReturn(Optional.empty());
        assertTrue(service.firstSeenAt(MID).isEmpty(), "从未见过的机器应返回空（客户端按全新试用处理）");
    }
}
