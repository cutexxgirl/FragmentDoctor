# Fragment Doctor Leafstorm Context Report

## Current Situation

Fragment Doctor `Scanner` successfully identified the original Regions Unexplored redwood issue as a leaf scheduled-tick storm.

The first bad scan showed:

- `regions_unexplored:redwood_leaves`: about `1,362,165` scheduled block tick enqueues in 300 seconds.
- TPS/MSPT impact in redwood biome: MSPT often `100+`, with severe spikes.
- Stack:
  - `LeavesBlock#updateShape`
  - `BlockBehaviour$BlockStateBase#updateShape`
  - `LevelAccessor#scheduleTick`
  - `LevelTicks#schedule`

Root cause found in Regions Unexplored source:

- RU redwood/pine logs and branches were in `minecraft:logs_that_burn`.
- They were mostly missing from `minecraft:logs`.
- Vanilla leaf distance/decay logic depends on `minecraft:logs`.
- Result: generated leaves failed to see nearby support as logs and produced huge scheduled leaf update storms.

Fix already applied in:

- `P:\FragmentModFixes\RegionsUnexplored\src\neoforge\21.1\data\java\net\regions_unexplored\datagen\provider\tag\RuBlockTagProvider.java`
- `P:\FragmentModFixes\RegionsUnexplored\src\shared\21.1\main\generated\data\minecraft\tags\block\logs.json`

The fix adds all RU log tags plus `RUBlockTags.BRANCHES` to `BlockTags.LOGS`.

## Build Notes For Regions Unexplored

The first jar placed into Prism was a dev jar:

- Bad: `regions-unexplored-0.6-neoforge-21.1-dev.jar`
- Crash cause: `net.minecraft.resources.Identifier` missing at runtime.

Correct jar must be production/remapped:

- Good shape: `regions-unexplored-0.6-neoforge-21.1.jar`
- Do not use jars under `build\libs\intermediates`.

Second crash was caused by missing `json5-java`:

- `NoClassDefFoundError: de/marhali/json5/stream/Json5Lexer`

Patch added in `P:\FragmentModFixes\RegionsUnexplored\build.gradle.kts`:

- Created `bundledRuntime`.
- Added `bundledRuntime("de.marhali:json5-java:3.0.0")`.
- Injected bundled runtime files into NeoForge jars via `tasks.withType<Jar>()`.

## Latest Scan After RU Fix

Redwood no longer appears to be the main MSPT killer. The scan still shows a leafstorm, but the dominant target changed.

Fresh scan:

- `scan-20260604-112243`
- Requested: 20 seconds.
- Actual TPS: about `2.00`.
- Average MSPT: `471.950`.
- Max MSPT: `1976.603`.
- p95 MSPT: `1697.612`.

Main scheduled tick storm:

- `minecraft:spruce_leaves`: `50,209` scheduled block tick enqueues in 20 seconds.
- `regions_unexplored:redwood_leaves`: `15,232` scheduled block tick enqueues in 20 seconds.
- `bloomingnature:fir_leaves`: `247` scheduled block tick enqueues in 20 seconds.

The overworld scheduled block tick queue increased during the scan:

- Tick 1: about `140,295` block ticks.
- Tick 41: about `175,946` block ticks.

Important coordinates from expensive spruce leaf ticks:

- `-1523,158,1636`, chunk `-96,102`, about `128 ms`.
- `-1527,149,1632`, chunk `-96,102`, about `119 ms`.
- `-1525,148,1634`, chunk `-96,102`, about `79 ms`.
- `-1644,128,1645`, chunk `-103,102`, about `87 ms`.
- `-1640,137,1672`, chunk `-103,104`, about `82 ms`.

Important coordinates from remaining redwood ticks:

- `-1617,174,1772`, chunk `-102,110`, about `114 ms`.
- `-1606,182,1773`, chunk `-101,110`, about `109 ms`.
- `-1608,166,1780`, chunk `-101,111`, about `97 ms`.

Current interpretation:

- This is not primarily Iron's Spells.
- This is not Matmos.
- It is a server-side scheduled block tick storm from leaf distance/decay updates.
- Tectonic may amplify the issue by creating larger/taller/more dramatic terrain where spruce forests and tree updates happen at scale, but the direct stack is vanilla leaf update logic.

## Why Not Async Or Multithreaded Leaf Ticks

Do not execute block ticks off-thread.

Reasons:

- `LeavesBlock#tick` and related update logic can read and mutate world state.
- It can interact with chunks, block states, neighbor updates, and scheduled tick queues.
- Minecraft world mutation is not thread-safe.
- Off-thread execution risks race conditions, corrupted chunks, phantom blocks, and rare hard crashes.

Safe async work:

- Analyze logs.
- Aggregate suspect chunks.
- Compute reports.
- Prepare decisions for the next server tick.

Unsafe async work:

- Running `BlockState#tick`.
- Mutating block states.
- Scheduling or removing world ticks from worker threads.

## Recommended Fix: Adaptive Leaf Tick Queue

Implement a doctor-style mitigation, not as normal Scanner behavior.

Goal:

- Preserve all biomes, trees, and leaves.
- Prevent the server from trying to process tens of thousands of leaf updates in a small number of ticks.
- Smooth TPS/MSPT by spreading leaf update work across time.

Expected effect based on latest scan:

- Current:
  - TPS: about `2`.
  - Average MSPT: about `472`.
  - p95 MSPT: about `1698`.
  - Max MSPT: about `1977`.
- With limiter, estimated:
  - TPS: `12-20`, depending on active worldgen pressure.
  - Average MSPT: `40-90`.
  - p95 MSPT: `80-180`.
  - Max MSPT: roughly `150-300` during heavy generation.

Tradeoff:

- Unsupported leaves may decay/update later.
- Large leafstorm backlogs may take 1-3 minutes to fully drain instead of freezing the game immediately.

## Proposed Architecture

Create a future module/package:

- `dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm`

Or, if keeping it inside Scanner temporarily:

- `dev.cutexxgirl.fragmentdoctor.modules.scanner.mitigation`

Core parts:

- `LeafstormDoctor`
  - Runtime state and config.
  - Tracks leaf scheduled tick pressure.
  - Decides per-tick leaf budget.
- `DeferredLeafTickQueue`
  - Stores deferred leaf `ScheduledTick` entries.
  - Deduplicates repeated leaf ticks for the same block position and block type.
- `LeafTickBudget`
  - Adaptive budget based on last MSPT/rolling TPS/queue depth.
- `LeafstormReport`
  - Writes events to Fragment Doctor logs when mitigation triggers.

## Suggested Policy

Default disabled until explicitly enabled.

Example behavior:

- If MSPT `<45`: no intervention.
- If MSPT `50-100`: soft leaf tick cap.
- If MSPT `>100`: strong leaf tick cap.
- If scheduled leaf queue exceeds `50,000`: emergency mode.

Suggested first thresholds:

- Soft budget: `2000` leaf ticks per server tick.
- Hard budget: `500` leaf ticks per server tick.
- Emergency budget: `150-300` leaf ticks per server tick.
- Deduplicate repeated same-position leaf schedules within a short window.

Important:

- Only target leaf block ticks:
  - Vanilla `LeavesBlock`.
  - Modded leaves that extend/use similar leaf behavior.
- Do not throttle random ticks, crops, fluids, redstone, fire, or tile/entity logic.

## Required Mixin Points

Existing useful mixin:

- `LevelTicksMixin`
  - Currently observes `LevelTicks#schedule`.
  - Can record scheduled tick enqueues.

Need additional or modified mixins:

- Intercept scheduled tick execution, not only enqueue.
- Possible target:
  - `LevelTicks#runCollectedTicks`
  - `LevelTicks#tick`
  - internal collected tick iteration, depending on mapped method signatures.

Implementation must keep world mutation on the server thread.

Safer first version:

- Do not cancel tick execution globally.
- Detect leaf tick pressure and use Sponge/MixinExtras wrapper around the collected tick consumer.
- If tick is a leaf tick and budget exhausted:
  - Re-schedule it for a later tick.
  - Skip current execution.
  - Count it in mitigation logs.

Need to verify actual NeoForge 1.21.1 method descriptors before coding.

## Scanner Improvements Needed Before/Alongside Fix

The current scanner identifies the target leaves and queue pressure, but not the block placement/worldgen source.

Add attribution for:

- `LevelChunk#setBlockState`
- `WorldGenRegion#setBlock`
- Tree/feature placement context if reachable.
- Chunk post-processing.

Desired future report:

Instead of:

```text
scheduled_block_tick:minecraft:spruce_leaves
caller: minecraft:unknown
```

It should say:

```text
spruce leafstorm caused by feature/tree/worldgen caller:
mod: tectonic or vanilla or another worldgen mod
feature/biome/chunk/position evidence
```

## Matmos Note

Matmos is present:

- `matmos_tct-7.4-neoforge-1.21.1.jar`

But current scan does not implicate it in TPS/MSPT leafstorm.

Matmos can plausibly affect:

- FPS.
- Client frame pacing.
- Client-side entity/block/environment scans.

It should not be the direct cause of server-side:

- `scheduled_block_tick:minecraft:spruce_leaves`
- `LeavesBlock#updateShape`
- `LevelTicks#schedule`

## Important User Preferences

- Do not remove content.
- Do not disable biomes or trees as the main solution.
- Avoid compromises that reduce the RPG/worldgen experience.
- Prefer surgical diagnostics and mitigation.
- Future `doctor` module may modify behavior via mixins, but current Scanner should primarily diagnose.

## Implementation Progress

Initial doctor-style leafstorm limiter has been added in:

- `dev.cutexxgirl.fragmentdoctor.modules.doctor.leafstorm`
- `LevelTicksMixin`
- `MinecraftServerMixin`

Behavior:

- Disabled by default through `doctor.leafstorm.enabled=false` in `fragmentdoctor-common.toml`.
- Runtime commands:
  - `/fragmentdoctor doctor leafstorm status`
  - `/fragmentdoctor doctor leafstorm enable`
  - `/fragmentdoctor doctor leafstorm disable`
- Only targets scheduled block ticks where the tick type is a `LeavesBlock`.
- Does not run leaf/block logic off-thread.
- When over budget, re-schedules the leaf tick for a later game tick instead of executing it immediately.
- Keeps fluids, redstone, crops, fire, random ticks, entities, and block entities untouched.
- Writes limiter summaries to the normal Fragment Doctor logger and, when enabled, to:
  - `fragmentdoctor/doctor/leafstorm/leafstorm-limiter.jsonl`

Config knobs:

- `softMsptThreshold`
- `hardMsptThreshold`
- `emergencyQueueDepth`
- `softBudget`
- `hardBudget`
- `emergencyBudget`
- `deferDelayTicks`
- `logEveryTicks`
- `consoleLogging`
- `fileLogging`

Build status:

- Not built yet by request.
- Before testing in game, compile validation is still needed to verify the mixin redirect descriptor and NeoForge constructor/config wiring.
