import pytest

from searchengine.index_stores import (open_store, search_ids, term_filename, filename_to_term, STORES)


def mongo_available():
    try:
        from pymongo import MongoClient
        MongoClient("mongodb://127.0.0.1:27017", serverSelectionTimeoutMS=500).server_info()
        return True
    except Exception:
        return False


INDEX = {
    "island": {5: 3, 1342: 1},
    "adventure": {5: 2, 12: 1},
    "ship": {12: 4},
    "οδος": {7: 1},
}


@pytest.fixture(params=STORES)
def store(request, tmp_path):
    if request.param == "mongo" and not mongo_available():
        pytest.skip("MongoDB no esta arrancado")
    s = open_store(request.param, tmp_path / "datamarts", mongo_db="stage1_pytest")
    yield s
    if request.param == "mongo":
        s.drop()
    s.close()


def test_build_and_query(store):
    store.write_full(INDEX)
    store.open()
    assert store.postings("island") == {5: 3, 1342: 1}
    assert store.postings("missing") == {}
    assert store.postings("οδος") == {7: 1}
    assert search_ids(store, ["island", "adventure"]) == [5]
    assert search_ids(store, ["island", "ship"]) == []
    assert search_ids(store, []) == []


def test_add_book_is_idempotent(store):
    store.write_full(INDEX)
    store.open()
    store.add_book(99, {"island": 2, "volcano": 1})
    store.add_book(99, {"island": 5, "volcano": 1})
    assert store.postings("island") == {5: 3, 1342: 1, 99: 5}
    assert store.postings("volcano") == {99: 1}


def test_terms_export(store):
    store.write_full(INDEX)
    store.open()
    exported = {term: found for term, found in store.terms()}
    assert exported == INDEX


def test_filenames():
    assert term_filename("adventure") == "adventure.txt"
    assert term_filename("con") == "con_.txt"
    assert term_filename("café") == "caf%C3%A9.txt"
    for term in ("café", "con", "οδος", "nul"):
        assert filename_to_term(term_filename(term)) == term
