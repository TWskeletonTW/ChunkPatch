# ChunkPatch 0.2.0 — Post-Refactor Review, Lifecycle Hardening & Release Checklist

> Status: **Core generation refactor accepted; lifecycle hardening still required before long soak testing / release**  
> Review target: `ChunkPatch(1).zip` / current `0.2.0` implementation  
> Minecraft target: **1.19.4 / Fabric**  
> Review date: **2026-08-18**

---

## 1. Purpose of this document

This document is the follow-up review for the ChunkPatch generation refactor described in `ChunkPatch_REFACTOR_PLAN.md`.

The original problem was a sequence of native JVM crashes while ChunkPatch was generating chunks. The crash logs consistently showed the JVM failing inside G1 GC worker threads (`GCTaskThread`) with `EXCEPTION_ACCESS_VIOLATION` in `jvm.dll`. The previous design synchronously called `getChunk(..., ChunkStatus.FEATURES, true)` from the server tick and only applied cooldowns after an expensive generation burst had already happened.

The `0.2.0` refactor fixes the most important architectural problem by moving to a **future-driven, single-in-flight scheduler** with **proactive admission control**.

This review does **not** recommend reverting that design. The new architecture is fundamentally better. The remaining work is primarily about lifecycle safety, stale asynchronous callbacks, reference cleanup, stable region scanning, and release validation.

The goals of this document are therefore:

1. Record what the `0.2.0` refactor has successfully fixed.
2. Identify the remaining correctness and lifecycle risks.
3. Define exact implementation recommendations.
4. Define tests that must be added before release.
5. Define soak-test and acceptance criteria.
6. Define the repository `.gitignore` policy so only source-related files and required project metadata are tracked.

---

## 2. Executive summary

### 2.1 Overall assessment

The current `0.2.0` architecture is **substantially safer than 0.1.12**.

The following critical design changes are correct:

- synchronous per-tick `getChunk(...FEATURES, true)` generation is gone;
- the old `chunksPerTick` 1–8 burst model is gone;
- the old slow-chunk post-event cooldown/brake is gone;
- `GenerationScheduler` enforces a single in-flight target;
- the next target is not submitted until the previous future has completed and its completion has been committed;
- `MinecraftChunkGenerationGateway` calls `ServerChunkCache#getChunkFuture` from a dedicated non-main coordinator thread;
- generation completion is marshalled back to the Minecraft server thread;
- pause/stop transitions drain the current generation instead of force-cancelling Minecraft worldgen;
- generation callbacks have a session token to suppress stale callbacks;
- server MSPT and heap pressure are checked before submitting a new target;
- preview state is published through snapshots instead of sharing mutable arrays directly;
- region scanning detects concurrent file mutation and retries;
- unit tests were added for the scheduler, snapshot behavior, and scanner mutation behavior.

### 2.2 Release recommendation

Do **not** redesign generation again.

Before treating `0.2.0` as release-ready, fix these remaining items:

| Priority | Item | Required? |
|---|---|---|
| **P0** | Full server-shutdown teardown | **Yes** |
| **P0/P1** | Invalidate stale asynchronous scan results with a scan session token | **Yes** |
| **P1** | Never run owner-thread callbacks inline when `activeServer == null` | **Yes** |
| **P2** | Use full `FileTime` precision for region stable-read checks | Recommended |
| **P2** | Review heap gate behavior with very large `-Xmx` | Recommended |
| **P3** | Optional one-tick submission gap | Optional safety margin |
| **P3** | Additional lifecycle/integration/soak tests | Strongly recommended |

Once the P0/P1 items are fixed, the next step should be **real-world soak testing**, not another major generation rewrite.

---

# 3. What 0.2.0 fixed correctly

## 3.1 The blocking generation path was removed

The previous design performed chunk generation from the server tick with a blocking call equivalent to:

```java
level.getChunkSource().getChunk(x, z, ChunkStatus.FEATURES, true);
```

This was problematic because requesting `FEATURES` can trigger a substantial dependency graph of surrounding chunk-generation stages. One apparent target can therefore cause a much larger burst of allocations and worldgen activity than the name "one chunk" suggests.

The current implementation delegates generation to `MinecraftChunkGenerationGateway`:

```java
return CompletableFuture
    .supplyAsync(() -> source.getChunkFuture(x, z, ChunkStatus.FEATURES, true), coordinator)
    .thenCompose(Function.identity())
    .thenApply(...);
```

This is an important distinction.

For the Minecraft 1.19.4 implementation being targeted, the `getChunkFuture` call only avoids the main-thread `managedBlock(...)` path when it is entered from a non-server-main thread. The dedicated coordinator thread therefore exists for a real reason; it is not merely cosmetic asynchronous wrapping.

### Required invariant

The following must remain true in future refactors:

> **Do not call the generation entry point directly from the server main thread.**

Also avoid replacing it with:

```java
CompletableFuture.supplyAsync(() -> source.getChunk(...));
```

That would merely move a blocking Minecraft API call to an arbitrary external thread and is not equivalent to using Minecraft's future-based scheduling path.

---

## 3.2 Single-in-flight backpressure is implemented

`GenerationScheduler` stores exactly one current target:

```java
private Target inFlight;
```

and refuses to submit another target while it exists:

```java
if (inFlight != null) return false;
```

A target is cleared only when its completion callback is processed:

```java
inFlight = null;
```

This is the correct replacement for the old `chunksPerTick` model.

### Desired invariant

At every point in time:

```text
number of ChunkPatch generation requests outstanding <= 1
```

This should be treated as a hard safety invariant unless future profiling provides very strong evidence that controlled concurrency can be safely increased.

For the current crash investigation there is no reason to increase it.

---

## 3.3 Backpressure happens before work is submitted

The old design behaved like this:

```text
submit expensive synchronous generation
    ↓
server tick becomes slow
    ↓
measure how bad it was
    ↓
apply cooldown afterwards
```

That is reactive braking after the load already happened.

The new design checks admission before submitting a new generation target:

- disk free space;
- average server tick time;
- heap-use ratio;
- whether another target is already in flight.

This is much better:

```text
observe current server state
    ↓
if unsafe: do not submit more work
    ↓
if safe: submit exactly one target
```

The old slow-chunk cooldown mechanism should therefore remain deleted.

---

## 3.4 Pause and stop drain the current worldgen future

Force-cancelling a Minecraft generation future is risky because ChunkPatch does not own the underlying Minecraft worldgen scheduler and cannot assume cancellation can unwind every dependency safely.

The current state transitions instead wait for the current target to finish before finalizing pause or stop.

That is the correct behavior.

### Desired semantics

#### Pause

```text
RUNNING
  ↓ user requests pause
PAUSING
  ↓ no more submissions
  ↓ current in-flight target completes
PAUSED
```

#### Stop

```text
RUNNING
  ↓ user requests stop
STOPPING
  ↓ no more submissions
  ↓ current in-flight target completes
READY / IDLE
```

This should remain the design.

---

## 3.5 Generation callback session invalidation is correct

`GenerationScheduler` uses a `sessionId` and captures it when a target is submitted:

```java
long submittedSession = sessionId;
```

Completion checks:

```java
if (submittedSession != sessionId) return;
```

and invalidation increments the token:

```java
sessionId++;
inFlight = null;
```

This prevents a completion from a previous generation session from mutating a newly-started session.

That pattern should also be reused for region scanning, which currently lacks equivalent protection.

---

## 3.6 Preview state is safer

The new snapshot design avoids sharing a mutable preview backing array between the server-side scanner/controller and the client render path.

That removes a real data race present in the old design.

The implementation should continue following the rule:

> Mutable model state stays on the owning thread; published UI state is immutable or defensively copied.

---

## 3.7 RegionScanner concurrent-mutation detection is a good improvement

The scanner now records region file state before and after reading and retries if the region file changed during the read.

This addresses the scenario:

```text
scanner reads region header
    ↓
Minecraft autosave mutates the .mca file
    ↓
scanner reads payload from a different file state
```

The retry design is correct in principle and avoids falsely classifying an actively-written region as corrupt merely because the scanner observed an inconsistent snapshot.

A precision improvement is recommended later in this document.

---

# 4. Remaining P0 issue — server shutdown does not fully tear down controller state

## 4.1 Current implementation

Current shutdown code is effectively:

```java
public synchronized void onServerStopping() {
    if (scheduler != null) scheduler.invalidateSession();
}
```

This invalidates callbacks, but it does **not** fully dispose the generation subsystem or remove references to the old Minecraft server/world.

Important retained fields include:

```java
private GenerationScheduler scheduler;
private MinecraftChunkGenerationGateway gateway;
private MinecraftServer activeServer;
private ChunkScanResult scan;
private ChunkBounds selected;
private String dimensionId;
```

`MinecraftChunkGenerationGateway` itself holds:

```java
private final ServerChunkCache source;
```

Therefore the controller can retain a reference chain to a closed world after server shutdown.

---

## 4.2 Why this matters

The controller is long-lived/static at mod scope. The integrated server is not.

A typical lifecycle is:

```text
Minecraft client process starts
    ↓
World A integrated server starts
    ↓
ChunkPatch scans/generates
    ↓
World A closes
    ↓
Minecraft client remains open
    ↓
World B integrated server starts
```

If shutdown only invalidates a scheduler token, old references may remain reachable.

Potential consequences:

1. **Memory retention**
   - old `MinecraftServer`;
   - old `ServerLevel` graph;
   - old `ServerChunkCache` / `ChunkMap` graph;
   - old scan sets and preview data;
   - world-related resources that would otherwise become collectable.

2. **Cross-world lifecycle contamination**
   - the new server can call controller methods while stale state still describes the old server/world;
   - if dimension IDs happen to match (for example both worlds use `minecraft:overworld`), logical checks based only on dimension name do not prove the state belongs to the current integrated server.

3. **Coordinator thread lifetime**
   - `gateway.close()` is not guaranteed to run on shutdown in the current method;
   - a stale coordinator executor may stay alive until other paths eventually dispose it.

---

## 4.3 Required fix

`onServerStopping()` should perform a **full in-memory lifecycle teardown**.

It must:

1. invalidate generation session callbacks;
2. close and null the gateway;
3. null the scheduler;
4. invalidate the asynchronous scan session;
5. clear `activeServer`;
6. clear world-specific in-memory references;
7. stop generation timing state;
8. reset transient counters/holds;
9. **not blindly delete persisted resumable progress**.

### Recommended shape

```java
public synchronized void onServerStopping() {
    invalidateScanSession();
    disposeScheduler();

    endGenerationTimer();

    activeServer = null;

    // World/session-owned memory.
    scan = null;
    selected = null;
    dimensionId = null;

    // Runtime-only state.
    laggingHold = false;
    heapPressureHold = false;
    generatedSinceCheckpoint = 0L;
    scannedRegionFiles = 0;
    totalRegionFiles = 0;
    lastUsableBytes = -1L;

    planned = 0L;
    completed = 0L;
    existingInside = 0L;
    partialInside = 0L;
    corruptInside = 0L;

    resetGenerationTimer();

    state = State.IDLE;
    message = "伺服器已關閉";
}
```

The exact fields may differ, but the lifecycle guarantee matters more than the exact formatting.

---

## 4.4 Do not automatically clear persisted progress here

Do **not** automatically call:

```java
ProgressStore.clear();
```

just because the server is closing.

A normal world exit is not equivalent to "user intentionally cancelled the job permanently".

If progress recovery is intended, persisted progress should survive:

- closing the world;
- client restart;
- normal shutdown;
- crash/restart.

The controller's **in-memory references** should be released; persistent recovery metadata should follow its own explicit policy.

---

## 4.5 Required tests

Add lifecycle tests covering at least:

### Test A — shutdown disposes gateway

```text
create controller/gateway
start generation session
call onServerStopping()
assert gateway is closed/disposed
assert scheduler is null
```

### Test B — old world is no longer retained by controller-visible state

At minimum assert all controller references that directly identify the old server/world are cleared.

### Test C — stale generation completion after shutdown is ignored

```text
submit target
shutdown controller
complete fake future
assert no scan/controller state is modified
```

### Test D — start a second server/world after first shutdown

```text
server A lifecycle
shutdown A
server B lifecycle
scan/start B
assert only B's state is used
```

---

# 5. Remaining P0/P1 issue — asynchronous region scans need a session token

## 5.1 Current scan flow

Current behavior is structurally:

```java
CompletableFuture
    .supplyAsync(() -> RegionScanner.scan(...), SCAN_EXECUTOR)
    .whenComplete((result, error) ->
        server.execute(() -> completeScan(result, error))
    );
```

The async task captures the `server` instance from the scan request.

There is no scan-session generation/token check before applying the result.

---

## 5.2 Race scenario

This can happen:

```text
World A starts scan
    ↓
RegionScanner is still reading large region directory
    ↓
player exits World A
    ↓
World A server stops
    ↓
World B starts
    ↓
old scan from A finishes
    ↓
old completion attempts serverA.execute(...)
```

Even if Minecraft rejects or harmlessly drops the task in some cases, ChunkPatch should not depend on shutdown timing or executor behavior for correctness.

A stale result must be rejected explicitly.

---

## 5.3 Recommended implementation

Use the same generation-token concept as `GenerationScheduler`.

Add:

```java
private long scanSessionId;
```

### On new scan

```java
long submittedScanSession = ++scanSessionId;
MinecraftServer submittedServer = server;
```

Then:

```java
CompletableFuture
    .supplyAsync(() -> {
        try {
            return RegionScanner.scan(
                dimensionPath,
                requestedDimension,
                (completed, total) -> updateScanProgress(
                    submittedScanSession,
                    completed,
                    total
                )
            );
        } catch (Exception exception) {
            throw new RuntimeException(exception);
        }
    }, SCAN_EXECUTOR)
    .whenComplete((result, error) -> {
        if (submittedServer.isStopped()) {
            return;
        }
        submittedServer.execute(() -> completeScan(
            submittedScanSession,
            submittedServer,
            result,
            error
        ));
    });
```

The exact server-stop API may be adapted to the available 1.19.4 mappings. The essential rule is to gate the completion by token and expected server identity.

### Completion validation

```java
private synchronized void completeScan(
    long submittedScanSession,
    MinecraftServer submittedServer,
    ChunkScanResult result,
    Throwable error
) {
    if (submittedScanSession != scanSessionId) return;
    if (submittedServer != activeServer) return;
    if (state != State.SCANNING) return;

    // Existing completion logic...
}
```

### Shutdown invalidation

```java
private void invalidateScanSession() {
    scanSessionId++;
}
```

Call it on:

- server shutdown;
- a new scan replacing an old scan;
- any explicit operation that makes the old scan irrelevant.

---

## 5.4 Progress callback must also be stale-safe

It is not enough to protect only the final result.

`RegionScanner.scan(..., this::updateScanProgress)` can still call progress updates from the old scan.

Change it to capture the scan token:

```java
(completed, total) ->
    updateScanProgress(submittedScanSession, completed, total)
```

Then:

```java
private synchronized void updateScanProgress(
    long submittedScanSession,
    int completed,
    int total
) {
    if (submittedScanSession != scanSessionId) return;
    if (state != State.SCANNING) return;

    // Existing logic...
}
```

This prevents an old scan from changing progress text after another lifecycle transition.

---

## 5.5 Required tests

### Test A — stale final result ignored

```text
start scan session 1
invalidate session 1
start session 2
complete session 1
assert session 2 state unchanged
```

### Test B — stale progress ignored

```text
start scan session 1
invalidate it
invoke old progress callback
assert progress counters/message unchanged
```

### Test C — shutdown while scanning

```text
start scan
shutdown
complete fake scan
assert controller remains IDLE/shutdown state
```

---

# 6. Remaining P1 issue — owner-thread callback fallback must not execute inline

## 6.1 Current code

Current listener logic is:

```java
public void marshalToOwnerThread(Runnable task) {
    MinecraftServer server = activeServer;
    if (server == null) {
        task.run();
        return;
    }
    server.execute(task);
}
```

The fallback is dangerous because the caller can be a generation completion thread.

That means controller-owned state can suddenly be mutated from:

- the coordinator executor;
- Minecraft worldgen completion thread;
- another CompletableFuture completion thread.

This violates the class-level ownership assumption that mutable generation/controller state is processed on the integrated-server owning thread.

---

## 6.2 Required fix

If there is no active server, the callback is stale and should be dropped.

Recommended implementation:

```java
@Override
public void marshalToOwnerThread(Runnable task) {
    MinecraftServer server = activeServer;
    if (server == null) {
        return;
    }
    server.execute(task);
}
```

Combined with scheduler session invalidation, a callback arriving after teardown has no useful work to perform anyway.

### Important invariant

> Never use `task.run()` as a fallback for a callback whose correctness depends on server-thread ownership.

If tests need synchronous execution, the fake scheduler listener used in tests can intentionally execute inline. Production code should not.

---

# 7. Remaining P2 issue — preserve full region-file modification time precision

## 7.1 Current code

The stable-read logic currently reduces modification timestamps to milliseconds:

```java
long modifiedBefore = Files.getLastModifiedTime(regionFile).toMillis();
...
long modifiedAfter = Files.getLastModifiedTime(regionFile).toMillis();

if (sizeBefore == sizeAfter && modifiedBefore == modifiedAfter) {
    ...
}
```

The design is correct, but converting to milliseconds before comparison can discard filesystem timestamp precision.

---

## 7.2 Better implementation

Compare `FileTime` values directly:

```java
FileTime modifiedBefore = Files.getLastModifiedTime(regionFile);
RegionState scanned = scanRegion(regionFile, regionX, regionZ);
FileTime modifiedAfter = Files.getLastModifiedTime(regionFile);

if (
    sizeBefore == sizeAfter &&
    modifiedBefore.equals(modifiedAfter)
) {
    return new StableRead(
        scanned.states(),
        scanned.cacheable(),
        sizeBefore,
        modifiedBefore.toMillis()
    );
}
```

This retains the cache's existing millisecond representation while making the stability comparison as precise as the filesystem provides.

Add:

```java
import java.nio.file.attribute.FileTime;
```

---

## 7.3 Optional stronger fingerprint

If later testing shows scanner false positives/false negatives under heavy autosave, consider comparing more than size+mtime.

For example, before and after scanning, fingerprint:

- file size;
- `FileTime`;
- region location table (first 4096 bytes);
- optionally the timestamp table (next 4096 bytes).

This is not currently required unless real testing demonstrates a remaining race.

---

# 8. Heap admission control and `-Xmx`

## 8.1 Current thresholds

Current values are:

```java
HEAP_PAUSE_RATIO = 0.85
HEAP_RESUME_RATIO = 0.75
```

This is a reasonable hysteresis scheme.

However, the ratio is relative to JVM maximum heap.

If a user configures an unnecessarily large heap such as 25–32 GiB, a very large temporary allocation burst may still appear to use only 40–60% of `maxMemory()`.

Example:

```text
-Xmx = 25 GiB
used = 12 GiB
ratio = 48%
```

The 85% gate would not activate despite the JVM already moving a very large amount of live/transient data through G1.

---

## 8.2 Recommendation

Keep the ratio-based gate, but document a practical runtime recommendation:

### Suggested test/runtime heap

For Minecraft 1.19.4 ChunkPatch validation:

```text
-Xmx8G
```

For a genuinely large modpack:

```text
-Xmx10G
```

Increase only if actual measured use requires it.

Avoid using 25–32 GiB merely because the machine has enough physical RAM.

---

## 8.3 Optional future admission signal

If heap behavior remains relevant after soak testing, consider supplementing the ratio with an absolute condition, for example:

```text
pause if used heap > absolute limit
OR
pause if old-gen / GC pressure indicates sustained stress
```

Do not add complex GC telemetry preemptively unless testing proves the current simple gate insufficient.

The objective is to keep the scheduler understandable and deterministic.

---

# 9. Optional one-tick gap after completion

The current single-in-flight model already provides strong backpressure.

A small additional safety margin would be to require at least one server tick between committing one completed target and submitting the next target.

Conceptually:

```text
future completes
    ↓
server thread commits completion
    ↓
next tick: eligible to submit next target
```

This gives Minecraft one scheduling opportunity for:

- chunk bookkeeping;
- save/unload activity;
- GC;
- ordinary gameplay tasks.

This is **not** a return of the old 40-tick slow-chunk cooldown.

It is merely a minimum submission interval.

### Recommendation

Treat this as optional.

Do not block release on it unless soak testing shows back-to-back completion/submission is still too aggressive.

---

# 10. Scanner executor lifecycle

`SCAN_EXECUTOR` is currently a static daemon single-thread executor.

This is acceptable for a mod that lives for the lifetime of the Minecraft client process.

The more important rule is that tasks submitted to it must be logically invalidated when their server/world session ends.

Therefore:

- it is not necessary to shut down `SCAN_EXECUTOR` every time a world closes;
- it **is** necessary to prevent old tasks from applying progress or results to a new world/session.

The scan session token described earlier is the preferred solution.

---

# 11. Gateway executor lifecycle

Unlike the global scan executor, `MinecraftChunkGenerationGateway` is world-specific because it holds a world-specific `ServerChunkCache`.

Therefore its coordinator executor **must** be disposed when the scheduler/world session ends.

Current `disposeScheduler()` correctly does:

```java
if (scheduler != null) scheduler.invalidateSession();
if (gateway != null) gateway.close();
scheduler = null;
gateway = null;
```

The required change is to ensure every world lifecycle termination path actually calls `disposeScheduler()`, especially `onServerStopping()`.

---

# 12. Gateway close behavior

Current gateway close is:

```java
@Override
public void close() {
    coordinator.shutdown();
}
```

This is a sensible default because it allows an already submitted coordinator task to finish rather than force-interrupting it.

Do not immediately switch to `shutdownNow()` unless testing proves a real shutdown hang.

The existing stale-session suppression already prevents completed stale work from changing ChunkPatch state.

If desired for diagnostics, a future version can expose whether the coordinator has terminated, but this is not necessary for the first hardened release.

---

# 13. Selection-size policy

The current code still permits:

```java
MAX_SELECTION_AREA = 5_000_000L;
```

With the new single-in-flight architecture, a large selection is less dangerous than under the old 1–8 chunks-per-tick burst model.

Therefore this no longer needs to be treated as a crash-fix blocker.

However, five million targets can represent a very long-running job.

Recommendations:

1. keep the limit if the product intentionally supports very large offline/long-running fills;
2. clearly display estimated remaining work/time;
3. checkpoint progress regularly;
4. make stop/pause/resume reliable;
5. never use selection size as justification to increase in-flight concurrency.

---

# 14. Checkpoint behavior

Current checkpoint behavior saves every 256 generated targets:

```java
SAVE_CHECKPOINT_INTERVAL = 256L;
```

and queues:

```java
level.getChunkSource().save(false);
```

without intentionally blocking for a full flush.

This is a reasonable compromise for normal progress persistence.

Important distinction:

- **ChunkPatch scan state** records what has completed in the current process;
- **Minecraft disk save state** determines what survives a crash or shutdown;
- recovery should be prepared to rescan and regenerate a target if a checkpoint was not fully persisted.

Do not make each chunk generation synchronously flush to disk; that would reintroduce serious blocking and I/O pressure.

---

# 15. Error handling expectations

Generation failure currently transitions to `ERROR`, saves what it can, disposes the scheduler, and persists progress.

That is preferable to repeatedly retrying an unknown worldgen failure forever.

Keep the default behavior conservative:

```text
one unexpected generation failure
    ↓
stop submitting new work
    ↓
record target + root cause
    ↓
save/checkpoint
    ↓
require user intervention / rescan
```

Avoid broad silent retries for arbitrary exceptions.

---

# 16. Logging / diagnostics recommendations

For soak testing, log enough information to correlate a future crash without creating huge per-tick logs.

Recommended events:

### Generation session start

Log:

- session ID;
- dimension;
- selected bounds;
- planned targets;
- JVM max heap;
- current heap use;
- current MSPT thresholds.

### Target submission

Do **not** log every target at INFO for million-chunk runs.

Use TRACE/DEBUG or periodic sampling.

### Admission hold transitions

Log only transitions, for example:

```text
Generation admission paused: MSPT 52.4 > 45.0
Generation admission resumed: MSPT 31.7 < 35.0
```

and:

```text
Generation admission paused: heap 86.2%
Generation admission resumed: heap 73.8%
```

### Session invalidation

Log:

- reason: stop / shutdown / dimension missing / error;
- session ID;
- whether a target was in flight.

### Scan stale result

At DEBUG level, optionally log when a stale scan completion is discarded.

This makes lifecycle races observable during testing.

---

# 17. Tests to add before release

The existing test suite already covers important scheduler and scanner behavior. Add these tests next.

## 17.1 Controller shutdown lifecycle

- scheduler disposed;
- gateway closed;
- active server cleared;
- scan/world references cleared;
- generation callback after shutdown ignored.

## 17.2 Scan token lifecycle

- scan result from old token ignored;
- scan progress from old token ignored;
- old scan cannot overwrite new scan;
- shutdown invalidates scan;
- world A scan cannot update world B.

## 17.3 Owner-thread callback discipline

Production listener must not execute completion inline when no active server exists.

## 17.4 Pause/stop while future is pending

For each state:

```text
submit target
request pause/stop
assert no second target submitted
complete first target
assert final state correct
```

## 17.5 Failure while pausing/stopping

Verify the controller still reaches the requested terminal state when the in-flight target completes exceptionally during drain.

## 17.6 Server shutdown while target is pending

```text
submit target
shutdown
complete future
assert no controller mutation
```

## 17.7 Region timestamp precision

Where feasible, mock or isolate the stable-read stamp comparison and ensure differing `FileTime` values that collapse to the same millisecond are still considered unstable.

---

# 18. Real-world soak-test plan

After the lifecycle fixes, stop changing architecture and test the actual workload.

## 18.1 Test environment

Record:

- Java vendor/version;
- Minecraft version;
- Fabric loader version;
- Fabric API version;
- ChunkPatch build/commit;
- mod list;
- `-Xmx`;
- CPU;
- physical RAM;
- XMP/undervolt/overclock state;
- overlays/security hooks enabled.

Recommended first JVM configuration:

```text
Java 17 current supported build
-Xmx8G
```

---

## 18.2 Test stages

### Stage 1 — Vanilla/Fabric + ChunkPatch only

Run a large fresh-world generation selection for at least:

- 30 minutes;
- then 2 hours;
- then an overnight/long run if stable.

Monitor:

- server MSPT;
- heap use;
- GC behavior;
- chunk completion rate;
- disk growth;
- pauses/errors;
- JVM fatal errors.

### Stage 2 — normal modpack

Add the user's normal mod set.

Repeat the test.

### Stage 3 — lifecycle stress

Repeatedly:

```text
start scan
cancel/leave world
re-enter
scan
start generation
pause
resume
stop
leave world
open another world
```

This specifically validates the fixes in this document.

### Stage 4 — autosave/scan stress

Run scanner while the integrated server is active and modifying region files.

Confirm:

- unstable files are retried;
- unstable files are not falsely marked corrupt;
- scan eventually succeeds or reports a clear unstable/unreadable count.

---

# 19. Crash interpretation after this refactor

If the same native JVM crash still occurs after:

- the single-in-flight refactor;
- lifecycle fixes;
- sane `-Xmx`;
- Java update;
- ChunkPatch-only testing;

then the probability shifts further away from a simple ChunkPatch Java logic bug.

At that point investigate:

1. RAM stability / memory test;
2. XMP;
3. CPU undervolt/overclock;
4. Java/HotSpot implementation issue;
5. native hooks/overlays;
6. endpoint-security injection;
7. graphics/native driver interaction;
8. another mod using JNI/JNA/LWJGL native memory.

The important distinction remains:

> ChunkPatch can be the workload that exposes a JVM/hardware/native-memory instability without itself directly performing invalid native memory writes.

The project currently contains no obvious direct `Unsafe`/JNI/JNA/native manual-allocation code that would explain a classic Java-side use-after-free or double-free.

---

# 20. Exact implementation order

Recommended commit sequence:

## Commit 1 — scan lifecycle token

Implement:

- `scanSessionId`;
- stale progress suppression;
- stale completion suppression;
- shutdown invalidation.

Add scan lifecycle tests.

## Commit 2 — server shutdown teardown

Implement:

- `disposeScheduler()` during shutdown;
- clear `activeServer`;
- clear world-owned controller references;
- reset transient state;
- preserve resumable persisted progress.

Add shutdown lifecycle tests.

## Commit 3 — remove inline owner-thread fallback

Change production `marshalToOwnerThread` to drop callbacks when no active server is available.

Add callback discipline test.

## Commit 4 — FileTime precision

Change stable-read comparison from millisecond `long` values to direct `FileTime` equality.

Add targeted scanner test.

## Commit 5 — diagnostics and documentation

Add transition-oriented logs and update README/CHANGELOG if desired.

## Commit 6 — soak-test candidate

Build a release candidate and perform real-world testing before further scheduler changes.

---

# 21. Things that should NOT be reintroduced

Do not reintroduce these unless there is a completely new measured reason:

### 21.1 `chunksPerTick = 1..8`

The target count is not an accurate measure of actual worldgen work.

### 21.2 Blocking `getChunk(FEATURES, true)` on server tick

This was the central architectural problem.

### 21.3 Slow-chunk 40-tick brake as the primary safety mechanism

It reacts after the expensive burst already occurred.

### 21.4 Multiple concurrent in-flight generation requests

Do not increase concurrency merely to increase throughput.

### 21.5 `CompletableFuture.join()` / `.get()` in server tick

Future-driven code becomes blocking again if the server waits synchronously for it.

### 21.6 Force-cancelling Minecraft worldgen futures

ChunkPatch does not own the entire dependency graph and should drain/ignore stale results instead.

### 21.7 Inline completion callback when the owning server is gone

Dropping stale work is safer than mutating state from an arbitrary completion thread.

---

# 22. Repository hygiene / `.gitignore` policy

The repository should track only files required to understand, build, test, document, and maintain the source project.

The updated `.gitignore` uses an **allowlist-oriented policy**:

> Ignore everything at the repository root by default, then explicitly unignore source-related project files/directories.

This intentionally excludes:

- `.gradle/` caches;
- `build/` output;
- `bin/` output;
- `out/` / `classes/`;
- run directories;
- logs and crash dumps;
- generated JARs;
- Graphify output/cache/report directories;
- `.vscode/` and `.idea/`;
- local `.claude/` settings/skills/cache;
- archives and temporary files;
- local world/save data;
- profiling/heap dump files.

It intentionally keeps:

- `src/**` including code, tests, resources and required source assets;
- `build.gradle` / `settings.gradle` / `gradle.properties`;
- Gradle wrapper files, including `gradle-wrapper.jar` because it is required for reproducible wrapper execution;
- `.github/**` repository workflows/configuration;
- `.gitattributes`;
- `.gitignore`;
- root Markdown documentation;
- `docs/**` if a project documentation directory is introduced;
- `LICENSE*`.

### Important Git note

`.gitignore` only affects **untracked files**.

If generated files are already tracked, adding them to `.gitignore` will not remove them from Git history/index automatically.

For already-tracked generated directories, use commands such as:

```bash
git rm -r --cached build .gradle bin logs graphify-out .vscode .claude
```

Only include paths that are actually tracked in the current repository.

Then verify:

```bash
git status
```

and:

```bash
git status --ignored
```

---

# 23. Release acceptance checklist

## Architecture

- [ ] No blocking `getChunk(FEATURES, true)` generation path exists.
- [ ] Generation requests enter `getChunkFuture` from the dedicated coordinator path.
- [ ] Maximum generation in-flight count remains exactly 1.
- [ ] No `.join()` / `.get()` waits are used in server tick.
- [ ] Old post-slow-chunk cooldown system remains removed.

## Generation lifecycle

- [ ] Pause drains current target.
- [ ] Stop drains current target.
- [ ] Generation stale-session callback is ignored.
- [ ] Server shutdown disposes scheduler and gateway.
- [ ] `activeServer` is cleared on shutdown.
- [ ] Old world-specific references are cleared on shutdown.
- [ ] Old coordinator executor cannot keep old world cache alive indefinitely.

## Scan lifecycle

- [ ] Each asynchronous scan has a unique session token.
- [ ] Old scan progress cannot update a new session.
- [ ] Old scan result cannot update a new session.
- [ ] Shutdown invalidates current scan token.
- [ ] World A scan cannot overwrite World B state.

## Thread ownership

- [ ] Production completion callbacks are only applied on the intended server owning thread.
- [ ] `activeServer == null` does not cause inline callback execution.
- [ ] Client preview only observes immutable/defensive snapshot state.

## Region scanner

- [ ] Concurrent mutation triggers retry.
- [ ] Permanently unstable file is not falsely marked corrupt.
- [ ] Stable-read comparison uses full `FileTime` precision.
- [ ] Scanner cache only stores stable reads.

## Persistence

- [ ] Normal server shutdown does not accidentally destroy resumable progress.
- [ ] Explicit permanent stop behavior is documented.
- [ ] Interrupted checkpoint can recover through rescan/regeneration.

## Safety gates

- [ ] Disk-space gate runs before new submission.
- [ ] MSPT hysteresis works.
- [ ] Heap hysteresis works.
- [ ] Tests are performed with sane `-Xmx` values.

## Tests

- [ ] Scheduler tests pass.
- [ ] Snapshot tests pass.
- [ ] Scanner tests pass.
- [ ] Scanner mutation tests pass.
- [ ] Shutdown lifecycle tests pass.
- [ ] Scan token lifecycle tests pass.
- [ ] Pause/stop drain tests pass.
- [ ] Stale completion tests pass.

## Repository

- [ ] `build/` is ignored.
- [ ] `.gradle/` is ignored.
- [ ] `bin/` is ignored.
- [ ] `logs/` is ignored.
- [ ] `graphify-out/` is ignored.
- [ ] IDE/local-agent state is ignored.
- [ ] `src/**` is tracked.
- [ ] Gradle wrapper/build configuration is tracked.
- [ ] root project Markdown/source documentation is tracked.
- [ ] GitHub workflow files are tracked.

## Soak test

- [ ] ChunkPatch-only fresh-world generation runs for at least 2 hours without fatal JVM crash.
- [ ] Normal modpack generation runs for at least 2 hours without fatal JVM crash.
- [ ] World close/reopen lifecycle stress produces no stale updates.
- [ ] Pause/resume/stop stress produces no second in-flight target.
- [ ] Long-run heap usage remains bounded/recoverable.
- [ ] No repeated runaway MSPT submission behavior occurs.

---

# 24. Final recommendation

The major `0.2.0` generation refactor should be kept.

The project has already moved from an unsafe reactive architecture:

```text
blocking worldgen burst
    ↓
measure damage
    ↓
cooldown
```

to a much better proactive architecture:

```text
no in-flight work?
    ↓
server healthy enough?
    ↓
submit exactly one asynchronous Minecraft worldgen request
    ↓
wait for completion without blocking server tick
    ↓
commit completion on owner thread
    ↓
only then consider the next target
```

The remaining high-priority work is **lifecycle correctness**, not throughput tuning.

Fix server teardown, add scan-session invalidation, remove the inline completion fallback, improve region timestamp precision, then freeze the architecture and move to real soak testing.

If the same native G1/JVM crash survives those changes under a clean ChunkPatch-only test with a sane heap configuration, investigation should expand to JVM, hardware stability, and native hooks rather than continuing to add arbitrary throttles to ChunkPatch.
