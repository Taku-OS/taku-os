# Taku Memory 0.16.0 acceptance: faster recall, same results

Scope: `search`, the recall shared by Agents (`memory_search`, open-access `search`), the Suggestions page and the user card. The search was first timed stage by stage. Then the slow stages were optimized **without changing what search returns**. See the [module notes](../../../system/memory/README.en.md) for the change summary.

## How it was measured

No test emulator was available, so everything ran on a cloud machine (2-core Xeon at 2.1 GHz). Absolute times on a phone will be higher. What matters here is the share of each stage and the before/after comparison.

1. **Synthetic stores.** [gen.py](gen.py) writes 1k, 10k and 50k memories with 200, 2,000 and 10,000 graph nodes (1–3 versions each). The content is chat- and notification-style Chinese and English lines, with random normalized 512-d vectors. [real.py](real.py) rebuilds `memories` in the device's column order: `head`, `fresh` and `lines` are later `ALTER TABLE` columns placed after `body`. [gen_big.py](gen_big.py) writes the 1M store, with vectors and nodes kept outside the database.
2. **Keyword SQL.** Python's SQLite 3.45 runs the exact statements `Recall` builds ([sqlfinal.py](sqlfinal.py), [sqlbig.py](sqlbig.py)). Results are in [keyword-sql.txt](keyword-sql.txt) and [keyword-sql-1m.txt](keyword-sql-1m.txt).
3. **Query vector.** The same quantized bge-small-zh model runs on onnxruntime 1.29 with 2 threads, as in `Embedder` ([ort.py](ort.py), [query-vector-ort.txt](query-vector-ort.txt)).
4. **In-memory ranking.** [RecallBench.java](RecallBench.java) and [RecallBenchBig.java](RecallBenchBig.java) run the previous and the new code side by side on the JVM. They time both and compare the full ranked lists entry by entry ([inmemory-jvm.txt](inmemory-jvm.txt), [inmemory-jvm-1m.txt](inmemory-jvm-1m.txt)). The previous code is taken from `main` as follows:

   ```sh
   git show origin/main:system/memory/src/org/agentos/memory/Recall.java \
     | sed 's/^final class Recall {/final class OldRecall {/; s/synchronized(Recall\.class)/synchronized(OldRecall.class)/g'
   ```

## Where the time went

A search ran these steps one after another:

1. `prune()`, which takes the store lock and a write transaction;
2. keyword SQL over evidence;
3. the query vector;
4. the top 30 of all evidence vectors;
5. node keyword and node vector lists;
6. a one-hop expansion along relations;
7. fusion.

Two of these grow with the store:

- **The keyword scan with no match.** Agents often ask a whole sentence such as "明天要交什么材料", which never appears verbatim, so the scan reads every row. This took 64 ms at 50k memories.
- **In-memory ranking.** Every search rebuilt and lowercased the text of every node and re-parsed every `related` list once per hop seed. It also boxed every vector score and fully sorted all of them, only to keep 30. This took 93–111 ms at 50k memories.

## What changed

1. **The vectors run alongside the keyword scan.** The query vector and the evidence top 30 run on a `memory-query` thread, while the caller scans keywords and node text. Search time becomes the longer of the two paths instead of their sum. The model's first load takes its own lock (`Recall.EMBED`), so a cold start no longer holds `Recall.class`, which every search needs.
2. **Query vectors are cached.** The vectors for the last 64 questions stay in memory, so a repeated question skips the model.
3. **Only the top 30 are kept.** Evidence ids, times and vectors live in plain arrays, and scoring keeps the best 30 as it goes. Equal scores keep the earlier row, exactly as the previous stable sort did. Node vectors are selected the same way.
4. **Nodes are prepared once.** The lowercased search text and the `related` set are computed when nodes load. The hop builds a reverse "listed by" map once per search instead of scanning every node for every seed.
5. **Keyword SQL is cheaper.**
   - A covering index `memory_kw(first_seen,id,title,head,fresh)` lets the newest-first scan read only the index instead of whole rows, and stop once 30 rows match. Existing databases get it from the 15-minute maintenance job, not on database open, so the UI never waits for it.
   - Terms without cased letters (Chinese, digits, symbols) use `instr()` instead of `LIKE`. `LIKE`'s case folding has nothing to fold in such terms, so the same rows match. Terms with letters keep `LIKE` and stay case-insensitive.
6. **`prune()` runs at most once a minute before a search.** Most searches no longer queue behind ingestion for the store lock. Changing settings resets the timer, so the next search after a retention change prunes first. The maintenance job prunes every 15 minutes as before.

## Results

**In-memory ranking** (JVM, 16 queries per size, medians; previous code from current `main`):

| Size | Evidence vector top 30 | Node lists | Whole in-memory ranking | Same ranked lists |
| --- | --- | --- | --- | --- |
| 1k | 1.15 → 0.57 ms | 0.67 → 0.17 ms | 1.88 → 0.99 ms | 16/16 |
| 10k | 10.31 → 7.43 ms | 6.44 → 2.18 ms | 18.34 → 11.01 ms | 16/16 |
| 50k | 55.88 → 38.99 ms | 30.88 → 12.61 ms | 111.00 → 57.09 ms | 16/16 |
| 1M (100k nodes) | 1298 → 711 ms | 296 → 132 ms | 2030 → 955 ms | 16/16 |

The 16 queries are 8 questions, each paired with two query vectors: one random, and one identical to a stored memory's vector. The second kind checks the order of equal scores. The 1M row comes from the run before the port to the open-source tree; the code under test did not change.

**Keyword SQL** (device column order, medians; old and new return the same rows):

| Size | Whole sentence, no match | Name, matches |
| --- | --- | --- |
| 1k | 1.2 → 0.6 ms | 0.2 → 0.1 ms |
| 10k | 11.2 → 5.2 ms | 0.1 → 0.0 ms |
| 50k | 63.6 → 27.4 ms | 0.1 → 0.0 ms |
| 1M | 1533 → 642 ms | 0.2 → 0.1 ms |

The index makes the `memories` table about 16% larger: 1.63 → 1.89 GB at 1M rows. That is about 3% of a whole 50k database that also holds vectors and nodes.

**One search** (whole-sentence question, estimated from the stage measurements; the new parallel part takes the longer path):

| Size | Before | After | Saved |
| --- | --- | --- | --- |
| 1k | about 6 ms | about 4 ms | about 30% |
| 10k | about 29 ms | about 12 ms | about 60% |
| 50k | about 159 ms | about 55 ms | about 65% |
| 1M | about 3.6 s | about 0.96 s | about 73% |

These totals are **estimates**, not end-to-end runs:

- **Before** is the sum of the stages.
- **After** takes the longer of two paths, keyword SQL or query vector plus evidence top 30, then adds nodes and fusion.
- **Not counted:** time no longer spent waiting for the store lock, which depends on concurrent ingestion.

## Checks

- **Component tests.** `StoreTests` adds four checks:
  - only terms without cased letters use `instr()`; full-width, accented and titlecase letters keep `LIKE`;
  - mixed `LIKE` and `instr()` terms still match case-insensitively;
  - a search reports its stage times;
  - a settings change makes the next search prune first.

  The existing search checks still apply: multiple keywords, escaping, revisions, forgetting and retention. The test APK compiles; it has not run on an emulator in this round.
- **Builds.** The release, debug and test APKs all build.
- **On-device timing.** Debug builds gain a root-only, read-only `benchSearch` hook that returns median stage times and the first (cold) call:

  ```sh
  content call --uri content://org.agentos.memory --method benchSearch \
    --arg '{"queries":["明天要交什么材料","婷婷"],"repeat":5}'
  ```

## Not covered and risks

- **No device measurement.** All numbers come from the cloud machine.
- **Every vector stays in memory.** That costs about 100 MB at 50k memories and 2.4 GB at 1M, which a phone app cannot hold. This was already the design; this change does not alter it. At normal use (roughly 100–300 records a day) a store reaches 10k–100k records in a year or two. Reaching 1M would need int8 vectors with exact rescoring, an approximate nearest-neighbour index, or vectors on disk.
- **Dot products are unchanged.** Vector scoring still multiplies in the same order, so scores match bit for bit. The top 30 still takes about 39 ms at 50k.
- **No full-text index.** An FTS5 trigram index would make no-match scans much faster. It is not used yet because it is not confirmed that Android's built-in SQLite includes FTS5.

The synthetic stores, queries and benchmark inputs are Chinese and English on purpose: they model the same Chinese-locale user as earlier Memory reviews. Code comments and this record are in English.
