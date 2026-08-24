package com.fundradar.core.integration.ai;

/** Raised when the internal AI service cannot provide a required read model. */
public class AiServiceUnavailableException extends RuntimeException {

    public AiServiceUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
