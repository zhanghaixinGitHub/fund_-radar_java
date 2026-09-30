package com.fundradar.core.notification.controller;

import com.fundradar.core.common.api.ApiResponse;
import com.fundradar.core.notification.service.ReviewNoticeService;
import org.springframework.web.bind.annotation.*;
import jakarta.servlet.http.HttpServletResponse;
import java.util.UUID;

/** 私有站内提醒；不接受用户名、事项内容或状态写入，仅允许本人标记已读。 */
@RestController
@RequestMapping("/api/v1/notifications/reviews")
public class ReviewNoticeController {
    private final ReviewNoticeService service;
    public ReviewNoticeController(ReviewNoticeService service) { this.service=service; }
    public record ReadRequest(long revision) {}
    @GetMapping public ApiResponse<ReviewNoticeService.Page<ReviewNoticeService.Notice>> list(@RequestParam(defaultValue="1") int page,HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");return ApiResponse.success(service.read(page));
    }
    @GetMapping("/{id}/history") public ApiResponse<ReviewNoticeService.Page<ReviewNoticeService.Revision>> history(@PathVariable UUID id,@RequestParam(defaultValue="1") int page,HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");return ApiResponse.success(service.history(id,page));
    }
    @PostMapping("/{id}/read") public ApiResponse<ReviewNoticeService.Notice> read(@PathVariable UUID id,@RequestBody ReadRequest request,HttpServletResponse response) {
        response.setHeader("Cache-Control","private, no-store");return ApiResponse.success(service.markRead(id,request.revision()));
    }
}
