package com.fundradar.core.prediction;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.integration.ai.AiServiceProperties;
import com.fundradar.core.integration.ai.AiSyncJobStatus;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** 不启动服务或真实任务，核对自动入口移除、内部认证和旧记录兼容。 */
class MultiPredictionSyncBoundaryTests {
    @Test
    void pipelineHasNoScheduledPredictionEntry() {
        assertTrue(Arrays.stream(MultiPredictionPipeline.class.getDeclaredMethods())
                .noneMatch(method -> method.isAnnotationPresent(Scheduled.class)));
    }

    @Test
    void authenticatedManualFinalizeAndSavedDatesRemainReachable() {
        var pipeline = mock(MultiPredictionPipeline.class);
        var properties = new AiServiceProperties();
        properties.setToken("isolated-test-token");
        var controller = new InternalMultiPredictionController(pipeline, properties, mock(AutoModelService.class));
        var request = new MockHttpServletRequest();
        assertThrows(AccessDeniedException.class, () -> controller.savedResults(request));
        request.addHeader("X-Service-Token", "isolated-test-token");
        var saved = List.<Map<String, Object>>of(Map.of("horizonId", "T1", "targetDate", "2026-09-30", "count", 2));
        when(pipeline.savedResults()).thenReturn(saved);
        assertEquals(saved, controller.savedResults(request));
        var id = UUID.randomUUID();
        when(pipeline.archive(id, true)).thenReturn(Map.of("failed", 0));
        assertEquals(0, controller.finish(request, Map.of("taskId", id.toString())).get("failed"));
        verify(pipeline).archive(id, true);
        request.addHeader("Origin", "https://example.test");
        assertThrows(AccessDeniedException.class, () -> controller.savedResults(request));
    }

    @Test
    void oldSyncRecordDoesNotInventResultSummary() throws Exception {
        var mapper = new ObjectMapper().findAndRegisterModules();
        var old = mapper.readValue("{\"status\":\"PARTIAL_SUCCESS\",\"job_type\":\"MULTI_PREDICTIONS\"}", AiSyncJobStatus.class);
        assertNull(old.resultSummary());
        var current = mapper.readValue("{\"status\":\"PARTIAL_SUCCESS\",\"result_summary\":{\"dailyUpdated\":true,\"historicalGapCount\":2}}", AiSyncJobStatus.class);
        assertEquals(2, current.resultSummary().get("historicalGapCount"));
    }
}
