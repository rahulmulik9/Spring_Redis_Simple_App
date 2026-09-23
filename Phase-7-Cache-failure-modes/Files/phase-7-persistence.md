## Phase 7 — Substep 7.1: Persistence

**What I did:**
- Confirmed: no volume + `docker compose down` → data gone (container removed = data gone, `stop`/`start` alone would NOT lose it)
- Added `redis-data` volume mounted at `/data` → data survives `down` + `up`
- Enabled AOF: `--appendonly yes --appendfsync everysec`
- Redis 7 uses `/data/appendonlydir/` (a directory), not a single flat `.aof` file — different from older tutorials
- Ran `BGSAVE` manually → confirmed `dump.rdb` in `/data`

**Gotchas / errors hit:**
- (fill in anything that actually tripped you up — e.g. permission issues on the volume, wrong path, etc.)

**Fsync policies (conceptual, not deeply benchmarked):**
- `always` — fsync every write, safest, slowest
- `everysec` — batches ~1s, practical default
- `no` — OS decides, fastest, riskiest

**Time taken:** ___ min

**Still to test:** kill mid-write (step 5) to see AOF vs RDB loss window directly