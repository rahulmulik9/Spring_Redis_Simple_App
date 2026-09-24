package com.rahul.minishop.controller;

import com.rahul.minishop.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Set;

@RestController
@RequestMapping("/wishlist")
@RequiredArgsConstructor
public class WishlistController {

    private final ShopService shopService;

    // 5.4
    @PostMapping("/add")
    public void addToWishlist(@RequestParam Long userId, @RequestParam Long productId) {
        shopService.addToWishlist(userId, productId);
    }

    // 5.4
    @DeleteMapping("/remove")
    public void removeFromWishlist(@RequestParam Long userId, @RequestParam Long productId) {
        shopService.removeFromWishlist(userId, productId);
    }

    // 5.4
    @GetMapping
    public Set<String> getWishlist(@RequestParam Long userId) {
        return shopService.getWishlist(userId);
    }
}