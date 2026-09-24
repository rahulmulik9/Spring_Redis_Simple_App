package com.rahul.minishop.service;

import com.rahul.minishop.entity.Product;
import com.rahul.minishop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.Duration;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopService {

    private final ProductRepository productRepository;
    private final StringRedisTemplate redisTemplate;
    private static final String VIEWS_KEY = "shop:views:";
    private static final String CART_KEY = "shop:cart:";
    private static final Duration CART_TTL = Duration.ofMinutes(30);

    //list data structure
    private static final String RECENT_KEY = "shop:recent:";
    private static final int RECENT_LIMIT = 5;

    //Wish list
    private static final String WISHLIST_KEY = "shop:wishlist:";

    //Sorted
    private static final String TOP_PRODUCTS_KEY = "shop:top-products";

    //coupon claim
    private static final String COUPON_KEY = "shop:coupon:";
    private static final Duration COUPON_TTL = Duration.ofMinutes(5);

    //Fixed Window rate
    private static final String RATE_LIMIT_KEY = "shop:ratelimit:";
    private static final int RATE_LIMIT_MAX = 5;
    private static final Duration RATE_LIMIT_WINDOW = Duration.ofSeconds(30);


    private final RedissonClient redissonClient;


    // sync = true fixes cache stampede — when multiple threads miss this key at the same time, only the first one runs the method and hits the DB;
    // the rest block and wait for its result instead of all rebuilding the cache independently. Note: this lock is per-JVM only, not
    // distributed — multiple app instances would still each rebuild once.
    @Cacheable(cacheNames = "product", key = "#id", sync = true)
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
//        return productRepository.findById(id)
//                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id));
        // 8.1: no more orElseThrow — a missing product is now a null return,
        // which @Cacheable treats as a valid, cacheable result
        return productRepository.findById(id).orElse(null);
    }

    //manually increasing view count
    public Long addView(Long id) {
        return redisTemplate.opsForValue().increment(VIEWS_KEY + id);
    }


    /// ===== Count and Hash data structure ==================
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

    /// ==================   List data structure
    public void addToRecentlyViewed(Long userId, Long productId) {
        String key = RECENT_KEY + userId;
        redisTemplate.opsForList().leftPush(key, String.valueOf(productId));
        redisTemplate.opsForList().trim(key, 0, RECENT_LIMIT - 1);  //push at the top of array(0 index), maz size is RECENT_LIMIT (5)
    }

    public List<String> getRecentlyViewed(Long userId) {
        List<String> list = redisTemplate.opsForList().range(RECENT_KEY + userId, 0, -1);
        return list != null ? list : List.of();
    }


    /// ========================== Wish list data structure
    //this will be saved into redis without expiry, so redis here is acting like database which store wishlist
    // Set: SADD is idempotent, adding the same product twice has no extra effect
    public void addToWishlist(Long userId, Long productId) {
        redisTemplate.opsForSet().add(WISHLIST_KEY + userId, String.valueOf(productId));
    }

    // SREM removes one member
    public void removeFromWishlist(Long userId, Long productId) {
        redisTemplate.opsForSet().remove(WISHLIST_KEY + userId, String.valueOf(productId));
    }

    // SMEMBERS: all members, no guaranteed order, no duplicates
    public Set<String> getWishlist(Long userId) {
        Set<String> members = redisTemplate.opsForSet().members(WISHLIST_KEY + userId);
        return members != null ? members : Set.of();
    }

    public void incrementProductScore(Long productId) {
        redisTemplate.opsForZSet().incrementScore(TOP_PRODUCTS_KEY, String.valueOf(productId), 1);
    }

    //ZREVRANGE with scores: highest score first, top `limit` members
    public Set<ZSetOperations.TypedTuple<String>> getTopProducts(int limit) {
        Set<ZSetOperations.TypedTuple<String>> top =
                redisTemplate.opsForZSet().reverseRangeWithScores(TOP_PRODUCTS_KEY, 0, limit - 1);
        return top != null ? top : Set.of();
    }


    /// ======== claim coupn
    // 6.1 SET NX EX - first request wins, rest are rejected.
    // NX = only set if the key doesn't exist yet, so only the first caller succeeds.
    // EX = auto-expires, so the coupon becomes claimable again after the TTL.
    // One atomic command, so no crash can ever leave the key stuck without a TTL.
    public boolean claimCoupon(String code, Long userId) {
        Boolean won = redisTemplate.opsForValue()
                .setIfAbsent(COUPON_KEY + code, String.valueOf(userId), COUPON_TTL);
        return Boolean.TRUE.equals(won);
    }


    ///  ====== fixed window rate limiter
    public RateLimitResult checkRateLimit(Long userId) {
        String key = RATE_LIMIT_KEY + userId;
        Long count = redisTemplate.opsForValue().increment(key);

        Long ttl = redisTemplate.getExpire(key);
        if (ttl != null && ttl < 0) {
            // key exists but has no TTL (-1) - either brand new, or a crash left it stuck. Fix it now.
            redisTemplate.expire(key, RATE_LIMIT_WINDOW);
            ttl = RATE_LIMIT_WINDOW.getSeconds();
        }

        if (count != null && count > RATE_LIMIT_MAX) {
            return new RateLimitResult(false, ttl != null ? ttl : 0);
        }
        return new RateLimitResult(true, 0);
    }


    /// ================= Race condition : When multiple requrest hit buy at same time
    /// Two way to handle tace contion : By sql and another by Redis
    //Race Handled By using sql
    // 1: naive buy lost decrements when many concurrent requests all read the same stock value before any of them saved.
    // Fixed here with a conditional UPDATE — Postgres only decrements if stock is still > 0 at the moment the UPDATE runs,
    // so there's no separate read-then-write gap for two requests to race inside.
    public Product buy(Long id) {
        int rowsAffected = productRepository.decrementStockIfAvailable(id);

        if (rowsAffected == 0) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Out of stock or not found: " + id);
        }

        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id));
    }

    // Redis version : atomic decrement via Redis DECR.
    // Redis is single-threaded, so each command below runs fully before the next one starts — even with 20 concurrent requests,
    // there's no gap for two threads to read the same value and both decide to decrement it.
    // setIfAbsent seeds the Redis stock counter from Postgres, but only on the very first buy for this product —
    // later calls see the key already exists and skip seeding, so stock never gets reset mid-sale.
    public Product buyWithRedisDecr(Long id) {
        String stockKey = "shop:stock:" + id;

        redisTemplate.opsForValue().setIfAbsent(stockKey, String.valueOf(getDbStock(id)));

        Long remaining = redisTemplate.opsForValue().decrement(stockKey);

        if (remaining == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Stock not initialized for: " + id);
        }

        if (remaining < 0) {
            // oversold — put the stock back in Redis, then reject this request
            redisTemplate.opsForValue().increment(stockKey);
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Out of stock: " + id);
        }

        Product product = productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id));
        product.setStock(remaining.intValue());
        productRepository.save(product);

        return product;
    }

    // helper — current stock from Postgres, used only to seed Redis on first buy
    private int getDbStock(Long id) {
        return productRepository.findById(id)
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "Product not found: " + id))
                .getStock();
    }


    /// ============= Distribute lock
    public String lockedCheckout(Long id) {
        String lockKey = "lock:product:" + id;
        String token = UUID.randomUUID().toString();

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey, token, Duration.ofMillis(5000)
        );

        if (!Boolean.TRUE.equals(acquired)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout already in progress for: " + id);
        }

        try {
            // simulated multi-step critical section — stands in for
            // "check stock, call slow external service, decrement" — no
            // single Redis command could make this whole sequence atomic,
            // which is exactly why we need a lock instead
            log.info("Checkout started for product {} (token {})", id, token);
            Thread.sleep(1000);
            log.info("Checkout finished for product {} (token {})", id, token);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            // 10.1: naive release — plain DEL, no ownership check yet.
            // This is deliberately unsafe; 10.2 fixes it with a Lua
            // compare-and-delete so we never delete someone else's lock.
            redisTemplate.delete(lockKey);
        }

        return "Checkout completed for product " + id;
    }

    // 10.2: DELIBERATELY reproduces the TTL-expiry overlap bug — short lock
// expiry (500ms), long critical section (2000ms). The lock expires while
// still "held," so a second caller acquires it and both callers end up
// inside the critical section at the same time. This proves a single-node
// lock's PX alone doesn't guarantee exclusivity if the work outlives it.
    public String lockedCheckoutBrokenTtl(Long id) {
        String lockKey = "lock:product:" + id;
        String token = UUID.randomUUID().toString();

        Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
                lockKey, token, Duration.ofMillis(500)   // too short for the work below
        );

        if (!Boolean.TRUE.equals(acquired)) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout already in progress for: " + id);
        }

        try {
            log.info("BROKEN checkout started for product {} (token {})", id, token);
            Thread.sleep(2000);  // outlives the 500ms lock — this is the bug
            log.info("BROKEN checkout finished for product {} (token {})", id, token);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            safeUnlock(lockKey, token);
        }

        return "Checkout completed (broken TTL demo) for product " + id;
    }
    // 10.2: safe unlock — only delete the lock if it still holds OUR token.
// A plain DEL (10.1) would delete whatever is at this key, even if our
// lock already expired and someone else has since acquired it. The check
// and the delete must happen as ONE atomic step (Lua), otherwise another
// client could acquire the lock in the gap between our GET check and our
// DEL call — which would defeat the whole point of checking first.
    private static final RedisScript<Long> UNLOCK_SCRIPT = RedisScript.of(
            "if redis.call('GET', KEYS[1]) == ARGV[1] then " +
                    "  return redis.call('DEL', KEYS[1]) " +
                    "else " +
                    "  return 0 " +
                    "end",
            Long.class
    );

    private void safeUnlock(String lockKey, String token) {
        Long result = redisTemplate.execute(UNLOCK_SCRIPT, Collections.singletonList(lockKey), token);
        if (result == 0) {
            log.warn("Tried to unlock {} but token didn't match — lock was not ours anymore", lockKey);
        }
    }


    /// =========== Redisson
    // 10.3: same checkout operation as 10.1/10.2, now using Redisson's RLock.
    // No manual token, no manual TTL math, no manual Lua unlock script — the
    // watchdog automatically renews the lock's expiry every ~10s (a third of
    // the default 30s lease) as long as this thread is still alive, so a
    // legitimately slow critical section does NOT lose its lock early like
    // the raw SET NX PX version did in 10.2.
    public String lockedCheckoutRedisson(Long id) {
        RLock lock = redissonClient.getLock("lock:product:" + id);

        boolean acquired = lock.tryLock();   // no wait/lease args = watchdog mode, default 30s lease, auto-renewed
        if (!acquired) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout already in progress for: " + id);
        }

        try {
            log.info("Redisson checkout started for product {}", id);
            Thread.sleep(2000);   // same duration that broke the raw lock in 10.2 — this time it should NOT overlap
            log.info("Redisson checkout finished for product {}", id);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            lock.unlock();   // Redisson checks ownership internally — safe by default, no compare-and-delete script needed
        }

        return "Checkout completed (Redisson) for product " + id;
    }

}




