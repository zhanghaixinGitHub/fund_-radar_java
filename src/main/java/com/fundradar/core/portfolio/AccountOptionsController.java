package com.fundradar.core.portfolio;

import com.fundradar.core.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import java.math.BigDecimal;
import static com.fundradar.core.portfolio.AccountFundingTypes.Scope;

/** 本人范围只读核对；明确基金参数，不存在空参数退化为全量写入的路径。 */
@RestController
@RequestMapping("/api/v1/account/options")
public class AccountOptionsController {
    private final AccountOptionsService service;
    public AccountOptionsController(AccountOptionsService service) { this.service=service; }
    @GetMapping public ApiResponse<AccountOptionsService.Result> read(@RequestParam Scope scope,@RequestParam String fundCode,
            @RequestParam(required=false) BigDecimal hypotheticalDeclinePct,HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");return ApiResponse.success(service.read(scope,fundCode,hypotheticalDeclinePct));
    }
}
