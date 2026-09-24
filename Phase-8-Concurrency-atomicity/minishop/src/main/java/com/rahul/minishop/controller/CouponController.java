package com.rahul.minishop.controller;

import com.rahul.minishop.service.RateLimitResult;
import com.rahul.minishop.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/coupon")
@RequiredArgsConstructor
public class CouponController {

    private final ShopService shopService;

    // 6.1
//    @PostMapping("/claim")
//    public Map<String, Object> claim(@RequestParam String code, @RequestParam Long userId) {
//        boolean won = shopService.claimCoupon(code, userId);
//        return Map.of(
//                "won", won,
//                "message", won ? "You claimed the coupon!" : "Sorry, already claimed."
//        );
//    }

    // 6.2
    @PostMapping("/claim")
    public ResponseEntity<Map<String, Object>> claim(@RequestParam String code, @RequestParam Long userId) {
        RateLimitResult limit = shopService.checkRateLimit(userId);
        if (!limit.isAllowed()) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .header("Retry-After", String.valueOf(limit.getRetryAfterSeconds()))
                    .body(Map.of("error", "Rate limit exceeded", "retryAfterSeconds", limit.getRetryAfterSeconds()));
        }

        boolean won = shopService.claimCoupon(code, userId);
        return ResponseEntity.ok(Map.of(
                "won", won,
                "message", won ? "You claimed the coupon!" : "Sorry, already claimed."
        ));
    }

}