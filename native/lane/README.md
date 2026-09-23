# routerunner_lane

A 1:1 Rust port of the Routerunner lane planner (`com.routerunner.lane` plus the solver classes it
uses), built as a JNI `cdylib` and as `lane_cli`, a drop-in replacement for
`com.routerunner.lane.LaneCli`.

The port is behavioural, not just algorithmic: on the 13-room reference set the exported plan JSON
is **byte-for-byte identical** to what the Java CLI writes through Gson.

## Build

```
cd C:\Users\river\routerunner\native\lane
cargo build --release
```

produces

```
target\release\routerunner_lane.dll
target\release\lane_cli.exe
```

Stable toolchain, `x86_64-pc-windows-msvc`, no feature flags, no `.cargo/config.toml`. The release
profile is `opt-level = 3`, `lto = "fat"`, `codegen-units = 1`; `panic` is deliberately left at
`unwind` because the cdylib must not abort the JVM, and every JNI entry point wraps its body in
`catch_unwind` and returns null / 0 instead.

## CLI

```
lane_cli rooms.jsonl plans.jsonl legmodel.json [threads]
```

Same arguments, same room-record schema, same per-room `log` lines and same stderr summary as
`LaneCli`. `threads` defaults to `available_parallelism() - 1`.

## Test

Java reference (single-threaded, for a like-for-like per-room time):

```
"C:\Users\river\wv_decompile\tools\jdk-17.0.19+10\bin\java.exe" -Xmx4g ^
  -cp "C:\Users\river\routerunner\build\libs\Routerunner-0.17.0.jar;C:\Users\river\.gradle\caches\forge_gradle\maven_downloader\com\google\code\gson\gson\2.8.9\gson-2.8.9.jar" ^
  com.routerunner.lane.LaneCli rooms_v2.3c.jsonl java_ref.jsonl ^
  C:\Users\river\routerunner\research\legmodel_ridge.json 1
```

This port:

```
target\release\lane_cli.exe rooms_v2.3c.jsonl rust_out.jsonl ^
  C:\Users\river\routerunner\research\legmodel_ridge.json 1
```

Comparing the two: parse both files, key the records by `key`, and compare each mode object. A
`json.loads(a) == json.loads(b)` over the mode object is an exact (bit-level) float comparison and
is the check that should pass; the `log` array holds wall-clock timings and will not match.

## Results on `rooms_v2.3c.jsonl` (13 rooms, point mode, 1068–1316 chests each)

| | Java | Rust |
|---|---|---|
| sum of per-room times | 13.12 s | 2.13 s |
| speedup | | **6.16x** (per room 4.8x – 8.2x) |
| process wall time | 13.4 s | 2.2 s |

All 13 rooms agree on `nLanes`, `nRuns`, `cover`, `tTotal`, `yieldTotal`, every run polyline,
`exitPath`, `exitStraight`, `ghost`, `clears` and `heat` — and the serialized mode object is
byte-identical to Gson's output.

Corridor mode (3 rooms, both modes): 6.6 s Java vs 1.0 s here, and 5 of the 6 mode objects are
byte-identical. The sixth differs only in `tTotal`, by **one ULP** (18.105703037159795 vs
18.1057030371598); every lane, run, trigger and ghost sample in that plan is identical. The cause
is `Math.exp` / `Math.log`, which HotSpot replaces with its own x86 stub while Rust calls the UCRT;
both are sub-ULP accurate but they are not the same implementation, so a leg time can land one ULP
apart. No decision the planner makes was affected.

## JNI surface

Java class `com.routerunner.lane.NativeLane`, all methods static:

```java
static native long   create(int sx, int sy, int sz, byte[] solidBits, int[] chests,
                            int chainRange, int chainLimit, double[] model);
static native String plan(long handle, int ex, int ey, int ez, int xx, int xy, int xz,
                          byte[] mask, double[] params);
static native String path(long handle, int ax, int ay, int az, int bx, int by, int bz);
static native void   destroy(long handle);
static native String version();          // "rust-1"
```

- `solidBits` is the run log's BitSet encoding: bit `i` is bit `i % 8` of byte `i / 8`, LSB first,
  cell index `(x * sy + y) * sz + z`, a set bit meaning solid. `create` bakes the clearance field.
- `chests` is x,y,z triples, room-local.
- `model` is `mean[12]`, `scale[12]`, `coef[12]`, `intercept` — 37 doubles. A 38th, `sigma`, is
  read if present and is only used when `ghostNoise > 0`; without it sigma is 0 and ghost noise has
  no effect.
- `create` returns 0 on failure; `plan` and `path` return null on failure or on a panic.
- `mask` is one byte per chest, non-zero meaning still standing.
- `params` is 33 doubles, ints and booleans passed as doubles, in this order:
  `breakReach, headings, seedSpacing, minLaneLen, minLaneClr, maxStepUp, maxDrop, entrySpacing,
  proxyTopK, beamWidth, lookahead, turnCap, mergeTrans, maxRunLen, bailAggression, turnaroundDeg,
  turnaroundPenaltyS, allowFly, flyPenaltyS, sweepGain, timeScale, pointMode, ghostNoise, seed,
  maxLanes, triggerS, strandPenaltyS, opportunityFloor, exitWeight, maxTransLen, farTries, farOk,
  bailFloor`.
- The entrance and exit are passed already snapped (`Grid.snapInside` on the Java side); `snapNear`
  still runs inside the searches, as in Java.
- A handle keeps the reach, component, landing, exit-field and exit-time caches alive between
  calls, so a replan on the same room is cheaper than the first one. The exit field is rebuilt only
  when the exit cell changes. Corridors are built lazily on the first `plan`, because `create` has
  no params and therefore does not yet know whether the caller wants corridor or point mode.
- One handle is not thread-safe: use it from one thread at a time, or make one handle per thread.
  `destroy(0)` and calls on a destroyed handle are the caller's responsibility, as usual for a raw
  handle; `destroy` itself tolerates 0.

## Java behaviours this port reproduces on purpose

The plan depends on several things the Java source never states, so `jcompat.rs` reproduces them:

- **`java.util.PriorityQueue`'s heap.** The A* comparator orders on `f` alone, so equal-`f` entries
  pop in whatever order the binary heap happens to hold them. `JPq` is Java's exact
  `siftUp`/`siftDown`, which is what makes A* return the same path among equal-cost ones.
- **`java.util.HashMap` iteration order.** In point mode the candidate list is built by iterating a
  `HashMap<Long, P>`, and equal-scoring candidates keep that order through the stable sorts, so it
  decides which candidates get evaluated. `java_hashmap_order` reproduces it: table bucket first,
  insertion order within a bucket, with Java's capacity growth and `Long.hashCode` spreading.
- **`Math.round`.** The exact JDK bit algorithm, which is not `floor(x + 0.5)`.
- **`Double.toString` and `String.format("%.Nf")`**, so the JSON bytes and the log lines match.
- **`Math.hypot`** over integer coordinate differences: verified against the JDK to be exactly
  `sqrt(dx*dx + dz*dz)` for every integer pair in range, so plain `sqrt` is used.
- **Integer negation before widening** in the ghost yaw: Java's `-(b.x - a.x)` is an int, so a zero
  delta gives `+0.0` and `atan2` returns `+180`, not `-180`.

## Where the speed comes from

Every per-cell map Java keys by a packed cell key is a flat array indexed by `(x*sy+y)*sz+z`,
stamped with a generation counter so a search neither allocates nor clears: `gcost`/`parent` and
the sweep-discount memo share one 24-byte struct per cell, the closed set is a bitset, `standable`
and `columnFree` are baked bitsets, and the reach lists live in one arena.

The largest single win is `SuccTable`: which walk steps a cell has, where they land and what they
cost is a pure function of the walls (the head-room test, the diagonal corner test and the drop
scan all read only the grid), so the whole walk graph is baked once per room and the A*, the
reachability BFS and the exit field walk a table instead of re-deriving it tens of millions of
times. Entries keep the `dx`/`dz` order Java emits them in, which is what the A* ties break on.

Two smaller ones that are worth knowing about because they *look* like semantic changes and are
not: the sweep-discount memo is kept for a whole `evalTop` instead of being rebuilt per candidate
(the discount is a pure function of cell and `remaining`, which do not change across one `evalTop`),
and a relax is skipped without consulting the discount when the cell is already cheaper than the
floor-discounted step could ever make it (the discount is bounded below by `DISCOUNT_FLOOR`). The
discount scales walk steps only, never a DROP edge — that asymmetry is Java's and getting it wrong
changes plans.

## Known limitation

`java_hashmap_order` models Java's `HashMap` as (bucket, insertion order). That is exact while
every bin stays a linked list, but when a bin reaches 9 entries in a table of 64 or more Java
treeifies it and `moveRootToFront` moves the red-black root to the head of that bin, which the
model does not reproduce. The port detects this, warns once, and reports the count at the end of a
CLI run (`1895` bins over the 13 reference rooms). On this data it changed nothing — all 13 rooms
are byte-identical — but it is a real divergence risk on other rooms. Closing it properly means
porting `HashMap.TreeNode` (`treeify`, `balanceInsertion`, `moveRootToFront`, `putTreeVal` and
`split`); nothing short of that is exact.

Two deliberate deviations from `LaneCli`, neither visible to `tools/lane_bundle.py`, which keys
records by `key`:

- output lines are written in input order rather than completion order;
- the per-room `log` line carries this port's own timing, so it will never match Java's.
