# FPS investigation notes for scan-20260614-211236

Source scan:

- `P:\PrismLauncher\instances\Fragment 0.0.7v7 попытка намбер ту\minecraft\fragmentdoctor\scanner\scan-20260614-211236-summary.md`
- `P:\PrismLauncher\instances\Fragment 0.0.7v7 попытка намбер ту\minecraft\fragmentdoctor\scanner\scan-20260614-211236.jsonl`

## Short conclusion

This scan no longer looks like the old server-side TPS collapse. Server TPS stayed healthy for the full 120 seconds, but client frame pacing was still bad.

Server side:

- `ticks`: 2400 over 119.988 seconds.
- `actual_tps_wall_clock`: 20.00.
- `avg_mspt`: 18.694.
- `p95_mspt`: 40.039.
- `p99_mspt`: 78.945.
- `max_mspt`: 513.694.

Client side:

- `estimated_average_fps`: 112.24.
- `one_second_fps_median`: 106.
- `one_second_fps_p10`: 83.
- `frames_over_8.33ms_below_120fps`: 4260.
- `frames_over_16.67ms_below_60fps`: 982.
- `frame_spikes_over_33ms`: 46.
- `render_microfreezes_over_100ms`: 2.

Interpretation: the server is mostly recovered compared with earlier catastrophic scans, but FPS/frame pacing still has separate client-render problems.

## Important correction about GC

The scanner currently reports `gc_pause` from JVM MXBean collection-time deltas. That number can be accumulated collector time between probes, not necessarily a single stop-the-world freeze on the current frame/tick.

Example from this scan:

- One reported GC delta is `5983 ms`.
- Nearby tick time is only about `65 ms`.

So the label `gc_pause` is too scary as written. Treat it as "GC collection-time delta" until the scanner separates real wall-clock stalls from accumulated GC time.

## FPS drop episodes

### 23s-25s

- Average FPS: 71.77.
- Minimum one-second FPS: 68.
- Max frame: 88.828 ms.
- Dominant component: `render_level`.
- Frame breakdown: `render_level` 62.6%, `other_renderer` 7.0%, `present_or_gpu_wait` 2.2%, `other_frame` 28.2%.
- Correlated server spikes: 7, max 253.756 ms.
- Correlated GC collection-time delta: 6427 ms.

This episode mixes client render work with server/GC pressure. It is not a clean single-mod signature.

### 53s-54s

- Average FPS: 75.00.
- Max frame: 37.011 ms.
- Dominant component: `render_level`.
- Render stack shows Flywheel/Create frame work:
  - `dev.engine_room.flywheel.lib.task.BarrierPlan#execute`
  - `dev.engine_room.flywheel.impl.visualization.VisualizationManagerImpl#beginFrame`

This looks like Create/Flywheel visualization cost, not TPS collapse.

### 56s-57s

- Average FPS: 72.34.
- Max frame: 78.184 ms.
- Dominant component: `render_level`.
- Render stack again includes Flywheel/Create work:
  - `dev.engine_room.flywheel.lib.task.BarrierPlan#execute`
  - framebuffer bind/render-level paths.

This is another client-render episode, probably around Create/Flywheel visuals.

### 111s-112s

- Average FPS: 64.01.
- Max frame: 136.792 ms.
- Dominant component: `other_game_renderer`, not `render_level`.
- Screen: inventory was open on the biggest 137 ms frame.
- Render stack contains a suspicious first-person/item-render path:
  - `net.minecraft.world.item.ItemStack#handler$fdo000$neoorigins$edibleItemAnimation`
  - `net.minecraft.world.item.ItemStack#getUseAnimation`
  - `punchy.client.render.ToolKindResolver#isSpearStack`
  - `punchy.client.render.ToolKindResolver#resolveItemKind`
  - `punchy.client.render.PunchyArmRenderer#renderItemInHand`
  - `punchy.client.render.PunchyArmRenderer#renderFirstPerson`

The live thread sampler also saw:

- `com.cyberday1.neoorigins.data.PowerDataManager#getAllPowers`
- `java.util.HashMap#putMapEntries`
- `java.util.HashMap#<init>`
- `net.minecraft.world.item.ItemStack#neoorigins$ediblesItems`
- `punchy.client.render.ToolKindResolver#isSpearStack`
- `punchy.client.render.ToolKindResolver#resolveItemKind`

This suggests a likely hot path where item/hand rendering calls Punchy logic, which calls item animation/use checks, which runs NeoOrigins power lookup/allocation work.

Important: this scan does not prove NeoOrigins alone is the root cause. It shows NeoOrigins and Punchy appearing together in the hot render path during a client FPS drop. The next investigation should confirm whether this happens every frame, only with specific held items, only in inventory/first-person, or only with specific origin powers.

## Punchy / NeoOrigins hypothesis

Suspicious chain:

1. `PunchyArmRenderer#renderFirstPerson`
2. `PunchyArmRenderer#renderItemInHand`
3. `ToolKindResolver#resolveItemKind`
4. `ToolKindResolver#isSpearStack`
5. `ItemStack#getUseAnimation`
6. NeoOrigins injected item animation path:
   - `ItemStack#handler$fdo000$neoorigins$edibleItemAnimation`
   - `ItemStack#neoorigins$ediblesItems`
   - `PowerDataManager#getAllPowers`
7. Allocation/copy evidence:
   - `HashMap#<init>`
   - `HashMap#putMapEntries`

What to check in a dedicated Punchy/NeoOrigins pass:

- Does `ToolKindResolver` call `ItemStack#getUseAnimation` every rendered frame?
- Does NeoOrigins allocate or copy all powers from `PowerDataManager#getAllPowers` during that call?
- Can Punchy cache item kind per `ItemStack`/item id for a short time instead of resolving every frame?
- Can NeoOrigins avoid constructing/copying maps in the render path?
- Does the issue disappear when the same scene is tested with:
  - Punchy disabled.
  - NeoOrigins disabled.
  - Specific held item changed.
  - Inventory closed.
  - First-person hand rendering hidden.

Possible fixes:

- Cache `ToolKindResolver` result by item type and relevant NBT/data-component signature.
- Avoid calling `getUseAnimation` only to classify weapon/tool type if a cheaper item-tag check is enough.
- In NeoOrigins, cache edible/use-animation power lookups per player/item/frame or make `getAllPowers` return a stable read-only view instead of allocating/copying.
- Add a small profiler hook around `PowerDataManager#getAllPowers` and Punchy `ToolKindResolver` to count calls per frame.

## Photon / particle render hypothesis

The thread sampler also saw Photon render work:

- `com.lowdragmc.photon.client.gameobject.emitter.particle.ParticleConfig$RenderPass#drawParticlesInternal`
- `com.lowdragmc.photon.client.gameobject.emitter.renderpipeline.PhotonFXRenderPass#drawParticles`
- `RenderPassPipeline#renderParticlesSequential`
- `RenderPassPipeline#renderParticles`
- `RenderPassPipeline#build`
- `TileParticle#render`

This is not the clearest top cause in the sustained-drop sections, but it is present often enough to deserve a separate test.

What to check:

- Repeat the same camera path with Photon effects disabled or reduced.
- Look for particle-heavy blocks/effects near the test location.
- Check whether `RenderPassPipeline#build` happens every frame instead of being cached.
- Check whether tile particles are being rebuilt/re-sorted too often.

Possible fixes:

- Cache render pass pipeline construction where safe.
- Reduce per-frame allocation in particle render passes.
- Add distance/frustum culling or lower update rate for tile particles.

## Large entity/radius searches

Large entity queries were observed, but they are not the main FPS killer in this scan.

Notable examples:

- ShoulderSurfing/EpicFight/Immersive Aircraft camera trace:
  - around `35x37x39`, sometimes near 10 ms.
- Create `ContraptionCollider#collideEntities`:
  - `17x49x5`, max 18.911 ms.
  - `11x43x6`, smaller recurring samples.
- Cataclysm fog:
  - `128.6x129.8x128.6`, about 1 ms.
- Figura viewed-entity checks:
  - up to roughly `55x45x57`, sub-millisecond to low-millisecond.
- Presence Footsteps:
  - about `32.6x33.8x32.6`, sub-millisecond.

These are worth keeping on the radar, but the strongest client-render path in this scan is still Punchy/NeoOrigins plus Flywheel/Create visualization.

## FallingTree note

FallingTree appears in this scan but is not the main cause:

- Suspect: `fr.rakambda.fallingtree.neoforge.common.wrapper.BlockStateWrapper`
- Total: 44.897 ms.
- Max spike: 27.549 ms.
- Stack:
  - `BlockStateWrapper#tick`
  - `LeafBreakingHandler#onServerTick`
  - `LeafBreakingListener#onServerTick`

This means FallingTree can contribute short spikes during/after leaf breaking, but it does not explain the overall FPS problem here.

## Create redstone link storm

This is separate from the FPS/client-render issue, but it should be fixed or at least contained.

Evidence:

- Scheduled tick source:
  - `vanilla_schedule | create:scheduled_tick -> scheduled_block_tick:create:redstone_link`
  - `enqueue_count`: 50400 in 120 seconds.
- Hot chunks:
  - `51,3` x24000.
  - `51,2` x24000.
  - `50,1` x2400.
- Create runtime cost in this scan:
  - total suspect time: 363.314 ms.
  - max spike: 18.872 ms.
  - involved objects include `block_tick:create:redstone_link`, `block_entity:create:redstone_link`, `fluid_pipe`, `depot`, `mechanical_pump`, `chute`.

Interpretation:

The redstone links are not causing a 10-second freeze in this scan, but they are producing a huge amount of scheduled tick traffic. That keeps the scheduled tick system noisy, can interact badly with chunk/block update storms, and can become worse in larger bases or bigger modpacks.

What to do:

1. Locate the machines/chunks at `51,2`, `51,3`, and `50,1`.
2. Check for redstone links being rapidly neighbor-updated by clocks, contraptions, moving blocks, or chunk-border update loops.
3. If this is caused by a specific structure, simplify or isolate the redstone-link network.
4. Consider a Doctor-side guardrail:
   - Detect `create:redstone_link` scheduled tick bursts per chunk.
   - Rate-limit repeated identical redstone-link scheduled ticks in the same chunk/position within a short window.
   - Log top positions so the player can remove or redesign the noisy setup.
5. Be conservative with automatic suppression. Redstone links are gameplay logic, so dropping ticks blindly can break contraptions. Prefer diagnostics first, then an opt-in limiter with a config flag.

Recommended next implementation:

- Add a `create_redstone_link_storm` diagnostic that records exact block positions and per-position enqueue rates.
- Report:
  - top positions.
  - top chunks.
  - calls per second.
  - whether the same position is scheduled multiple times before its previous tick runs.
- Only after confirming duplicate scheduling, add an optional dedupe/rate-limit mode.
