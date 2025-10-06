\# CS441 — RAG over the MSR Corpus (Lucene + Ollama)



Author: YOUR NAME (exact as roster)  

Email: your\_uic\_email@uic.edu  

Demo Video: <YouTube link>



Retrieval-Augmented Generation (RAG) over the MSR PDF corpus. Extract → chunk → embed → index with Lucene HNSW, then answer questions using only retrieved MSR context via Ollama. The LLM is not fine-tuned; answers are guardrailed to the corpus (out-of-corpus ⇒ refusal).



------------------------------------------------------------

REQUIREMENTS

------------------------------------------------------------

\- JDK 11–21 (tested on 11)

\- sbt 1.9+ (tested on 1.11.6)

\- Ollama running at http://127.0.0.1:11434

\- Windows 10/11 (tested) — Linux/macOS OK



Models:

\- Embeddings: mxbai-embed-large

\- Generator:  llama3.2:1b-instruct-q4\_K\_M



Pull:

&nbsp;   ollama pull mxbai-embed-large

&nbsp;   ollama pull llama3.2:1b-instruct-q4\_K\_M



------------------------------------------------------------

PROJECT LAYOUT (selected)

------------------------------------------------------------

src/

&nbsp; main/scala/edu/uic/msr/pdf/Pdfs.scala                 # PDF text extraction

&nbsp; main/scala/edu/uic/msr/chunk/Chunker.scala            # windowed chunking w/ overlap

&nbsp; main/scala/edu/uic/msr/ollama/Ollama\*.scala           # embeddings/chat clients

&nbsp; main/scala/edu/uic/msr/rag/JobMain.scala              # Map/Reduce builder for Lucene shards

&nbsp; main/scala/edu/uic/msr/rag/AskLucene.scala            # RAG Q\&A + guardrails + citations

&nbsp; main/scala/edu/uic/msr/rag/LuceneSearchSmoke.scala    # quick kNN smoke

&nbsp; main/scala/edu/uic/msr/stats/\*.scala                  # vocab, neighbors, evals

src/test/scala/...                                      # Scalatest suite



conf/local.conf                                         # MR + stats mains (config below)

src/main/resources/application.conf                     # AskLucene defaults (config below)



------------------------------------------------------------

DATA PREPARATION (make a list of absolute PDF paths)

------------------------------------------------------------

PowerShell:

&nbsp;   Get-ChildItem "E:\\msr\_data\\MSRCorpus" -Filter \*.pdf | % { $\_.FullName } |

&nbsp;     Out-File -Encoding UTF8 C:\\tmp\\pdf\_list.txt

&nbsp;   Copy-Item C:\\tmp\\pdf\_list.txt E:\\msr\_data\\msr\_pdfs.txt



------------------------------------------------------------

CONF: conf/local.conf  (used by MR + stats mains)

------------------------------------------------------------

\# paste EXACTLY this file content into conf/local.conf

app { job = "extract-chunk-smoke" }



io {

&nbsp; pdfListFile = "E:/msr\_data/msr\_pdfs.txt"

&nbsp; workDir     = "E:/msr\_data/artifacts"

}



embed {

&nbsp; model    = "mxbai-embed-large"

&nbsp; maxChars = 1000

&nbsp; overlap  = 200

&nbsp; batch    = 8

}



index {

&nbsp; out     = "E:/msr\_data/artifacts/index.csv"

&nbsp; maxDocs = 999999

}



search {

&nbsp; query = "data loss bugs Android"

&nbsp; k = 10

}



mr {

&nbsp; inputList = "file:///C:/tmp/pdf\_list.txt"

&nbsp; outputDir = "file:///C:/tmp/index\_shards"

&nbsp; shards    = 2

}



\# Reducer copies final shard dirs here

msr.output.dir = "file:///C:/tmp/index\_shards\_mr\_out"



\# Stats output

stats.outputDir = "C:/tmp/stats"

stats.topVocab  = 5000

stats.kNN       = 10



------------------------------------------------------------

CONF: src/main/resources/application.conf  (AskLucene)

------------------------------------------------------------

\# paste EXACTLY this file content into src/main/resources/application.conf

embed.model  = "mxbai-embed-large"

answer.model = "llama3.2:1b-instruct-q4\_K\_M"



index.outRoot = "C:/tmp/index\_shards\_mr\_out"

index.shards  = 2



ask {

&nbsp; k                 = 4

&nbsp; perShardFetch     = 10

&nbsp; minTopScore       = 0.28

&nbsp; minKeywordOverlap = 1

&nbsp; temperature       = 0.0

&nbsp; numCtxTokens      = 2048

&nbsp; refusal           = "I don't know based on the MSR corpus."

}



------------------------------------------------------------

BUILD \& RUN (LOCAL)

------------------------------------------------------------

1\) Build Lucene shards (Map/Reduce; Hadoop LocalJobRunner)

&nbsp;   sbt "runMain edu.uic.msr.rag.JobMain"

&nbsp;  Output: C:/tmp/index\_shards\_mr\_out/index\_shard\_0, index\_shard\_1

&nbsp;  If you see “Output directory … already exists”, delete C:/tmp/index\_shards\_mr\_out or change path in conf/local.conf.



2\) Smoke test retrieval

&nbsp;   sbt "runMain edu.uic.msr.rag.LuceneSearchSmoke android permissions"



3\) Ask a question (strict RAG)

&nbsp;   sbt "runMain edu.uic.msr.rag.AskLucene What do MSR papers report about Android permission misuse?"

&nbsp;   sbt "runMain edu.uic.msr.rag.AskLucene What is Fortnite?"

&nbsp;  In-corpus ⇒ answer + Sources like MSR.2019.00090.pdf#3

&nbsp;  Out-of-corpus ⇒ “I don't know based on the MSR corpus.”



4\) Generate stats artifacts

&nbsp;   sbt "runMain edu.uic.msr.stats.VocabExport --conf conf/local.conf"

&nbsp;   sbt "runMain edu.uic.msr.stats.TokenEmbedAndNeighbors --conf conf/local.conf"

&nbsp;   sbt "runMain edu.uic.msr.stats.EvalEmbeddings --conf conf/local.conf"

&nbsp;  Outputs in C:\\tmp\\stats:

&nbsp;    - vocab.csv                (vocabulary + counts)

&nbsp;    - token\_embeddings.csv     (token → vector)

&nbsp;    - neighbors.csv            (kNN per token; cosine)

&nbsp;    - similarity.csv, analogy.csv (simple embedding evals)



------------------------------------------------------------

TESTS

------------------------------------------------------------

Run:

&nbsp;   sbt test

Included (≥5):

&nbsp;   ChunkerSpec  (non-empty chunks, max length, overlap)

&nbsp;   MathSpec     (cosine correctness)

&nbsp;   JsonSpec     (mapper JSON shape)

&nbsp;   PdfsSpec     (PDF text extraction)

Optional:

&nbsp;   AskLuceneSpec (assert out-of-corpus refusal)



------------------------------------------------------------

DESIGN CHOICES

------------------------------------------------------------

\- Chunking: ~1000 chars, ~200 overlap (~10–25%)

\- Embedding: mxbai-embed-large (1024-dim) for both index \& query

\- Similarity: cosine (L2-normalized vectors)

\- Index: Lucene HNSW vector field; 2 shards for MR + fan-out/fan-in queries

\- Guardrails: refuse if topScore < 0.28 or keywordOverlap < 1; sanitize stray “\[n]” citations

\- Generator: llama3.2:1b-instruct-q4\_K\_M, temperature = 0.0


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
PER-CLUSTER QUICK START (RUN THESE EACH TIME YOU SPIN UP A NEW CLUSTER)
======================================================================

# 0) confirm user
whoami

# 1) ensure Ollama is up (restart if not) and list tags
sudo systemctl is-active --quiet ollama || sudo systemctl restart ollama
curl -sf http://127.0.0.1:11434/api/tags | head || echo "Ollama not responding"

# 2) pull models on-demand (no-op if already present)
ollama list | grep -q 'mxbai-embed-large' || ollama pull mxbai-embed-large
ollama list | grep -q 'llama3.2:1b-instruct-q4_K_M' || ollama pull llama3.2:1b-instruct-q4_K_M

# 3) fetch your fat JAR from S3 to the master
aws s3 cp s3://cs-441-bucket/jars/msr-rag-hw1-assembly.jar /tmp/app.jar

# 4) clear previous outputs (safe if not present)
#    a) MR part-files on S3
hadoop fs -rm -r -f s3a://cs-441-bucket/outputs/mr_partfiles_mr_out || true
#    b) HDFS mirror target (not used by current run, but safe to clear)
hdfs dfs -rm -r -f /msr/index_shards_mr_out || true

# 5) run the MapReduce job on YARN (uses emr-dev.conf)
yarn jar /tmp/app.jar edu.uic.msr.rag.JobMain --conf emr-dev.conf --mode yarn
# Wait for: map 100% reduce 100% and "completed successfully"

# 6) copy Lucene shards from S3 to local NVMe/EBS for fast search
mkdir -p /tmp/index_shards_mr_out
aws s3 cp --recursive \
  s3://cs-441-bucket/outputs/mr_partfiles_mr_out/ \
  /tmp/index_shards_mr_out/ \
  --exclude "*" --include "index_shard_*/*"

# 7) sanity check: segments_* exist in both shards
ls -l /tmp/index_shards_mr_out/index_shard_0
ls -l /tmp/index_shards_mr_out/index_shard_1

# 8) ask a question (use Java 11 explicitly)
JAVA11=/usr/lib/jvm/java-11-amazon-corretto.x86_64/bin/java
$JAVA11 \
  -Dindex.outRoot=/tmp/index_shards_mr_out \
  -Dindex.shards=2 \
  -Dembed.model=mxbai-embed-large \
  -Danswer.model=llama3.2:1b-instruct-q4_K_M \
  -cp /tmp/app.jar edu.uic.msr.rag.AskLucene \
  "what does section 3 say about inconsistency?"


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
OPTIONAL: LUCENE SMOKE (NON-MR MAIN)
======================================================================
Quick shard probe using the smoke main (also Java 11):

JAVA11=/usr/lib/jvm/java-11-amazon-corretto.x86_64/bin/java
$JAVA11 -cp /tmp/app.jar edu.uic.msr.rag.LuceneSearchSmoke \
  --model mxbai-embed-large \
  --indexRoot /tmp/index_shards_mr_out \
  "what does section 3 say about inconsistency?"


======================================================================
TROUBLESHOOTING
======================================================================
Ollama not responding
- sudo systemctl restart ollama
- curl -sf http://127.0.0.1:11434/api/tags | head
- If remote host/port: export OLLAMA_HOST=http://HOST:PORT

“model not found”
- ollama pull mxbai-embed-large
- ollama pull llama3.2:1b-instruct-q4_K_M

Java UnsupportedClassVersionError (class file version 55.0)
- You ran with Java 8. Use Java 11 explicitly:
  /usr/lib/jvm/java-11-amazon-corretto.x86_64/bin/java

AskLucene: IndexNotFoundException (no segments* file)
- Local shard folder empty or wrong. Re-copy:
  mkdir -p /tmp/index_shards_mr_out
  aws s3 cp --recursive \
    s3://cs-441-bucket/outputs/mr_partfiles_mr_out/ \
    /tmp/index_shards_mr_out/ \
    --exclude "*" --include "index_shard_*/*"
- Then ls -l the shard directories to ensure segments_* exist.

YARN job succeeded but local shards missing
- Reducers write to S3. You must copy index_shard_* down locally (Step 6).

EMR clone failed due to EC2 vCPU quota on c5.2xlarge
- Use a smaller instance family/size or request a vCPU quota increase.

Slow query or timeouts
- Ensure shards are local (/tmp/index_shards_mr_out), not S3 paths.
- Keep shard count and corpus size modest on CPU-only nodes.


======================================================================
RE-RUN CHECKLIST
======================================================================
1) SSH to EMR master as “hadoop”
2) Ensure Ollama and models:
   sudo systemctl is-active --quiet ollama || sudo systemctl restart ollama
   curl -sf http://127.0.0.1:11434/api/tags | head
   ollama list | grep -q 'mxbai-embed-large' || ollama pull mxbai-embed-large
   ollama list | grep -q 'llama3.2:1b-instruct-q4_K_M' || ollama pull llama3.2:1b-instruct-q4_K_M
3) Update JAR:
   aws s3 cp s3://cs-441-bucket/jars/msr-rag-hw1-assembly.jar /tmp/app.jar
4) Clean old outputs:
   hadoop fs -rm -r -f s3a://cs-441-bucket/outputs/mr_partfiles_mr_out || true
   hdfs dfs -rm -r -f /msr/index_shards_mr_out || true
5) Run job:
   yarn jar /tmp/app.jar edu.uic.msr.rag.JobMain --conf emr-dev.conf --mode yarn
6) Copy shards local:
   mkdir -p /tmp/index_shards_mr_out
   aws s3 cp --recursive \
     s3://cs-441-bucket/outputs/mr_partfiles_mr_out/ \
     /tmp/index_shards_mr_out/ \
     --exclude "*" --include "index_shard_*/*"
7) Ask:
   JAVA11=/usr/lib/jvm/java-11-amazon-corretto.x86_64/bin/java
   $JAVA11 -Dindex.outRoot=/tmp/index_shards_mr_out -Dindex.shards=2 \
     -Dembed.model=mxbai-embed-large -Danswer.model=llama3.2:1b-instruct-q4_K_M \
     -cp /tmp/app.jar edu.uic.msr.rag.AskLucene \
     "what does section 3 say about inconsistency?"


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




======================================================================
README – CSV ARTIFACTS (PLAIN TEXT)
======================================================================

Purpose
-------
This document explains how to generate all CSV artifacts produced by the project:
vocab.csv, token_embeddings.csv, neighbors.csv, similarity.csv, and analogy.csv.
It also includes optional smoke checks and verification tips. Everything below is
plain text for easy copy/paste.

Prerequisites
-------------
- Java 11 or newer (Java 11 recommended)
- sbt installed and on PATH
- Ollama running locally (only needed for embedding steps)
- A text file containing absolute PDF paths, one per line (example: conf/pdf_list.txt)

Environment checks (Ollama)
---------------------------
If Ollama is not already running or the model is missing:
  curl -sf http://127.0.0.1:11434/api/tags | head
  ollama list | grep -q mxbai-embed-large || ollama pull mxbai-embed-large

If running Ollama on another host/port:
  export OLLAMA_HOST=http://HOST:PORT


Configuration
-------------
Create a HOCON file (example: conf/local.conf). Adjust paths for your machine.

io {
  pdfListFile = "PATH/TO/conf/pdf_list.txt"
  workDir     = "tmp/work"
}

embed {
  model    = "mxbai-embed-large"
  batch    = 64
  maxChars = 800
  overlap  = 160
}

stats {
  outputDir = "tmp/stats"
  topVocab  = 5000
  kNN       = 10
}

Notes:
- io.pdfListFile must point to a text file with absolute paths to PDFs (one per line).
- stats.outputDir is where all CSVs in Steps 1–3 will be written.
- Paths with backslashes (Windows) may require escaping; prefer absolute POSIX-style paths
  when possible.


STEP 1) Build vocabulary (vocab.csv)
------------------------------------
Description:
Extract text from PDFs, chunk, tokenize, count frequencies, and write vocab.csv.

Command:
  sbt "runMain edu.uic.msr.stats.VocabExport --conf conf/local.conf"

Output (in stats.outputDir):
- vocab.csv
  Header: token,token_id,freq

Quick verification:
  head -n 5 tmp/stats/vocab.csv
  wc -l tmp/stats/vocab.csv      (should be > 1)

OR
gc C:\tmp\stats\vocab.csv 
 (Get-Content C:\tmp\stats\vocab.csv | Measure-Object -Line).Lines


STEP 2) Embed tokens and compute neighbors (token_embeddings.csv, neighbors.csv)
--------------------------------------------------------------------------------
Description:
Read the first N tokens from vocab.csv (N = stats.topVocab), embed with Ollama,
write token vectors, and brute-force k nearest neighbors.

Ensure Ollama model exists:
  ollama list | grep -q mxbai-embed-large || ollama pull mxbai-embed-large

Command:
  sbt "runMain edu.uic.msr.stats.TokenEmbedAndNeighbors --conf conf/local.conf"

Outputs (in stats.outputDir):
- token_embeddings.csv   (header: token,d0,d1,...,d{dim-1}; rows are L2-normalized)
- neighbors.csv          (header: token,neighbor,cosine)

Quick verification:
  gc tmp\stats\token_embeddings.csv -TotalCount 3
  gc tmp\stats\neighbors.csv -TotalCount 5

Health checks:
- token_embeddings.csv should have at least 2 columns (token + d0)
- All rows should have the same number of dimensions
- neighbors.csv should list kNN rows per token (k = stats.kNN)


STEP 3) Evaluate similarity and analogies (similarity.csv, analogy.csv)
-----------------------------------------------------------------------
Description:
Load token_embeddings.csv and write simple evaluation CSVs for word similarity
and word analogies.

Command:
  sbt "runMain edu.uic.msr.stats.EvalEmbeddings --conf conf/local.conf"

Outputs (in stats.outputDir):
- similarity.csv   (header: w1,w2,cosine)
- analogy.csv      (header: a,b,c,predicted,cosine)

Quick verification:
 gc tmp\stats\similarity.csv -TotalCount 5
 gc tmp\stats\analogy.csv -TotalCount 5


Optional smoke checks
---------------------
A) Extract and chunk counts (no Ollama required). Writes a tiny CSV under io.workDir:
  sbt "runMain edu.uic.msr.Driver --conf conf/local.conf"
Output:
  tmp/work/chunk_counts.csv

B) Embed a few chunks from the first PDF to verify Ollama access:
  sbt "runMain edu.uic.msr.EmbedSmoke --conf conf/local.conf"
Expected:
- Logs showing vectors count, dimension, elapsed time
- A line like: OK: vectors=... dim=... first5=[...]


Expected file layout (example)
------------------------------
conf/
  local.conf
  pdf_list.txt              (absolute PDF paths)

tmp/
  work/
    chunk_counts.csv
  stats/
    vocab.csv
    token_embeddings.csv
    neighbors.csv
    similarity.csv
    analogy.csv


------------------------------------------------------------
TROUBLESHOOTING
------------------------------------------------------------

- Port in use / multiple ollama: ensure single server on 127.0.0.1:11434
- "model not found": ollama pull <model>
- token_embeddings.csv empty or dimension is 0:
    * Ensure mxbai-embed-large is pulled and responding.
    * Confirm stats.topVocab > 0 and tmp/stats/vocab.csv exists and is non-empty.
- vocab.csv very small:
    * io.pdfListFile might be wrong or PDFs have no extractable text.
    * Image-only PDFs require OCR (not covered).
- Inconsistent dimensionality in token_embeddings.csv:
    * Remove partially written files and rerun Step 2.
    * Ensure the same model is used across the entire run.
- Memory pressure during Step 2 (large topVocab):
    * Lower stats.topVocab (e.g., 2000) and rerun.
    * Increase JVM heap via SBT_OPTS:
        export SBT_OPTS="-Xmx4g"


Clean re-runs
-------------
If you want to regenerate CSVs from scratch:
  rm -rf tmp/stats
  mkdir -p tmp/stats
  sbt "runMain edu.uic.msr.stats.VocabExport --conf conf/local.conf"
  sbt "runMain edu.uic.msr.stats.TokenEmbedAndNeighbors --conf conf/local.conf"
  sbt "runMain edu.uic.msr.stats.EvalEmbeddings --conf conf/local.conf"


FAQ
---
Q: Do I need Ollama for Step 1?
A: No. Step 1 only extracts/chunks/tokenizes/counts.

Q: Where are the CSVs written?
A: To stats.outputDir in your conf (default example: tmp/stats).

Q: Can I point to a subset of PDFs?
A: Yes — place only those absolute paths in conf/pdf_list.txt.

Q: What if I change the embedding model?
A: Re-run Step 2 (and Step 3). Embedding vectors differ across models.


======================================================================
 BUILD, TEST, AND RUN PORTION
======================================================================

PURPOSE
This document explains how to build, test, and run the project from the command line
using sbt. It also clarifies what “run” executes, how to switch to other mains,
and a few platform-specific tips (Windows/macOS/Linux).

PREREQUISITES
- Java 11 or newer (Java 11 recommended)
- sbt installed and on PATH
- (Optional) Ollama running locally if you plan to embed text:
  curl -sf http://127.0.0.1:11434/api/tags
  ollama list | grep -q mxbai-embed-large || ollama pull mxbai-embed-large

BUILD
sbt clean compile
This compiles all sources under src/main/scala and prepares them for running.

TEST
sbt clean compile test
This compiles both main and test sources and runs the ScalaTest suite. The test
suite includes:
- ChunkerSpec   (chunking invariants)
- JsonSpec      (mapper JSON shape)
- MathSpec      (cosine properties)
- PdfsSpec      (simple PDF extraction)
- VocabExportSpec (vocabulary CSV shape)
- EvalEmbeddingsSpec (sanity checks for similarity/analogy CSVs)

If a test fails on Windows due to temporary-path parsing (HOCON), ensure paths are
quoted or use forward slashes. The test suite in this repo already accounts for this.

DEFAULT “RUN”
sbt clean compile run
By default, “run” invokes the simple smoke-driver:
- edu.uic.msr.Driver
This is an extraction + chunk-count smoke test (no EMR needed).
It prints a small report and writes:
  <io.workDir>/chunk_counts.csv
where io.workDir is configured in your conf (see conf/local.conf example).
Use this to verify your toolchain without requiring a full cluster.

RUNNING OTHER MAINS
Use runMain to call a specific entry point:

1) Local CSV artifacts (no EMR required)
   a) Build vocabulary (vocab.csv)
      sbt "runMain edu.uic.msr.stats.VocabExport --conf conf/local.conf"

   b) Embed tokens and compute neighbors (token_embeddings.csv, neighbors.csv)
      sbt "runMain edu.uic.msr.stats.TokenEmbedAndNeighbors --conf conf/local.conf"

   c) Evaluate similarity & analogies (similarity.csv, analogy.csv)
      sbt "runMain edu.uic.msr.stats.EvalEmbeddings --conf conf/local.conf"

   d) Optional quick embedding smoke
      sbt "runMain edu.uic.msr.EmbedSmoke --conf conf/local.conf"

2) Lucene Ask (local, requires shards already built & copied locally)
   Example (Java 11 binary shown explicitly; you may also use sbt runMain):
   /usr/lib/jvm/java-11-amazon-corretto.x86_64/bin/java \
     -Dindex.outRoot=/tmp/index_shards_mr_out \
     -Dindex.shards=2 \
     -Dembed.model=mxbai-embed-large \
     -Danswer.model=llama3.2:1b-instruct-q4_K_M \
     -cp target/scala-3.5.1/msr-rag-hw1_3-0.1.0-SNAPSHOT.jar edu.uic.msr.rag.AskLucene \
     "what does section 3 say about inconsistency?"

3) EMR Map/Reduce (cluster)
   You do NOT run the MR job with plain “sbt run” on your laptop. Instead, on EMR:
   yarn jar /path/to/app.jar edu.uic.msr.rag.JobMain --conf emr-dev.conf --mode yarn
   (See your cluster README for the exact sequence of AWS commands and model checks.)

CONFIGURATION
We use Typesafe Config (HOCON). A minimal example conf/local.conf:

io {
  pdfListFile = "PATH/TO/conf/pdf_list.txt"  # absolute PDF paths, one per line
  workDir     = "tmp/work"
}
embed {
  model    = "mxbai-embed-large"
  batch    = 64
  maxChars = 800
  overlap  = 160
}
stats {
  outputDir = "tmp/stats"
  topVocab  = 5000
  kNN       = 10
}

- On Windows, prefer forward slashes in HOCON values or quote paths: "C:/abs/path".
- For Ollama on a non-default host/port:
  set OLLAMA_HOST=http://127.0.0.1:11434   (PowerShell: $env:OLLAMA_HOST="http://...")

WHAT “RUN” IS (AND ISN’T)
- “run” is a local smoke check (Driver) to satisfy “sbt clean compile run” requirement.
- It does NOT require EMR/YARN.
- It does NOT fetch embeddings or call the LLM; it only extracts/chunks and writes a tiny CSV.

PLATFORM NOTES
Windows (PowerShell) lacks head/wc. Use:
Get-Content tmp\stats\vocab.csv -TotalCount 5
(Get-Content tmp\stats\vocab.csv).Count

Linux/macOS:
head -n 5 tmp/stats/vocab.csv
wc -l tmp/stats/vocab.csv

TROUBLESHOOTING
- Classpath issues: run “sbt clean” and retry.
- Config parse errors on Windows: ensure paths in .conf are quoted or use forward slashes.
- Ollama errors: confirm the model is pulled; export OLLAMA_HOST if not localhost.
- YARN errors locally: “JobMain” is for cluster; use “Driver” locally.

DONE
If “sbt clean compile test” succeeds and “sbt clean compile run” produces chunk_counts.csv,
your local toolchain is good. Use runMain targets above for CSV artifacts or cluster jobs.


------------------------------------------------------------
LICENSE
------------------------------------------------------------
MIT (or your chosen license)
