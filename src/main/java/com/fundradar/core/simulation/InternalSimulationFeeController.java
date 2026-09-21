package com.fundradar.core.simulation;

import com.fundradar.core.auth.service.AccessDeniedException;
import com.fundradar.core.integration.ai.AiServiceProperties;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import static com.fundradar.core.simulation.SimulationTypes.*;

/** Python 费率任务的专用回写边界；只交换基金代码和公共费率，不传用户、持仓金额或订单。 */
@RestController
@RequestMapping("/internal/v1/simulation/fee-sync")
@ConditionalOnProperty(name="simulation.enabled",havingValue="true",matchIfMissing=true)
public class InternalSimulationFeeController {
    private final SimulationFeeService fees;
    private final AiServiceProperties properties;

    public InternalSimulationFeeController(SimulationFeeService fees, AiServiceProperties properties) {
        this.fees=fees;
        this.properties=properties;
    }

    /** 返回原全量初始化范围：模拟持仓与定投计划涉及基金的去重代码。 */
    @GetMapping("/fund-codes")
    public List<String> fundCodes(HttpServletRequest request) {
        authenticate(request);
        return fees.fundCodes();
    }

    /** 单基金全部档位在一个事务中保存；解析失败时任务不会调用此接口，旧规则保留。 */
    @PostMapping("/profiles")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void save(HttpServletRequest request, @RequestBody FundFee profile) {
        authenticate(request);
        fees.saveFetchedProfile(profile);
    }

    /** 复用部署已有的服务令牌；不接受浏览器来源，空配置或错误令牌均拒绝。 */
    private void authenticate(HttpServletRequest request) {
        String expected=properties.getToken();
        String supplied=request.getHeader("X-Service-Token");
        if (request.getHeader("Origin")!=null || request.getHeader("Sec-Fetch-Site")!=null
                || expected==null || expected.isBlank() || supplied==null
                || !MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),supplied.getBytes(StandardCharsets.UTF_8))) {
            throw new AccessDeniedException();
        }
    }
}
