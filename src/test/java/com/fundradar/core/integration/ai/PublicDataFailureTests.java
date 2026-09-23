package com.fundradar.core.integration.ai;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import java.net.SocketTimeoutException;
import static org.junit.jupiter.api.Assertions.*;

class PublicDataFailureTests {
    @Test void timeout503AndAuthenticationRemainDistinct() {
        var timeout=PublicDataFailure.classify(new ResourceAccessException("internal",new SocketTimeoutException()),"CALENDAR","trace-test");
        var service=PublicDataFailure.classify(new HttpServerErrorException(HttpStatus.SERVICE_UNAVAILABLE),"CALENDAR","trace-test");
        var auth=PublicDataFailure.classify(new org.springframework.web.client.HttpClientErrorException(HttpStatus.FORBIDDEN),"CALENDAR","trace-test");
        assertEquals("UPSTREAM_TIMEOUT",timeout.code());
        assertEquals("UPSTREAM_UNAVAILABLE",service.code());
        assertTrue(service.summary().contains("503"));
        assertEquals("UPSTREAM_AUTH_FAILED",auth.code());
        assertFalse(auth.retryable());
        assertEquals("trace-test",timeout.traceId());
        assertFalse(timeout.summary().contains("internal"));
    }
}
