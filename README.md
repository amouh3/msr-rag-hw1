\# CS441 HW 1 - RAG MSR Corpus (Lucene + Ollama)



Name: Ali Mouhtadi

Email: amouh3@uic.edu  


## Demo Video on AWS Deployment and Execution: 
- https://www.youtube.com/watch?v=ipGOIyptAg8

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

The repo is self-contained for the default run: it uses a small 30-PDF corpus and a relative list file so users don’t need extra data.


Further information regarding my selected configuration parameters, limitations, and detail on my findings are attached near the bottom of this readme. Additional information beyond this can be found in the comments of my code.

CSV outputs are by default attached in the repo but rerunning the commands will update them. Details are provided below.

/src/main/scala contain the relevant functions.

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

## TESTS

Run:

`sbt clean compile test`

Included (7):

ChunkerSpec - chunking invariants (non-empty, maxChars, overlap)

MathSpec -cosine similarity properties

JsonSpec - mapper JSON record shape

PdfsSpec - PDF text extraction sanity checks

VocabExportSpec - vocab.csv shape and non-emptiness

EvalEmbeddingsSpec - similarity.csv / analogy.csv sanity (headers, row count > 0)



To run a single test:
- `sbt "testOnly *ChunkerSpec"`

#Logging & noise


Logback is preconfigured. Change levels in src/main/resources/logback.xml (e.g., set edu.uic.msr to INFO).





### Map & Reduce (conceptual explanation)

# How I partitioned the data
I use a stable hash partitioner so each document’s chunks always go to the same reducer:

Rule: shardId = abs(hash(doc_id)) % shards
- where doc_id is the PDF filename (or path), and shards is index.shards from the config.

Why:

- Balanced: uniform-ish spread across reducers without a global shuffle of all chunks.

- Stable: re-runs send a doc to the same shard (unless shards changes).

- Cheap to compute: no metadata service required.

- Mapper effect: all chunks from a given doc_id emit with the same shardId.

- Reducer effect: each reducer builds one Lucene directory: index_shard_<shardId>.

Edge cases: highly uneven docs can cause minor skew; for this HW , the scale is acceptable.
If needed, shard on (doc_id, chunk_idRange) or increase shards.


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


## Further Info on my program deisgn/models and how I assembled and deployed my components


# General 

The experiments conducted in this project aim to evaluate the performance and behavior of my distributed retrieval pipeline implemented using Hadoop MapReduce and Lucene. The system’s overall purpose is to process a collection of PDF documents, tokenize and embed their contents, and generate shard-level Lucene indexes that can later be queried through  AskLucene's interface. The final outputs(the CSV reports vocab.csv, similarity.csv, and analogy.csv) reflect the internal word statistics, embedding relationships, and analogy performance computed across the corpus.

My configuration was designed to prioritize simplicity and scalability rather than raw performance, which aligns with the educational intent of this assignment. I prioritized understanding the flow and modularity of the MapReduce jobs over fine-tuning the parallelism parameters.


The experiments were executed on both local and AWS EMR cluster environments, with configuration parameters  adjusted to fit each as best as I could make them.

Mode and Input/Output Directories

In conf/mr.local.conf, I specified:

- `mr.inputList = "s3a://cs-441-bucket/input/pdf_list.txt"`
- `mr.outputDir = "s3a://cs-441-bucket/outputs/mr_partfiles_mr_out"`


This configuration allowed the system to read a list of PDF files stored in S3 and write all intermediate and final outputs back to S3. It was essential for distributed execution, ensuring that both mappers and reducers could access the data without local disk dependencies.


# Parallelism Parameters

I configured the job to launch multiple mapper tasks, one per shard of the corpus, using Hadoop’s built-in partitioning. Each mapper handled a subset of PDFs, extracted text, and produced partial Lucene indexes.

Reducers were configured to merge index shards and generate unified statistics. Because my corpus was relatively small (10 PDFs for AWS testing and 30 PDFS for the bulk of my local testing), I chose a modest reducer count (1-2) to avoid unnecessary overhead. For larger corpora, more reducers would improve throughput.



# Ollama Embedding Configuration

The embedding model mxbai-embed-large was pulled and served through a running Ollama service on port 11434.

I verified connectivity before job submission using:

`curl -sf http://127.0.0.1:11434/api/tags | head`


This ensured the mappers could access embeddings consistently across nodes. Although Ollama was not distributed in this setup, it acted as a fixed remote endpoint, demonstrating how embedding generation could integrate into a larger pipeline.


# File Setup

pdf_list.txt defined the corpus file paths.

Each output directory (e.g., out_mr_out/index_shard_*) corresponded to a Lucene index built by a mapper.

The part-r-00000 and part-r-00001 files stored final reducer outputs, ensuring Hadoop’s standard structure was preserved for further analysis.


# Results
After execution, the resulting CSVs contained both detailed quantitative and qualitative insights:

vocab.csv recorded token frequencies across the corpus, confirming successful text extraction and normalization.

similarity.csv contained pairwise cosine similarities between selected word embeddings, reflecting semantic coherence within the limited dataset.

analogy.csv tested vector arithmetic as described. Although the corpus was small, the analogy behavior did align with what was expected. Words in semantically related contexts showed distinct directional patterns.

The results validated that the end-to-end pipeline, from document input to embedding evaluation, functioned as intended. Even though limited data constrained the richness of relationships, the structural correctness of the output confirmed that my mappers and reducers were  properly integratd.

# Design
The Mapper component read each PDF path, extracted its text using Apache PDFBox, split it into tokenized sentences, and requested embeddings from Ollama. Each mapper emitted key–value pairs where the key was the shard identifier and the value was the serialized Lucene document or embedding data.

The Reducer merged the intermediate results, building Lucene indexes and aggregating word statistics. It handled shard consolidation, vocabulary counting, and vector computations necessary for similarity and analogy evaluation.


## Screenshots on AWS Deployment and Execution(detailed steps are below): 
<img width="1093" height="874" alt="shdfg" src="https://github.com/user-attachments/assets/252c17d1-1e42-46a6-a843-b81bc7d0800c" />
<img width="1157" height="487" alt="dkcjty" src="https://github.com/user-attachments/assets/cd77bc30-9e5f-4470-ac8a-d39ceb3824b4" />
<img width="843" height="455" alt="jxdyftr" src="https://github.com/user-attachments/assets/5d7308a5-8136-42e4-8679-0a48afdb0454" />
<img width="945" height="627" alt="ofo" src="https://github.com/user-attachments/assets/978f723d-75a8-4c1e-ae35-4d49c8d14482" />


# AWS Deployment
Deployment on EMR mimics the following sequence of steps. For a more detailed and comprehensive guide, watch the attached video above:

- Upload corpus PDFs and configuration files to S3.

- Launch EMR cluster (cpu of your choosing) with Hadoop 3.4.1.

- SSH into the primary node via PuTTY to verify Ollama and environment readiness.

- Confirm and pull required llama models

- Pull the app JAR onto the node with aws s3 cp s3://<yourbucket>/jars/msr-rag-hw1-assembly.jar /tmp/app.jar

- Execute the MapReduce job via:

yarn jar /tmp/app.jar edu.uic.msr.rag.JobMain --conf emr-dev.conf --mode yarn

- Stage the shards locally so AskLucene can read them(it expects a local directory)
mkdir -p /mnt/tmp/index_shards_mr_out
aws s3 cp --recursive \
  s3://cs-441-bucket/outputs/mr_partfiles_mr_out/ \
  /mnt/tmp/index_shards_mr_out/ \
  --exclude "" --include "indexshard/*"

- Query AsklLucene again against the local shards(will embed, retrieve, answer)
/usr/lib/jvm/java-11-amazon-corretto.x86_64/bin/java \
  -Dindex.outRoot=/mnt/tmp/index_shards_mr_out \
  -Dindex.shards=2 \
  -Dembed.model=mxbai-embed-large \
  -Danswer.model=llama3.2:1b-instruct-q4_K_M \
  -cp /tmp/app.jar edu.uic.msr.rag.AskLucene \
  "what does section 3 say about inconsistency?"

- Validate successful job completion and view logs for further information.

This design highlights modular separation between text processing, embedding generation, and statistical evaluation. Each phase was encapsulated and is possible of scaling independently on larger clusters.


# Limitations of the Implementation

- Corpus Size and Embedding Generality
My corpus on AWS contained only about 10 PDFs, significantly limiting the diversity of tokens and semantic relationships. As a result, analogy and similarity metrics were less representative of general linguistic behavior.

- Reducer Scalability
While sufficient for demonstration, my reducer design performs global merges sequentially. A larger dataset with many shards could lead to a performance bottleneck during final aggregation.

-Retry Logic
Although Hadoop inherently supports task retries, my current mapper implementation does not include fine-grained retry logic for failed embedding requests (e.g., HTTP timeouts). This would be important for robustness in real deployments.

-Limited parameter Tuning
The pipeline currently relies on default Lucene and embedding parameters as tokenization thresholds, vector dimensionality, and index merge factors were not tuned. Optimizing these could yield more accurate similarity and analogy results.

-S3 I/O Overhead
Reading and writing directly to S3 simplified deployment but introduced latency. Local HDFS storage on EMR nodes would likely improve performance for larger workloads.

# Conclusion

In summary, my system successfully demonstrated an end-to-end distributed text processing pipeline, capable of extracting, embedding, indexing, and evaluating a corpus through Hadoop MapReduce. While limited by dataset size, embedding centralization, and basic configuration choices, the implementation correctly illustrated the architecture of a scalable retrieval-augmented generation (RAG) system.

The experiments emphasize how parameter tuning, distributed embedding generation, and larger corpora would meaningfully extend this framework’s realism and performance in a production-grade cloud environment.













