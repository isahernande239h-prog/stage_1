"""Tokenizador comun: minusculas, secuencias de letras unicode, longitud >= 2 y sin stop words."""
import re
import sys
import unicodedata
from collections import Counter

from .spec import load_stopwords


def _letter_class():
    # El modulo re no tiene \p{L}, asi que la clase se construye con unicodedata.
    ranges = []
    start = None
    for cp in range(sys.maxunicode + 2):
        is_letter = cp <= sys.maxunicode and unicodedata.category(chr(cp))[0] == "L"
        if is_letter and start is None:
            start = cp
        elif not is_letter and start is not None:
            end = cp - 1
            ranges.append(re.escape(chr(start)) if start == end
                          else f"{re.escape(chr(start))}-{re.escape(chr(end))}")
            start = None
    return "[" + "".join(ranges) + "]+"


LETTERS = re.compile(_letter_class())
STOPWORDS = load_stopwords()


def tokenize(text, stopwords=STOPWORDS):
    return [t for t in LETTERS.findall(text.lower()) if len(t) >= 2 and t not in stopwords]


def term_frequencies(text, stopwords=STOPWORDS):
    return Counter(tokenize(text, stopwords))
