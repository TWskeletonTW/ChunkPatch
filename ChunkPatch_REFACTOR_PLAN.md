# ChunkPatch 生成架構重構與 JVM 崩潰調查報告

> 專案：ChunkPatch  
> 基準版本：`0.1.12`  
> Minecraft：`1.19.4`  
> Fabric Loader：`0.19.3`  
> Fabric API：`0.87.2+1.19.4`  
> 文件目的：完整記錄目前 JVM 原生崩潰的證據、可能根因、現行架構問題，以及建議的生成、掃描、執行緒、持久化與測試重構方案。

---

## 目錄

1. [結論摘要](#1-結論摘要)
2. [調查範圍與證據](#2-調查範圍與證據)
3. [四次 JVM 崩潰的共同特徵](#3-四次-jvm-崩潰的共同特徵)
4. [崩潰原因判讀](#4-崩潰原因判讀)
5. [為什麼 ChunkPatch 很可能是觸發器](#5-為什麼-chunkpatch-很可能是觸發器)
6. [目前煞車機制為什麼只能降低機率](#6-目前煞車機制為什麼只能降低機率)
7. [重構總目標](#7-重構總目標)
8. [新的生成架構](#8-新的生成架構)
9. [Minecraft 1.19.4 API 的重要陷阱](#9-minecraft-1194-api-的重要陷阱)
10. [建議的狀態機](#10-建議的狀態機)
11. [單一 In-Flight 工作模型](#11-單一-in-flight-工作模型)
12. [暫停、停止與取消語意](#12-暫停停止與取消語意)
13. [負載與記憶體安全閘門](#13-負載與記憶體安全閘門)
14. [移除舊煞車與速度設定](#14-移除舊煞車與速度設定)
15. [進度與 Crash Recovery 重構](#15-進度與-crash-recovery-重構)
16. [RegionScanner 一致性問題](#16-regionscanner-一致性問題)
17. [Preview 執行緒安全問題](#17-preview-執行緒安全問題)
18. [例外處理重構](#18-例外處理重構)
19. [儲存與磁碟策略](#19-儲存與磁碟策略)
20. [大量選取範圍策略](#20-大量選取範圍策略)
21. [診斷與 Telemetry](#21-診斷與-telemetry)
22. [外部環境與 JVM 建議](#22-外部環境與-jvm-建議)
23. [測試計畫](#23-測試計畫)
24. [驗收標準](#24-驗收標準)
25. [建議修改檔案清單](#25-建議修改檔案清單)
26. [建議實作順序](#26-建議實作順序)
27. [禁止採用的錯誤修法](#27-禁止採用的錯誤修法)
28. [版本與文件整理](#28-版本與文件整理)
29. [最終風險判斷](#29-最終風險判斷)
30. [給 Coding Agent 的執行摘要](#30-給-coding-agent-的執行摘要)

---

# 1. 結論摘要

目前四份 `hs_err_pid*.log` 顯示的都不是一般 Java exception，也不是 Fabric/Mixin 常見的 crash，而是 **HotSpot JVM 原生層的 `EXCEPTION_ACCESS_VIOLATION`**。

共同模式是：

```text
Minecraft / Fabric
    ↓
ChunkPatch 持續要求生成到 ChunkStatus.FEATURES
    ↓
Minecraft worldgen scheduler 展開大量鄰近 chunk dependency
    ↓
大量短生命週期物件、ProtoChunk、WorldGenRegion、Heightmap、biome / feature generation 工作
    ↓
高 allocation throughput + G1 GC 高負載
    ↓
GC worker thread 內 jvm.dll 發生 EXCEPTION_ACCESS_VIOLATION
```

目前能夠高度確定的是：

- 四次 crash 都死在 JVM 的 `GCTaskThread`。
- 前三次使用 Temurin `17.0.8+7`，全部死在相同 `jvm.dll+0x31a47c`。
- 第四次換成 Temurin `17.0.20+8` 後仍然是 G1 GC worker 的原生 access violation，只是 JVM build 改變後 offset 變成 `jvm.dll+0x31bb1d`。
- Crash 當下並沒有證據顯示 Java heap 已耗盡；不是典型 `OutOfMemoryError`。
- ChunkPatch 原始碼中沒有找到 `Unsafe`、JNI、JNA、`MemoryUtil.memFree`、manual direct-buffer free、`System.loadLibrary` 等能直接任意寫 native memory 的程式。
- ChunkPatch 的主生成路徑目前在 integrated server 的 `END_SERVER_TICK` 內同步呼叫 `getChunk(..., FEATURES, true)`，這會阻塞主執行緒直到 Minecraft 的 chunk future 完成。
- `FEATURES` 在 Minecraft 1.19.4 的 dependency range 是 `8`。一次目標 chunk 的 generation scheduler 會建立以目標為中心的 `17 × 17 = 289` 個 dependency position 工作集合；這 **不代表 289 個 chunk 全都生成到 FEATURES**，但代表一次看似單一的目標請求可以展開成非常大的 dependency fan-out。
- 目前 `1～8 chunks/tick` 的設計讓多個大型 worldgen burst 有機會在很短時間內連續出現。
- `0.1.11` 與 `0.1.12` 的 lag brake / slow-chunk cooldown 是正確方向的緊急 mitigation，但兩者都發生在同步 `getChunk()` 已經開始或已經結束之後，因此不能真正限制一次 worldgen burst 本身。

因此本文件建議：

> **將生成器從「每 tick 同步產生 N 個 chunk」重構成「最多一個 target chunk in-flight、真正非阻塞、future 驅動的 backpressure scheduler」。**

重構完成後：

- 刪除 `chunksPerTick`。
- 刪除 `LAG_BACKOFF_THRESHOLD_NANOS`。
- 刪除 `SLOW_CHUNK_NANOS`。
- 刪除 `SLOW_CHUNK_COOLDOWN_TICKS`。
- 刪除同步 `getChunk(FEATURES, true)` 路徑。
- 不再以「爆完之後休息」控制負載。
- 改成「只有前一個 target 真正完成後，才允許提交下一個 target」。
- 保留提交前的輕量安全閘門，例如平均 MSPT、heap pressure、磁碟空間與 minimum interval。

**重要：** 這個重構可以大幅降低 ChunkPatch 對 JVM/GC 的壓力，但不能聲稱 100% 證明或修復 JVM native crash 的最底層原因。若重構後在乾淨環境仍發生同類 GCTaskThread access violation，下一步應轉向 RAM/XMP、CPU 穩定性、native overlay/hook、顯卡/防毒注入 DLL 等環境因素。

---

# 2. 調查範圍與證據

本報告依據：

## 2.1 ChunkPatch 原始碼

主要檔案：

```text
src/main/java/tw/skeleton/chunkpatch/ChunkFillController.java
src/main/java/tw/skeleton/chunkpatch/ChunkScanResult.java
src/main/java/tw/skeleton/chunkpatch/RegionScanner.java
src/main/java/tw/skeleton/chunkpatch/RegionScanCache.java
src/main/java/tw/skeleton/chunkpatch/ProgressStore.java
src/main/java/tw/skeleton/chunkpatch/ChunkPatchMod.java
src/client/java/tw/skeleton/chunkpatch/client/ChunkFillScreen.java
CHANGELOG.md
README.md
```

基準版本：

```properties
minecraft_version=1.19.4
loader_version=0.19.3
mod_version=0.1.12
fabric_api_version=0.87.2+1.19.4
```

## 2.2 JVM Fatal Error Logs

```text
hs_err_pid36284.log
hs_err_pid72484.log
hs_err_pid77464.log
hs_err_pid89176.log
```

## 2.3 專案 Loom cache 內的 Minecraft 1.19.4 source

調查了：

```text
net.minecraft.server.level.ServerChunkCache
net.minecraft.server.level.ChunkMap
net.minecraft.server.level.TicketType
net.minecraft.world.level.chunk.ChunkStatus
net.minecraft.server.MinecraftServer
```

這些 source 是目前專案實際 mapping / Minecraft 版本所使用的 1.19.4 source，因此比用其他 Minecraft 版本推測 API 行為可靠。

---

# 3. 四次 JVM 崩潰的共同特徵

## 3.1 Crash 對照表

| Log | Java | Xmx | 遊戲執行時間 | Crash thread | Problematic frame | Crash 時 heap used |
|---|---:|---:|---:|---|---|---:|
| `hs_err_pid36284.log` | Temurin 17.0.8+7 | 15 GB | 4m18s | `GC Thread#12` | `jvm.dll+0x31a47c` | 約 0.70 GiB |
| `hs_err_pid72484.log` | Temurin 17.0.8+7 | 25,440 MB | 32m05s | `GC Thread#8` | `jvm.dll+0x31a47c` | 約 5.77 GiB |
| `hs_err_pid77464.log` | Temurin 17.0.8+7 | 31,776 MB | 2h44m48s | `GC Thread#2` | `jvm.dll+0x31a47c` | 約 8.06 GiB |
| `hs_err_pid89176.log` | Temurin 17.0.20+8 | 25,440 MB | 12m55s | `GC Thread#3` | `jvm.dll+0x31bb1d` | 約 7.78 GiB |

共同 fatal error：

```text
EXCEPTION_ACCESS_VIOLATION (0xc0000005)
```

前三份的非法讀取地址包含：

```text
0xffffffffffffffff
```

第四份則有：

```text
0x00000000000000c0
RCX=0x0
```

這代表 JVM native code 嘗試讀取無效地址。

## 3.2 不是一般 Java crash

一般 Fabric / mod crash 常見：

```text
java.lang.NullPointerException
java.lang.IllegalStateException
MixinApplyError
NoSuchMethodError
Caused by: ...
```

目前的四份 log 則是：

```text
A fatal error has been detected by the Java Runtime Environment
EXCEPTION_ACCESS_VIOLATION
Problematic frame: jvm.dll
Current thread: GCTaskThread
```

這一類 crash 已經越過 Java exception handler，JVM process 直接終止。

## 3.3 不是典型 Java heap OOM

四份 log 沒有發現典型：

```text
java.lang.OutOfMemoryError: Java heap space
Out of Memory Error
GC overhead limit exceeded
```

而且 crash 時實際使用量離 `-Xmx` 還有很大距離。

第一份：

```text
Heap Max Capacity: 15G
Crash 時 committed total：約 2.48 GiB
Crash 時 used：約 0.70 GiB
```

第三份即使配置：

```text
-Xmx31776m
```

Crash 時 used 仍約：

```text
8.06 GiB
```

所以：

> **「再加更多 RAM 給 Minecraft」不是合理的根本修法。**

## 3.4 但 GC allocation 壓力非常明顯

GC history 顯示 used heap 會大量上下波動。

例如四份 log 中記錄到的 GC history used 範圍大約是：

```text
15G Xmx  ：0.68 ～ 2.79 GiB
25G Xmx  ：3.65 ～ 11.15 GiB
31G Xmx  ：6.26 ～ 23.44 GiB
25G Xmx新：4.03 ～ 14.43 GiB
```

這種 pattern 比較符合：

```text
短時間大量配置物件
    ↓
heap 快速膨脹
    ↓
G1 回收
    ↓
heap 大幅下降
    ↓
下一輪再大量配置
```

它本身不是 JVM crash 的直接證明，但和大型 world generation workload 非常一致。

---

# 4. 崩潰原因判讀

必須把「已證明」和「推論」分開。

## 4.1 已確認事實

### A. JVM native 層崩潰

四次皆為：

```text
EXCEPTION_ACCESS_VIOLATION
jvm.dll
GCTaskThread
```

### B. Java 更新沒有消除 crash 類型

```text
17.0.8+7 → 17.0.20+8
```

仍然發生 G1 GC worker access violation。

所以不能單純歸因：

```text
「因為 Java 17.0.8 太舊」
```

### C. ChunkPatch 沒有明顯直接 native-memory 操作

在 project source 中未找到：

```text
Unsafe
JNA
JNI native methods
System.loadLibrary
MemoryUtil.memAlloc
MemoryUtil.memFree
allocateDirect
DirectBuffer manual free
MappedByteBuffer manual unmap
```

唯一明確擁有 native state 的自建物件是：

```java
Inflater inflater = new Inflater();
```

而目前已有：

```java
finally {
    inflater.end();
}
```

而且是一個 region 重用一個 inflater，這反而比每個 chunk 建立一個 inflater 更合理。

## 4.2 高可信度推論

ChunkPatch 的同步 pregeneration 是 **非常可能的 crash 觸發器 / 壓力放大器**。

原因是：

1. 在 server tick 同步要求 FEATURES。
2. FEATURES dependency range 非常大。
3. 一次 target request 可能展開大量 worldgen dependency。
4. 原版 worldgen 會配置大量短生命週期 Java objects。
5. `1～8 chunks/tick` 允許大型 burst 密集出現。
6. crash thread 全部位於 G1 GC。
7. heap history 呈現大型 allocation / collection 波動。
8. 0.1.11、0.1.12 加入 cooldown 後的 changelog 本身也記錄了現場觀察到長時間 lag 與 GC 壓力。

## 4.3 尚未能證明的部分

目前不能下結論：

```text
ChunkPatch 的某一行 Java code 直接把 JVM pointer 寫壞了
```

因為沒有直接 native write primitive。

也不能排除：

- RAM instability。
- XMP / memory overclock。
- CPU undervolt / unstable boost。
- NVIDIA native driver issue。
- Discord overlay injection。
- Overwolf injection。
- Surfshark / antivirus AMSI injection。
- 其他 mod 的 JNI / JNA / LWJGL native interaction。
- HotSpot / G1 特定 corner-case bug。

比較精確的描述是：

> **ChunkPatch 很可能建立了足以重現 native JVM / hardware / injected-library 問題的極端 GC 與 worldgen workload。**

---

# 5. 為什麼 ChunkPatch 很可能是觸發器

# 5.1 目前生成入口

`ChunkPatchMod.java`：

```java
ServerTickEvents.END_SERVER_TICK.register(CONTROLLER::tick);
```

也就是所有生成控制都從 integrated server 主執行緒的 tick callback 開始。

目前 `ChunkFillController.tick()`：

```java
while (generatedThisTick < chunksPerTick && coordinateChecks++ < MAX_COORDINATE_CHECKS_PER_TICK) {
    ...
    ChunkAccess chunk = level.getChunkSource().getChunk(
        x,
        z,
        ChunkStatus.FEATURES,
        true
    );
    ...
}
```

對應目前 source 約：

```text
ChunkFillController.java:287-382
核心同步 getChunk：327
```

## 5.2 `getChunk()` 在 server thread 會 blocking

Minecraft 1.19.4 的 `ServerChunkCache.getChunk()`：

```java
CompletableFuture<Either<ChunkAccess, ChunkHolder.ChunkLoadingFailure>> future =
    this.getChunkFutureMainThread(i, j, chunkStatus, bl);

this.mainThreadProcessor.managedBlock(future::isDone);

ChunkAccess chunk = future.join() ...
```

所以 ChunkPatch 現在實際做的是：

```text
END_SERVER_TICK
    ↓
getChunk(FEATURES, true)
    ↓
建立 / 排程 future
    ↓
server main thread managedBlock 等 future
    ↓
worldgen dependency 完成
    ↓
return
```

這就是為什麼單次 `getChunk()` 可以直接讓一個 tick 卡數百毫秒甚至數秒。

## 5.3 FEATURES 不是「只做這一格 chunk」

Minecraft 1.19.4：

```java
public static final ChunkStatus FEATURES = register(
    "features",
    LIQUID_CARVERS,
    8,
    ...
);
```

其 range：

```text
8
```

ChunkMap 在 generation 時：

```java
getChunkRangeFuture(
    chunkPos,
    chunkStatus.getRange(),
    i -> getDependencyStatus(chunkStatus, i)
)
```

而 `getChunkRangeFuture()`：

```java
for (int x = -range; x <= range; x++) {
    for (int z = -range; z <= range; z++) {
        ...
    }
}
```

FEATURES range = 8，所以空間是：

```text
(-8 ... +8) × (-8 ... +8)
17 × 17
289 positions
```

再次強調：

> **不是說每一次 ChunkPatch target 都會額外建立 289 個 FEATURES chunk。**

Minecraft 會根據距離使用不同的 dependency status，也會重用已存在 / 已排程的 chunk。

但它代表：

> **一個 target FEATURES request 天生就是一個大 dependency graph，而不是一個常數成本的小操作。**

## 5.4 `chunksPerTick = 8` 的問題

目前：

```java
this.chunksPerTick = Math.max(1, Math.min(8, requestedRate));
```

UI：

```java
int rate = Math.max(1, Math.min(8, Integer.parseInt(rateField.getValue())));
```

Command：

```text
/chunkfill start ... [每Tick區塊數]
```

問題是：

```text
1 target ≠ 1 小型 operation
```

因此：

```text
8 targets/tick
```

並不是「8 個很小的工作」，而可能是：

```text
8 個各自會展開大型 dependency graph 的 target request
```

即使相鄰 chunk 能重用部分 dependency，這個控制單位仍然不合理。

---

# 6. 目前煞車機制為什麼只能降低機率

## 6.1 0.1.11 Lag Backoff

目前：

```java
private static final long TICK_BUDGET_NANOS = 50_000_000L;
private static final long LAG_BACKOFF_THRESHOLD_NANOS = TICK_BUDGET_NANOS * 4L;
```

如果上一個 tick > 200ms：

```java
if (sinceLastTick > LAG_BACKOFF_THRESHOLD_NANOS) {
    return;
}
```

問題：

```text
Tick N
    ↓
getChunk() 卡 2 秒
    ↓
Tick N 終於結束
    ↓
Tick N+1 發現上一 tick 很慢
    ↓
跳過
```

它只能處理：

```text
「上一個 burst 已經發生」
```

無法限制：

```text
「這一次即將開始的 getChunk 到底會展開多大」
```

## 6.2 0.1.12 Slow Chunk Cooldown

目前：

```java
private static final long SLOW_CHUNK_NANOS = 150_000_000L;
private static final int SLOW_CHUNK_COOLDOWN_TICKS = 40;
```

流程：

```text
開始 getChunk
    ↓
同步 blocking
    ↓
花 2 秒
    ↓
return
    ↓
現在才知道 >150ms
    ↓
cooldown 40 tick
```

問題仍然相同：

> **最昂貴的 allocation burst 已經完成了才開始煞車。**

## 6.3 重構後是否可刪？

可以，而且建議刪除。

新的核心 backpressure 是：

```text
MAX_IN_FLIGHT_TARGETS = 1
```

只要前一個 FEATURES target 還沒有真正完成：

```text
永遠不提交下一個 target
```

因此舊版：

```text
LAG_BACKOFF_THRESHOLD_NANOS
SLOW_CHUNK_NANOS
SLOW_CHUNK_COOLDOWN_TICKS
cooldownTicksRemaining
lastTickMarkNanos
```

可以全部移除。

注意：

> 刪除舊煞車不代表完全移除所有 safety gate。

新的 safety gate 應該放在 **提交前**，而不是 **burst 發生後**。

---

# 7. 重構總目標

P0 必須達成：

1. Server tick 內禁止同步等待 FEATURES generation。
2. 同一時間最多只有 `1` 個 ChunkPatch target request in-flight。
3. 不在 ChunkPatch code 中對 worldgen future 呼叫 `.join()` / `.get()`。
4. Worldgen future 完成後，只能透過 `server.execute(...)` 回 server thread 修改 controller state。
5. Pause / stop 不再嘗試中斷 Minecraft 已經啟動的 worldgen graph。
6. 不再提供 `chunksPerTick`。
7. 移除舊 lag brake / slow cooldown。

P1：

8. 改善 crash recovery。
9. 修正 Preview mutable arrays 的跨執行緒 data race。
10. 修正 RegionScanner 掃描期間 region file 被 Minecraft 改寫時的一致性問題。
11. 移除 `catch (Throwable)`。
12. 增加 generation telemetry。

P2：

13. 改善超大型 selection 的 batch / UX。
14. 改善 scan cache 驗證。
15. 改善 exact preview 每 frame 大量 hash lookup。
16. 修正 README 與實際 0.1.12 行為不一致。

---

# 8. 新的生成架構

建議改成：

```text
Server Tick
    ↓
RUNNING ?
    ↓
inFlight == null ?
    ↓
安全閘門允許提交？
    ↓
找下一個真正需要生成的 target
    ↓
提交「非阻塞 FEATURES future」
    ↓
立刻 return tick

Minecraft scheduler / worldgen threads
    ↓
處理 dependency graph
    ↓
future complete
    ↓
server.execute(...)
    ↓
驗證 session / dimension / target
    ↓
markMapReady
completed++
save checkpoint if needed
inFlight = null
    ↓
下一個 server tick 才可能提交下一個 target
```

核心不變量：

```text
inFlightTargetCount <= 1
```

這個限制應該寫成架構 invariant，而不是 UI option。

---

# 9. Minecraft 1.19.4 API 的重要陷阱

這是整個重構最容易寫錯的地方。

## 9.1 錯誤修法 A：直接在 tick 把 getChunk 換成 getChunkFuture

看起來像：

```java
level.getChunkSource().getChunkFuture(
    x,
    z,
    ChunkStatus.FEATURES,
    true
);
```

但如果它從 server main thread 呼叫，Minecraft 1.19.4 內部：

```java
if (Thread.currentThread() == this.mainThread) {
    future = getChunkFutureMainThread(...);
    this.mainThreadProcessor.managedBlock(future::isDone);
}
```

也就是：

> **依然 blocking。**

所以這不是修復。

## 9.2 錯誤修法 B：把同步 getChunk 包到 supplyAsync

例如：

```java
CompletableFuture.supplyAsync(
    () -> source.getChunk(x, z, ChunkStatus.FEATURES, true)
);
```

也不建議。

Minecraft 的 off-main `getChunk()` 會：

```java
CompletableFuture.supplyAsync(
    () -> this.getChunk(...),
    mainThreadProcessor
).join();
```

結果仍會把真正同步 `getChunk()` 排回 main thread，然後 main thread 內再次 `managedBlock()`。

所以：

> **「把 blocking API 搬到另一個 executor」不等於 non-blocking。**

## 9.3 正確方向：從 non-main thread 呼叫 `getChunkFuture()`

Minecraft 1.19.4 的 `getChunkFuture()` 有明確 non-main branch：

```java
if (onMainThread) {
    future = getChunkFutureMainThread(...);
    mainThreadProcessor.managedBlock(future::isDone);
} else {
    future = CompletableFuture
        .supplyAsync(
            () -> getChunkFutureMainThread(...),
            mainThreadProcessor
        )
        .thenCompose(Function.identity());
}
```

因此最簡單的策略是：

1. 在 server thread 捕獲 `ServerChunkCache` reference、target coordinate 與 session token。
2. 把「呼叫 public `getChunkFuture()`」這個極小工作丟給專用 coordinator executor。
3. 因為呼叫發生在 non-main thread，會走 Minecraft 已有的 non-main branch。
4. Minecraft 自己把必要 setup 排回 main thread。
5. 我們拿到 future 後完全不 join。
6. completion 再 `server.execute(...)` 回 server thread。

概念程式碼：

```java
private CompletableFuture<ChunkAccess> requestFeaturesAsync(
    ServerChunkCache source,
    int x,
    int z
) {
    return CompletableFuture
        .supplyAsync(
            () -> source.getChunkFuture(
                x,
                z,
                ChunkStatus.FEATURES,
                true
            ),
            GENERATION_COORDINATOR
        )
        .thenCompose(Function.identity())
        .thenApply(result -> result.map(
            Function.identity(),
            failure -> {
                throw new CompletionException(
                    new IllegalStateException(
                        "Chunk generation failed for " + x + "," + z + ": " + failure
                    )
                );
            }
        ));
}
```

實作時依目前 mappings 補正 `Either` / `ChunkHolder.ChunkLoadingFailure` generic type。

### 非常重要

Coordinator thread **只允許**做這種 thread-aware API request。

不要在 coordinator thread：

```text
修改 ServerLevel
修改 ChunkScanResult
修改 controller counters
save world
讀寫 player/entity/block state
advance shared cursor
修改 UI snapshot
```

所有 ChunkPatch mutable state 仍統一回到 server thread。

---

# 10. 建議的狀態機

目前：

```text
IDLE
SCANNING
READY
RUNNING
PAUSED
COMPLETE
ERROR
```

建議加入：

```text
PAUSING
STOPPING
```

或保留 enum 不變，但以額外 flag 表示 draining。

推薦清楚的版本：

```text
IDLE
  ↓ scan
SCANNING
  ↓
READY
  ↓ start
RUNNING
  ├─ pause, no in-flight → PAUSED
  ├─ pause, in-flight    → PAUSING → PAUSED
  ├─ stop, no in-flight  → READY
  ├─ stop, in-flight     → STOPPING → READY
  ├─ done                → COMPLETE
  └─ fatal task error    → ERROR
```

## 10.1 RUNNING

代表：

```text
允許提交新的 target
```

不代表一定有 future。

## 10.2 PAUSING

代表：

```text
不再提交新 target
目前 target 若存在，讓它安全完成
完成後 save(true)
進入 PAUSED
```

## 10.3 STOPPING

代表：

```text
不再提交新 target
目前 target 若存在，讓它安全完成
完成後 save(true)
清除 active generation session
進入 READY
```

---

# 11. 單一 In-Flight 工作模型

建議欄位：

```java
private static final int MAX_IN_FLIGHT = 1;

private InFlightGeneration inFlight;
private long generationSessionId;
private long nextEligibleTick;
private long completedSinceCheckpoint;
```

例如：

```java
private record InFlightGeneration(
    long sessionId,
    int x,
    int z,
    boolean wasPartial,
    long submittedNanos
) {}
```

## 11.1 tick() 新責任

新的 `tick()` 不做 world generation。

只做：

```text
1. 檢查 state
2. 處理 PAUSING / STOPPING drain
3. 檢查 inFlight
4. 檢查 safety gate
5. 找下一個 target
6. submit request
7. return
```

概念：

```java
public synchronized void tick(MinecraftServer server) {
    if (state != State.RUNNING) return;
    if (scan == null || selected == null) return;
    if (inFlight != null) return;
    if (server.getTickCount() < nextEligibleTick) return;

    ServerLevel level = findLevel(server);
    if (level == null) {
        pauseBecauseDimensionMissing();
        return;
    }

    if (!safetyPolicyAllowsSubmission(server)) {
        return;
    }

    Target target = findNextTargetWithinBudget();
    if (target == null) {
        finish(level);
        return;
    }

    submitTarget(server, level, target);
}
```

## 11.2 `findNextTargetWithinBudget()`

目前有：

```java
MAX_COORDINATE_CHECKS_PER_TICK = 20_000
```

可以保留 cursor 模型，但建議不要讓「只是在 skip 已存在 chunk」也把單 tick 吃太久。

可以改成：

```text
最多 4096 次 lookup
或
最多使用 0.5～1ms cursor scan budget
```

例如：

```java
long deadline = System.nanoTime() + 1_000_000L;
int checks = 0;

while (checks++ < 4096 && System.nanoTime() < deadline) {
    ...
}
```

如果這 tick 沒找到 target：

```text
下一 tick 繼續
```

不必一次掃 20,000 格。

## 11.3 Future completion

future completion thread 不可直接改 controller。

```java
future.whenComplete((chunk, error) ->
    server.execute(() -> completeTarget(sessionId, x, z, chunk, error))
);
```

`completeTarget()` 才能：

```text
確認 sessionId
確認 inFlight coordinate
確認 dimension
確認 scan instance
markMapReady
partialInside--
completed++
checkpoint
publish preview
inFlight = null
nextEligibleTick = server.getTickCount() + delay
```

---

# 12. 暫停、停止與取消語意

Minecraft chunk generation future 已經提交後，不應假設：

```java
future.cancel(true)
```

就可以安全停止底層 worldgen。

原因：

- dependency futures 可能已建立。
- chunk ticket 可能已加入。
- worldgen mailbox 中可能已有工作。
- cancel wrapper 不一定會取消底層全部任務。
- 強制取消可能讓狀態更難推理。

因此採用 **drain** 模型。

## 12.1 Pause

如果 `inFlight == null`：

```text
RUNNING → PAUSED
save(true)
```

如果 `inFlight != null`：

```text
RUNNING → PAUSING
停止新 submission
等待目前 target completion
save(true)
PAUSED
```

UI message：

```text
正在暫停：等待目前區塊生成完成…
```

## 12.2 Stop

如果有 in-flight：

```text
RUNNING → STOPPING
等待目前 target 完成
save(true)
清除 session / progress intent
READY
```

## 12.3 Minecraft 關閉世界

建議監聽 lifecycle：

```text
SERVER_STOPPING / SERVER_STOPPED
```

行為：

```text
禁止新 submission
generationSessionId++
標記 current session stale
scanner cancellation token invalidated
不再讓 stale callback 修改新 server state
```

Generation future 可以自然結束，但 stale callback 必須安全忽略。

---

# 13. 負載與記憶體安全閘門

新的 safety mechanism 應該是：

```text
「提交下一個 target 前判斷」
```

而不是：

```text
「target 已經炸完後才 cooldown」
```

## 13.1 In-flight gate

硬限制：

```text
MAX_IN_FLIGHT = 1
```

永遠不要做成 UI 可調。

至少第一個穩定版本不要允許：

```text
2 / 4 / 8 concurrent targets
```

## 13.2 Minimum interval

即使 future 完成，也可以至少等下一個 tick：

```text
nextEligibleTick = currentTick + 1
```

這不是主要安全來源，只是避免 completion 與下一次 request 在同一個 tick 內形成過度緊密連鎖。

如果之後要有模式：

```text
Safe      : +5 ticks
Balanced  : +1 tick
Aggressive: +0 / +1 tick
```

但三個模式仍必須：

```text
MAX_IN_FLIGHT = 1
```

初版建議先只有一種安全模式。

## 13.3 MSPT gate

Minecraft 1.19.4 有：

```java
server.getAverageTickTime()
```

可在提交前檢查。

例如初始 policy：

```text
average MSPT > 45ms → 暫不提交新 target
average MSPT < 35ms → 恢復允許
```

建議使用 hysteresis，避免：

```text
44.9 / 45.1 / 44.9 / 45.1
```

每 tick 反覆切換。

數值不是 JVM crash 的神奇安全線，只是初始調校值，需要實測。

## 13.4 Heap pressure gate

可以透過：

```java
Runtime runtime = Runtime.getRuntime();
long used = runtime.totalMemory() - runtime.freeMemory();
long max = runtime.maxMemory();
```

建立簡單 hysteresis：

```text
used / max >= 0.85 → 不提交新 target
used / max <= 0.75 → 恢復
```

注意：

- 這不是 native memory detector。
- 這不能防止單一 FEATURES dependency graph 本身的 allocation peak。
- 它只是防止 heap 已經非常緊時又主動加新 target。

不要使用：

```java
System.gc();
```

作為排程策略。

## 13.5 Disk gate

目前：

```text
MIN_FREE_BYTES = 2 GiB
```

可以保留。

建議：

```text
每 32～64 個 successful target 檢查一次
```

如果 < 2 GiB：

```text
進入 PAUSING / PAUSED
```

而不是在 future callback 內繼續提交。

---

# 14. 移除舊煞車與速度設定

重構完成後刪除：

```java
TICK_BUDGET_NANOS
LAG_BACKOFF_THRESHOLD_NANOS
SLOW_CHUNK_NANOS
SLOW_CHUNK_COOLDOWN_TICKS
lastTickMarkNanos
cooldownTicksRemaining
chunksPerTick
```

`tick()` 中刪除：

```java
sinceLastTick
if (cooldownTicksRemaining > 0)
if (sinceLastTick > LAG_BACKOFF_THRESHOLD_NANOS)
chunkStartNanos
chunkNanos
if (chunkNanos > SLOW_CHUNK_NANOS)
```

UI 刪除：

```java
rateField
```

Command 從：

```text
/chunkfill start <minX> <maxX> <minZ> <maxZ> [rate]
```

改成：

```text
/chunkfill start <minX> <maxX> <minZ> <maxZ>
```

`prepare(...)` 從：

```java
prepare(ServerLevel level, ChunkBounds bounds, int requestedRate)
```

改成：

```java
prepare(ServerLevel level, ChunkBounds bounds)
```

如果為了 command backward compatibility 暫時保留 rate argument：

- parse 它。
- 顯示 deprecated warning。
- 完全忽略其值。
- 下一個版本再移除。

---

# 15. 進度與 Crash Recovery 重構

目前 `ProgressStore.ProgressData`：

```java
public String dimensionPath;
public String dimensionId;
public int minX;
public int maxX;
public int minZ;
public int maxZ;
public int nextX;
public int nextZ;
public long planned;
public long completed;
public int chunksPerTick;
```

但 `restoreProgressIfCompatible()` 實際上已經採用更安全的策略：

```text
重新 scan disk
以 scan 為 source of truth
cursor 從 selection origin 重新開始
跳過已經存在的 chunk
completed 重設 0
```

也就是目前：

```text
nextX
nextZ
completed
planned
```

基本不應被當作 crash recovery 的權威資料。

## 15.1 建議的新 ProgressData

把 progress file 改成「resume intent」，而不是「transaction journal」。

例如：

```java
public final class ProgressData {
    public int formatVersion = 2;
    public String dimensionPath;
    public String dimensionId;
    public int minX;
    public int maxX;
    public int minZ;
    public int maxZ;
    public String mode; // optional future use
}
```

啟動後：

```text
scan disk
    ↓
重新計算 missing / renderable / partial / corrupt
    ↓
從 selection origin 搜尋下一個 missing
```

這樣即使：

```text
Minecraft crash
Windows crash
JVM native crash
future 完成但 ProgressStore 尚未寫入
world save 比 progress 慢
```

也不會因為 cursor journal 錯誤永久跳過 chunk。

## 15.2 In-flight cursor

Async 架構中不要：

```text
提交 future 前就把 committed cursor 永久 advance
```

更安全：

```text
candidate = cursor
inFlight = candidate

future success
    ↓
markMapReady
advance committed cursor
```

但因為 crash recovery 最終仍會 rescan disk，所以 cursor 只需要 runtime correctness，不需要成為持久化 source of truth。

---

# 16. RegionScanner 一致性問題

目前 scan 流程：

```java
server.saveEverything(true, true, false);
```

然後立刻：

```java
CompletableFuture.supplyAsync(
    () -> RegionScanner.scan(...),
    SCAN_EXECUTOR
)
```

Minecraft 在 scan 執行期間仍然繼續跑。

所以可能發生：

```text
Scanner 讀 region header
    ↓
Minecraft autosave / world save 修改同一 .mca
    ↓
Scanner 繼續讀 chunk payload
```

目前 `RegionScanner` 有：

```java
long size = Files.size(regionFile);
long modifiedMillis = Files.getLastModifiedTime(regionFile).toMillis();

RegionState scanned = scanRegion(...);

long finalSize = Files.size(regionFile);
long finalModifiedMillis = ...;

if (same) {
    cache.put(...);
}
```

這只保護：

```text
是否寫入 cache
```

但即使 file 在 scan 中變過：

```java
applyStates(..., states, ...)
```

仍然會把該次不一致 scan 結果套進目前結果。

## 16.1 建議：Stable-read retry

每個 `.mca`：

```text
attempt 1
    attrsBefore
    scanRegion
    attrsAfter
    same ? accept : retry

attempt 2
    ...

attempt 3
    ...
```

如果連續 2～3 次都變更：

```text
不要標成 CORRUPT
不要 cache
把該 region 記錄為 CHANGED_DURING_SCAN / unstable
提示稍後重新掃描
```

概念：

```java
for (int attempt = 0; attempt < MAX_STABLE_READ_ATTEMPTS; attempt++) {
    FileStamp before = stamp(regionFile);
    RegionState scanned = scanRegion(...);
    FileStamp after = stamp(regionFile);

    if (before.equals(after)) {
        accept(scanned);
        break;
    }
}
```

## 16.2 更強 cache 驗證（P2）

目前 cache key：

```text
file size
lastModifiedTime
```

通常夠用。

若要更穩：

```text
size
mtime
location table / timestamp table checksum
```

例如只 hash region header 8 KiB，而不是整個 `.mca`。

這可以避免極少數：

```text
size 一樣
mtime granularity 撞到
內容其實變更
```

的情況。

---

# 17. Preview 執行緒安全問題

`ChunkScanResult`：

```java
private volatile PreviewData preview;
```

但：

```java
public record PreviewData(
    int width,
    int height,
    int[] savedCounts,
    int[] partialCounts,
    int[] possibleCounts,
    boolean[] invalid
) {}
```

Java record 並不會讓 array immutable。

目前 server thread：

```java
PreviewData currentPreview = preview;
currentPreview.savedCounts()[index]++;
currentPreview.partialCounts()[index]--;
```

client render thread：

```java
preview.savedCounts()[index]
preview.partialCounts()[index]
preview.invalid()[index]
```

也就是：

```text
volatile 只保護 preview reference
不保護 array element 的同步
```

這是確實存在的 Java data race。

它不像是目前 JVM G1 native crash 的主嫌，但應該修。

## 17.1 推薦設計

分成：

```text
MutablePreviewState
    ↓ server thread only
publish snapshot
    ↓
Immutable PreviewSnapshot
    ↓ client render read only
```

例如：

```java
final class MutablePreviewState {
    int[] saved;
    int[] partial;
    int[] possible;
    boolean[] invalid;
}
```

Published snapshot：

```java
public final class PreviewSnapshot {
    private final int[] saved;
    private final int[] partial;
    private final int[] possible;
    private final boolean[] invalid;

    // constructor clones input arrays

    public int savedCount(int index) { ... }
    public int partialCount(int index) { ... }
    public boolean invalid(int index) { ... }
}
```

不要再把 raw array reference 暴露給 UI。

## 17.2 Publish 頻率

Preview size：

```text
180 × 96 = 17,280 cells
```

不必每一 frame clone。

可以：

```text
每 5～10 server ticks publish 一次
或
每完成 8 / 16 個 target publish 一次
```

在 single-in-flight 模型中 target completion 本身不會太密集，因此甚至每次 completion publish 都可能可接受。

---

# 18. 例外處理重構

目前生成：

```java
} catch (Throwable throwable) {
    ...
    save(true);
}
```

不建議捕獲 `Throwable`。

因為它包含：

```text
OutOfMemoryError
StackOverflowError
LinkageError
VirtualMachineError
ThreadDeath
```

如果 JVM 已經處於嚴重資源失效，catch 後再做：

```text
logger formatting
save(true)
JSON write
chunk save
```

可能讓情況更糟。

而目前遇到的 native `EXCEPTION_ACCESS_VIOLATION` 本來就不會被 Java `catch(Throwable)` 安全救回。

## 18.1 建議

同步 controller code：

```java
catch (Exception exception)
```

Async future：

```java
whenComplete((result, error) -> ...)
```

再 unwrap：

```text
CompletionException
ExecutionException
```

如果底層是普通 generation failure：

```text
state = ERROR
停止新 submission
安全 save
記錄 target coordinate
```

不要試圖「吃掉」VM fatal errors。

---

# 19. 儲存與磁碟策略

目前：

```text
SAVE_CHECKPOINT_INTERVAL = 256 successful target chunks
```

到 checkpoint：

```java
level.getChunkSource().save(false);
ProgressStore.save(...);
```

Pause / stop / finish：

```java
save(true);
```

這個方向可以保留。

## 19.1 Async 架構下的 checkpoint

只在 **future 成功完成並回 server thread** 後增加：

```text
completedSinceCheckpoint
```

達到 128 / 256 後：

```text
save(false)
write resume intent
reset counter
```

初版可先保留 256，實測 I/O 後再調。

## 19.2 Pause / Stop

必須先 drain current in-flight target，再：

```text
save(true)
```

避免：

```text
正在生成中途就宣稱已經完整 checkpoint
```

## 19.3 磁碟預警

2 GiB 是 hard stop。

可以另外加入 soft warning，例如：

```text
< 5 GiB 顯示警告
< 2 GiB 停止新 submission
```

但 hard threshold 不是 JVM crash 修復核心。

---

# 20. 大量選取範圍策略

目前：

```java
MAX_SELECTION_AREA = 5_000_000L;
```

5,000,000 chunks 是非常大的工作量。

但：

> **單純把上限改小，不能取代生成架構重構。**

Single-in-flight 做好後，即使 selection 很大，也只是 runtime 很長，而不是同時塞 5M 個 futures。

## 20.1 建議

保留 hard max，但加入 soft warning：

```text
> 100,000：大型工作，建議備份
> 500,000：非常大型，建議分批
> 1,000,000+：要求二次確認
```

實際 threshold 可依測試調整。

## 20.2 Batch session

P2 可以在 UI 上把巨大 selection 邏輯切成：

```text
Session batch
    ↓
完成 N targets
    ↓
checkpoint / save
    ↓
下一批
```

但底層仍維持：

```text
inFlight = 1
```

Batch 是 persistence / UX 單位，不是 concurrency 單位。

---

# 21. 診斷與 Telemetry

目前最大的問題之一是：

```text
發生 hs_err 時不知道 ChunkPatch crash 前最後正在處理哪個 target
```

專案 ZIP 內的 `logs/latest.log` 與 `.log.gz` 主要是 Gradle/JUnit `Test worker` log，不是遊戲 crash 前的 client/server `latest.log`。

所以建議加入低頻率 telemetry。

## 21.1 每個 target 記錄

不要 INFO spam 每一格。

可以：

- DEBUG：每個 submit / complete。
- INFO：每 100 / 500 個完成摘要。
- WARN：單 target wall-clock duration 特別長。

即使 async 後不拿 duration 當 brake，仍可記：

```text
submittedAt
completedAt
wallClockMs
```

例如：

```text
ChunkPatch generation stats:
completed=12000
remaining=84000
inFlight=1
lastTarget=123,-456
lastTargetMs=1820
avgMspt=31.4
heapUsed=6.2GiB
heapMax=10.0GiB
freeDisk=128.4GiB
```

## 21.2 GC telemetry

可以用標準 MXBean：

```text
GarbageCollectorMXBeans
MemoryMXBean
```

低頻率記錄：

```text
collectionCount delta
collectionTime delta
heap used
heap committed
heap max
```

不要每 tick 讀與 log。

## 21.3 Session ID

每次 start：

```text
generationSessionId++
```

log 都帶：

```text
session=17
```

這樣 crash 前最後一筆 log 可以對回 target。

---

# 22. 外部環境與 JVM 建議

即使 ChunkPatch 重構完成，也應保留一套 isolation test。

## 22.1 Java

目前已證明：

```text
Temurin 17.0.8 crash
Temurin 17.0.20 仍 crash
```

所以至少應留在：

```text
Java 17.0.20 或當前穩定的 Java 17 build
```

不要回到 17.0.8。

## 22.2 Xmx

目前測過：

```text
15 GB
25 GB
31 GB
```

都 crash 過。

建議 debug / soak test 使用較合理的：

```text
-Xmx8G
```

大型模組環境可：

```text
-Xmx10G
-Xmx12G
```

不要因為主機有 64 GB RAM 就直接給 Minecraft 25～32 GB。

理由：

- 沒有 OOM 證據。
- 大 heap 不是這個問題的根本修復。
- 它可能讓大量 allocation 在更長時間內堆積後才回收。
- 更合理的 heap 讓 memory behavior 比較容易觀察。

## 22.3 Native injection / overlay

Fatal log 中可看到 process 載入過包括：

```text
Overwolf
Discord hook
Surfshark / AMSI component
NVIDIA native driver
JNA / LWJGL native libraries
```

這些不代表它們一定有 bug。

但 native access violation 的 isolation test 應該測：

```text
A. ChunkPatch + Fabric 必需依賴，關閉 overlay
B. 加回其他 mods
C. 加回 Discord overlay
D. 加回 Overwolf
E. 加回其他 native/injected software
```

## 22.4 RAM / CPU 穩定性

如果重構後仍是：

```text
GCTaskThread
jvm.dll
EXCEPTION_ACCESS_VIOLATION
```

建議：

1. 關閉 XMP / memory overclock 測試。
2. 取消 CPU undervolt / overclock。
3. 跑 MemTest86。
4. 跑 OCCT Memory / CPU stability。
5. 檢查 Windows Event Viewer 的 WHEA 硬體錯誤。
6. 更新 BIOS / chipset / GPU driver。

這是「重構後仍能復現」時的第二階段診斷，不是先用硬體問題否定 ChunkPatch 的 workload 問題。

---

# 23. 測試計畫

# 23.1 Unit Test：Scheduler invariant

最好把 Minecraft API wrapper 抽象出：

```java
interface ChunkGenerationGateway {
    CompletableFuture<ChunkAccess> requestFeatures(int x, int z);
}
```

正式環境：

```text
MinecraftChunkGenerationGateway
```

測試：

```text
FakeChunkGenerationGateway
```

這樣可以測 scheduler，而不必真正跑 worldgen。

必測：

### Test 1：永遠只有一個 in-flight

```text
submit A
A 未完成
tick 100 次
不得 submit B
```

### Test 2：完成才提交下一個

```text
submit A
complete A
下一 tick
submit B
```

### Test 3：Pause while in-flight

```text
submit A
pause
state = PAUSING
不得 submit B
complete A
state = PAUSED
```

### Test 4：Stop while in-flight

```text
submit A
stop
state = STOPPING
complete A
state = READY
不得 submit B
```

### Test 5：Future failure

```text
submit A
future completes exceptionally
state = ERROR
inFlight cleared
不得 submit B
```

### Test 6：Stale callback

```text
session 1 submit A
server/session 被 reset
session 2 start
A completion arrives
不得污染 session 2 counters
```

### Test 7：Partial chunk

```text
partial A
submit
success
partial count -1
mapReady +1
```

### Test 8：Corrupt chunk

```text
不得 submit
```

### Test 9：Existing map-ready chunk

```text
不得 submit
```

## 23.2 Progress recovery test

模擬：

```text
成功生成數個 target
不正常中止
重新 scan
resume intent 載入
```

驗證：

```text
已在 disk 的不重做
missing 的不跳過
corrupt 不覆寫
```

## 23.3 RegionScanner stable-read test

測試時在 scan 中間修改 region file metadata / contents。

期待：

```text
retry
而不是直接 accept unstable state
```

如果一直變動：

```text
skip unstable region
不是把整區誤判 CORRUPT
```

## 23.4 Preview concurrency test

新 snapshot 必須：

```text
publish 後不再被 server thread mutate
```

測：

```text
snapshot A
server updates mutable state
snapshot A 內容保持不變
publish snapshot B
B 才看到更新
```

## 23.5 Integration / Soak Test

建立乾淨 1.19.4 world，測：

### Scenario A：已有大量 chunk

主要測 skip path。

### Scenario B：大量完全未生成 terrain

主要測最昂貴的 dependency fan-out。

### Scenario C：混合 partial / FEATURES / missing

測 resume / classification。

### Scenario D：Pause / Resume 反覆操作

### Scenario E：遊戲直接關閉再重開

### Scenario F：長時間大型 selection

記錄：

```text
average MSPT
heap used
GC collection time
process RAM
生成 throughput
最大 target wall-clock duration
```

成功不是「跑得最快」，而是：

```text
server 保持可互動
沒有大量連續同步 freeze
heap 不呈現失控成長
不出現 JVM native crash
resume 正確
```

---

# 24. 驗收標準

重構版本在 merge 前至少滿足：

## Generation

- [ ] `ChunkFillController.tick()` 不再呼叫同步 `getChunk(FEATURES, true)`。
- [ ] ChunkPatch server tick path 沒有 `.join()` / `.get()` 等待 generation future。
- [ ] `getChunkFuture()` 不直接從 server main thread 作為 blocking call 使用。
- [ ] 同時最多一個 ChunkPatch target in-flight。
- [ ] 前一 target 未完成時，即使 tick 跑幾千次也不會提交下一 target。
- [ ] Future completion 回 server thread 後才修改 scan/counters/UI state。
- [ ] Pause / stop 有清楚 drain semantics。
- [ ] Stale future 不會修改下一個 session。

## Removed legacy throttling

- [ ] `chunksPerTick` 移除。
- [ ] rate UI 移除。
- [ ] rate command argument 移除或 deprecated-ignore。
- [ ] `LAG_BACKOFF_THRESHOLD_NANOS` 移除。
- [ ] `SLOW_CHUNK_NANOS` 移除。
- [ ] `SLOW_CHUNK_COOLDOWN_TICKS` 移除。
- [ ] `cooldownTicksRemaining` 移除。

## Thread safety

- [ ] Client 不再直接讀 server thread 正在修改的 mutable preview arrays。
- [ ] Coordinator thread 不直接修改 Minecraft world/controller mutable state。

## Scanner

- [ ] Region file 在 scan 中途變更時不直接 accept unstable result。
- [ ] Cache 只寫入 stable scan。
- [ ] Unstable region 不被當成真正 corrupt chunk 資料覆蓋。

## Recovery

- [ ] Crash recovery 以 disk rescan 為 source of truth。
- [ ] Progress JSON 不依賴已提交但未完成的 cursor。
- [ ] JVM / game 被強制關閉後可重新掃描並補齊缺口。

## Exception handling

- [ ] Generation path 不再 `catch(Throwable)`。
- [ ] Future error 會記錄 target coordinate / session id。

## Documentation

- [ ] README 不再宣稱使用已於 0.1.9 移除的 Xaero `.xwmc` coverage 判定。
- [ ] README build output version 不再固定寫 `0.1.8`。
- [ ] CHANGELOG 說明 async single-in-flight 重構與移除 rate/brakes。

---

# 25. 建議修改檔案清單

## P0

### `ChunkFillController.java`

主要重構。

刪除：

```text
chunksPerTick
legacy brakes
同步 getChunk loop
```

新增：

```text
inFlight
session id
nextEligibleTick
async request submission
future completion callback
PAUSING / STOPPING drain
safety policy
```

### `ChunkPatchMod.java`

更新 command：

```text
remove / deprecate rate
```

加入 server lifecycle reset hook。

### `ChunkFillScreen.java`

移除：

```text
rateField
```

更新 layout / status。

### `ProgressStore.java`

移除舊 `chunksPerTick` 等非權威進度欄位。

加入 format version。

## P1

### `ChunkScanResult.java`

重構 mutable preview / immutable snapshot。

### `RegionScanner.java`

加入 stable-read retry。

### `RegionScanCache.java`

若需要，加入 stronger stamp / header hash。

## Tests

### 新增

```text
ChunkGenerationSchedulerTest.java
ChunkFillControllerStateTest.java
PreviewSnapshotTest.java
RegionScannerConcurrentMutationTest.java
ProgressRecoveryTest.java
```

---

# 26. 建議實作順序

不要一次混在一個巨大 commit。

## Commit 1：Generation gateway / telemetry scaffolding

- 建立 async generation adapter。
- 先不改 UI。
- 加 session id / in-flight diagnostics。

## Commit 2：Single in-flight scheduler

- `tick()` 改成 submit-only。
- 移除同步 `getChunk()`。
- Completion 回 server thread。
- 加 scheduler unit tests。

## Commit 3：Pause / stop draining

- `PAUSING`。
- `STOPPING`。
- stale session handling。

## Commit 4：移除 legacy brake / rate

刪：

```text
chunksPerTick
rateField
lag backoff
slow chunk cooldown
```

## Commit 5：ProgressStore v2

- resume intent。
- disk rescan source of truth。
- migration / old file compatibility。

## Commit 6：Preview thread safety

- Mutable server state。
- Immutable published snapshot。

## Commit 7：RegionScanner stable reads

- before/after stamp。
- retry。
- unstable handling。

## Commit 8：Docs + integration tests

- README。
- CHANGELOG。
- soak test notes。

---

# 27. 禁止採用的錯誤修法

## 27.1 不要只增加 Xmx

```text
15G / 25G / 31G 都已 crash
```

更多 RAM 不等於修復。

## 27.2 不要再疊更多「事後 cooldown」

例如：

```text
>150ms 休 40 tick
>500ms 休 200 tick
>2s 休 1 分鐘
```

這仍然無法控制「目前這一個同步 getChunk burst」。

## 27.3 不要直接從 server tick 呼叫 `getChunkFuture()` 就以為 async

Minecraft 1.19.4 main-thread branch 一樣 `managedBlock()`。

## 27.4 不要 `supplyAsync(() -> getChunk(...))`

它最後仍把 blocking getChunk 排回 main thread。

## 27.5 不要 `.join()` / `.get()`

ChunkPatch generation path 不應主動等待 future。

## 27.6 不要把 `MAX_IN_FLIGHT` 開給使用者調到 8

這只會把舊問題換成 concurrent 版本。

## 27.7 不要用 `System.gc()`

GC 是結果，不是排程 API。

## 27.8 不要從 coordinator thread 修改 world

Worker thread 只能進入 Minecraft 明確 thread-aware 的 `getChunkFuture()` API request path。

## 27.9 不要強制 cancel Minecraft worldgen future 當作 pause

採 drain。

## 27.10 不要 catch `Throwable` 然後嘗試正常繼續

普通 exception 可以恢復；VM fatal state 不應被當一般錯誤處理。

---

# 28. 版本與文件整理

## 28.1 建議版本

這不是單純 bugfix，而是 generation execution model 改變。

如果採 semantic versioning，建議：

```text
0.2.0
```

如果專案目前希望維持 0.1.x：

```text
0.1.13
```

也可以，但 CHANGELOG 應明確標示重大內部架構重構。

## 28.2 README 已有 stale information

目前 README 還寫：

```text
綠色顯示 Xaero World Map 1.39.12 已有地表圖磚...
```

但 `0.1.9` CHANGELOG 已明確說：

```text
移除 0.1.7 引入的 .xwmc 快取覆蓋判定
現在只以世界區塊 Status 為準
```

README 應同步修正。

另外 README build output 還寫：

```text
build/libs/ChunkPatch-0.1.8.jar
```

應改成泛化：

```text
build/libs/ChunkPatch-<version>.jar
```

---

# 29. 最終風險判斷

以下是目前依證據的風險排序，不是數學機率保證。

## 高優先級

### 1. 同步 FEATURES generation workload

可信度：**高**

理由：

- server tick 內 managedBlock。
- FEATURES range=8。
- 大型 dependency graph。
- 大 heap allocation 波動。
- 四次 crash 都在 G1 worker。
- 長時間 generation workload 與 crash 時間吻合。

### 2. 多 target / tick 的 burst 疊加

可信度：**高**

`chunksPerTick` 不適合用來控制 FEATURES workload。

## 中優先級

### 3. Native injection / hardware stability

可信度：**中，尚未排除**

因為 JVM native access violation 不應只用 Java code 解釋。

Single-in-flight 重構後仍 crash 才能更有效區分。

### 4. Preview data race

可信度：**確定存在，但不像目前 fatal GC crash 主因**

必須修，但不應宣稱它就是 `jvm.dll` crash 根因。

### 5. RegionScanner live-file inconsistency

可信度：**確定存在 correctness window，但不像 GC fatal crash 主因**

主要風險是 scan 結果誤判，而非 native JVM crash。

## 低優先級 / 未發現

### ChunkPatch 直接 native memory corruption

目前 source review **沒有找到支持證據**。

沒有：

```text
Unsafe
JNI/JNA write
manual native pointer
manual free
```

所以不應讓 agent 花大量時間在不存在的 JNI code 上。

---

# 30. 給 Coding Agent 的執行摘要

以下可以直接作為 coding agent 的主要任務規格：

---

## Objective

Refactor ChunkPatch 0.1.12's Minecraft 1.19.4 chunk generation pipeline to remove synchronous `getChunk(FEATURES, true)` calls from the integrated server tick and replace the current `chunksPerTick` + post-spike cooldown model with a strictly single-in-flight, future-driven backpressure scheduler.

## Non-negotiable invariants

1. Never call synchronous `ServerChunkCache.getChunk(..., ChunkStatus.FEATURES, true)` from `ChunkFillController.tick()`.
2. Never block the server tick on generation with `.join()`, `.get()`, or equivalent waits.
3. Do not simply call `getChunkFuture()` directly from the server main thread; in Minecraft 1.19.4 its main-thread path invokes `mainThreadProcessor.managedBlock(...)` and still blocks.
4. Enter the public `ServerChunkCache.getChunkFuture()` non-main-thread branch through a dedicated generation coordinator executor, then compose the returned future without blocking.
5. The coordinator thread may only request the thread-aware future. It must never mutate `ServerLevel`, controller state, scan state, cursor state, world data, or UI state.
6. All generation completion mutations must be marshalled back through `server.execute(...)`.
7. `MAX_IN_FLIGHT_TARGETS` must remain exactly `1` in the first stable implementation and must not be user configurable.
8. A new target may not be submitted until the previous target's future has completed and its server-thread completion callback has finished.
9. Pause and stop must stop new submissions and drain the current in-flight generation rather than relying on `future.cancel(true)`.
10. Use a monotonically increasing generation session ID/token so stale callbacks cannot mutate a new session or a newly opened world.

## Remove legacy throttle model

Delete or retire:

```text
chunksPerTick
rateField
rate command argument
LAG_BACKOFF_THRESHOLD_NANOS
SLOW_CHUNK_NANOS
SLOW_CHUNK_COOLDOWN_TICKS
lastTickMarkNanos
cooldownTicksRemaining
per-call synchronous chunk timing used as a brake
```

The old brakes were reactive: they only triggered after a synchronous generation spike had already happened. Replace them with proactive submission backpressure.

## Submission safety gates

Before submitting the next target:

- `inFlight == null`
- correct dimension still loaded
- state is RUNNING
- minimum interval has elapsed
- disk free space is above hard threshold
- optionally gate on `server.getAverageTickTime()` using hysteresis
- optionally gate on JVM heap pressure using hysteresis

These are admission controls, not post-spike cooldowns.

## Cursor / persistence

Treat the region scan as the source of truth after restart.

Refactor `ProgressStore` into a resume-intent file containing the selected bounds and dimension identity. Do not trust persisted `completed`, `nextX`, `nextZ`, `planned`, or `chunksPerTick` as authoritative recovery state. After restart, rescan disk, recompute missing chunks, and start searching from the selection origin while skipping chunks already present on disk.

Do not permanently advance a runtime committed cursor in a way that can skip an in-flight target if the process crashes before that target is saved.

## State machine

Support draining states, preferably:

```text
RUNNING → PAUSING → PAUSED
RUNNING → STOPPING → READY
```

If no target is in flight, pause/stop can complete immediately. If a target is in flight, do not submit another target and finalize the requested transition after the current completion callback.

## Preview thread safety

`volatile PreviewData` is insufficient because the record exposes mutable arrays that are written on the server thread and read on the client render thread.

Separate server-owned mutable preview counters from immutable published snapshots. Do not expose raw arrays that continue to mutate after publication. Publish cloned/read-only snapshots at a controlled interval.

## Region scanner consistency

`requestScan()` saves once and then scans `.mca` files on a background thread while Minecraft continues running. Current code checks file size/mtime after scanning only to decide cacheability, but still applies states even if the file changed during the scan.

Implement stable-read retries:

```text
metadata before
scan region
metadata after
same → accept/cache
changed → retry
repeatedly unstable → skip/mark unstable, not corrupt
```

## Error handling

Do not `catch (Throwable)` for normal generation recovery. Catch normal exceptions / future exceptional completion. Log session ID and target coordinate. Do not attempt to treat `VirtualMachineError`-class conditions as ordinary recoverable generation failures.

## Diagnostics

Add rate-limited telemetry with:

```text
session id
target coordinate
submitted/completed count
remaining count
last target wall-clock duration
server average MSPT
heap used/max
free disk
```

Do not log every target at INFO level.

## Tests required

Add deterministic tests for:

```text
only one in-flight target
no second submission before completion
pause while in-flight
stop while in-flight
future failure
stale callback/session token
partial chunk promotion
skip map-ready/corrupt chunks
progress recovery after simulated crash
region file mutation during scan
immutable preview snapshot behavior
```

## Acceptance criteria

The refactor is not complete until:

```text
ChunkFillController.tick() performs no blocking world generation
no generation future is joined by ChunkPatch
MAX_IN_FLIGHT == 1 is enforced
pause/stop drain safely
old brake constants and rate UI are removed
preview cross-thread mutable arrays are eliminated
unstable live region scans are retried/rejected
recovery is based on disk rescan
```

---

# 附錄 A：重構前後流程比較

## Before 0.1.12

```text
END_SERVER_TICK
    ↓
for 1..chunksPerTick
    ↓
getChunk(FEATURES, true)
    ↓
main thread blocks
    ↓
large dependency generation
    ↓
return
    ↓
measure duration
    ↓
if too slow, cooldown later
```

## After refactor

```text
END_SERVER_TICK
    ↓
state RUNNING?
    ↓
inFlight empty?
    ↓
safety admission OK?
    ↓
select one target
    ↓
coordinator requests getChunkFuture from non-main thread
    ↓
return immediately

Minecraft scheduler runs generation
    ↓
future completes
    ↓
server.execute(completion)
    ↓
commit state
clear inFlight
    ↓
next tick may submit one new target
```

---

# 附錄 B：建議初始常數

這些不是永久 API，只是初版 conservative defaults：

```java
MAX_IN_FLIGHT_TARGETS = 1;
MIN_TICKS_BETWEEN_TARGETS = 1;
DISK_HARD_STOP_BYTES = 2 GiB;
CURSOR_SCAN_MAX_CHECKS_PER_TICK = 4096;
CURSOR_SCAN_BUDGET_NANOS = 1 ms;
REGION_STABLE_READ_ATTEMPTS = 3;
```

可選 telemetry safety policy：

```text
MSPT pause threshold  : 45 ms
MSPT resume threshold : 35 ms
Heap pause threshold  : 85%
Heap resume threshold : 75%
```

這些 threshold 必須被視為「可調 policy」，而不是崩潰根因的硬邊界。

---

# 附錄 C：Crash 診斷決策樹

```text
完成 single-in-flight async refactor
        ↓
乾淨 Fabric + ChunkPatch + Java 17.0.20+ + 8~12G heap
        ↓
是否仍 GCTaskThread / jvm.dll access violation？
        │
        ├─ 否
        │   ↓
        │  逐步加回其他 mods / overlays
        │   ↓
        │  找到重現組合
        │
        └─ 是
            ↓
           關閉 Discord / Overwolf / AV injection
            ↓
           是否仍 crash？
            │
            ├─ 否 → native injected component interaction
            │
            └─ 是
                ↓
               關 XMP / OC / undervolt
                ↓
               MemTest86 / OCCT / WHEA
                ↓
               若硬體穩定仍重現
                ↓
               收集新 hs_err + latest.log + JVM build
               再調查 HotSpot/G1 或 Minecraft/native interaction
```

---

# 附錄 D：一句話版本

> ChunkPatch 目前最大的設計問題不是「冷卻不夠久」，而是把一個可能展開 17×17 dependency graph 的 `FEATURES` worldgen request 當成可在 server tick 中同步執行、甚至每 tick 執行 1～8 次的普通小工作。真正的修法是把工作單位改成嚴格 `1 in-flight` 的非阻塞 future pipeline，讓 backpressure 在下一個工作提交之前發生，而不是讓大型 allocation burst 發生後再補煞車。
