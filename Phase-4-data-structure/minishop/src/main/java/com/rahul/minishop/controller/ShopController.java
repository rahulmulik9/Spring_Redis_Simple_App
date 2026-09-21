package com.rahul.minishop.controller;

import com.rahul.minishop.entity.Product;
import com.rahul.minishop.service.ShopService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/products")
@RequiredArgsConstructor
public class ShopController {

    private final ShopService shopService;


    @GetMapping("/{id}")
    public Product getProduct(@PathVariable Long id) {
        return shopService.getProduct(id);
    }


    @PutMapping("/{id}")
    public Product updateProduct(@PathVariable Long id, @RequestBody Product product) {
        return shopService.updateProduct(id, product);
    }


    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deleteProduct(@PathVariable Long id) {
        shopService.deleteProduct(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping
    public List<Product> getAllProducts() {
        return shopService.getAllProducts();
    }

    //controller to get count
    @PostMapping("/{id}/view")
    public Long addView(@PathVariable Long id) {
        return shopService.addView(id);
    }


    //=============Cart

    @PostMapping("/cart/add")
    public Map<String, String> addToCart(@RequestParam Long userId, @RequestParam Long productId, @RequestParam(defaultValue = "1") int qty) {
        return shopService.addToCart(userId, productId, qty);
    }


    @GetMapping("/cart")
    public Map<String, String> getCart(@RequestParam Long userId) {
        return shopService.getCart(userId);
    }


    @DeleteMapping("/cart/item")
    public Map<String, String> removeFromCart(@RequestParam Long userId, @RequestParam Long productId) {
        return shopService.removeFromCart(userId, productId);
    }

    @PostMapping("/cart/decrease")
    public Map<String, String> decreaseInCart(@RequestParam Long userId, @RequestParam Long productId, @RequestParam(defaultValue = "1") int qty) {
        return shopService.decreaseInCart(userId, productId, qty);
    }

}