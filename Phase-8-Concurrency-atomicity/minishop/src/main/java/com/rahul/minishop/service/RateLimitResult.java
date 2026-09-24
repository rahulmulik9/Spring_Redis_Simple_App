package com.rahul.minishop.service;

import lombok.AllArgsConstructor;
import lombok.Getter;

// 6.2 - result of a rate limit check
@Getter
@AllArgsConstructor
public class RateLimitResult {
    private final boolean allowed;
    private final long retryAfterSeconds;
}