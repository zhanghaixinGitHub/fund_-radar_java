package com.fundradar.core.integration.ai;

/** Raised when a requested fund code is absent from the AI read model. */
public class FundNotFoundException extends RuntimeException {

    public FundNotFoundException(String fundCode) {
        super("Fund not found: " + fundCode);
    }
}
