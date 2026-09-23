# Phase 6 Notes: First Real Patterns

Two things in this phase: a coupon that only one user can win, and a rate limiter that stops one user from spamming an endpoint. Both use TTL (expiring keys), but for very different reasons.

---

## 6.1 — Coupon claim (`SET NX EX`)

**Goal:** only the first person to call the endpoint should win. Everyone after that should be rejected.

**The one command that does it:**
```
SET shop:coupon:SAVE10 "1" NX EX 300
```

| Part | Meaning |
|---|---|
| `SET key value` | store a value under a key |
| `NX` | only do it if the key does **not already exist** |
| `EX 300` | the key disappears after 300 seconds (5 minutes) |

**Simple example**

Two users click claim at nearly the same moment.

- User 1's request reaches Redis first → key doesn't exist yet → Redis creates it → returns **success**.
- User 2's request reaches Redis right after → key already exists → Redis does nothing → returns **failure**.

```
SET shop:coupon:SAVE10 "1" NX EX 300   -> OK      (user 1 wins)
SET shop:coupon:SAVE10 "2" NX EX 300   -> (nil)   (user 2 loses, key untouched)
GET shop:coupon:SAVE10                 -> "1"
```

**Why can't two people both win, even at the exact same instant?**
Redis handles one command at a time, never two together. So even if both requests arrive in the same millisecond, Redis still processes them one after another. Whoever is processed first creates the key; the second one always sees "already exists" and loses.

**Why one command, not two (`SETNX` then `EXPIRE`)?**
If it were two separate steps, a crash between them could leave the key created but with **no expiry** — it would be stuck forever, and nobody could ever claim that coupon code again, even after 5 minutes. One atomic command means there's no gap where that can happen.

**Each coupon `code` is a separate key**, so `SAVE10` and `WELCOME20` don't affect each other — it's "first person per code wins," not "first person overall wins everything."

---

## 6.2 — Fixed-window rate limiter

**Goal:** stop one user from calling an endpoint too many times in a short period. Limit: 5 requests per 30 seconds.

**Two commands per request:**
```
INCR shop:ratelimit:{userId}         -> counts this request
EXPIRE shop:ratelimit:{userId} 30    -> only set on the very first request of the window
```

**Simple example** (limit = 5, window = 30s)

```
Request 1: INCR -> 1   (count==1, so set EXPIRE 30)   -> allowed
Request 2: INCR -> 2                                    -> allowed
Request 3: INCR -> 3                                    -> allowed
Request 4: INCR -> 4                                    -> allowed
Request 5: INCR -> 5                                    -> allowed
Request 6: INCR -> 6                                    -> REJECTED (429), count > 5
```

After 30 seconds, the key expires, the count resets to 0, and the next request starts a fresh window.

**Why `EXPIRE` only on the first request (`count == 1`)?**
If we set the expiry on every request, a very active user would keep pushing the 30-second window further into the future forever, and their limit would never reset. Setting it only once, on the first request, means the window is a fixed 30 seconds from when the user's first request landed.

---

## 6.3 — The two problems with this simple version

### Problem A: the crash gap between `INCR` and `EXPIRE`

These are **two separate Redis commands**. If the app crashed right after `INCR` but before `EXPIRE` ran, the key would be left with a count but **no TTL**. It would never expire on its own, and that user would be rate-limited forever.

**The fix we used:** check the key's TTL on every request. If it's missing (`-1`, meaning "exists but never expires"), set it right then. This doesn't stop the gap from ever happening, but it heals itself on the very next request instead of staying broken permanently.

```
Simulate it:
  1. Send 1 request -> key exists, count = 1
  2. Manually remove its TTL (pretend the app crashed before EXPIRE ran):
       PERSIST shop:ratelimit:99
       TTL shop:ratelimit:99   -> -1  (stuck, in the old code)
  3. Send another request -> the fixed code notices TTL is -1 and repairs it
```

The fully atomic fix (one command that can never leave a gap) needs a small script, which we'll do with Lua in Phase 9.5.

### Problem B: the boundary burst (a design flaw, not a bug)

A fixed window resets at a fixed point in time, not "30 seconds after this user's first request specifically" — it resets 30 seconds after the window key was *created*. But if the user is clever about timing, they can send 5 requests right at the *end* of one window, then immediately send 5 more the instant the next window starts:

```
Window 1 (about to expire): 5 requests sent in the last second   -> all allowed
Window 2 (just started):    5 more requests sent immediately      -> all allowed
```

Total: **10 requests in about 1-2 seconds**, even though the limit is "5 per 30 seconds." The fixed window has no memory of what happened just before it reset.

**We don't fix this in Phase 6.** The real fix is a **sliding window** — instead of a fixed bucket, count requests in the last 30 seconds *relative to right now*. That's Phase 11.1, using a sorted set with timestamps as scores.

---

## Quick comparison

| Feature | Redis piece | What it protects against |
|---|---|---|
| Coupon claim | `SET NX EX` (one atomic command) | Two people both winning |
| Rate limit count | `INCR` | Losing count under concurrent requests |
| Rate limit expiry | `EXPIRE`, set once per window | Window resetting cleanly after 30s |
| Crash-gap self-heal | Check TTL, re-set if missing | Key stuck forever with no expiry |
| (Not fixed yet) Boundary burst | — | Needs a sliding window (Phase 11) |

## The common thread

Both features rely on **TTL (automatic expiry)** to solve a real problem without any cleanup code:
- The coupon "resets" after 5 minutes with zero extra code — the key just disappears.
- The rate limit window "resets" after 30 seconds the same way.

Neither needs a scheduled job or manual cleanup. Redis does it by itself.