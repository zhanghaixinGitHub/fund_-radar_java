package com.fundradar.core.portfolio;
import com.fundradar.core.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import java.math.BigDecimal;
import org.springframework.web.bind.annotation.*;
import static com.fundradar.core.portfolio.AccountFundingTypes.Scope;

/** 本人只读检查；不会生成订单、保存持仓或对外发送通知。 */
@RestController
@RequestMapping("/api/v1/account/risk")
public class AccountRiskController {
    private final AccountRiskService service;
    public AccountRiskController(AccountRiskService service) { this.service=service; }
    @GetMapping public ApiResponse<AccountRiskService.Result> read(@RequestParam Scope scope,
            @RequestParam(required=false) BigDecimal scenarioDeclinePct,HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");
        return ApiResponse.success(service.read(scope,scenarioDeclinePct));
    }
}
