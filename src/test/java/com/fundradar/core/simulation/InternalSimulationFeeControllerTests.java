package com.fundradar.core.simulation;

import com.fundradar.core.common.web.GlobalExceptionHandler;
import com.fundradar.core.integration.ai.AiServiceProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.math.BigDecimal;
import java.util.List;
import static com.fundradar.core.simulation.SimulationTypes.*;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 不访问数据库的内部契约验证：服务身份、浏览器拒绝、合法档案落库及无效档案不写入。 */
class InternalSimulationFeeControllerTests {
    private static final String BASE="/internal/v1/simulation/fee-sync";
    private static final String TOKEN="fee-contract-test-only";
    private static final String PROFILE="""
        {"fundCode":"008888","fundName":"测试基金","purchaseRate":"0","purchaseOriginalRate":"0",
         "discountInfo":null,"dataSource":"EASTMONEY_F10",
         "redeemBands":[{"minDays":0,"maxDays":6,"rate":"0.015"},{"minDays":7,"maxDays":null,"rate":"0"}]}
        """;
    private SimulationRepository repo;
    private SimulationFeeService fees;
    private AiServiceProperties properties;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        repo=mock(SimulationRepository.class);
        fees=new SimulationFeeService(repo,null);
        properties=new AiServiceProperties();
        properties.setToken(TOKEN);
        mvc=MockMvcBuilders.standaloneSetup(new InternalSimulationFeeController(fees,properties))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @Test
    void refusesMissingWrongBlankTokensAndBrowserRequestsBeforeReadingOrWriting() throws Exception {
        mvc.perform(get(BASE+"/fund-codes")).andExpect(status().isForbidden());
        mvc.perform(get(BASE+"/fund-codes").header("X-Service-Token","wrong")).andExpect(status().isForbidden());
        mvc.perform(get(BASE+"/fund-codes").header("X-Service-Token",TOKEN).header("Origin","http://localhost:5173"))
                .andExpect(status().isForbidden());
        mvc.perform(post(BASE+"/profiles").header("X-Service-Token",TOKEN).header("Sec-Fetch-Site","same-origin")
                .contentType(MediaType.APPLICATION_JSON).content(PROFILE)).andExpect(status().isForbidden());
        properties.setToken("");
        mvc.perform(get(BASE+"/fund-codes").header("X-Service-Token",TOKEN)).andExpect(status().isForbidden());
        verifyNoInteractions(repo);
    }

    @Test
    void returnsOnlyCodesAndSavesCompleteCamelCaseProfile() throws Exception {
        when(repo.simFundCodes()).thenReturn(List.of("008888"));
        mvc.perform(get(BASE+"/fund-codes").header("X-Service-Token",TOKEN))
                .andExpect(status().isOk()).andExpect(content().json("[\"008888\"]"));
        mvc.perform(post(BASE+"/profiles").header("X-Service-Token",TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(PROFILE)).andExpect(status().isNoContent());
        verify(repo).upsertFees(argThat(fee -> fee.fundCode().equals("008888") && fee.redeemBands().size()==2));
    }

    @Test
    void rejectsInvalidProfileWithoutReplacingOldRules() throws Exception {
        mvc.perform(post(BASE+"/profiles").header("X-Service-Token",TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(PROFILE.replace("\"minDays\":7","\"minDays\":8")))
                .andExpect(status().isBadRequest());
        mvc.perform(post(BASE+"/profiles").header("X-Service-Token",TOKEN)
                .contentType(MediaType.APPLICATION_JSON).content(PROFILE.replace("EASTMONEY_F10","MANUAL")))
                .andExpect(status().isBadRequest());
        assertThrows(IllegalArgumentException.class,() -> fees.saveFetchedProfile(new FundFee("008888","测试",
                BigDecimal.ZERO,BigDecimal.ZERO,null,List.of(),"EASTMONEY_F10")));
        verifyNoInteractions(repo);
    }
}
