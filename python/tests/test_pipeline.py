from searchengine.pipeline import Pipeline, search

HEADER = "Title: {title}\n\nAuthor: {author}\n\nLanguage: English\n"
BOOKS = {
    1: ("Moby Dick", "Herman Melville", "the whale and the sea"),
    2: ("Treasure Island", "R. L. Stevenson", "an island with gold treasure by the sea"),
    3: ("Missing", "Nobody", None),   # 404
}


def make_cache(folder):
    folder.mkdir()
    for book_id, (title, author, body) in BOOKS.items():
        if body is None:
            continue
        text = (HEADER.format(title=title, author=author)
                + "*** START OF THE PROJECT GUTENBERG EBOOK X ***\n" + body
                + "\n*** END OF THE PROJECT GUTENBERG EBOOK X ***\n")
        (folder / f"pg{book_id}.txt").write_text(text, encoding="utf-8")
    (folder / "pg4.txt").write_text("no markers here", encoding="utf-8")


def test_control_loop_downloads_then_indexes(tmp_path):
    make_cache(tmp_path / "cache")
    logs = []
    pipe = Pipeline(tmp_path / "data", "range", "sqlite", f"cache:{tmp_path / 'cache'}",
                    candidates=[1, 2, 3, 4], delay=0, log=logs.append)

    assert pipe.step() == ("downloaded", 1)
    assert pipe.step() == ("indexed", 1)      # primero se indexa lo pendiente
    assert pipe.step() == ("downloaded", 2)
    assert pipe.step() == ("indexed", 2)
    assert pipe.step() == ("failed", 3)
    assert pipe.step() == ("failed", 4)
    assert pipe.step() == ("idle", None)
    assert pipe.control.failed() == {3: "not_found", 4: "no_markers"}
    assert pipe.control.downloaded() == pipe.control.indexed() == {1, 2}
    pipe.close()

    result = search(tmp_path / "data", "sqlite", "the sea")
    assert result["terms"] == ["sea"]
    assert [r["book_id"] for r in result["results"]] == [1, 2]
    assert result["results"][1]["title"] == "Treasure Island"
    assert search(tmp_path / "data", "sqlite", "gold island")["total"] == 1
    assert search(tmp_path / "data", "sqlite", "the of")["total"] == 0


def test_pipeline_resumes_without_duplicates(tmp_path):
    make_cache(tmp_path / "cache")
    source = f"cache:{tmp_path / 'cache'}"
    pipe = Pipeline(tmp_path / "data", "range", "json", source, candidates=[1, 2], delay=0, log=lambda m: None)
    pipe.run(3)
    pipe.close()
    pipe = Pipeline(tmp_path / "data", "range", "json", source, candidates=[1, 2], delay=0, log=lambda m: None)
    pipe.run(10)
    pipe.close()
    lines = (tmp_path / "data" / "control" / "downloaded_books.txt").read_text().split()
    assert sorted(lines) == ["1", "2"]
    assert search(tmp_path / "data", "json", "sea")["total"] == 2
