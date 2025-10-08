\# CS441 — RAG over the MSR Corpus (Lucene + Ollama)



Author: Ali Mouhtadi

Email: amouh3@uic.edu  

Demo Video: <YouTube link>



Retrieval-Augmented Generation (RAG) over the MSR PDF corpus. Extract → chunk → embed → index with Lucene HNSW, then answer questions using only retrieved MSR context via Ollama. 
Pipeline: extract → chunk → embed → **Lucene HNSW** index (MapReduce local mode) → retrieve → answer with **Ollama**.
- **Self-contained default run** uses `testCorpus/` + `input/pdf_list2.txt`.
- **Out-of-corpus questions** are refused.

## Getting Started


# What this project does

- End-to-end RAG over a small bundled MSR paper corpus:

- extract + chunk PDFs

- build a Lucene vector index (MapReduce “local” mode)

- embed query → retrieve → pack context → generate with an Ollama LLM

The repo is self-contained for the default run: it uses a small 30-PDF corpus and a relative list file so graders don’t need extra data.



## REQUIREMENTS(tested version in paranteheses)


Ensure you have the following set up and installed. Follow their tutorials on the linked sites if necessary.
- **Java:** 11 or 17 (tested on 11)
  Check: `java -version`

- **sbt:** 1.9+ (tested on 1.11.x)  
  Check: `sbt --version`

- **OS:** Windows 10/11 or macOS (Linux OK)
- **Ollama (for answer stage):**
  - Install: https://ollama.com/download  
    macOS: `brew install --cask ollama`
  - Run: `ollama serve` (or start the app on Windows)
  - Models (pull once):
    ```bash
    ollama pull mxbai-embed-large
    ollama pull llama3.2:1b-instruct-q4_K_M
    ```
  > If Ollama/models aren’t available, the index still builds; only the final Q&A will be skipped/failed gracefully.


# Hadoop (for the MapReduce job)

> **Hadoop:** Not required to install. The job runs with Hadoop **LocalJobRunner** via the libraries on the classpath.

If you do want a full Hadoop install, Hadoop 3.3.x works. Not required for the default run.


Optional: Build a runnable JAR (no sbt on target machine)

This repo already works with sbt run. If you prefer a single fat JAR:

`sbt assembly`


Output: `target/scala-3.*/*-assembly.jar`

Run a main with the JAR

Using defaults (bundled small corpus):

java -cp target/scala-3.*/msr-rag-hw1-assembly.jar edu.uic.msr.RunAll


With a custom config:

`java -Dconfig.file=conf/big.conf \
     -cp target/scala-3.*/msr-rag-hw1-assembly.jar \
     edu.uic.msr.rag.AskLucene "your question here"`

Note: I use multiple mains; the JAR isn’t -jar runnable unless a single Main-Class is set. Prefer -cp ... <fully.qualified.Main> as shown.


------------------------------------------------------------------------------------------------


Models:

- Embeddings: mxbai-embed-large

- Generator:  llama3.2:1b-instruct-q4\_K\_M

Ollama (for Q&A generation)

- Install Ollama: Windows: https://ollama.com/download

- macOS: brew install --cask ollama (or download app)

Start the Ollama service:

- Windows: Start Menu → Ollama (or ollama serve in a new terminal)

- macOS: ollama serve

Pull the two models used by default:

- `ollama pull mxbai-embed-large`
- `ollama pull llama3.2:1b-instruct-q4_K_M`



If models aren’t present, the pipeline still builds the index; Q&A will fail gracefully with a clear error.



# Disk & RAM

~200–500 MB free disk for the small corpus outputs.

4–8 GB RAM recommended.



## Repo layout (Default run relevant portions)
testCorpus/                     # 30 sample PDFs (bundled)
input/pdf_list2.txt             # list of those PDFs (relative paths)

conf/
  - local.conf                    # optional local override (uses bundled corpus, writes into repo)
  - big.example.conf              # template for large-corpus runs (copy → big.conf and edit)
  - big.conf                    # (user-specific; not committed)

src/main/resources/application.conf   # DEFAULTS used by `sbt run`:

                                      # - io.pdfListFile = input/pdf_list2.txt

                                      # - index.outRoot  = out_mr_out

                                      # - stats.outputDir= out/stats

                                      # - index.shards   = 1

out/                             # generated artifacts (gitignored)
out_mr_out/                      # Lucene index shards (gitignored)


The default application.conf points to input/pdf_list2.txt, writes outputs to out/ and out_mr_out/, and uses shards=1.

This ensures sbt clean compile run works for anyone who clones the repo—no flags needed.
---

------------------------------------------------------------
# BUILD & TEST / QUICK START COMMANDS
From the repo root:

- `sbt clean compile test`

- `sbt clean compile run`

What you should see:

What you’ll see during run:
- extraction + chunking logs for 30 PDFs
- a Hadoop “local” job that writes a Lucene index to out_mr_out/index_shard_0
- a retrieval of top-K chunks
- (If Ollama is running and models are pulled) a generated answer printed under " === ANSWER ==="

What you’ll see during tests:
 - a summary of the tests run and whether they passed or failed.

# To clean any stale outputs(previous runs or partial ones)
powershell: 
- `Remove-Item -Recurse -Force .\out_mr_out, .\out_mr_mr_out -ErrorAction SilentlyContinue`

macOS/Linux:
- `rm -rf out out_mr_out`


## Switching configs

You can override the default application.conf in two supported ways:

1) JVM system property (works for all mains)

- `sbt -J-Dconfig.file=conf/local.conf clean compile run`

2) Program Flag (assuming main supports it)
- `sbt "runMain edu.uic.msr.Driver --conf conf/local.conf"`


Big corpus runs

Copy the template and edit paths:

- conf/big.example.conf  →  conf/big.conf
# edit: io.pdfListFile, index.outRoot, index.shards, etc.

After doing so, simply run it with:
- `sbt -J-Dconfig.file=conf/big.conf clean compile run`



By default, big.conf is set for my own local usage of my large corpus but alterations make it functional for any corpus you might want to test it on.



### Map & Reduce (conceptual explanation)

Mapper (local mode):

- Read a PDF path

- Extract text with PDFBox

- Chunk (≈1000 chars, 200 overlap)

- Embed chunks with mxbai-embed-large (1024-d)

- Emit (shardId, JSON chunk record) where shardId = hash(path) % shards

Reducer:

- For each shardId, collect its JSON records

- Build a Lucene HNSW index (vector field + metadata)

- Write a shard directory: index_shard_<id> (must have segments_*)

- (Local mode) copy to out_mr_out/index_shard_<id>

AskLucene:

- Embed query

- Search all shards (k, perShardFetch)

- Check guards (minTopScore, minKeywordOverlap)

- Pack context → call Ollama generator → print answer + sources

 Further Design Choices

- Chunking: ~1000 chars, ~200 overlap (~10–25%)

- Embedding: mxbai-embed-large (1024-dim) for both index \& query

- Similarity: cosine (L2-normalized vectors)

- Index: Lucene HNSW vector field; 2 shards for MR + fan-out/fan-in queries

- Guardrails: refuse if topScore < 0.28 or keywordOverlap < 1; sanitize stray “\[n]” citations

- Generator: llama3.2:1b-instruct-q4_K_M, temperature = 0.0



------------------------------------------------------------
## Generate Statistics (csv files; by defualt uses the small repository corpus)
------------------------------------------------------------

Once again mentioning that stats is configured to run using the small corpus bundled into the repo.

With the default config, they use input/pdf_list2.txt and write to out/stats.

# Build vocabulary CSV
`sbt "runMain edu.uic.msr.stats.VocabExport"`

# Embed top vocab + neighbors
`sbt "runMain edu.uic.msr.stats.TokenEmbedAndNeighbors"`

# Evaluate similarity/analogies
`sbt "runMain edu.uic.msr.stats.EvalEmbeddings"`

Quick verification:
powershell:
 - `gc out\stats\similarity.csv -TotalCount 5`
-  `gc out\stats\analogy.csv -TotalCount 5`
macOS/Linux:
- `head -n 5 out/stats/similarity.csv`
- `head -n 5 out/stats/analogy.csv`


Default outputs:

out/stats/vocab.csv
out/stats/token_embeddings.csv
out/stats/neighbors.csv
out/stats/similarity.csv
out/stats/analogy.csv

Clean re-runs
-------------
If you want to regenerate CSVs from scratch:
- `rm -rf out/stats`
- `sbt "runMain edu.uic.msr.stats.VocabExport"`
- `sbt "runMain edu.uic.msr.stats.TokenEmbedAndNeighbors"`
- `sbt "runMain edu.uic.msr.stats.EvalEmbeddings"`



To use a big corpus for stats, pass the override:

PowerShell: 
- `sbt -J-Dconfig.file=conf/big.conf "runMain edu.uic.msr.stats.VocabExport"`

macOS/Linux: 
- `sbt -J-Dconfig.file=conf/big.conf "runMain edu.uic.msr.stats.VocabExport"`



------------------------------------------------------------

TESTS

Run:

`sbt clean compile test`

Included (7):

ChunkerSpec - chunking invariants (non-empty, maxChars, overlap)

MathSpec -cosine similarity properties

JsonSpec - mapper JSON record shape

PdfsSpec - PDF text extraction sanity checks

VocabExportSpec - vocab.csv shape and non-emptiness

EvalEmbeddingsSpec - similarity.csv / analogy.csv sanity (headers, row count > 0)

AskLuceneSpec (optional) - out-of-corpus refusal behavior


To run a single test:
- `sbt "testOnly *ChunkerSpec"`


======================================================================
README – EMR + LUCENE RAG WORKFLOW (PLAIN TEXT)
======================================================================

Purpose
-------
This document captures the exact AWS EMR workflow used to build Lucene
shards with MapReduce and then run a live RAG query using Ollama.
Everything below is plain text for easy copy/paste.

You will:
- verify Ollama on the EMR master
- pull the required models
- fetch your fat JAR from S3
- clean any previous outputs
- run the MR job with YARN
- copy the produced Lucene index_shard_* from S3 to local disk
- ask a question via the AskLucene main

Assumptions
-----------
- S3 bucket:               s3://cs-441-bucket
- Input list (on S3):      s3://cs-441-bucket/input/pdf_list_small.txt
- MR output root (on S3):  s3://cs-441-bucket/outputs/mr_partfiles_mr_out
- Fat JAR (on S3):         s3://cs-441-bucket/jars/msr-rag-hw1-assembly.jar
- EMR config file:         emr-dev.conf (present in the jar)
- MR main:                 edu.uic.msr.rag.JobMain
- Local Q&A main:          edu.uic.msr.rag.AskLucene
- Models:                  mxbai-embed-large  and  llama3.2:1b-instruct-q4_K_M
- Run commands on the EMR master as user "hadoop".

Java Requirement
----------------
Lucene 9.x requires Java 11 (classfile version 55).
Use this explicit Java (referenced below as JAVA11):

/usr/lib/jvm/java-11-amazon-corretto.x86_64/bin/java

Models
------
- Embedding: mxbai-embed-large
- Answer:    llama3.2:1b-instruct-q4_K_M
Ollama should be running on the master. Steps below will restart it if needed.



======================================================================
WHAT EACH STEP DOES
======================================================================
1) Start/verify Ollama
   Ensures the embedding and answer models can be served locally.

2) Pull models
   Idempotent: pulls only if missing.

3) Fetch JAR
   Copies the fat JAR to /tmp/app.jar on the master.

4) Clean outputs
   Avoids stale results in S3 or HDFS confusing new runs.

5) Run MapReduce on YARN
   Mappers: extract → chunk → embed → emit (shardId, JSON record).
   Reducers: build Lucene index locally, then copy each shard directory to:
             s3://cs-441-bucket/outputs/mr_partfiles_mr_out/index_shard_<N>

6) Copy Lucene shards to local disk
   Search is much faster from local disk than directly from S3.

7) Sanity check
   Each index_shard_* must contain at least: segments_N, _0.cfs, _0.cfe, _0.si.

8) Ask a question
   AskLucene embeds the query, searches all index_shard_* locally, and calls
   the answer model with retrieved context.


======================================================================
VIDEO CHECKLIST (WHAT TO SHOW)
======================================================================
- Intro with your name (camera briefly on)
- Show S3 inputs (pdf_list_small.txt)
- Launch MR job (yarn jar …) and watch it complete
- Show S3 outputs with index_shard_* produced
- Copy shards locally; list segment files
- Run AskLucene; show retrieved chunks and final answer
- Bonus: ask an out-of-corpus question and show safe refusal/low-confidence behavior



# AWS Cluster Usage Steps:


1)   sudo systemctl is-active --quiet ollama || sudo systemctl restart ollama
curl -sf http://127.0.0.1:11434/api/tags | head || echo "Ollama not responding"

2)  ollama list | grep -q 'mxbai-embed-large' || ollama pull mxbai-embed-large
ollama list | grep -q 'llama3.2:1b-instruct-q4_K_M' || ollama pull llama3.2:1b-instruct-q4_K_M

3)) aws s3 cp s3://cs-441-bucket/jars/msr-rag-hw1-assembly.jar /tmp/app.jar

4) hadoop fs -rm -r -f s3a://cs-441-bucket/outputs/mr_partfiles_mr_out || true

# Lucene shard target on HDFS (what your reducer writes to)
hdfs dfs -rm -r -f /msr/index_shards_mr_out || true

5) yarn jar /tmp/app.jar edu.uic.msr.rag.JobMain --conf emr-dev.conf --mode yarn

6) mkdir -p /mnt/tmp/index_shards_mr_out
aws s3 cp --recursive \
  s3://cs-441-bucket/outputs/mr_partfiles_mr_out/ \
  /mnt/tmp/index_shards_mr_out/ \
  --exclude "" --include "indexshard/*"

7) /usr/lib/jvm/java-11-amazon-corretto.x86_64/bin/java \
  -Dindex.outRoot=/mnt/tmp/index_shards_mr_out \
  -Dindex.shards=2 \
  -Dembed.model=mxbai-embed-large \
  -Danswer.model=llama3.2:1b-instruct-q4_K_M \
  -cp /tmp/app.jar edu.uic.msr.rag.AskLucene \
  "what does section 3 say about inconsistency?"