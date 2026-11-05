// Shared tokenizer: lowercase, runs of unicode letters, length >= 2 and no stop words.
import { loadStopwords } from './spec.js';

const LETTERS = /\p{L}+/gu;
const STOPWORDS = loadStopwords();

// length is counted in code points: a letter outside the BMP is two UTF-16 units
function atLeastTwoCodePoints(token) {
  return token.length > 2 || (token.length === 2 && token.codePointAt(0) <= 0xffff);
}

export function tokenize(text, stopwords = STOPWORDS) {
  const tokens = [];
  for (const token of text.toLowerCase().match(LETTERS) ?? []) {
    if (atLeastTwoCodePoints(token) && !stopwords.has(token)) tokens.push(token);
  }
  return tokens;
}

// Map term -> count, in the order they appear (like Counter)
export function termFrequencies(text, stopwords = STOPWORDS) {
  const tfs = new Map();
  for (const token of tokenize(text, stopwords)) tfs.set(token, (tfs.get(token) ?? 0) + 1);
  return tfs;
}
