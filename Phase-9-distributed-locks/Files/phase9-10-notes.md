# Redis Mini Shop — Notes: Phase 9 & Phase 10

**Stack:** Java 17, Spring Boot 4.1.1, Maven, PostgreSQL + Redis (Docker), Lombok

---

# Phase 9: Concurrency and Atomicity
*Branch: `m9-atomicity`*

**Outcome:** reproduced a real race condition, fixed it multiple ways, and understand the trade-offs between each fix.

## 9.1 — The naive race (reproduced, not fixed)

`buy(id)` written the obviously-wrong way on purpose:
1. `SELECT` product (read stock)
2. Check `stock > 0` in Java
3. Subtract 1 in Java
4. `save()` back

**The bug:** two threads can both read `stock = 5` at nearly the same instant, both compute `4`, both save `4` — one decrement silently vanishes (the "lost update" problem).

**Test:** seeded stock = 20, fired 20 concurrent `buy` requests → final stock was **higher than 0** (not all decrements applied). Confirmed the bug is real, not theoretical.

**Important note:** wrapping this in plain `@Transactional` (default READ COMMITTED isolation in Postgres) would **not** have fixed it — the read and write are still two separate statements. Default isolation doesn't prevent lost updates.

## 9.2 — Two atomic fixes

### Fix A: Conditional SQL `UPDATE`
```java
public Product buy(Long id) {
    int rowsAffected = productRepository.decrementStockIfAvailable(id);
    if (rowsAffected == 0) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Out of stock or not found: " + id);
    }
    return productRepository.findById(id).orElseThrow(...);
}
```
```java
@Modifying
@Query("UPDATE Product p SET p.stock = p.stock - 1 WHERE p.id = :id AND p.stock > 0")
int decrementStockIfAvailable(@Param("id") Long id);
```
The `WHERE stock > 0` clause means Postgres only updates the row if stock is still positive **at the moment the UPDATE runs** — the database enforces the check-and-write atomically via row-level locking. No application-level race window.

### Fix B: Redis `DECR` (later replaced by Lua in 9.5)
Stock mirrored into Redis as `shop:stock:{id}`, seeded from Postgres on first buy (`setIfAbsent`, atomic — only one caller ever wins the seed). `DECR` itself is atomic because Redis is single-threaded and never interleaves commands.

**Test result:** both fixes, tested under the same 20-concurrent-request load → final stock landed on exactly **0**, no lost decrements.

### Comparison

| | Conditional `UPDATE` (SQL) | Redis `DECR` / Lua |
|---|---|---|
| Source of truth | Postgres only | Redis (synced to Postgres after) |
| Atomicity mechanism | DB row-level lock | Redis single-threaded execution |
| Extra setup | None | Needs seeding before first use |
| Risk | None — DB is authoritative | Redis/Postgres can drift if the Postgres write fails after Redis already changed |

## 9.3 — `MULTI` / `EXEC` / `WATCH` (redis-cli only, no Java)

**Basic MULTI/EXEC:** commands queue (`QUEUED`) between `MULTI` and `EXEC`; nothing executes until `EXEC`, and no other client's commands can interleave in between. This is **isolation**.

**The "no rollback" demo (the key insight):**
```
SET mykey "not-a-number"
MULTI
SET a 100        -> QUEUED
INCR mykey        -> QUEUED   (will fail — not numeric)
SET b 200         -> QUEUED
EXEC
1) OK
2) (error) ERR value is not an integer or out of range
3) OK
```
`SET a 100` and `SET b 200` **both still ran** even though `INCR mykey` failed in between. Unlike a SQL transaction, Redis does **not** roll back the rest of the queue when one command errors at runtime.

**`WATCH` — optimistic locking:**
```
WATCH shop:stock:1
MULTI
DECR shop:stock:1   -> QUEUED
--- another client changes shop:stock:1 in between ---
EXEC
(nil)   <- whole transaction aborted because the watched key changed
```

**Takeaway (answers "are Redis transactions ACID?"):** Partly. `MULTI/EXEC` gives isolation and runs the queue uninterrupted, but there's no rollback on runtime failure. `WATCH` adds optimistic locking on top — abort and retry if the watched key changed.

## 9.4 — Pipelining vs Transaction, timed (redis-cli only)

- **Individual commands** (1000x separate `SET`, one round trip each): slow — pays full network latency per command.
- **Pipelined** (`redis-cli --pipe`, 1000 commands in one round trip, no `MULTI`): dramatically faster — often 10–50x.
- **`MULTI`/`EXEC`** of the same 1000 commands: roughly the **same speed** as pipelining (also sent/read as one batch under the hood).

**Key distinction — speed vs guarantees:**

| | Pipelining | `MULTI`/`EXEC` |
|---|---|---|
| Round trips | 1 (batched) | 1 (batched) |
| Isolation | **No** — another client's commands can interleave on the server | **Yes** |
| Atomicity (rollback) | N/A | No (per 9.3) |
| Purpose | Network efficiency | Correctness under concurrency |

**Common misconception to avoid:** pipelining is NOT a concurrency-safety mechanism. It's purely about not paying network latency N times. Only `MULTI`/`EXEC` (+ `WATCH`) gives isolation.

## 9.5 — Lua: atomic check-and-decrement, and the 6.3 rate limiter

### Check-and-decrement (replaces the DECR-then-compensate approach from 9.2)
```lua
local stock = tonumber(redis.call('GET', KEYS[1]))
if stock == nil then return -2 end
if stock <= 0 then return -1 end
redis.call('DECR', KEYS[1])
return stock - 1
```
The check and the decrement happen **inside Redis as one atomic script** — no round trip back to the app in between, no oversell-then-compensate dance needed (unlike the 9.2 Redis `DECR` version, which had to `INCR` back if it oversold).

```java
private static final RedisScript<Long> BUY_SCRIPT = RedisScript.of(
    "local stock = tonumber(redis.call('GET', KEYS[1])) " +
    "if stock == nil then return -2 end " +
    "if stock <= 0 then return -1 end " +
    "redis.call('DECR', KEYS[1]) " +
    "return stock - 1",
    Long.class
);
```
This method (`buyWithLua`) **replaced** `buyWithRedisDecr` entirely.

### Rate limiter from 6.3, rewritten as Lua
```lua
local current = redis.call("INCR", KEYS[1])
if current == 1 then
  redis.call("EXPIRE", KEYS[1], ARGV[1])
end
if current > tonumber(ARGV[2]) then
  return 0
end
return 1
```
Closes the exact race identified back in Phase 6: plain `INCR` + `EXPIRE` as two separate commands leaves a window where a crash between them leaves a counter with no TTL, blocking a client forever. Doing both inside one Lua script removes that window entirely.

## Phase 9 — Final code state
- `buy(id)` — SQL conditional `UPDATE` fix
- `buyWithLua(id)` (exposed via `/buy-redis`) — Lua check-and-decrement fix
- (Redis `DECR`-only version and its seed-helper endpoint were removed / superseded by the Lua version)

## Phase 9 — Interview Q&A

1. **What is a race condition in "check then save"?**
   Two requests both read the same value, both compute a new value based on that same stale read, and one update overwrites the other — the "lost update" problem.
2. **`DECR` vs conditional `UPDATE`?**
   `DECR` is atomic in Redis but requires stock to live in Redis. A conditional `UPDATE ... WHERE stock > 0` needs no extra infrastructure and keeps Postgres as the single source of truth.
3. **Are Redis transactions ACID?**
   Partly — isolation and uninterrupted execution, yes; rollback on runtime failure, no. `WATCH` adds optimistic locking.
4. **Pipeline vs transaction vs Lua?**
   Pipelining saves round trips, not atomic. A transaction is atomic (isolated) but can't make mid-stream decisions. Lua is atomic and can read/decide/write, but a long script blocks every other client while it runs.

---

# Phase 10: Distributed Locks
*Branch: `m10-locks`*

**Outcome:** built a correct single-node lock by hand, compared it to the Redisson library version, and understand exactly where and why locks can fail.

**Why locks, when 9.2 already solved atomicity?** A lock protects a **multi-step critical section** (e.g., check stock → call a slow external service → decrement) — something no single atomic Redis command or SQL statement can cover, because the atomicity needs to span the *entire sequence*, not just one operation.

## 10.1 — Raw lock: `SET lock:product:{id} <token> NX PX 5000`

```java
public String lockedCheckout(Long id) {
    String lockKey = "lock:product:" + id;
    String token = UUID.randomUUID().toString();

    Boolean acquired = redisTemplate.opsForValue().setIfAbsent(
            lockKey, token, Duration.ofMillis(5000));

    if (!Boolean.TRUE.equals(acquired)) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout already in progress for: " + id);
    }
    try {
        Thread.sleep(1000); // simulated multi-step critical section
    } finally {
        redisTemplate.delete(lockKey); // naive release — fixed in 10.2
    }
    return "Checkout completed for product " + id;
}
```
- `NX` — atomic acquire, only succeeds if the key doesn't already exist.
- `PX 5000` — safety expiry, so a crash while holding the lock doesn't block the resource forever.
- Unique `<token>` per attempt — needed for 10.2's safe unlock.

**Test:** fired 3 concurrent requests at the same product → exactly **one** succeeded (200), the other two got **409 Conflict** immediately. Logs showed the critical section ("Checkout started"/"finished") exactly once.

## 10.2 — Safe unlock + the TTL-expiry overlap problem

### Part 1: Lua compare-and-delete unlock
```lua
if redis.call("GET", KEYS[1]) == ARGV[1] then
  return redis.call("DEL", KEYS[1])
else
  return 0
end
```
A plain `DEL` (10.1) deletes whatever is at the key — even if it's no longer *your* lock (e.g. it already expired and someone else acquired it). This script only deletes if the value still matches your token, and the check + delete happen as **one atomic step** (must be Lua — two separate commands would reopen the exact race being closed).

### Part 2: Deliberately reproduced the TTL-expiry overlap
Shrunk `PX` to 500ms, kept the critical section at 2000ms (`Thread.sleep`). Fired two requests ~0.7s apart.

**Result (confirmed):** the first request's lock expired *while it was still "inside" the critical section*. The second request successfully acquired the now-empty lock key and started its own critical section. **Both "BROKEN checkout started" log lines appeared overlapping**, before either finished — proof that two callers believed they held exclusive access simultaneously.

**Takeaway:** safe unlock alone only stops you from deleting *someone else's* lock. It does nothing about the lock expiring too early in the first place — that's a separate problem, solved by renewal (watchdog, 10.3) or by accepting the risk.

## 10.3 — Redisson `RLock` and its watchdog

**Dependency:** `org.redisson:redisson-spring-boot-starter:4.6.1` — Redisson added explicit Spring Boot 4.1.0 integration (and Spring Data Redis 4.1.0 integration) in its 4.6.0 release, compatible with this project's Boot 4.1.1.

```java
@Bean
public RedissonClient redissonClient() {
    Config config = new Config();
    config.useSingleServer().setAddress("redis://localhost:6379");
    return Redisson.create(config);
}
```

```java
public String lockedCheckoutRedisson(Long id) {
    RLock lock = redissonClient.getLock("lock:product:" + id);
    boolean acquired = lock.tryLock(); // watchdog mode: default 30s lease, auto-renewed
    if (!acquired) {
        throw new ResponseStatusException(HttpStatus.CONFLICT, "Checkout already in progress for: " + id);
    }
    try {
        Thread.sleep(2000); // same duration that broke the raw lock in 10.2
    } finally {
        lock.unlock(); // ownership checked internally — safe by default
    }
    return "Checkout completed (Redisson) for product " + id;
}
```

**How the watchdog works:** without an explicit lease time, Redisson sets a default lease (30s) and a background task extends it (roughly every third of the lease, ~10s) while the holding thread/JVM is alive. If the process dies, renewals stop and the lock expires naturally.

**Test (same timing as the 10.2 overlap demo — second request at 0.7s, first still sleeping until 2s):** this time the second request was correctly **rejected (409)** — only one `"Redisson checkout started"` line appeared. The watchdog kept renewing the lock underneath the first holder, so it never expired mid-work.

## 10.4 — Discussion: Redlock, failover, fencing tokens, lock vs alternatives

**What the watchdog does NOT fix:** it only prevents *early expiry while the holder is alive on a single node*. It does nothing if the Redis node itself crashes entirely mid-lock.

**Redlock:** an algorithm for acquiring the same lock across multiple independent Redis instances (typically 5), requiring a majority (3 of 5) before considering the lock held — so one node dying doesn't erase the lock's existence.
- **Controversial:** Martin Kleppmann argued Redlock doesn't guarantee mutual exclusion under certain timing/clock assumptions (GC pauses, clock drift) and that genuine correctness needs a consensus system (ZooKeeper, etcd). Redis's creator (antirez) rebutted, defending its practical safety under reasonable assumptions.
- **Honest interview answer:** Redlock improves availability across node failures; whether it gives strict correctness guarantees is debated — for genuinely critical correctness, fencing tokens or a consensus system are the safer bet.

**Failover losing a lock — concrete mechanism:**
Client A acquires lock on master → master hasn't replicated that write to its replica yet (async replication) → master crashes → replica promoted to new master with **no record the lock ever existed** → Client B acquires "the same" lock → both A and B now believe they hold it.

**Fencing tokens — the actual fix for stale-holder writes:**
A strictly increasing number issued on every lock acquisition (e.g., `INCR` a companion fence-counter key). The **protected resource itself** (not Redis) rejects any write carrying a lower fencing number than one it has already seen. Moves the guarantee from "trust the lock" to "trust a monotonic counter checked at write time" — considered more robust than the lock alone, since it also protects against a paused holder waking up late and writing after being superseded.

**Lock vs `SET NX` vs conditional `UPDATE`:**

| Approach | Blocks others up front? | Extra infra? | Best for |
|---|---|---|---|
| Conditional `UPDATE` (9.2) | No — DB arbitrates | None | Single-statement atomic changes |
| `SET NX` alone (6.1) | Yes, atomically, one-shot | None | "First one wins" claims, no ongoing section |
| Full lock (10.1–10.3) | Yes, for the whole duration | Redis (+ optionally Redisson) | Multi-step operations spanning calls/systems |

**Guiding principle:** use the simplest mechanism that solves the actual problem. A lock is the heaviest of the three — reach for it only when the critical section can't be expressed as a single atomic command.

## Phase 10 — Interview Q&A

1. **How do you implement a distributed lock in Redis?**
   `SET lock:key <unique-token> NX PX <ttl>`. `NX` makes acquisition atomic; `PX` makes it expire so a crash can't hold it forever.
2. **Why a unique token, and why unlock with Lua?**
   If your lock expired and someone else acquired it, a plain `DEL` would delete *their* lock. The script deletes only if the value is still your token, atomically (check + delete must be one step).
3. **How does Redisson's watchdog work?**
   Without an explicit lease, Redisson sets a default 30s lease and a background task renews it periodically while the JVM/thread is alive. If the process dies, renewals stop and the lock expires.
4. **Is a single-node Redis lock safe?**
   Safe enough for efficiency, not for strict correctness. Pauses/slow work can expire the lock early (fixed by watchdog renewal), and asynchronous replication can lose it entirely on failover (not fixed by the watchdog — needs Redlock or acceptance of the risk).
5. **What is a fencing token?**
   A monotonically increasing number tied to each lock acquisition. The protected resource rejects writes carrying a lower number than one it has already seen, protecting against stale/paused holders writing late.
6. **Lock vs optimistic locking vs conditional `UPDATE`?**
   A lock blocks others up front for the whole critical section. Optimistic locking (`WATCH`) lets everyone try and rejects the loser at commit time. A conditional update is the simplest — no extra infrastructure, single atomic statement.

---

## Errors hit / gotchas (fill in your own specifics)

- `IllegalArgumentException: Cache 'product' does not allow 'null' values` — hit in Phase 8 (penetration), not 9/10, but relevant pattern: `RedisCacheManager` rejects nulls unless `.disableCachingNullValues()` is explicitly *not* called.
- WSL/bash bracketed-paste mode mangled multi-line `for` loops pasted into the terminal (`^[[200~` escape codes) — fixed with `bind 'set enable-bracketed-paste off'`, or by writing test scripts to a file and running with `bash script.sh` instead of pasting directly.
- Redisson version compatibility with Spring Boot 4.1.1 needed active verification — not something to assume from prior Boot-3-era tutorials. Confirmed via Redisson's own release notes (4.6.0 added Spring Boot 4.1.0 integration).

## Time taken (fill in)

- Phase 9 total: ___
- Phase 10 total: ___
