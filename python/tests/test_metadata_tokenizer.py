from searchengine.metadata import parse_header, MetadataStore
from searchengine.tokenizer import tokenize, term_frequencies

HEADER = """The Project Gutenberg eBook of Pride and Prejudice

This ebook is for the use of anyone anywhere in the United States and
most other parts of the world at no cost and with almost no restrictions
whatsoever.

Title: Pride and Prejudice

Author: Jane Austen

Release date: June 1, 1998 [eBook #1342]
                Most recently updated: October 29, 2024

Language: English

Original publication: London: T. Egerton, 1813

Credits: Chuck Greif"""


def test_parse_header():
    meta = parse_header(HEADER)
    assert meta == {"title": "Pride and Prejudice", "author": "Jane Austen", "language": "en",
                    "year": 1813, "release_year": 1998}


def test_continuation_lines():
    header = ("Title: The Life and Adventures\n   of Robinson Crusoe\n\n"
              "Author: First Author\n        Second Author\n\nLanguage: French and English")
    meta = parse_header(header)
    assert meta["title"] == "The Life and Adventures of Robinson Crusoe"
    assert meta["author"] == "First Author; Second Author"
    assert meta["language"] == "fr"


def test_missing_fields_are_none():
    meta = parse_header("Title: Only a title\nLanguage: Klingon")
    assert meta["author"] is None and meta["year"] is None and meta["language"] is None


def test_release_year_ignores_ebook_number():
    meta = parse_header("Release Date: [EBook #1234] March, 2004")
    assert meta["release_year"] == 2004


def test_store_upsert_does_not_duplicate(tmp_path):
    store = MetadataStore(tmp_path / "metadata.db")
    meta = parse_header(HEADER)
    store.upsert(1342, meta, "001000/1342.header.txt", "001000/1342.body.txt")
    store.upsert(1342, meta, "001000/1342.header.txt", "001000/1342.body.txt")
    assert len(store.all()) == 1
    assert store.get(1342)["author"] == "Jane Austen"
    assert store.by_author("austen")[0]["book_id"] == 1342
    store.close()


def test_tokenize_rules():
    text = "The Whale! a whale's TALE, café-au-lait 1851 x Δέλτα"
    assert tokenize(text) == ["whale", "whale", "tale", "café", "au", "lait", "δέλτα"]


def test_unicode_letters_and_length_in_code_points():
    # 𝔄 es una letra fuera del BMP: cuenta como un caracter
    assert tokenize("𝔄𝔅 𝔄") == ["𝔄𝔅"]


def test_term_frequencies():
    assert term_frequencies("sea sea ship the") == {"sea": 2, "ship": 1}
