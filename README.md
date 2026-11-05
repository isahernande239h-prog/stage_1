# Stage 1 - Search engine data layer

First stage of the Big Data project (Degree in Data Science and Engineering, ULPGC). The goal is to
build the data layer of a search engine for books from [Project Gutenberg](https://www.gutenberg.org):

- **Datalake**: the downloaded books, split into header and body.
- **Datamarts**: metadata (title, author, language, year) in SQLite and the inverted index.
- **Control layer**: files that keep track of which books are downloaded and indexed.

Everything is implemented **three times, in Python, Java and JavaScript (Node.js)**, with the same rules,
so the languages can be compared. It also compares **three datalake layouts** (`time`, `book`,
`range`) and **four index structures** (`json`, `hier`, `mongo`, `sqlite`).

The shared rules (download, splitting, tokenizer, layouts, index format...) are in
[spec/pipeline_spec.md](spec/pipeline_spec.md). The report is in [report/](report/).

## Structure

```
spec/            shared rules + stopwords, language table, queries and corpus ids
python/          Python implementation (searchengine package) + tests (pytest)
java/            Java 17 implementation (Maven) + tests (JUnit 5)
javascript/      Node.js implementation + tests (node:test)
sample_data/     12 sample books (pg<id>.txt) to try it without internet
scripts/         downloads the corpus for the benchmarks
benchmarks/      benchmark orchestrator, local HTTP mirror, analysis and results
report/          the report (Word document and PDF)
```

Inside a data folder (`--data`) each implementation creates:

```
datalake/        range layout: 001000/1342.header.txt, 1342.body.txt
datamarts/       metadata.db, inverted_index.json | inverted_index/ | inverted_index.db
control/         downloaded_books.txt, indexed_books.txt, failed_books.txt
```

## Requirements

- Python 3.10 or newer
- Java 17 and Maven 3.9
- Node.js 22.13 or newer (tested with Node 24; it uses the built-in `node:sqlite` module)
- MongoDB 7 on `127.0.0.1:27017`, only for the `mongo` structure (the others don't need it)

Setup:

```bash
python -m venv .venv && source .venv/bin/activate      # on Windows: .venv\Scripts\activate
pip install -r python/requirements.txt -r benchmarks/requirements.txt
mvn -f java/pom.xml package                            # builds java/target/stage1.jar
(cd javascript && npm install)
```

For the `mongo` structure you need `mongod` running, for example:

```bash
mongod --dbpath ./mongo-data --bind_ip 127.0.0.1
```

## Usage

All three languages have the same commands and options:

| | |
|---|---|
| Python | `cd python && python -m searchengine <command> ...` |
| Java | `java -jar java/target/stage1.jar <command> ...` |
| JavaScript | `node javascript/src/cli.js <command> ...` |

### Trying it with the sample data (no internet)

```bash
cd python
python -m searchengine pipeline --data ../data --source cache:../sample_data/raw \
    --candidates ../sample_data/ids.txt --steps 30 --delay 0
python -m searchengine search --data ../data king england
```

`pipeline` runs steps of the control layer: in each step, if there is a book that is downloaded but not
indexed it indexes it, and if not, it downloads the next candidate (skipping the ones that were already
downloaded or failed). When it starts it runs a reconciliation, in case the previous run got cut off halfway.

Same thing with Java or JavaScript (from the repo root):

```bash
java -jar java/target/stage1.jar pipeline --data data-java --source cache:sample_data/raw \
    --candidates sample_data/ids.txt --steps 30 --delay 0
node javascript/src/cli.js search --data data-java king england
```

All three languages produce exactly the same files, so each one can read the data written by the others.

### With gutenberg.org

```bash
python -m searchengine pipeline --data ../data --steps 20          # 1 s pause between downloads
```

Without `--candidates` it uses the ids in `spec/corpus_ids.txt`. Other options:

| option | values | default |
|---|---|---|
| `--layout` | `time`, `book`, `range` | `range` |
| `--store` | `json`, `hier`, `mongo`, `sqlite` | `sqlite` |
| `--source` | base URL (or a mirror with the same paths) or `cache:<folder>` | `https://www.gutenberg.org` |
| `--delay` | seconds between downloads | 1 |
| `--mongo-uri`, `--mongo-db` | MongoDB connection | `mongodb://127.0.0.1:27017`, `searchengine` |

## Tests

```bash
cd python && python -m pytest          # 45 tests
mvn -f java/pom.xml test               # 62 tests
cd javascript && npm test              # 55 tests
```

The `mongo` structure tests are skipped if there is no local MongoDB.

## Benchmarks

The `ingest`, `bench-datalake`, `bench-index` and `canonical` commands of each language run one
configuration and write a JSON file with the measurements. The orchestrator `benchmarks/bench.py` runs them
for the three languages, each configuration in a new process and with an empty working folder, measures
the peak memory from outside (psutil) and saves one row per run in `benchmarks/results/raw/`.

1. Download the corpus once (400 books, ~145 MB, about 10 minutes because of the 1 s pause):

   ```bash
   python scripts/download_corpus.py --cache ~/bd_work/raw_cache --count 400
   ```

   The ids are picked with a fixed seed, so you always get the same corpus (`spec/corpus_ids.txt`).

2. Run the campaigns (with the jar built, `npm install` done and MongoDB running):

   ```bash
   python benchmarks/bench.py env                    # machine info
   python benchmarks/bench.py equivalence            # the 12 cases give the same output
   python benchmarks/bench.py datalake --reps 5      # writing, lookup, detection, disk space
   python benchmarks/bench.py recovery --reps 3      # crash halfway through an ingest and resume
   python benchmarks/bench.py mirror --reps 3        # ingest with each language's HTTP client
   python benchmarks/bench.py live                   # 10 books from gutenberg.org (just for reference)
   python benchmarks/bench.py index --reps 3         # build, queries, updates, RAM and disk
   python benchmarks/analyze.py                      # tables and figures
   ```

   Options: `--cache` (corpus folder) and `--work` (working folder, `~/bd_work/runs` by default).
   If a campaign gets interrupted, running it again skips whatever is already in the CSV.

Results:

- `benchmarks/results/raw/*.csv`: one row per run.
- `benchmarks/results/equivalence.txt`: check that the 3 languages x 4 structures give the same
  metadata and the same index.
- `benchmarks/results/tables.md` and `benchmarks/results/figures/`: the tables and figures behind the report.
- `benchmarks/results/env.json`: machine and versions.

## Report

The report is `report/report.pdf`. I wrote it in Microsoft Word and exported it to PDF, and the
editable Word version is in `report/report.docx`.
