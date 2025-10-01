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



------------------------------------------------------------

AWS / EMR (DEPLOYMENT DEMO)

------------------------------------------------------------

1\) Build fat JAR

&nbsp;   sbt assembly

&nbsp;  Artifact: target/scala-3.5.1/msr-rag-hw1-assembly-0.1.0.jar



2\) Upload inputs to S3

&nbsp;   aws s3 cp E:\\msr\_data\\msr\_pdfs.txt s3://<bucket>/msr/msr\_pdfs.txt



3\) EMR cluster (EMR 6.x; 1 master + 2 core). Add Step (Custom JAR):

&nbsp;  - JAR: s3://<bucket>/path/msr-rag-hw1-assembly-0.1.0.jar

&nbsp;  - Main class: edu.uic.msr.rag.JobMain

&nbsp;  - Configure mr.inputList / mr.outputDir to S3 in conf or via args.



4\) Verify index\_shard\_\* in S3.



(Option) EC2 demo: install Ollama, copy shards locally from S3, run AskLucene live.



Video checklist:

\- Intro with your name (camera briefly on)

\- Show S3 inputs → EMR step running → shards produced

\- Live Q\&A: one in-corpus (answers + sources), one out-of-corpus (refusal)



------------------------------------------------------------

TROUBLESHOOTING

------------------------------------------------------------

\- Port in use / multiple ollama: ensure single server on 127.0.0.1:11434

\- “model not found”: ollama pull <model>

\- MR output exists: delete C:/tmp/index\_shards\_mr\_out or change conf

\- README edits not visible: edit root README.md (not under target/)

\- Notepad saved README.md.txt: Save As → All Files, or rename to README.md



------------------------------------------------------------

LICENSE

------------------------------------------------------------

MIT (or your chosen license)



