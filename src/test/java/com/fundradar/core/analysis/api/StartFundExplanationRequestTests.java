package com.fundradar.core.analysis.api;

import jakarta.validation.Validation;
import jakarta.validation.Validator;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/** 验证解释任务只接受标准基金代码，不能把自由文本变成模型输入。 */
class StartFundExplanationRequestTests {

    private static jakarta.validation.ValidatorFactory validatorFactory;
    private static Validator validator;

    @BeforeAll
    static void setUpValidator() {
        validatorFactory = Validation.buildDefaultValidatorFactory();
        validator = validatorFactory.getValidator();
    }

    @AfterAll
    static void closeValidator() {
        validatorFactory.close();
    }

    @Test
    void acceptsSixDigitFundCode() {
        assertEquals(0, validator.validate(new StartFundExplanationRequest("000001")).size());
    }

    @Test
    void rejectsBlankNonNumericAndIncorrectLengthFundCodes() {
        assertFalse(validator.validate(new StartFundExplanationRequest(" ")).isEmpty());
        assertFalse(validator.validate(new StartFundExplanationRequest("ABCDEF")).isEmpty());
        assertFalse(validator.validate(new StartFundExplanationRequest("00001")).isEmpty());
    }
}
