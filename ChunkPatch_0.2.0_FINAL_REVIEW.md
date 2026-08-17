# ChunkPatch 0.2.0 — Final Review & Soak Test Readiness

> Status: **Core refactor accepted, minor lifecycle/cache hardening recommended before long-running soak tests**
>
> Target environment: **Minecraft 1.19.4 / Fabric / Java 17**
>
> Reviewed revision: `ChunkPatch(2).zip`

---

## 1. Executive Summary

ChunkPatch 0.2.0 has successfully completed the most important architectural refactor required to address the previous JVM crash pattern.

The original high-risk design used synchronous chunk generation from the integrated server tick path:

```text
Server tick
  ↓
getChunk(..., ChunkStatus.FEATURES, true)
  ↓
block server thread
  ↓
large world-generation dependency fan-out
  ↓
large allocation burst
  ↓
G1 GC pressure
```

The current implementation no longer uses that design.

The new architecture now uses:

```text
Server tick
  ↓
single in-flight admission
  ↓
coordinator thread
  ↓
getChunkFuture(..., ChunkStatus.FEATURES, true)
  ↓
Minecraft world-generation pipeline
  ↓
future completion
  ↓
server.execute(...)
  ↓
commit result
  ↓
next target may be admitted later
```

The following former mechanisms are no longer required and have correctly been removed from the generation path:

- `chunksPerTick`
- `SLOW_CHUNK_*`
- `SLOW_CHUNK_COOLDOWN_TICKS`
- `LAG_BACKOFF_*`
- synchronous `getChunk(...)`
- blocking `.join()` / `.get()` waits on world-generation futures

The current architecture provides **backpressure before additional work is submitted**, rather than attempting to brake only after an expensive synchronous generation call has already completed.

### Current assessment

| Area | Status |
|---|---|
| Core generation architecture | ✅ Accepted |
| Single in-flight backpressure | ✅ Accepted |
| True non-blocking `getChunkFuture()` path | ✅ Accepted |
| Old cooldown/brake removal | ✅ Accepted |
| Pause / stop draining | ✅ Accepted |
| Generation session invalidation | ✅ Accepted |
| Server shutdown teardown | ✅ Fixed |
| Scan session invalidation | ✅ Fixed |
| Client/server preview race | ✅ Fixed |
| Region stable-read protection | ✅ Fixed |
| Next-target same-tick prevention | ✅ Fixed |
| Gateway shutdown queued-task guard | ⚠️ Recommended |
| Region scan cache timestamp precision | ⚠️ Recommended |
| Long-duration real-world validation | ⏳ Required |

The project is now at the stage where **further major generation refactoring is not recommended**.

The remaining work should focus on two small hardening changes, followed by real-world soak testing.

---

# 2. Background: Previous JVM Crash Pattern

The earlier ChunkPatch builds were associated with repeated fatal JVM crashes showing the same general pattern:

```text
EXCEPTION_ACCESS_VIOLATION
Problematic frame: jvm.dll
Current thread: GCTaskThread
GC: G1
```

The crashes did not look like ordinary Fabric or Minecraft Java exceptions.

Typical Java/mod failures would normally resemble:

```text
java.lang.IllegalStateException
MixinApplyError
NullPointerException
Caused by:
net.minecraft....
```

Instead, the process itself terminated inside HotSpot's native JVM implementation.

The strongest common factor was:

```text
GCTaskThread
  ↓
jvm.dll
  ↓
EXCEPTION_ACCESS_VIOLATION
```

This strongly suggested that the crash occurred while G1 was processing heap state.

However, the source review did **not** find ChunkPatch code using common direct native-memory corruption mechanisms such as:

```text
sun.misc.Unsafe
jdk.internal.misc.Unsafe
JNI
JNA native writes
MemoryUtil.memAlloc
MemoryUtil.memFree
ByteBuffer.allocateDirect
manual native pointer manipulation
```

Therefore the appropriate conclusion remains:

> ChunkPatch was very likely a **trigger for an extreme world-generation / allocation workload**, but the available evidence does not prove that ChunkPatch directly corrupted native JVM memory.

That distinction should remain explicit in future bug reports and release notes.

---

# 3. Why the Old Architecture Was Dangerous

The previous controller directly invoked synchronous chunk generation from the server tick:

```java
level.getChunkSource().getChunk(
    x,
    z,
    ChunkStatus.FEATURES,
    true
);
```

The problem was not simply "one chunk takes too long."

`ChunkStatus.FEATURES` is a comparatively deep world-generation stage.

Requesting one target at `FEATURES` may cause Minecraft to satisfy a much larger dependency graph involving neighboring chunks and earlier generation statuses.

Conceptually:

```text
requested target
    ↓
BIOMES
    ↓
NOISE
    ↓
SURFACE
    ↓
CARVERS
    ↓
FEATURES
    ↓
neighbor/dependency requirements
    ↓
many temporary objects and worldgen structures
```

This produces potentially large transient allocation bursts.

The old design then multiplied the problem with a configurable rate such as:

```text
1–8 chunks per tick
```

That value was misleading.

One "requested chunk" was not equivalent to one cheap unit of work.

---

# 4. Why the Old Brake Mechanism Was Insufficient

Older versions attempted to detect slow generation:

```text
call getChunk()
    ↓
generation takes a long time
    ↓
measure elapsed time
    ↓
if too slow:
    pause for N ticks
```

This is fundamentally reactive.

The expensive operation had already occurred before the controller decided to back off.

Example:

```text
server tick
  ↓
getChunk(FEATURES)
  ↓
large allocation burst
  ↓
worldgen dependency work
  ↓
GC pressure
  ↓
call finally returns
  ↓
ChunkPatch notices that it was slow
  ↓
cooldown
```

The new scheduler is better because it controls admission **before the next target is submitted**.

Therefore the old slow-chunk cooldown should remain removed.

---

# 5. Current Generation Architecture

## 5.1 Single In-Flight Work

The current scheduler effectively maintains:

```text
MAX_IN_FLIGHT = 1
```

This is the most important safety invariant in the new design.

At any moment:

```text
0 active generation requests
```

or:

```text
1 active generation request
```

There should never be multiple ChunkPatch-owned `FEATURES` targets intentionally in flight at the same time.

This substantially limits the amount of additional generation pressure that ChunkPatch can introduce.

---

## 5.2 True Asynchronous Gateway

The current implementation uses a coordinator executor to call Minecraft's future-based API outside the server main thread.

Conceptually:

```java
CompletableFuture
    .supplyAsync(
        () -> source.getChunkFuture(
            x,
            z,
            ChunkStatus.FEATURES,
            true
        ),
        coordinator
    )
    .thenCompose(Function.identity());
```

This detail is important.

Simply replacing:

```java
getChunk(...)
```

with:

```java
getChunkFuture(...)
```

while still calling it directly from the server main thread would not necessarily solve the blocking behavior.

The current coordinator-based entry path avoids that mistake.

### Required invariant

Do not regress this into:

```java
END_SERVER_TICK
    ↓
source.getChunkFuture(...)
```

without preserving the non-main-thread entry behavior.

---

# 6. Completion Thread Ownership

Generation completion should not mutate controller/world state directly from an arbitrary completion thread.

The current design correctly marshals completion back through:

```java
server.execute(...)
```

The intended ownership model is:

```text
coordinator/worldgen threads
    ↓
perform/observe async work only
    ↓
server.execute(...)
    ↓
server thread owns controller state mutation
```

This invariant should remain documented and tested.

---

# 7. Next-Target Tick Separation

The current version introduces a small submission separation:

```java
nextEligibleTick = activeServer.getTickCount() + 1;
```

This means that after target A completes, target B cannot immediately be admitted in the exact same server tick.

This is a useful lightweight safety margin.

It allows the server another scheduling opportunity for:

- normal server work
- chunk bookkeeping
- save/unload activity
- GC progress
- unrelated mod tasks
- network processing

This is **not** equivalent to the old 40-tick brake.

It should remain.

---

# 8. Pause and Stop Semantics

The current architecture correctly treats pause/stop as a drain operation rather than attempting to forcibly cancel Minecraft world generation.

Desired behavior:

## Pause

```text
RUNNING
  ↓
user requests pause
  ↓
PAUSING
  ↓
current in-flight request allowed to complete
  ↓
no new target submitted
  ↓
PAUSED
```

## Stop

```text
RUNNING
  ↓
user requests stop
  ↓
STOPPING
  ↓
current request allowed to settle
  ↓
no new requests
  ↓
scheduler disposed
  ↓
READY / IDLE
```

Hard cancellation of Minecraft's internal generation future should generally be avoided unless the exact Minecraft implementation is known to safely support it.

---

# 9. Generation Session Tokens

Async work may finish after its logical generation session has ended.

The current scheduler protects against stale completion callbacks with session invalidation.

Conceptually:

```text
session A submits target
  ↓
session A stops
  ↓
session B starts
  ↓
old target A completes late
  ↓
callback sees stale session
  ↓
discard
```

This is required for correct world/server lifecycle behavior.

Keep this mechanism even though only one target is in flight.

---

# 10. Server Shutdown Lifecycle

The previous review identified a serious lifecycle problem: the controller could retain references to the old world after server shutdown.

That has now been corrected.

The shutdown path now performs the equivalent of:

```text
invalidate generation session
invalidate scan session
dispose scheduler
close gateway
clear server reference
clear world/dimension state
clear scan state
clear selection state
reset controller state
```

This prevents the static controller from retaining:

```text
old MinecraftServer
old ServerLevel
old ServerChunkCache
old ChunkMap
old ChunkScanResult
old generation scheduler
```

It also prevents a newly opened world from accidentally interacting with objects from the previous integrated server.

This was one of the most important fixes in the post-refactor review.

---

# 11. Scan Session Lifecycle

Scanning is asynchronous and therefore has the same stale-result problem as generation.

The current version now uses scan-session invalidation.

Expected behavior:

```text
World A:
start scan A

user exits World A

World B:
start scan B

scan A completes late

scan A:
session mismatch
or server mismatch
→ discard result
```

This protects the next world from receiving stale scanner state.

The current design should retain both checks where available:

```text
scan session ID
submitted server identity
```

---

# 12. `marshalToOwnerThread()` Behavior

The previous implementation had a dangerous fallback:

```java
if (server == null) {
    task.run();
}
```

That could execute controller mutation on an arbitrary future/coordinator thread during shutdown.

The current behavior instead drops the callback if no active server exists.

Conceptually:

```java
MinecraftServer server = activeServer;

if (server == null) {
    return;
}

server.execute(task);
```

This preserves the server-thread ownership invariant.

Do not reintroduce a direct `task.run()` fallback.

---

# 13. Preview Snapshot Concurrency

The original preview implementation exposed mutable backing arrays to both server and client/render paths.

That created a data race:

```text
server thread:
savedCounts[index]++

render thread:
savedCounts[index]
```

Even though Java `int` access is atomic, unsynchronized shared mutable arrays violate the intended ownership model and can produce inconsistent snapshots.

The current implementation publishes cloned immutable snapshot data.

Example:

```java
publishedPreview = new PreviewSnapshot(
    ...,
    savedCounts.clone(),
    partialCounts.clone(),
    possibleCounts.clone(),
    invalid.clone()
);
```

This is the correct architecture.

The server owns mutable working data.

The client/render side receives immutable published snapshots.

---

# 14. Region Scanner Stable-Read Protection

Minecraft may write `.mca` region files while the async scanner is reading them.

The current scanner now compares file metadata before and after reading.

Importantly, the current revision uses full `FileTime` equality rather than truncating timestamps to milliseconds.

Desired pattern:

```java
FileTime modifiedBefore =
    Files.getLastModifiedTime(regionFile);

long sizeBefore =
    Files.size(regionFile);

scanRegion(...);

long sizeAfter =
    Files.size(regionFile);

FileTime modifiedAfter =
    Files.getLastModifiedTime(regionFile);

if (
    sizeBefore == sizeAfter &&
    modifiedBefore.equals(modifiedAfter)
) {
    // stable read
}
```

This is substantially better than:

```java
getLastModifiedTime(...).toMillis()
```

for the stable-read comparison.

---

# 15. Remaining Recommendation #1:
# Gateway Shutdown Guard

This is the most important remaining code-level hardening item.

## 15.1 Problem

The gateway currently shuts down the coordinator executor with behavior equivalent to:

```java
coordinator.shutdown();
```

`ExecutorService.shutdown()` prevents new tasks from being accepted, but tasks that were already queued are still allowed to execute.

A race is therefore possible:

```text
requestFeatures()
  ↓
task queued into coordinator

SERVER_STOPPING
  ↓
gateway.close()
  ↓
coordinator.shutdown()

queued task has not executed yet
  ↓
executor is allowed to execute it
  ↓
task calls old source.getChunkFuture(...)
```

The stale completion callback is already protected by session validation.

However, ideally shutdown should prevent the queued task from touching the old `ServerChunkCache` at all.

---

## 15.2 Recommended Design

Introduce an explicit closed state.

Example:

```java
private final AtomicBoolean closed =
    new AtomicBoolean(false);
```

At API entry:

```java
public CompletableFuture<Void> requestFeatures(
    int chunkX,
    int chunkZ
) {
    if (closed.get()) {
        return CompletableFuture.failedFuture(
            new IllegalStateException(
                "Generation gateway is closed"
            )
        );
    }

    // submit work
}
```

Inside the coordinator task, check again:

```java
return CompletableFuture
    .supplyAsync(() -> {
        if (closed.get()) {
            throw new CompletionException(
                new IllegalStateException(
                    "Generation gateway is closed"
                )
            );
        }

        return source.getChunkFuture(
            chunkX,
            chunkZ,
            ChunkStatus.FEATURES,
            true
        );
    }, coordinator)
    .thenCompose(Function.identity())
    ...;
```

Shutdown:

```java
@Override
public void close() {
    closed.set(true);
    coordinator.shutdown();
}
```

The second check is required because the gateway may be closed after the API call passes the first check but before the queued task actually runs.

---

## 15.3 Stronger Variant

If exact serialization is desired, use a lifecycle lock around:

```text
closed check
+
source.getChunkFuture(...)
```

and:

```text
close()
```

This makes the shutdown boundary explicit.

However, the simple double-checked closed flag is already a useful defensive improvement.

---

## 15.4 Suggested Tests

Add tests covering:

### Request after close

```text
create gateway
close gateway
request target
→ future fails
→ source is not touched
```

### Queued task then close

```text
block coordinator
queue generation request
close gateway
release coordinator
→ queued task observes closed state
→ source.getChunkFuture is not called
```

---

# 16. Remaining Recommendation #2:
# Region Scan Cache Timestamp Precision

The stable-read check now uses full `FileTime`, but the scan cache may still use millisecond timestamps.

If cache lookup uses:

```java
long modifiedMillis =
    Files.getLastModifiedTime(regionFile).toMillis();
```

then two different modifications within the same millisecond can theoretically produce the same key.

Example:

```text
original:
12:00:00.123100

new write:
12:00:00.123800

toMillis():
both → 12:00:00.123
```

If file size also remains unchanged:

```text
same size
same millisecond mtime
```

the cache could incorrectly treat the region as unchanged.

---

# 17. Recommended RegionScanCache v4

Bump the cache format version.

Example:

```java
private static final int VERSION = 4;
```

Use full timestamp precision.

One simple representation is:

```java
Instant modified =
    Files.getLastModifiedTime(regionFile).toInstant();
```

Store:

```java
private record Entry(
    long size,
    long modifiedEpochSecond,
    int modifiedNano,
    byte[] states
) {}
```

Lookup compares:

```text
size
epochSecond
nano
```

This aligns cache validation with the precision already used by stable-read detection.

No migration is required.

Old v3 cache files can simply be invalidated and rebuilt.

---

# 18. Suggested Cache Precision Tests

Add at least these cases.

## Same size, different nanosecond timestamp

```text
cache entry:
size = X
mtime = second S + nano A

new file:
size = X
mtime = second S + nano B

A != B

→ cache miss
```

## Same size and exact timestamp

```text
size equal
seconds equal
nanos equal

→ cache hit
```

## Version mismatch

```text
stored cache version = 3
expected version = 4

→ ignore/rebuild cache
```

---

# 19. Heap Admission Gate

The current scheduler performs submission-time safety checks based on metrics such as heap usage and server timing.

This is preferable to the old post-generation brake.

However, heap-ratio gates depend strongly on `-Xmx`.

For example:

```text
-Xmx25G
used heap = 12G

ratio = 48%
```

Even if the process is rapidly cycling between:

```text
5G
→ 12G
→ GC
→ 5G
→ 12G
```

a pause threshold such as:

```text
85%
```

will never trigger.

Therefore heap admission logic should not be interpreted as a substitute for reasonable JVM sizing.

---

# 20. Recommended JVM Memory for Testing

For the initial validation:

```text
-Xmx8G
```

Recommended starting point:

```text
-Xms512M
-Xmx8192M
```

For unusually large modpacks:

```text
-Xmx10G
```

may be reasonable for testing.

Avoid returning to configurations such as:

```text
-Xmx25G
-Xmx31G
```

during initial diagnosis.

Oversized heaps make memory-ratio admission gates less useful and may make allocation/GC behavior harder to interpret.

---

# 21. Why the Old Cooldown Should Stay Deleted

Do not reintroduce logic such as:

```text
if generation took >150 ms:
    pause 40 ticks
```

The new architecture already provides the correct form of pressure control:

```text
no current request?
  ↓
admission conditions acceptable?
  ↓
submit exactly one target
  ↓
wait for completion
  ↓
commit result
  ↓
wait until next eligible tick
  ↓
repeat
```

That is true backpressure.

The old cooldown was an after-the-fact reaction and is no longer the correct abstraction.

---

# 22. Current Test Status

The reviewed project contains test results indicating:

```text
28 tests
0 failures
0 errors
0 skipped
```

The test suite includes coverage for important lifecycle behaviors such as:

- generation scheduler behavior
- shutdown lifecycle
- scan lifecycle
- preview snapshots
- region scanner behavior
- concurrent region mutation
- next-target tick separation

Examples of particularly useful regression tests include scenarios equivalent to:

```text
shutdownWhileTargetIsPendingIsIgnoredOnLateCompletion
```

```text
startingASecondWorldAfterShutdownOnlyUsesTheNewState
```

```text
worldAScanCannotUpdateStateAfterWorldBScanStarts
```

```text
doesNotSubmitTheNextTargetOnTheSameTickAsTheLastCompletion
```

These are meaningful tests because they exercise actual failure modes found during review.

---

# 23. Build Environment Note

A future reviewer should rerun:

```bash
./gradlew test
```

and:

```bash
./gradlew build
```

locally or in CI.

The prior review environment could inspect the project and its existing Gradle test reports, but could not independently download the required Gradle distribution because external network/DNS access was unavailable.

Therefore the repository's existing passing reports are useful evidence, but CI or a local developer machine should remain the source of truth before release.

---

# 24. `.gitignore` Status

The allowlist-oriented `.gitignore` approach is appropriate for this repository.

The intended policy is:

> Keep source code, build configuration, Gradle wrapper, CI configuration, and source-related documentation. Ignore generated/runtime/developer-local artifacts.

Expected ignored content includes:

```text
.gradle/
build/
bin/
out/
run/
logs/
graphify-out/
.vscode/
.idea/
.claude/
saves/
crash-reports/
*.jar
*.zip
hs_err_*.log
*.hprof
*.jfr
```

Expected retained content includes:

```text
src/**
build.gradle
settings.gradle
gradle.properties
gradlew
gradlew.bat
gradle/wrapper/**
.github/**
README.md
CHANGELOG.md
docs/**
*.md
LICENSE*
NOTICE*
.gitignore
.gitattributes
```

The Gradle wrapper JAR should remain tracked because it is part of the reproducible project wrapper.

---

# 25. Do Not Change the Core Scheduler Again Without Evidence

At this stage, avoid speculative redesign of the generation architecture.

Do **not** reintroduce:

```text
multiple targets per tick
```

Do **not** add:

```text
MAX_IN_FLIGHT > 1
```

without profiling and explicit evidence.

Do **not** replace the coordinator path with direct server-thread future submission unless Minecraft's behavior is revalidated.

Do **not** block with:

```java
future.join();
future.get();
```

Do **not** use:

```java
getChunk(..., true)
```

from the server tick path for pre-generation.

The current architecture should be treated as stable unless soak testing reveals a concrete failure.

---

# 26. Recommended Final Code Changes Before Soak Test

Only two code hardening tasks are recommended.

## Required / Strongly Recommended

### A. Gateway closed guard

Implement:

```text
closed state
API-entry closed check
queued-task closed check
shutdown closed transition
```

Goal:

> No queued coordinator task should begin a new request against an old `ServerChunkCache` after gateway shutdown.

---

### B. RegionScanCache full timestamp precision

Implement:

```text
cache VERSION 4
size
mtime epochSecond
mtime nano
```

Goal:

> Avoid false cache hits when a same-size region file changes more than once inside one millisecond.

---

# 27. Suggested Commit Structure

A clean implementation history could be:

## Commit 1

```text
fix(generation): guard gateway requests after shutdown
```

Changes:

- add `closed`
- reject new request after close
- re-check `closed` inside queued coordinator task
- add lifecycle tests

---

## Commit 2

```text
fix(scanner): preserve full region mtime precision in cache
```

Changes:

- cache version 3 → 4
- store seconds + nanos
- update cache lookup/write
- add precision/version tests

---

## Commit 3

```text
test: add final lifecycle and cache regression coverage
```

If tests are kept separate.

---

# 28. Soak Test Plan

After the two small hardening fixes, stop changing architecture and begin real-world testing.

Recommended progression:

```text
1,000 targets
  ↓
10,000 targets
  ↓
50,000+ targets
  ↓
1–3 hour continuous soak
```

Use a test world where the selected area contains substantial previously ungenerated terrain.

---

# 29. Soak Test Environment

Recommended baseline:

```text
Minecraft: 1.19.4
Loader: Fabric
Java: current supported Java 17 build
Heap: 8 GB
ChunkPatch: current refactored build
```

Initially test with as few unrelated mods as practical.

Then repeat with the normal modpack.

This helps distinguish:

```text
ChunkPatch-specific behavior
```

from:

```text
interaction with other mods/native hooks
```

---

# 30. Metrics to Observe

During soak testing, record:

- total requested targets
- completed targets
- skipped/already-generated targets
- current in-flight count
- server MSPT
- heap used
- heap committed
- heap maximum
- GC frequency if available
- generation completion latency
- admission pauses
- save activity
- scan activity
- fatal JVM logs
- Java exceptions
- server watchdog warnings

---

# 31. Functional Scenarios to Test

## Normal generation

```text
select area
scan
start generation
finish
rescan
verify expected completion
```

---

## Pause

```text
start generation
wait for active target
pause
verify current target drains
verify no new target submits
resume
verify generation continues
```

---

## Stop

```text
start generation
stop while target is active
verify active target settles
verify no new generation
verify controller returns to stable state
```

---

## Exit world during generation

```text
start generation
exit singleplayer world
verify no crash
open another world
verify old callback/state does not appear
```

---

## Exit during scan

```text
start large scan
exit world before scan completes
open another world
verify old scan result is ignored
```

---

## Rapid world replacement

```text
World A
start scan/generation
exit
World B
start new scan
verify only B state is visible
```

---

## Save/load recovery

```text
generate partially
exit normally
restart
rescan/recover
verify completed progress is consistent
```

---

# 32. Memory/GC Validation

The original crash investigation was strongly associated with G1 GC threads.

Therefore long-running validation should specifically watch for recurrence of:

```text
hs_err_pid*.log
```

containing:

```text
EXCEPTION_ACCESS_VIOLATION
jvm.dll
GCTaskThread
```

If no such crash occurs under prolonged generation, that is strong evidence that the architecture change removed the previous trigger.

---

# 33. If the JVM Crash Still Happens

If the refactored scheduler, shutdown hardening, and reasonable 8–10 GB heap still produce the same fatal pattern:

```text
GCTaskThread
jvm.dll
EXCEPTION_ACCESS_VIOLATION
```

then the probability of a normal ChunkPatch Java logic error becomes substantially lower.

At that point prioritize investigation of:

- physical RAM stability
- XMP
- CPU undervolt / overclock
- BIOS memory settings
- Java runtime build
- Windows memory errors
- NVIDIA/graphics native components
- Overwolf
- Discord overlay
- antivirus / endpoint injection
- other native game hooks
- another mod's JNI/JNA/native library

---

# 34. Hardware Validation If Needed

If the crash persists with the new architecture:

1. Disable XMP temporarily.
2. Remove CPU/GPU undervolts and overclocks.
3. Return BIOS memory settings to stock.
4. Test RAM with MemTest86.
5. Run OCCT memory/CPU stability testing.
6. Try with overlays disabled.
7. Try a minimal Fabric instance.
8. Compare a different Java 17 distribution/build if necessary.

This should only become the primary investigation path if the new ChunkPatch scheduler still reproduces the same native GC crash.

---

# 35. Release Acceptance Checklist

## Core generation

- [ ] No synchronous `getChunk(..., true)` in ChunkPatch generation path.
- [ ] No `.join()` on world-generation futures.
- [ ] No `.get()` on world-generation futures.
- [ ] Single ChunkPatch-owned target in flight.
- [ ] Completion returns to server owner thread.
- [ ] Next target cannot submit in the same tick as previous completion.
- [ ] Admission gate remains pre-submission.
- [ ] Old slow-chunk cooldown remains removed.

## Lifecycle

- [ ] Generation session invalidates on stop/shutdown.
- [ ] Scan session invalidates on shutdown/new scan.
- [ ] Old server/world references are cleared.
- [ ] Gateway closes during teardown.
- [ ] Gateway rejects post-close requests.
- [ ] Queued gateway work re-checks closed state.
- [ ] Late stale callbacks cannot modify current state.

## Scanner

- [ ] Stable reads compare full `FileTime`.
- [ ] Region mutation during scan triggers retry/rejection.
- [ ] Cache validation uses full mtime precision.
- [ ] RegionScanCache version bumped if format changed.
- [ ] Old cache is safely rebuilt.

## Concurrency

- [ ] Mutable preview arrays remain server-owned.
- [ ] Client uses immutable/cloned snapshot.
- [ ] Controller state is not mutated from coordinator/worldgen threads.
- [ ] No `task.run()` fallback when server is unavailable.

## Tests

- [ ] Unit tests pass.
- [ ] Integration tests pass.
- [ ] Gateway close race test passes.
- [ ] Cache precision test passes.
- [ ] Shutdown lifecycle tests pass.
- [ ] Scan replacement tests pass.
- [ ] `./gradlew build` passes in CI/local environment.

## Soak testing

- [ ] 1,000 target test passes.
- [ ] 10,000 target test passes.
- [ ] 50,000+ target test passes.
- [ ] 1-hour continuous run passes.
- [ ] 3-hour continuous run passes.
- [ ] Pause/resume passes.
- [ ] Stop passes.
- [ ] Exit/re-enter world passes.
- [ ] No new `hs_err_pid*.log`.
- [ ] No recurring G1 fatal crash.

---

# 36. Release Decision

The project should be considered ready for release when:

```text
core async architecture remains intact
+
gateway shutdown guard is implemented
+
cache timestamp precision is hardened
+
all tests pass
+
real-world soak testing produces no JVM fatal crash
```

At that point further scheduler changes should require measurable evidence rather than speculative tuning.

---

# 37. Final Recommendation

The current ChunkPatch 0.2.0 generation architecture should be treated as the new baseline.

The major problem has already been addressed:

```text
OLD:
synchronous generation + multiple chunks per tick + post-hoc brake

NEW:
single in-flight async generation + pre-admission backpressure
```

Do not bring back the old brake mechanism.

Do not increase concurrency for speed until the current implementation has completed long-duration validation.

Complete only the two remaining hardening items:

1. **Gateway shutdown closed guard**
2. **Full-precision RegionScanCache timestamps**

Then freeze the generation architecture and move to real-world soak testing.

The next important data point is no longer another code redesign.

It is:

> **Can this build generate tens of thousands of new chunks for hours without reproducing the previous `GCTaskThread / jvm.dll / EXCEPTION_ACCESS_VIOLATION` crash?**

That result should determine the next engineering step.
