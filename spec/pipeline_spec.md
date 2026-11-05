# Shared pipeline rules

The three implementations (Python, Java and JavaScript) have to follow these rules to the letter.
Otherwise the benchmark would be comparing programs that do different things. The files in this folder
(`stopwords.txt`, `languages.tsv`, `queries.txt`, `corpus_ids.txt`) are read by all three languages.

## 1. Download

- URL: `{base}/cache/epub/{id}/pg{id}.txt`, with `base = https://www.gutenberg.org` by default.
  The base can be changed (local mirror for the benchmarks).
- It can also read from a local cache with `cache:<dir>`, which contains `pg{id}.txt`.
- Timeout of 10 s to connect and 30 s to read. Up to 3 attempts, waiting 1 s and then 2 s, if there is
  a network error, HTTP 5xx or 429.
- 404 -> the book doesn't exist (`not_found`) and it isn't retried.
- If there is a `Content-Length` and it doesn't match the bytes received, the download is incomplete and gets retried.
- The text is decoded as UTF-8 (invalid bytes are replaced with U+FFFD) and the leading BOM is removed.

## 2. Header, body and footer

1. Line breaks are normalized: `\r\n` and `\r` become `\n`.
2. The text is read line by line (splitting only on `\n`).
3. Start line: the first one that matches `^\*\*\*[ \t]*START OF (THE|THIS) PROJECT GUTENBERG E-?BOOK`,
   case-insensitive (ASCII only).
4. End line: the first one **after** the start line that matches the same pattern with `END`.
5. Header = everything before the start line. Body = everything between the two lines.
   The footer (from the end line on) is dropped.
6. If either of the two lines is missing, the book is rejected with the reason `no_markers`.
7. Header and body are trimmed, removing only ASCII whitespace (` `, `\t`, `\n`, `\r`, `\f`, `\v`) at
   the start and the end. They are saved in UTF-8 as they are, without adding a final newline.

## 3. Datalake

Each book is two files, `header` and `body`. There are three layouts:

| layout | path |
|---|---|
| `time`  | `YYYYMMDD/HH/<id>.header.txt` and `<id>.body.txt` (UTC date and hour of the write) |
| `book`  | `<id>/header.txt` and `<id>/body.txt` |
| `range` | `<bucket>/<id>.header.txt` and `<id>.body.txt`, with `bucket = floor(id/1000)*1000` as 6 digits (`001000`) |

In `time` the path depends on when the book was downloaded, not on its id. That's why a registry file
`_registry.tsv` (`id<TAB>YYYYMMDD/HH`) is kept at the root of the datalake.

Write order for a book:

1. `body` to `<path>.tmp`
2. `header` to `<path>.tmp`
3. rename the body
4. rename the header
5. (only `time`) append the line to the registry
6. append the id to `control/downloaded_books.txt`

A book is **complete** if both of its final files exist.

**Reconciliation** (when the pipeline starts): delete the `.tmp` files, delete orphan halves
(header without body or the other way round), in `time` keep only one copy if a book is in two partitions
(the registered one, or if there isn't one, the most recent) and rebuild the registry. Finally, make
`downloaded_books.txt` match the complete books.

## 4. Metadata

The header is read line by line:

- A `Name: value` line (the name starts with a letter and only has letters, spaces or `-`) opens a field.
  The name is compared in lowercase.
- A line that starts with a space or a tab continues the last open field.
- An empty line or any other line closes the field.
- Values are cleaned up: repeated spaces are merged into one and the ends are trimmed.

Fields:

- `title`: the `title` field. Continuation lines are joined with a space.
- `author`: the `author` field. Each continuation line is another author. If there are several `author` fields,
  they are all joined with `"; "`.
- `language`: the first language in the `language` field (split on `,`, `;` or ` and `), turned into a code with
  `languages.tsv`. If it's not in the table, `null`.
- `year`: original publication year. It's the **last** 4-digit number in the `original publication` field.
  If there isn't one, `null`. It's never filled in from another field.
- `release_year`: the **first** 4-digit number in the `release date` field, after removing anything in square
  brackets (`[eBook #1342]`).

They are saved in `datamarts/metadata.db` (SQLite), table `books`:
`book_id INTEGER PRIMARY KEY, title, author, language, year, release_year, header_path, body_path`.
The paths are relative to the datalake. It uses `INSERT OR REPLACE`, so reindexing doesn't create duplicates.

## 5. Tokenizer

1. Lowercase the whole body with the default Unicode mapping
   (`str.lower()`, `toLowerCase(Locale.ROOT)`, `toLowerCase()`).
2. Extract the maximal runs of Unicode letters (general category `L*`, that is, `\p{L}`).
3. Keep the ones with 2 or more characters (counting code points, not UTF-16 units).
4. Remove the stop words in `stopwords.txt`.

## 6. Inverted index

For each term the index stores `{book_id: tf}`, where tf is the number of times the term appears in the book.
Positions are not stored.

Structures:

| name | format |
|---|---|
| `json` | `datamarts/inverted_index.json`: `{"term": {"id": tf, ...}, ...}` |
| `hier` | `datamarts/inverted_index/<L>/<term>.txt` with lines `id<TAB>tf`. `<L>` is the first letter in uppercase if it's a-z, otherwise `_`. In the file name a-z are kept as they are and everything else is encoded as `%XX` (UTF-8 bytes). Windows reserved names (`con`, `prn`, `aux`, `nul`, `com1`-`com9`, `lpt1`-`lpt9`) get a `_` added at the end. |
| `mongo` | database `searchengine`, collection `inverted_index`, one document per term: `{_id: term, postings: {"id": tf}}` |
| `sqlite` | `datamarts/inverted_index.db`, table `postings(term, book_id, tf)` with key `(term, book_id)` |

All four have the same interface: `build(books)`, `add_book(id, frequencies)` and `postings(term)`.
Adding a book that was already there overwrites it (it doesn't get duplicated). In `hier` the file only grows,
so when reading, if an id shows up more than once, the last line is the one that counts.

## 7. Queries

The query is tokenized the same way as the books. The result is the books that contain **all** the
terms, sorted by id. If the query ends up with no terms (for example, only stop words), the
result is empty.

## 8. Control layer

In `control/`:

- `downloaded_books.txt`: one id per line, books that are downloaded and complete.
- `indexed_books.txt`: one id per line, indexed books.
- `failed_books.txt`: `id<TAB>reason` for the books that can't be downloaded (`not_found`, `no_markers`, ...).

A line is only written **after** the operation has finished successfully. When reading, a last line
without `\n` is considered incomplete and is ignored.

One pipeline step:

1. If there are books that are downloaded but not indexed, index the one with the lowest id.
2. If not, take the next candidate (from `corpus_ids.txt` or from a list passed in) that isn't
   downloaded or failed, and download it.
3. Update the matching control file.
