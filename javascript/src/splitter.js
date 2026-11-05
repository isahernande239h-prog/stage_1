// The markers change a bit between books (THE/THIS, EBOOK/E-BOOK, with or without a space
// after the asterisks), so we don't look for the exact string from the assignment.
// Without the u flag, i only matches ASCII upper and lower case (same as re.ASCII in python).
const START_RE = /^\*\*\*[ \t]*START OF (THE|THIS) PROJECT GUTENBERG E-?BOOK/i;
const END_RE = /^\*\*\*[ \t]*END OF (THE|THIS) PROJECT GUTENBERG E-?BOOK/i;

export class NoMarkersError extends Error {}

// trim() also removes unicode spaces (NBSP...) and would give different results from python/java
export function stripAscii(text) {
  let start = 0;
  let end = text.length;
  while (start < end && isAsciiSpace(text.charCodeAt(start))) start++;
  while (end > start && isAsciiSpace(text.charCodeAt(end - 1))) end--;
  return text.slice(start, end);
}

function isAsciiSpace(c) {
  // space, \t, \n, \v, \f, \r
  return c === 32 || (c >= 9 && c <= 13);
}

export function normalizeNewlines(text) {
  return text.replaceAll('\r\n', '\n').replaceAll('\r', '\n');
}

// Returns [header, body]. Throws NoMarkersError if the two markers aren't there.
export function splitBook(text) {
  text = normalizeNewlines(text);
  let startLine = null; // [start, end] of the START line
  let endOffset = null;
  let pos = 0;
  // split on \n only (the JS m flag would also break on U+2028 and U+2029)
  for (const line of text.split('\n')) {
    const lineEnd = pos + line.length;
    if (startLine === null) {
      if (START_RE.test(line)) startLine = [pos, lineEnd];
    } else if (END_RE.test(line)) {
      endOffset = pos;
      break;
    }
    pos = lineEnd + 1;
  }

  if (startLine === null || endOffset === null) {
    throw new NoMarkersError('no_markers');
  }

  const header = text.slice(0, startLine[0]);
  const body = text.slice(startLine[1] + 1, endOffset);
  return [stripAscii(header), stripAscii(body)];
}
