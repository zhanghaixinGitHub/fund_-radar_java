package com.fundradar.core.direction1d;

import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.integration.ai.AiServiceProperties;
import com.fundradar.core.prediction.*;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HeldFundSyncTests {
    @Test void holdingsArePagedForExplicitOwnerAndBrowserOriginIsRejected() {
        var batch=mock(Direction1dBatchService.class);
        var properties=new AiServiceProperties();properties.setToken("test-token");
        var controller=new InternalDirection1dSyncController(batch,properties);
        UUID owner=UUID.randomUUID();
        when(batch.heldFundCodes("123456",owner)).thenReturn(List.of("654321"));
        var request=new MockHttpServletRequest();request.addHeader("X-Service-Token","test-token");
        assertEquals(List.of("654321"),controller.heldFundCodes(request,owner,"123456"));
        verify(batch,never()).fundCodes(anyString(),any());
        request.addHeader("Origin","http://localhost");
        assertThrows(AccessDeniedException.class,()->controller.heldFundCodes(request,owner,""));
    }
    @Test void archiveRejectsWrongTokenWithoutReadingOrLinkingAnyPrediction() {
        var service=mock(MultiPredictionService.class);
        var properties=new AiServiceProperties();properties.setToken("test-token");
        var controller=new InternalHeldPredictionController(service,properties);
        assertThrows(AccessDeniedException.class,()->controller.archive(new MockHttpServletRequest(),UUID.randomUUID(),"123456",
                new InternalHeldPredictionController.Request(UUID.randomUUID(),java.time.LocalDate.of(2027,1,4))));
        verifyNoInteractions(service);
    }
    @Test void publicWatchlistCannotAuthorizeHeldArchive() {
        var repo=mock(Direction1dRepository.class);
        var client=mock(MultiPredictionClient.class);
        var service=new MultiPredictionService(client,repo,null,new com.fasterxml.jackson.databind.ObjectMapper());
        UUID owner=UUID.randomUUID();when(repo.predictionEligible(owner,"123456")).thenReturn(true);
        when(repo.holds(owner,"123456")).thenReturn(false);
        assertThrows(NoSuchElementException.class,()->service.archiveHeld(owner,"123456",UUID.randomUUID(),java.time.LocalDate.of(2027,1,4)));
        verifyNoInteractions(client);
    }
}
