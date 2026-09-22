# Phase 5 Notes: Redis Data Structures

One structure per feature. Each section: what it is, the commands we used, a simple example, and why we picked it over the obvious alternative.

---

## 1. String + INCR — view counter

**What it is:** the simplest Redis type. One key, one value.

**Commands:** `INCR`

**Our key:** `shop:views:{productId}`

**Example**
```
INCR shop:views:1   -> 1
INCR shop:views:1   -> 2
INCR shop:views:1   -> 3
```

**Why not do the math in Java?**
```
long v = Long.parseLong(get(key));   // read
set(key, String.valueOf(v + 1));     // write
```
If two requests run this at the same time, both might read `2`, both write `3`. One view is lost, forever, with no error.

`INCR` does the read-add-write as **one step inside Redis**, so it can never be interrupted halfway. Proven with 20 parallel requests → always ends at the exact right number.

**Where it's used:** `shop:views:{id}` counts total views per product.

---

## 2. Hash — shopping cart

**What it is:** a key that holds multiple field → value pairs, like a small object.

**Commands:** `HINCRBY`, `HGETALL`, `HDEL`, `EXPIRE`

**Our key:** `shop:cart:{userId}` → field = `productId`, value = `quantity`

**Example**
```
HINCRBY shop:cart:1 4 2   -> 2      (add 2 milk)
HINCRBY shop:cart:1 4 3   -> 5      (add 3 more milk)
HINCRBY shop:cart:1 7 1   -> 1      (add 1 bread, separate field)
HGETALL shop:cart:1       -> "4" "5" "7" "1"
```

**Why not one JSON string for the whole cart?**
```
{"4": 2, "7": 1}
```
To add 1 more milk: read the whole string, parse it in Java, change the number, write the whole string back. Two "add" clicks at once both read the same starting JSON — one writes its version, the other overwrites it. An item's change disappears silently.

A hash changes **one field** in one atomic Redis command (`HINCRBY`). Two clicks touching the same field just add up correctly; two clicks touching different fields never interfere at all.

**Decrease:** `HINCRBY` with a negative number subtracts. When the result reaches 0 or less, we run `HDEL` to remove the field completely (so it never sits at 0 or negative).

**TTL:** set on the whole hash key (30 min, renewed on every add), so an abandoned cart cleans itself up.

---

## 3. List — recently viewed

**What it is:** an ordered sequence of values, like a linked list. Fast to push/pop from either end.

**Commands:** `LPUSH`, `LTRIM`, `LRANGE`

**Our key:** `shop:recent:{userId}`

**Example** (viewing products 1, 2, 3 in order)
```
LPUSH shop:recent:1 1        -> [1]
LPUSH shop:recent:1 2        -> [2, 1]
LPUSH shop:recent:1 3        -> [3, 2, 1]
LTRIM shop:recent:1 0 4      -> keep only first 5 (no-op here, list is short)
LRANGE shop:recent:1 0 -1    -> ["3", "2", "1"]   (most recent first)
```

`LPUSH` always adds to the **front**, so the newest view is always first. `LTRIM shop:recent:1 0 4` keeps only positions 0 to 4 (5 items) and drops the rest — this is how we cap the list at 5 without writing any counting logic ourselves.

**Duplicates allowed:** viewing product 2 twice makes it appear twice in the list — a list does not deduplicate. That's fine for "recently viewed", where we want to know the last 5 *views*, not the last 5 *unique* products.

---

## 4. Set — wishlist

**What it is:** an unordered collection of unique values. No duplicates possible, ever.

**Commands:** `SADD`, `SREM`, `SMEMBERS`, `SISMEMBER`

**Our key:** `shop:wishlist:{userId}`

**Example**
```
SADD shop:wishlist:1 2       -> 1 (added)
SADD shop:wishlist:1 2       -> 0 (already there, no change, no error)
SADD shop:wishlist:1 5       -> 1 (added)
SMEMBERS shop:wishlist:1     -> {"2", "5"}   (order not guaranteed)
SISMEMBER shop:wishlist:1 2  -> 1 (yes, it's a member)
```

**Why a set instead of a list?** A wishlist should never show the same product twice, and we don't care about order, only "is this product on the list or not". `SADD` is naturally idempotent — adding the same item twice has zero extra effect, no duplicate check needed in our code.

---

## 5. Sorted set — top products

**What it is:** like a set (unique members), but each member also has a numeric **score**, and Redis keeps members ordered by score automatically.

**Commands:** `ZINCRBY` (via `incrementScore`), `ZREVRANGE ... WITHSCORES`

**Our key:** `shop:top-products` → member = `productId`, score = view count

**Example**
```
ZINCRBY shop:top-products 1 1     -> product 1 score = 1
ZINCRBY shop:top-products 1 1     -> product 1 score = 2  (viewed again)
ZINCRBY shop:top-products 1 3     -> product 3 score = 1
ZREVRANGE shop:top-products 0 -1 WITHSCORES
   -> "1" "2"   "3" "1"        (highest score first)
```

**Why not just sort a list of products by their String view counters in Java?** That means reading every product's counter, then sorting in application code every time someone asks for the top list — slow as the catalog grows, and it happens on every single request.

A sorted set keeps itself ordered as scores change. `ZREVRANGE 0 4` for "top 5" is a single fast Redis command (O(log N + M)), no sorting logic in Java at all.

**Note:** we keep both `shop:views:{id}` (String) and `shop:top-products` (sorted set) — they answer different questions: "how many views does product 1 have?" (String, O(1)) vs "which products have the most views?" (sorted set, ordered).

---

## Quick comparison table

| Structure | Unique members? | Ordered? | Our use | Key command |
|---|---|---|---|---|
| String | n/a (one value) | n/a | View counter | `INCR` |
| Hash | n/a (fields are unique) | insertion order (not guaranteed) | Cart | `HINCRBY` |
| List | No (duplicates allowed) | Yes, by insertion | Recently viewed | `LPUSH` + `LTRIM` |
| Set | Yes, always unique | No | Wishlist | `SADD` |
| Sorted set | Yes, unique members | Yes, by score | Top products | `ZINCRBY` |

## Common thread across all five

Every one of these solves the same underlying problem: **do the read-modify-write as one atomic step inside Redis**, instead of pulling data into Java, changing it, and writing it back — which breaks under concurrent requests. `INCR`, `HINCRBY`, `LPUSH`+`LTRIM`, `SADD`, and `ZINCRBY` are all atomic single commands for exactly this reason.
