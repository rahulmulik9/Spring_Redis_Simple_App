package com.rahul.minishop.controller;

import com.rahul.minishop.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequiredArgsConstructor
public class RecentlyViewedController {

    private final ShopService shopService;

    // 5.3
    @GetMapping("/recently-viewed")
    public List<String> getRecentlyViewed(@RequestParam Long userId) {
        return shopService.getRecentlyViewed(userId);
    }
}