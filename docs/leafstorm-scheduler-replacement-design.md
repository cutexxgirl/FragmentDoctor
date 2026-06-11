# Leafstorm Scheduler Replacement Design

## Purpose

This document is for future Codex in a new chat.

The current Leafstorm work went through three stages:

- `1.0.0`: scheduled leaf tick limiter.
- `1.0.1`: internal deferred queue, origin split, dedup, no repeated doctor `schedule(...)`.
- `1.0.2`: softer deferred drain after new scans showed the drain itself can become expensive.

Those versions are useful and should stay as a fallback, but they are still reactive. They intervene after a leaf scheduled tick storm already reached `LevelTicks`. The next architecture should prevent the storm from being born.

The goal is maximum performance and quality without async world mutation, without deleting forests, and without relying on bigger and bigger limiter budgets.

## Latest Evidence

New 120 second scans showed a mixed result for `1.0.1`.

Generating chunks and forests:

- leafstorm on: TPS `8.21`, avg MSPT `99.3`, p95 `248.4`, p99 `602.7`, max `1755.2`
- leafstorm off: TPS `8.79`, avg MSPT `103.2`, p95 `298.7`, p99 `490.8`, max `1624.8`

Mostly loaded forest:

- leafstorm on: TPS `14.20`, avg MSPT `61.7`, p95 `133.5`, p99 `213.3`
- leafstorm off: TPS `19.39`, avg MSPT `37.5`, p95 `74.4`, p99 `117.7`

Interpretation:

- The limiter reduced some p95 pressure during active generation.
- The limiter did not reliably improve TPS.
- In a mostly loaded forest, `LeafstormDoctor#drainDeferredTicks` became a visible cost source.
- `1.0.2` softened that drain, but the deeper problem remains: too many duplicate leaf scheduled ticks exist in the first place.

## Core Idea

Do not let leaf storms enter the vanilla scheduled tick queue as thousands of duplicate tasks.

Instead of:

```text
neighbor update
-> LeavesBlock#updateShape
-> LevelAccessor#scheduleTick
-> LevelTicks#schedule
-> vanilla queue receives 100000 leaf ticks
-> doctor later tries to run/defer/dedup them
```

Use:

```text
neighbor update
-> LeavesBlock#updateShape
-> LevelAccessor#scheduleTick
-> LevelTicks#schedule
-> Fragment Doctor intercepts leaf scheduled tick
-> canonical leaf scheduler marks one dirty job per leaf position
-> small fair main-thread batches execute due jobs later
```

One leaf position should not be able to create 20 identical scheduled tasks. One chunk should not be able to consume the whole server tick. One forest should not be able to own the scheduler.

This is still a queue, but it is not a queue layered on top of an already-overloaded vanilla queue. It is a replacement scheduler for leaf scheduled ticks.

## Non-Goals

- Do not execute `BlockState#tick`, `LeavesBlock#tick`, or any world mutation off-thread.
- Do not rewrite worldgen.
- Do not disable biomes, trees, modded leaves, or logs as the primary fix.
- Do not globally cancel all scheduled block ticks.
- Do not intercept non-leaf ticks.
- Do not make scanner summaries count scheduler coalescing as primary vanilla leafstorm source.

Safe async work remains limited to log parsing, reporting, statistics, and preparing immutable snapshots. Live `Level` reads and world mutations stay on the server thread.

## Architecture

### 1. Intercept At Source

Hook `LevelTicks.schedule(ScheduledTick<T> tick)` at `HEAD`, cancellable.

If all conditions are true, cancel the vanilla schedule and send the job to the Fragment Doctor scheduler:

- leafstorm scheduler replacement is enabled;
- `tick` is not null;
- `tick.type()` is a `Block`;
- the block is a `LeavesBlock`;
- the tick belongs to a server-side `LevelTicks` instance.

Otherwise, pass through to vanilla exactly as before.

This is the key change: the leaf tick never enters the vanilla scheduled tick queue, so the vanilla queue cannot explode from duplicate leaf reschedules.

### 2. Canonical Leaf Job

Use a canonical job map per `LevelTicks` instance:

```text
IdentityHashMap<LevelTicks instance, LeafScheduler>
```

Inside each `LeafScheduler`:

```text
Map<LeafKey, LeafJob> jobsByKey
PriorityQueue<HeapEntry> dueHeap
Long2IntMap chunkBudgetCounters
```

Recommended key:

```text
LeafKey(blockRegistryId, packedBlockPos)
```

The `LevelTicks` instance identity separates dimensions/worlds. The block registry id keeps diagnostics precise and prevents odd modded edge cases. In practice, packed position alone may be enough because a position cannot hold multiple blocks at once, but keeping the block id is safer for scanner attribution.

### 3. Avoid Mutable Heap Bugs

Do not use plain `ArrayDeque`.

A bug in the current deferred design is possible when an existing deferred tick has its trigger tick changed while it is already inside the deque. If the front entry is moved into the future, ready entries behind it can be blocked.

Use one of these designs:

1. Indexed heap with decrease/increase-key support.
2. Simpler versioned heap entries:

```text
LeafJob {
  LeafKey key;
  Object type;
  BlockPos pos;
  long dueTick;
  long version;
  int attempts;
}

HeapEntry {
  LeafKey key;
  long dueTick;
  long version;
  long sequence;
}
```

When a repeated schedule arrives:

- update the canonical `LeafJob`;
- increment `version`;
- push a new `HeapEntry`;
- ignore stale heap entries whose version no longer matches `jobsByKey`.

This avoids expensive heap mutation and preserves correct ordering.

### 4. Due Tick Semantics

A repeated dirty mark should not create a duplicate job.

Recommended rule:

- if an existing job is already due earlier, keep the earlier due tick;
- if the new schedule is earlier, move the job earlier;
- if the new schedule is later, keep the existing due tick but update diagnostic counters.

In other words, coalescing should not silently delay a leaf that vanilla wanted to tick sooner. Budget pressure may delay execution, but duplicate schedule calls should not.

If a job is skipped due to budget, reschedule it for the next server tick or leave it at the head as due. Do not push it far into the future unless the system is in emergency mode.

### 5. Main-Thread Execution

Drain scheduler jobs from `LevelTicks.runCollectedTicks` or another safe server-thread hook.

Use the vanilla-provided `BiConsumer<BlockPos, T> ticker` when executing jobs:

```text
ticker.accept(job.pos, job.type)
```

Do not call `LeavesBlock#tick` directly. The vanilla ticker path preserves mod hooks and the normal `ServerLevel#tickBlock` behavior.

Before execution, stale jobs should be dropped when possible:

- block at the position is no longer the same leaf block;
- job is too old;
- job count exceeds memory caps;
- the owning `LevelTicks` appears abandoned.

If level context is not available from the mixin, keep this conservative: use the vanilla ticker and rely on vanilla to validate current state. Add deeper stale checks only when a safe `ServerLevel` reference is available.

### 6. Fair Budgets

Budgets should be global and per chunk.

Starting defaults:

```text
globalLeafJobsPerTick = 64
perChunkLeafJobsPerTick = 8
healthyMspt = 30
softMspt = 45
hardMspt = 70
maxQueuedLeafJobs = 250000
jobTtlTicks = 20 * 120
```

Adaptive behavior:

- MSPT below `30`: drain faster, maybe `128` global.
- MSPT `30..45`: normal drain, maybe `64` global.
- MSPT `45..70`: slow drain, maybe `8..16` global.
- MSPT above `70`: pause or drain only `0..4`.
- one chunk cannot consume more than `perChunkLeafJobsPerTick`.
- if the queue is huge but shrinking and MSPT is healthy, allow recovery budget.
- if the queue is huge and growing, prioritize coalescing over execution.

Chunk fairness is critical. Without it, one redwood/wisteria/larch chunk can starve every other chunk.

### 7. Coalescing Metrics

The scanner needs to distinguish source from scheduler behavior.

Suggested origins:

- `vanilla_leaf_intercepted`: vanilla attempted to schedule a leaf tick and Fragment Doctor intercepted it.
- `scheduler_enqueued`: a new canonical leaf job was created.
- `scheduler_coalesced`: a repeated schedule updated or touched an existing job.
- `scheduler_executed`: the scheduler executed a due leaf job via vanilla ticker.
- `scheduler_skipped_budget`: a due job was left pending due to global/chunk/MSPT budget.
- `scheduler_dropped_stale`: a job was dropped because it was stale or expired.
- `vanilla_schedule`: non-intercepted vanilla scheduled ticks.

Scanner summaries should not present `scheduler_coalesced` as a primary leafstorm source. The primary source is the original vanilla attempt; coalescing is mitigation.

### 8. Configuration

Keep existing limiter settings as fallback.

Add a separate scheduler replacement section:

```toml
[doctor.leafstorm.scheduler]
enabled = false
shadowMode = true
interceptVanillaLeafSchedules = true
globalBudget = 64
perChunkBudget = 8
healthyMsptThreshold = 30.0
softMsptThreshold = 45.0
hardMsptThreshold = 70.0
maxJobs = 250000
jobTtlTicks = 2400
logEveryTicks = 100
```

Suggested rollout:

1. `shadowMode=true`: log what would be intercepted, do not cancel vanilla.
2. `enabled=true`, `shadowMode=false`: cancel leaf schedules and use canonical scheduler.
3. Keep `1.0.2` limiter available behind config in case replacement has a compatibility issue.

## Correctness Invariants

These are more important than raw speed.

- Non-leaf scheduled ticks must behave exactly like vanilla.
- Leaf jobs must execute only on the server thread.
- One canonical job per leaf key per `LevelTicks` instance.
- Heap ordering must not be blocked by stale or mutated entries.
- Earlier due ticks must not be overwritten by later duplicate schedules.
- Repeated updates must be counted and visible, but not multiplied into world work.
- Persistent/player-placed leaves must retain vanilla behavior when their tick eventually executes.
- Unsupported leaves must still eventually decay or update.
- The system must have bounded memory.
- If the scheduler is disabled, vanilla behavior must be restored immediately.

## Compatibility Risks

### Modded Leaves

Some modded leaves may subclass `LeavesBlock` and rely on scheduled ticks for special behavior. They still get scheduled, but through the canonical scheduler. That should be acceptable if the scheduler preserves due order and eventually executes jobs.

Risk: a mod schedules multiple different semantic leaf ticks at the same position. Mitigation: include block registry id in the key, preserve earliest due tick, and keep diagnostics for repeated schedules.

### Chunk Unload

The scheduler may hold jobs for chunks that unload.

Mitigations:

- TTL expiry.
- max job cap.
- optional chunk loaded check if safe level context is available.
- drop or freeze jobs on chunk unload only after verifying hooks.

### Vanilla Queue Semantics

`ScheduledTick` has timing/order fields. The replacement should preserve:

- due tick;
- insertion ordering for equal due tick where practical;
- block type;
- position.

If the exact sub-tick order is accessible, carry it into `HeapEntry`. If not, use a monotonic sequence number.

## Implementation Plan

### Phase 1: Shadow Scheduler

- Add `LeafstormScheduler`.
- Hook `LevelTicks.schedule`.
- Detect leaf ticks.
- Log `vanilla_leaf_intercepted` and what canonical key would be used.
- Do not cancel vanilla.
- Add status command fields:
  - pending jobs;
  - coalesced count;
  - executed count;
  - skipped count;
  - stale drops;
  - top leaf ids;
  - top chunks.

### Phase 2: Active Scheduler

- Enable cancellation for leaf schedules.
- Store canonical jobs.
- Drain at server-thread hook using `ticker.accept`.
- Apply global and per-chunk budgets.
- Keep old limiter disabled or as emergency fallback only.

### Phase 3: Scanner Quality

- Split origin summaries cleanly.
- Add queue snapshots:
  - vanilla block tick queue;
  - scheduler pending jobs;
  - coalesced per second;
  - executed per second;
  - skipped per second.
- Add top leaf ids and top chunks.

### Phase 4: Real Pack Validation

Run four comparable scans:

- scheduler off, generating forest chunks;
- scheduler on, generating forest chunks;
- scheduler off, mostly loaded forest;
- scheduler on, mostly loaded forest.

Acceptance:

- TPS should not be worse than scheduler off in loaded forest.
- In active generation, p95 and p99 MSPT should improve without max spikes getting worse.
- Vanilla block tick queue should not explode with leaf ticks.
- Scheduler pending jobs should stay bounded and eventually shrink.
- Visual check: leaves do not mass disappear incorrectly, but unsupported leaves still update later.

## Future Stretch: Replace Leaf Decay Mechanics

The radical version is not to tick every leaf at all.

Instead, leaf decay could become event-driven:

- tree/log removal marks nearby leaves dirty;
- chunk idle tick processes a small leaf graph batch;
- player-caused tree changes get priority;
- worldgen leaf checks wait until chunk generation/post-processing calms down.

That is a bigger semantic change and should not be the first replacement. The first mature target is safer: intercept leaf scheduled ticks and canonicalize them.

## Codex Notes

The external advice is directionally right: stop treating leafstorm as a patient to stabilize after collapse. Prevent duplicate scheduled work at the source.

My additions:

- Use a priority heap, not `ArrayDeque`.
- Prefer versioned heap entries over mutating queued entries.
- Preserve earliest due tick when coalescing.
- Execute through vanilla `ticker.accept`, not direct block methods.
- Keep a `shadowMode` rollout path.
- Make scanner origins explicit so doctor work is not blamed as vanilla source.
- Keep the old limiter as a safety net until replacement proves itself in the real pack.

The intended end state is:

```text
vanilla leaf schedule attempts: high but harmless
canonical scheduler jobs: bounded
per tick leaf work: small and fair
MSPT: stable
world behavior: close to vanilla
```

