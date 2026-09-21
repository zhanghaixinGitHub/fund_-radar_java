package com.fundradar.core.simulation;

import com.fundradar.core.auth.*;
import com.fundradar.core.common.web.GlobalExceptionHandler;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static com.fundradar.core.simulation.SimulationTypes.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/** 费率搜索的公开契约验证：沿用权限，兼容旧代码参数，规范化关键词并限制长度。 */
class SimulationFeeSearchTests {
    private static final String BASE="/api/v1/admin/sim-fee-rules";
    private SimulationRepository repo;
    private MockMvc mvc;

    @BeforeEach
    void setup() {
        repo=mock(SimulationRepository.class);
        mvc=MockMvcBuilders.standaloneSetup(new SimulationFeeController(new SimulationFeeService(repo,null)))
                .setControllerAdvice(new GlobalExceptionHandler()).build();
    }

    @AfterEach
    void cleanup() { CurrentUserContext.clear(); }

    private void login(PermissionCode... permissions) {
        CurrentUserContext.set(new AuthenticatedUser(UUID.randomUUID(),"test","查询测试",
                AccountRole.DATA_OPERATOR,Set.of(permissions)));
    }

    @Test
    void rejectsMissingIdentityAndPermissionBeforeSearching() throws Exception {
        mvc.perform(get(BASE).param("keyword","半导体")).andExpect(status().isUnauthorized());
        login(PermissionCode.SYNC_JOB_READ);
        mvc.perform(get(BASE).param("keyword","半导体")).andExpect(status().isForbidden());
        verifyNoInteractions(repo);
    }

    @Test
    void searchesTrimmedCodeOrNameAndKeepsPagination() throws Exception {
        login(PermissionCode.SIM_FEE_RULE_ADMIN);
        for (String keyword:List.of("008888","半导体")) {
            when(repo.pageRules(null,keyword,2,20)).thenReturn(new Page<>(List.of(),2,20,21));
            mvc.perform(get(BASE).param("keyword","  "+keyword+"  ").param("page","2"))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.data.page").value(2))
                    .andExpect(jsonPath("$.data.totalCount").value(21));
            verify(repo).pageRules(null,keyword,2,20);
        }
    }

    @Test
    void blankKeywordShowsAllAndLegacyExactCodeStillWorks() throws Exception {
        login(PermissionCode.SIM_FEE_RULE_ADMIN);
        when(repo.pageRules(null,null,1,20)).thenReturn(new Page<>(List.of(),1,20,0));
        mvc.perform(get(BASE).param("keyword","   ")).andExpect(status().isOk());
        verify(repo).pageRules(null,null,1,20);
        when(repo.pageRules("008888",null,1,20)).thenReturn(new Page<>(List.of(),1,20,0));
        mvc.perform(get(BASE).param("fundCode","008888")).andExpect(status().isOk());
        verify(repo).pageRules("008888",null,1,20);
    }

    @Test
    void rejectsTooLongKeywordAndInvalidPageBeforeQuerying() throws Exception {
        login(PermissionCode.SIM_FEE_RULE_ADMIN);
        mvc.perform(get(BASE).param("keyword","基".repeat(51))).andExpect(status().isBadRequest());
        mvc.perform(get(BASE).param("page","0")).andExpect(status().isConflict());
        mvc.perform(get(BASE).param("pageSize","101")).andExpect(status().isConflict());
        verifyNoInteractions(repo);
    }
}
