import re

# The markers change a bit between books (THE/THIS, EBOOK/E-BOOK, with or without a space
# after the asterisks), so we don't look for the exact string from the assignment.
START_RE = re.compile(r"\*\*\*[ \t]*START OF (THE|THIS) PROJECT GUTENBERG E-?BOOK", re.IGNORECASE | re.ASCII)
END_RE = re.compile(r"\*\*\*[ \t]*END OF (THE|THIS) PROJECT GUTENBERG E-?BOOK", re.IGNORECASE | re.ASCII)

# plain strip() also removes unicode spaces and would give different results from Java/JS
ASCII_WS = " \t\n\r\f\v"


class NoMarkersError(ValueError):
    pass


def normalize_newlines(text):
    return text.replace("\r\n", "\n").replace("\r", "\n")


def split_book(text):
    """Returns (header, body). Raises NoMarkersError if one of the two markers is missing."""
    text = normalize_newlines(text)
    start_line = None   # (start, end) of the START line
    end_offset = None
    pos = 0
    for line in text.split("\n"):
        line_end = pos + len(line)
        if start_line is None:
            if START_RE.match(line):
                start_line = (pos, line_end)
        elif END_RE.match(line):
            end_offset = pos
            break
        pos = line_end + 1

    if start_line is None or end_offset is None:
        raise NoMarkersError("no_markers")

    header = text[:start_line[0]]
    body = text[start_line[1] + 1:end_offset]
    return header.strip(ASCII_WS), body.strip(ASCII_WS)
