package com.fundradar.core.portfolio;

import com.fundradar.core.common.api.ApiResponse;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.web.bind.annotation.*;
import static com.fundradar.core.portfolio.AccountFundingTypes.*;

/** 沿用会话、Origin/CSRF 防护；从会话确定本人，不接受任何 userId。 */
@RestController
@RequestMapping("/api/v1/account/funding")
public class AccountFundingController {
    private final AccountFundingService service;
    public AccountFundingController(AccountFundingService service) { this.service=service; }
    @ModelAttribute public void privateResponse(HttpServletResponse response) { response.setHeader("Cache-Control","private, no-store"); }
    @GetMapping public ApiResponse<Page> read(@RequestParam Scope scope,@RequestParam(defaultValue="1") int page) {
        return ApiResponse.success(service.read(scope,page));
    }
    @PostMapping("/confirm") public ApiResponse<View> confirm(@RequestParam Scope scope,@RequestBody Change change) {
        return ApiResponse.success(service.change(scope,change,false));
    }
    @PostMapping("/revoke") public ApiResponse<View> revoke(@RequestParam Scope scope,@RequestBody Change change) {
        return ApiResponse.success(service.change(scope,change,true));
    }
}
