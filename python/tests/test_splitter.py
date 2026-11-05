import pytest

from searchengine.splitter import split_book, NoMarkersError
from searchengine.source import decode

BOOK = (
    "The Project Gutenberg eBook of Test\r\n"
    "Title: Test\r\n"
    "\r\n"
    "*** START OF THE PROJECT GUTENBERG EBOOK TEST ***\r\n"
    "\r\n"
    "Chapter 1\r\n"
    "Some text.\r\n"
    "*** END OF THE PROJECT GUTENBERG EBOOK TEST ***\r\n"
    "License stuff\r\n"
)


def test_basic_split():
    header, body = split_book(BOOK)
    assert header == "The Project Gutenberg eBook of Test\nTitle: Test"
    assert body == "Chapter 1\nSome text."


def test_variants_this_and_ebook_with_dash():
    text = "h\n***START OF THIS PROJECT GUTENBERG E-BOOK X\nbody\n*** end of this project gutenberg e-book X\nf"
    assert split_book(text) == ("h", "body")


def test_missing_end_marker():
    with pytest.raises(NoMarkersError):
        split_book("header\n*** START OF THE PROJECT GUTENBERG EBOOK X\nbody only\n")


def test_marker_must_be_at_line_start():
    text = "h\nsee *** START OF THE PROJECT GUTENBERG EBOOK here\nbody\n"
    with pytest.raises(NoMarkersError):
        split_book(text)


def test_loose_asterisks_inside_body_are_ignored():
    text = ("h\n*** START OF THE PROJECT GUTENBERG EBOOK X\n"
            "a\n***\nb\n*** END OF THE PROJECT GUTENBERG EBOOK X\n")
    assert split_book(text)[1] == "a\n***\nb"


def test_only_ascii_whitespace_is_trimmed():
    text = "h\n*** START OF THE PROJECT GUTENBERG EBOOK X\n body \n*** END OF THE PROJECT GUTENBERG EBOOK X\n"
    assert split_book(text)[1] == " body "


def test_decode_removes_bom():
    assert decode("﻿hello".encode("utf-8")) == "hello"
