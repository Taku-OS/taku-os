# Taku Memory 0.17.0 acceptance: Chinese and English

Memory was built for Chinese. Its vector model read only Chinese, and the local rules that decide when a reminder is worth a model call, and which agenda items cost most to miss, knew only Chinese words. This version makes three changes:

1. a multilingual vector model;
2. English versions of the local rules, plus question-style keyword search;
3. a rule that a language is supported only when the privacy rules can read it.

See the [module notes](../../../system/memory/README.en.md) for the summary.

## How it was measured

No test emulator was available. Everything ran on a cloud machine (2-core Xeon at 2.1 GHz, ONNX Runtime 1.29 with 2 threads), with these test sets:

- **Phone-style set** ([cases.py](cases.py)): 36 target records (18 Chinese and 18 English chats, notifications and receipts) and 40 lookalike distractors that share each target's topic but not its answer. There are 72 questions, one in the record's language and one in the other language.
- **Chinese retrieval set** ([zh.py](zh.py)): 300 C-MTEB MMarcoRetrieval dev queries against their positive passages plus 3,000 random passages. The passages are not committed.
- **Tokenizer texts** ([tokenizer_texts.py](tokenizer_texts.py)): the phone-style texts, 60 edge cases and 400 MMarco passages.

The edge cases cover:

- whitespace runs, tabs and newlines;
- emoji and ZWJ sequences, full-width and no-break spaces, combining marks;
- Japanese, Korean, Russian, Arabic, Hindi and Thai;
- control characters and rare CJK;
- HTML-like and special-token text;
- 300-character runs.

## 1. Vector model

**Choice.** Six on-device candidates were run the way their authors specify (prefixes, pooling; [spec.py](spec.py), [run.py](run.py)). Top-1 on the phone-style set:

| Model | Dims | ms/record | All | zh→zh | en→en | zh→en | en→zh |
| --- | --- | --- | --- | --- | --- | --- | --- |
| bge-small-zh-v1.5 (before) | 512 | 2.6 | 0.43 | 1.00 | 0.61 | 0.11 | 0.00 |
| multilingual-e5-small | 384 | 6.4 | 0.54 | 0.89 | 1.00 | 0.00 | 0.28 |
| jina-embeddings-v2-base-zh | 768 | 15.0 | 0.68 | 0.78 | 0.89 | 0.44 | 0.61 |
| bekko-embedding-v1-a25m | 384 | 14.8 | 0.79 | 0.83 | 1.00 | 0.72 | 0.61 |
| **bekko-embedding-v1-a8m** | 384 | 3.8 | **0.88** | 0.94 | 0.94 | 0.89 | 0.72 |
| embeddinggemma-300m (q4) | 768 | 52.5 | 0.92 | 1.00 | 1.00 | 0.83 | 0.83 |

([phone-set-models.txt](phone-set-models.txt) also has top-5 and MRR.)

On the Chinese retrieval set ([mmarco-zh-models.txt](mmarco-zh-models.txt)):

| Model | MRR@10 | Recall@10 | ms/passage |
| --- | --- | --- | --- |
| bge-small-zh-v1.5 (before) | 0.802 | 0.870 | 8.1 |
| bekko-embedding-v1-a8m | 0.911 | 0.963 | 11.0 |
| multilingual-e5-small | 0.903 | 0.940 | 14.3 |
| bekko-embedding-v1-a25m | 0.928 | 0.960 | 33.8 |
| jina-embeddings-v2-base-zh | 0.858 | 0.937 | 34.3 |
| embeddinggemma-300m (q4) | 0.930 | 0.967 | 135.5 |

bekko-embedding-v1-a8m was chosen:

- Its quality is within a few points of EmbeddingGemma, at about a twelfth of the time.
- It beats the old Chinese-only model on Chinese.
- It is MIT licensed, where EmbeddingGemma uses the Gemma terms.

Its ONNX file stores the 256k-token embedding table as int8, with the encoder in fp32, so it is about 130 MB. The model is fine-tuned from mmBERT-small and uses the Gemma 2 tokenizer.

**Tokenizer port.** `Bpe.java` reimplements the model's Hugging Face tokenizer:

- added tokens (newline, tab and space runs, HTML tags, specials) are cut out first, leftmost-longest;
- Metaspace with prepend-always;
- BPE with byte fallback.

`tools/tokenizer_bin.py` turns the 34 MB `tokenizer.json` into a 9.4 MB table (characters, added tokens, 580,604 merges as sorted id pairs). The phone memory-maps the table, so the merges stay off the Java heap. Token ids matched the reference on all 603 texts ([BpeParity.java](BpeParity.java), [tokenizer-parity.txt](tokenizer-parity.txt)).

**Embedder.** `Embedder.java` takes the mean of every token's last hidden state (special tokens included, as the model's pooling config says) and normalizes it; queries and documents get no prefix. It was run with desktop ONNX Runtime against the Python reference ([vector_ref.py](vector_ref.py)): over 208 texts the lowest cosine was 1.000000, at 6.5 ms per text ([VectorParity.java](VectorParity.java), [vector-parity.txt](vector-parity.txt)).

**Card threshold.** The user card adds up to three items whose similarity to a message is at least a floor. That floor was 0.45, tuned for bge, whose scores sit high: there it let 43% of unrelated pairs through. The new model separates related from unrelated pairs much more clearly:

- related pairs: median 0.56;
- unrelated pairs: median 0.12, 99th percentile 0.40.

The new floor is 0.30. It keeps every same-language related pair and 94% of cross-language ones, and lets 4% of unrelated pairs through ([calib.py](calib.py), [card-threshold.txt](card-threshold.txt)).

**Migration.** The model id changed, so the maintenance job recomputes node and record vectors, as it did when the model was quantized in 0.8. The work is resumable if the job is stopped. Until it finishes, search uses keywords and the vectors already redone. Vectors of a different length score as unrelated instead of failing. The old model file is deleted on first load.

## 2. English rules and questions in search

**Reminder check** (`Proactive`). Per-language tables (`DAYWORDS`, `REQUESTS`, `CLOCKS`) now read:

- English days: tomorrow, tonight, Friday, next Monday, the 22nd, Dec 5;
- English times: 7pm, 7:30 p.m., at 7 with an evening word, noon;
- English time words: dinner, lunch, afternoon, morning;
- English requests: can you, are you free, let's, please, a question mark.

English words of three or more letters now count toward topic overlap, without case and without function words. The Chinese rules are unchanged: same patterns, same two-character pieces.

**Agenda priorities** (`Recall`). `PRIORITY` holds penalty, deadline, promise, occasion, money, routine and "available from" words per language. Card notes now understand English month dates ("Dec 5", "5 December").

**Questions in keyword search.** Agents and the Suggestions page often search with a whole question, which never appears in a record, so the keyword list was empty. Now, when a query reads as a question, its content words replace its pieces:

- **What counts as a question:** a run of four or more Chinese characters, or four or more words including a function word.
- **Chinese words:** Chinese is cut by Android's ICU dictionary.
- **Unknown names:** runs of single characters are kept only when they look like a name: three or more characters, or a doubled one (婷婷).
- **Dropped words:** function words, time words and question words.

Three variants were measured, fused with the vector list as `Recall.search` fuses them ([e2e.py](e2e.py)):

| Keyword list for questions | Top-1 |
| --- | --- |
| None (vectors alone) | 0.88 |
| Any content word may match, more matches first | 0.60 |
| All content words must match | 0.82 |
| All content words, and only when at least two remain | 0.89 |

The shipped rule is the last row. A question with fewer than two content words keeps the old behavior.

Recall on the phone-style set, before and after, with the keyword list and vectors fused ([phone-set-recall.txt](phone-set-recall.txt)):

| | Top-1 | Top-5 | zh→zh top-1 | en→en | zh→en | en→zh |
| --- | --- | --- | --- | --- | --- | --- |
| Before: bge, whole-query keywords | 0.43 | 0.62 | 1.00 | 0.61 | 0.11 | 0.00 |
| After: bekko a8m, question content words | 0.89 | 1.00 | 0.94 | 1.00 | 0.89 | 0.72 |

Chinese-to-Chinese top-1 drops from 1.00 to 0.94: one question puts a lookalike first and the right record second.

## 3. Languages gated on privacy

`Privacy.RULES` holds the six sensitive-topic rules per language: others' conditions, others' care events, the user's action, private plans to leave, work handovers, and the user's own sensitive details. `Lang.SUPPORTED` is its key set, `zh` and `en`.

`Lang.of` names the languages in a text:

- **By script:** Han without kana is Chinese, kana is Japanese, Hangul is Korean, and other scripts are named by script.
- **Latin script:** telltale function words for Spanish, French, German, Portuguese, Italian, Dutch and Indonesian, plus accented letters that English does not borrow. English loanword accents (café, naïve, Zoë) stay English.

The two sensitivity tests report any text with an unsupported language as sensitive. That keeps it out of the user card, the agenda lists (due soon, newly added) and the verbatim lines a reminder sees. The text is still stored and searchable.

StoreTests checks that `Proactive.DAYWORDS`, `REQUESTS`, `CLOCKS` and `Recall.PRIORITY` cover exactly the supported languages. A language therefore cannot get reminder rules without privacy rules, or the reverse. `status.languages` and `status.vectorModel` expose this to Agents, and the open-access skill documents both.

## Checks

- **JVM.** The new rules were run on a JVM, with org.json and ICU4J 76.1 standing in for Android's ([LangChecks.java](LangChecks.java), [icu-shim](icu-shim), [jvm-language-checks.txt](jvm-language-checks.txt)). 25/25 passed, including the existing Chinese date, time and privacy checks.
- **Component tests.** `StoreTests.languages` repeats those checks on the device and adds the store-backed ones: `status` fields, and English and Chinese questions finding records by content words. The test APK compiles; it has not run.
- **Host and builds.** `test-host.sh` passes: native packaging, and keyword-only fallback without the native libraries. The release, debug and test APKs build. The APK stores `assets/embed/model.onnx` and `tokenizer.bin` uncompressed.

To reproduce:

1. Download the six models into `models_dir/<name>/` per [spec.py](spec.py).
2. Run `run.py`, `zh.py` and `calib.py`.
3. Compile the memory sources with the ICU shim, then run `DumpWords` over the questions and `e2e.py` over its output.

## Not covered and risks

- **No device run.** Nothing ran on the emulator or a phone: not StoreTests, not timing, not the first-load copy or the migration. Phone ARM encode time is unknown. On this CPU, a8m took 11 ms against 8 ms for bge on a Chinese passage.
- **Size.** The APK grows from about 37 MB to about 152 MB. App storage grows by about 140 MB, because ONNX Runtime and the mapped tokenizer need real files.
- **Migration cost.** At an estimated 10–20 ms per record on a phone, recomputing 50k records takes 8–17 minutes of maintenance, spread over job runs.
- **Small test sets.** The phone-style set has 72 questions and was written for this check; one question moves top-1 by 0.014. The Chinese set is translated MS MARCO, not phone text.
- **Language detection is heuristic.** A short Latin-script text with no telltale word (for example a Spanish line without accents or common function words) reads as English and is checked by the English rules only. The gate fails closed only for what it detects.
