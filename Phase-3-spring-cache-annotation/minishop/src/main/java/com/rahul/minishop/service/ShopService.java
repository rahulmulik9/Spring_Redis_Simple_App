package com.rahul.minishop.service;

import com.rahul.minishop.entity.Product;
import com.rahul.minishop.repository.ProductRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.CachePut;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Slf4j
@Service
@RequiredArgsConstructor
public class ShopService {

    private final ProductRepository productRepository;

    //    public Product getProduct(Long id) {
//        String key = "shop:product:" + id;
//
//        String cached = redisTemplate.opsForValue().get(key);
//        if (cached != null) {
//            log.info("CACHE HIT for product {}", id);
//            return jsonMapper.readValue(cached, Product.class);
//        }
//        log.info("CACHE MISS for product {} - loading from DATABASE", id);
//        Product product = loadFromDatabase(id);
//        saveToCache(product);
//        return product;
//    }

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
}
