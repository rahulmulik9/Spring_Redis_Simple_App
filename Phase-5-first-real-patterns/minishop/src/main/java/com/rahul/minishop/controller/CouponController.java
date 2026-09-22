package com.rahul.minishop.controller;

import com.rahul.minishop.service.ShopService;
import lombok.RequiredArgsConstructor;
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
    @PostMapping("/claim")
    public Map<String, Object> claim(@RequestParam String code, @RequestParam Long userId) {
        boolean won = shopService.claimCoupon(code, userId);
        return Map.of(
                "won", won,
                "message", won ? "You claimed the coupon!" : "Sorry, already claimed."
        );
    }
}