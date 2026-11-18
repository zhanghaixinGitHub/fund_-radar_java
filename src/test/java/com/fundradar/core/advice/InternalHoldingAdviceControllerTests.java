package com.fundradar.core.advice;

import com.fundradar.core.integration.ai.AiServiceProperties;
import com.fundradar.core.auth.service.AccessDeniedException;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class InternalHoldingAdviceControllerTests {
    @Test void currentReadRequiresServiceTokenAndNeverGenerates() {
        var service=mock(HoldingAdviceService.class);var properties=new AiServiceProperties();
        properties.setToken("test-only-token");var controller=new InternalHoldingAdviceController(service,properties);
        var request=new MockHttpServletRequest();var owner=UUID.randomUUID();
        assertThrows(AccessDeniedException.class,()->controller.current(request,owner,"001632"));
        verifyNoInteractions(service);
        request.addHeader("X-Service-Token","test-only-token");
        controller.current(request,owner,"001632");
        verify(service).currentFor(owner,"001632");verify(service,never()).generateFor(any(),anyString());
        request.addHeader("Origin","https://untrusted.example");
        assertThrows(AccessDeniedException.class,()->controller.current(request,owner,"001632"));
        verifyNoMoreInteractions(service);
    }
}
