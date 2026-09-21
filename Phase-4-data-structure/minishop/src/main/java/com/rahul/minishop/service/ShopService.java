package com.rahul.minishop.service;

import com.rahul.minishop.entity.Product;
import com.rahul.minishop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopService {

    private final ProductRepository productRepository;
    private final StringRedisTemplate redisTemplate;
    private static final String VIEWS_KEY = "shop:views:";
    private static final String CART_KEY = "shop:cart:";
    private static final Duration CART_TTL = Duration.ofMinutes(30);

    // key becomes "shop:" + cacheName + ":" + id = shop:product:1 (prefix set in RedisConfig)
    @Cacheable(cacheNames = "product", key = "#id")
    public Product getProduct(Long id) {
        log.info("Loading product {} from DATABASE", id);
        return loadFromDatabase(id);
    }

    // always runs, then puts the returned Product into the cache
    @CachePut(cacheNames = "product", key = "#id")
    public Product updateProduct(Long id, Product update) {
        Product existing = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id));
        existing.setName(update.getName());
        existing.setPrice(update.getPrice());
        existing.setStock(update.getStock());
        return productRepository.save(existing);
    }

    // runs, then removes the cache entry
    @CacheEvict(cacheNames = "product", key = "#id")
    public void deleteProduct(Long id) {
        if (!productRepository.existsById(id)) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id);
        }
        productRepository.deleteById(id);
    }


    public List<Product> getAllProducts() {
        log.info("Loading ALL products from DATABASE");
        return productRepository.findAll();
    }

    // TEMPORARY, delete after the test. Calls a cached method from inside the same class.
    public String getProductNameBroken(Long id) {
        return getProduct(id).getName();   // this.getProduct(...) skips the proxy
    }

    // simulates a slow database
    private Product loadFromDatabase(Long id) {
        try {
            Thread.sleep(2000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id));
    }

    //manually increasing view count
    public Long addView(Long id) {
        return redisTemplate.opsForValue().increment(VIEWS_KEY + id);
    }


    // Cart is stored as a Redis HASH: key = shop:cart:{userId}, field = productId, value = quantity.
    // Why not one JSON string? Adding an item would mean: read the whole cart, change it in Java, write it back.
    // If two requests do that at the same time, the second write overwrites the first, and an item is lost.
    // With a hash, HINCRBY changes one field atomically inside Redis, so nothing is lost.
    public Map<String, String> addToCart(Long userId, Long productId, int qty) {
        String key = CART_KEY + userId;
        redisTemplate.<String, String>opsForHash().increment(key, String.valueOf(productId), qty);
        redisTemplate.expire(key, CART_TTL);   // TTL on the whole key, renewed on every add
        return redisTemplate.<String, String>opsForHash().entries(key);
    }

    // HGETALL: returns productId -> quantity (empty map if the cart does not exist)
    public Map<String, String> getCart(Long userId) {
        return redisTemplate.<String, String>opsForHash().entries(CART_KEY + userId);
    }

    //HDEL: removes one item, the other fields are untouched
    public Map<String, String> removeFromCart(Long userId, Long productId) {
        String key = CART_KEY + userId;
        redisTemplate.<String, String>opsForHash().delete(key, String.valueOf(productId));
        return redisTemplate.<String, String>opsForHash().entries(key);
    }

    public Map<String, String> decreaseInCart(Long userId, Long productId, int qty) {
        String key = CART_KEY + userId;
        String field = String.valueOf(productId);
        Long newQty = redisTemplate.<String, String>opsForHash().increment(key, field, -qty);
        if (newQty != null && newQty <= 0) {
            redisTemplate.<String, String>opsForHash().delete(key, field);
        }
        return redisTemplate.<String, String>opsForHash().entries(key);
    }
}

/*
 * CART WITH REDIS HASH - SIMPLE EXPLANATION
 * Example: user id = 1, milk id = 4 and quantity=2
 * Redis stores the cart as: shop:cart:1 -> { "4": "2" }
 *   key   = shop:cart:1 (one cart per user)
 *   field = 4 (milk's product id)
 *   value = 2 (quantity)
 *
 * ADD:      HINCRBY shop:cart:1 4 2    -> adds 2 milk
 *   Cart before: empty       -> after: {"4":"2"}
 *   Add 1 more milk          -> after: {"4":"3"}
 *   Add bread (id 7), qty 1  -> after: {"4":"5","7":"1"}
 *
 * DECREASE: HINCRBY   -> a minus number subtracts
 *   decrease milk by 1 => Cart {"4":"3"} -> {"4":"2"}
 *   When the value reaches 0, we HDEL the field so it never goes negative.
 *
 * Remember:
 *   - HINCRBY adds to the old value. It does not set a new value.
 *   - Other products in the cart are never touched.
 *   - Values come back as text ("3"), Redis still does the math.
 */




