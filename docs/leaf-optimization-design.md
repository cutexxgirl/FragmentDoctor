# Fragment Doctor Leaf Optimization Design

## Goal

Build a Fragment Doctor module that optimizes Minecraft leaf update/decay storms without removing worldgen content.

The goal is not "fast leaf decay" as a gameplay feature. The goal is server stability:

- keep TPS close to 20 when large forests/chunks generate;
- reduce MSPT spikes from leaf scheduled tick storms;
- preserve trees, biomes, modded leaves, drops, and normal gameplay;
- keep all world mutations on the server thread;
- remain compatible with Tectonic, Regions Unexplored, other worldgen mods, and preferably C2ME.

## Current Evidence

Fragment Doctor scans showed severe TPS/MSPT drops caused by scheduled block ticks from leaves.

Original Regions Unexplored redwood scan:

- `regions_unexplored:redwood_leaves`: about `1,362,165` scheduled tick enqueues in 300 seconds.
- MSPT often above `100`.
- p95/p99/max spikes were extreme.

After fixing Regions Unexplored log tags, redwood improved, but leafstorms remained from other leaves:

- `minecraft:spruce_leaves`
- `regions_unexplored:pine_leaves`
- `regions_unexplored:larch_leaves`
- `regions_unexplored:wisteria_leaves`
- occasional vanilla leaves and fluids from chunk post-processing.

Fresh scan with leafstorm enabled:

- Before/early heavy storm:
  - TPS: `9.50`
  - avg MSPT: `90.50`
  - max MSPT: `505.06`
  - p95 MSPT: `228.98`
  - block tick queue average: about `275,520`
- Later scan:
  - TPS: `18.96`
  - avg MSPT: `38.37`
  - max MSPT: `286.31`
  - p95 MSPT: `114.37`
  - block tick queue average: about `89,875`

Previous no-limiter scan was much worse:

- TPS: about `2.00`
- avg MSPT: about `471.95`
- max MSPT: about `1976.60`
- p95 MSPT: about `1697.61`

Conclusion:

- The leafstorm limiter works and stabilizes the game.
- The underlying workload still exists.
- The next step is reducing duplicate/unnecessary work, not only delaying it.

## What Leaf Decay Is

Minecraft leaves have a distance/decay system:

- Leaves track distance to nearest log.
- If connected to logs, leaves remain.
- If too far from logs, leaves may decay.
- Player-placed leaves are usually `persistent=true` and should not decay.

This is useful because it prevents floating leaf blobs after trees are chopped.

The problem is not leaf decay itself. The problem is the scale of update propagation:

- large trees;
- modded branches;
- dramatic terrain;
- chunk post-processing;
- worldgen placing many tree blocks;
- neighbor updates triggering `LeavesBlock#updateShape`;
- `LeavesBlock#updateShape` scheduling thousands of future block ticks.

Typical stack:

```text
LeavesBlock#updateShape
BlockBehaviour$BlockStateBase#updateShape
LevelAccessor#scheduleTick
LevelTicks#schedule
```

## Existing Mods

Some existing mods are related, but they mostly target player-caused leaf decay.

### Accelerated Decay

URL: `https://modrinth.com/mod/accelerated-decay`

Relevant details:

- Fabric, Forge, NeoForge.
- Supports Minecraft `1.21-1.21.4`.
- Speeds up leaf decay.
- It avoids rapidly ticking leaf blocks and removes leaves once they are ready to decay.
- Good candidate to test, but it targets leaf decay after supports are removed, not necessarily worldgen leaf scheduled tick storms.

### Fast Leaf Decay

URL: `https://www.curseforge.com/minecraft/mc-mods/fast-leaf-decay`

Relevant details:

- Classic fast leaf decay mod.
- Has NeoForge versions for 1.21.x.
- Mostly gameplay-oriented: trees decay faster after being chopped.

### Minimalism Leaf Decay

URL: `https://modrinth.com/mod/minimalism_leaf_decay`

Relevant details:

- Fabric only.
- Server-side.
- Immediate/lightweight leaf decay.
- Not useful directly for NeoForge pack compatibility.

### Sepals

URL: `https://modrinth.com/mod/sepals`

Relevant details:

- Experimental server optimization mod.
- Targets AI/entity/task bottlenecks.
- Not specifically a leaf scheduled tick optimizer.

### Conclusion On Existing Mods

No discovered mod clearly provides:

- adaptive scheduled leaf tick limiting;
- duplicate leaf tick suppression;
- worldgen leaf update batching;
- source attribution for leafstorms;
- compatibility-focused handling for Tectonic, Regions Unexplored, C2ME, and large modpacks.

That is the niche for Fragment Doctor.

## Compatibility Requirements

### Tectonic

Tectonic likely amplifies leafstorms by producing more dramatic terrain and larger/high-altitude forest generation contexts.

Fragment Doctor must not assume Tectonic is "bad". It should treat Tectonic/worldgen mods as context:

- bigger terrain;
- more chunk post-processing;
- more exposed logs/leaves/water/lava updates;
- more neighbor update cascades.

Required behavior:

- support Tectonic worlds without disabling terrain features;
- avoid modifying biome placement;
- avoid removing trees;
- throttle/optimize update workload instead.

### Regions Unexplored

Regions Unexplored already exposed one root data issue:

- some logs/branches were missing from `minecraft:logs`;
- vanilla leaves could fail to detect support;
- this caused redwood leafstorms.

Fragment Doctor should add future runtime checks:

- scan modded leaves/logs/branches tags;
- warn if leaves exist but related logs/branches are absent from `minecraft:logs`;
- optionally provide a doctor runtime datapack/tag patch.

Important:

- Do not assume only redwood is affected.
- Also watch pine, larch, wisteria, and other modded leaves.

### Other Worldgen Mods

Must work with:

- biome mods;
- tree feature mods;
- structure mods;
- terrain mods;
- mods that place vanilla leaves/logs;
- mods that place custom leaves/logs/branches.

Do not hardcode only `minecraft:spruce_leaves`.

Use block behavior/tags:

- `#minecraft:leaves`
- `#minecraft:logs`
- `LeavesBlock` when possible;
- registry IDs for reporting.

### C2ME

C2ME can alter chunk generation, chunk post-processing, scheduling, and worldgen threading.

Compatibility concerns:

- Never mutate world/chunk state off server thread.
- Avoid assuming vanilla-only chunk generation call order.
- Do not race with C2ME worker threads.
- Intercept server-thread scheduled tick execution, not async generation internals, unless a C2ME-safe integration is proven.

Preferred approach with C2ME:

- observe scheduled ticks at `LevelTicks`;
- limit/defer execution on server thread;
- track C2ME stack frames as attribution only;
- do not interfere with C2ME internals directly in v1.

If adding worldgen attribution:

- treat C2ME frames as execution context;
- identify the feature/block placement caller above/beside C2ME when possible;
- never cancel C2ME chunk tasks.

## Design Principles

1. Preserve content.

No disabling biomes, forests, trees, or leaves as the default fix.

2. Server-thread safety.

World mutation stays on the server thread.

3. Adaptive behavior.

Do nothing when TPS/MSPT is healthy.

4. Target only leafstorms.

Do not throttle redstone, crops, machines, fluids, entities, or all block ticks blindly.

5. Report everything.

Every mitigation decision should be written to Fragment Doctor logs in AI-readable form.

6. Fail open.

If doctor logic is unsure or config is disabled, vanilla behavior should continue.

## Why Not Async Leaf Ticks

Do not run leaf block ticks in worker threads.

Unsafe operations inside or around leaf ticking may include:

- reading and writing block states;
- scheduling more ticks;
- causing neighbor updates;
- touching chunk sections;
- interacting with lighting/state updates;
- triggering modded block behavior.

Async world mutation can cause:

- race conditions;
- corrupted chunk state;
- invisible/fake blocks;
- rare non-deterministic crashes;
- incompatibility with C2ME and other performance mods.

Safe async work:

- metrics aggregation;
- report generation;
- suspicion ranking;
- non-mutating analysis of already-captured data.

Unsafe async work:

- executing `BlockState#tick`;
- calling `Level#setBlock`;
- modifying `LevelTicks`;
- mutating chunks.

## Recommended Architecture

Create a module:

```text
dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm
```

Suggested classes:

```text
LeafstormDoctor
LeafstormConfig
LeafTickBudget
DeferredLeafTickQueue
DeferredLeafTickKey
LeafstormMetrics
LeafstormLogEvent
LeafBlockClassifier
```

Mixin package:

```text
dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm.mixin
```

Resources:

```text
fragmentdoctor.doctor.mixins.json
```

Or if doctor module is not ready yet, put experimental implementation under:

```text
dev.cutexxgirl.fragmentdoctor.modules.scaner.mitigation
```

But long-term it belongs in `doctor`.

## Feature 1: Adaptive Leaf Tick Limiter

Purpose:

- prevent one server tick from processing too many leaf ticks.

Behavior:

- count leaf ticks executed this server tick;
- if budget remains, execute leaf tick normally;
- if budget exhausted, defer leaf tick to later;
- re-schedule/defer safely on server thread.

Budget should depend on:

- last MSPT;
- rolling TPS;
- current deferred queue size;
- current vanilla scheduled block tick queue size if available.

Suggested starting policy:

```text
MSPT < 45:
  no intervention or high budget

MSPT 50-100:
  soft budget, e.g. 2000 leaf ticks/server tick

MSPT > 100:
  hard budget, e.g. 500 leaf ticks/server tick

MSPT > 200 or queue > 100k:
  emergency budget, e.g. 150-300 leaf ticks/server tick
```

The exact values should be configurable.

## Feature 2: Leaf Tick Deduplication

Purpose:

- reduce actual work, not just delay it.

Observation:

Large leafstorms often schedule the same leaf position repeatedly.

Dedup key:

```text
dimension + block position + block registry id
```

Optional key expansion:

```text
scheduled trigger tick bucket
```

Rules:

- if the same leaf tick is already deferred, do not add another copy;
- if the same leaf was already executed recently, optionally suppress near-duplicate schedules for a tiny window;
- never dedup non-leaf block ticks.

Expected benefit:

- fewer repeated leaf ticks;
- lower queue growth;
- better TPS than limiter alone.

Risk:

- leaf decay might happen slightly later.

Acceptable because:

- content remains intact;
- unsupported leaves eventually update;
- normal gameplay is preserved.

## Feature 3: Worldgen Quiet Mode

Purpose:

- avoid schedule storms during chunk/tree generation.

Concept:

```text
during worldgen/chunk post-processing:
  collect leaf update requests
  suppress duplicate leaf schedules

after the chunk is server-ticking:
  process leaf validation gradually
```

This is more invasive than limiter/dedup and should be v2/v3.

Compatibility caution:

- Must be careful with C2ME.
- Do not cancel async generation work.
- Prefer server-thread post-processing hooks first.

## Feature 4: Batched Leaf Distance Recalculation

Purpose:

- replace many individual leaf distance recalculations with one chunk/region pass.

Possible algorithm:

1. Collect dirty leaf positions in a chunk/region.
2. Start BFS from nearby logs.
3. Compute leaf distances in a bounded radius.
4. Apply updates gradually on server thread.

Pros:

- can reduce algorithmic duplication dramatically.

Cons:

- more complex;
- higher compatibility risk with modded leaf behavior;
- must respect `persistent=true`;
- must respect custom leaf blocks.

Recommendation:

- Do not start here.
- Build limiter and dedup first.

## Feature 5: Runtime Tag/Data Fixes

Purpose:

- catch data mistakes like the Regions Unexplored log-tag issue.

Checks:

- if a mod has leaves and logs, ensure logs/branches intended to support leaves are in `minecraft:logs`;
- report suspicious custom branches that are in `logs_that_burn` but not `logs`;
- report leaves with persistent/distance states that appear unsupported in generated trees.

Optional doctor fix:

- generate runtime datapack tag patches.

Risks:

- changing tags can affect tree-chopping mods, recipes, tool behavior, and compatibility.

Therefore:

- default to report-only;
- make auto-fix explicit/configurable.

## Scanner Improvements Needed

Current scanner reports:

- target leaf type;
- scheduled tick count;
- chunks;
- stack showing `LeavesBlock#updateShape`.

Needed scanner improvements:

### 1. Mitigation Events

Add JSONL events:

```text
leafstorm_budget
leafstorm_deferred
leafstorm_executed
leafstorm_deduplicated
leafstorm_queue_snapshot
leafstorm_emergency_mode
```

Fields:

```json
{
  "event": "leafstorm_deferred",
  "dimension": "minecraft:overworld",
  "leaf_id": "minecraft:spruce_leaves",
  "pos": "-1523,158,1636",
  "chunk": "-96,102",
  "reason": "budget_exhausted",
  "budget": 500,
  "executed_this_tick": 500,
  "deferred_queue_size": 18420,
  "mspt": 128.4,
  "rolling_tps": 12.7
}
```

### 2. Distinguish New Schedules From Re-Schedules

Current scheduled tick summaries count deferred re-schedules as new scheduled ticks.

Need fields:

```text
origin = vanilla_schedule | doctor_reschedule | doctor_deferred | worldgen_schedule
```

### 3. Worldgen Source Attribution

Add instrumentation around:

- `LevelChunk#setBlockState`
- `WorldGenRegion#setBlock`
- chunk post-processing;
- tree feature placement if practical;
- feature/resource key if reachable.

Desired output:

```text
spruce leafstorm likely created by:
  feature: minecraft:spruce
  biome: ...
  worldgen context: tectonic / vanilla / other mod
  chunks: ...
```

### 4. Chunk Queue Trend

Report whether scheduled block tick queue is:

- growing;
- shrinking;
- stable.

This is critical to determine whether mitigation is working.

## Config

Suggested config file:

```text
config/fragmentdoctor/doctor-leafstorm.json
```

Suggested fields:

```json
{
  "enabled": false,
  "log_mitigation_events": true,
  "target_leaves_only": true,
  "enable_deduplication": true,
  "enable_budget_limiter": true,
  "enable_worldgen_quiet_mode": false,
  "soft_mspt_threshold": 50.0,
  "hard_mspt_threshold": 100.0,
  "emergency_mspt_threshold": 200.0,
  "soft_leaf_budget_per_tick": 2000,
  "hard_leaf_budget_per_tick": 500,
  "emergency_leaf_budget_per_tick": 250,
  "max_deferred_queue_size": 250000,
  "drop_duplicate_deferred_ticks": true,
  "warn_only_mode": false,
  "compatibility": {
    "tectonic": "safe",
    "c2me": "server_thread_only",
    "regions_unexplored_tag_checks": true
  }
}
```

Default should probably be:

```text
enabled=false
```

until enough testing is done.

For the user's pack, enabling it is reasonable because the scans already show leafstorms.

## Commands

Suggested commands:

```text
/fragmentdoctor doctor leafstorm status
/fragmentdoctor doctor leafstorm enable
/fragmentdoctor doctor leafstorm disable
/fragmentdoctor doctor leafstorm budget
/fragmentdoctor doctor leafstorm queue
/fragmentdoctor doctor leafstorm dump
```

Status should show:

- enabled/disabled;
- current mode;
- current budget;
- deferred queue size;
- executed/deferred/deduped counts;
- top leaf ids;
- top chunks;
- last MSPT/TPS.

## Testing Plan

### Unit/Dev Tests

Create artificial leafstorm:

- schedule thousands of leaf ticks for the same chunk;
- verify TPS stays stable;
- verify dedup works;
- verify non-leaf scheduled ticks are not throttled.

### Real Pack Tests

Use user world and coordinates from scans.

Known heavy areas:

- spruce leaf hotspots around chunks `-96,102`, `-103,102`, `-104,104`.
- later scan hotspots around chunks `-108,137`, `-110,134`, `-103,136`, `-104,137`.

Run scans:

1. Leafstorm off.
2. Leafstorm limiter only.
3. Limiter + dedup.
4. Optional worldgen quiet mode.

Compare:

- TPS;
- avg MSPT;
- p95/p99/max MSPT;
- block tick queue trend;
- deferred queue size;
- actual leaf executions;
- duplicate suppressions;
- visual leaf decay behavior.

### Compatibility Tests

Need to test with:

- Tectonic enabled;
- Regions Unexplored enabled;
- C2ME enabled;
- Lithium/Observable/Does It Tick/Performant style mods present;
- Create contraptions nearby;
- modded leaves and vanilla leaves.

Special C2ME checks:

- no async world mutation;
- no crashes during chunk generation;
- no deadlocks;
- no chunk corruption;
- no runaway deferred queue.

## Success Criteria

Minimum success:

- no crashes;
- no world corruption;
- leafstorm hook active;
- TPS improves from severe drops toward playable state.

Good success:

- TPS stays `18-20` after initial chunk generation burst;
- avg MSPT below `50`;
- p95 below `120` during active exploration;
- max spikes below `300` in heavy forest generation;
- leaf decay still eventually happens.

Excellent success:

- dedup reduces total leaf work substantially;
- scheduled block tick queue trends downward during stationary scans;
- reports identify the worldgen feature/mod/chunk source.

## Implementation Order

1. Add robust logging for current leafstorm limiter.
2. Distinguish doctor re-schedules from original schedules.
3. Add deduplication for deferred leaf ticks.
4. Add config and commands.
5. Add source attribution for block placement/worldgen.
6. Consider worldgen quiet mode.
7. Consider batched leaf distance recalculation.

Do not start with async/multithreaded block ticks.

