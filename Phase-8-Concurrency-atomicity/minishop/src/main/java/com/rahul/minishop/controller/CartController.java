package com.rahul.minishop.controller;

import com.rahul.minishop.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/cart")
@RequiredArgsConstructor
public class CartController {

    private final ShopService shopService;

    @PostMapping("/add")
    public Map<String, String> addToCart(@RequestParam Long userId, @RequestParam Long productId, @RequestParam(defaultValue = "1") int qty) {
        return shopService.addToCart(userId, productId, qty);
    }

    @GetMapping
    public Map<String, String> getCart(@RequestParam Long userId) {
        return shopService.getCart(userId);
    }

    @DeleteMapping("/remove")
    public Map<String, String> removeFromCart(@RequestParam Long userId, @RequestParam Long productId) {
        return shopService.removeFromCart(userId, productId);
    }

    @PostMapping("/decrease")
    public Map<String, String> decreaseInCart(@RequestParam Long userId, @RequestParam Long productId, @RequestParam(defaultValue = "1") int qty) {
        return shopService.decreaseInCart(userId, productId, qty);
    }
}
