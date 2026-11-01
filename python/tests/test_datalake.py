import pytest

from searchengine.control import Control
from searchengine.datalake import Datalake, SyntheticClock, SimulatedCrash


@pytest.mark.parametrize("layout,expected", [
    ("book", "1342/header.txt"),
    ("range", "001000/1342.header.txt"),
    ("time", "20260101/00/1342.header.txt"),
])
def test_paths(tmp_path, layout, expected):
    lake = Datalake(tmp_path, layout, clock=SyntheticClock())
    lake.write_book(1342, "h", "b")
    header, body = lake.locate(1342)
    assert lake.relative(header) == expected
    assert body.read_text() == "b"


def test_range_bucket_has_six_digits(tmp_path):
    lake = Datalake(tmp_path, "range")
    assert lake.partition_for(5) == "000000"
    assert lake.partition_for(75999) == "075000"


def test_time_layout_uses_registry(tmp_path):
    clock = SyntheticClock(books_per_hour=2)
    lake = Datalake(tmp_path, "time", clock=clock)
    for book_id in (1, 2, 3):
        lake.write_book(book_id, "h", "b")
        clock.tick()
    fresh = Datalake(tmp_path, "time")
    assert fresh.load_registry() == {1: "20260101/00", 2: "20260101/00", 3: "20260101/01"}
    assert fresh.locate(3) is not None
    assert fresh.locate(4) is None


def test_scan_and_pruned_scan(tmp_path):
    clock = SyntheticClock(books_per_hour=1)
    lake = Datalake(tmp_path, "time", clock=clock)
    for book_id in range(1, 6):
        lake.write_book(book_id, "h", "b")
        clock.tick()
    assert lake.scan().ids() == {1, 2, 3, 4, 5}
    assert lake.scan(min_partition="20260101/03").ids() == {4, 5}


@pytest.mark.parametrize("layout", ["time", "book", "range"])
def test_crash_between_renames_and_reconcile(tmp_path, layout):
    lake = Datalake(tmp_path / "lake", layout, clock=SyntheticClock())
    control = Control(tmp_path / "control")
    lake.write_book(1, "h", "b")
    control.mark_downloaded(1)
    with pytest.raises(SimulatedCrash):
        lake.write_book(2, "h", "b", crash_between_renames=True)

    scan = lake.scan()
    assert scan.ids() == {1}
    assert len(scan.orphans) == 1 and len(scan.temps) == 1

    stats = lake.reconcile(control)
    assert stats["orphans"] == 1 and stats["temps"] == 1
    scan = lake.scan()
    assert scan.ids() == {1} and not scan.orphans and not scan.temps


def test_reconcile_adds_complete_books_to_control(tmp_path):
    lake = Datalake(tmp_path / "lake", "range")
    control = Control(tmp_path / "control")
    lake.write_book(7, "h", "b")          # caida antes de anotarlo en el control
    control.mark_downloaded(99)           # anotado pero sin ficheros
    stats = lake.reconcile(control)
    assert control.downloaded() == {7}
    assert stats["added_to_control"] == 1 and stats["removed_from_control"] == 1


def test_reconcile_removes_time_duplicates(tmp_path):
    lake = Datalake(tmp_path / "lake", "time", clock=SyntheticClock())
    lake.write_book(1, "h", "b")
    lake.clock = SyntheticClock(offset_hours=24)
    lake.write_book(1, "h", "b")
    assert lake.scan().complete[1] == ["20260101/00", "20260102/00"]
    stats = lake.reconcile(Control(tmp_path / "control"))
    assert stats["duplicates"] == 1
    assert lake.scan().complete[1] == ["20260102/00"]


def test_control_ignores_cut_last_line(tmp_path):
    control = Control(tmp_path)
    control.mark_downloaded(10)
    control.mark_downloaded(11)
    with open(control.downloaded_file, "a") as f:
        f.write("12")  # se corto la escritura
    assert control.downloaded() == {10, 11}


def test_control_pending_and_failed(tmp_path):
    control = Control(tmp_path)
    for i in (1, 2, 3):
        control.mark_downloaded(i)
    control.mark_indexed(2)
    control.mark_failed(4, "not_found")
    assert control.pending() == {1, 3}
    assert control.failed() == {4: "not_found"}


def test_storage_stats(tmp_path):
    lake = Datalake(tmp_path, "book")
    lake.write_book(1, "hh", "bbb")
    lake.write_book(2, "hh", "bbb")
    stats = lake.storage_stats()
    assert stats["files"] == 4 and stats["dirs"] == 2 and stats["bytes"] == 10
