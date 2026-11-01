// Tokenizador comun: minusculas, secuencias de letras unicode, longitud >= 2 y sin stop words.
import { loadStopwords } from './spec.js';

const LETTERS = /\p{L}+/gu;
const STOPWORDS = loadStopwords();

// la longitud se cuenta en code points: una letra fuera del BMP son dos unidades UTF-16
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

// Map termino -> veces, en el orden en que aparecen (como Counter)
export function termFrequencies(text, stopwords = STOPWORDS) {
  const tfs = new Map();
  for (const token of tokenize(text, stopwords)) tfs.set(token, (tfs.get(token) ?? 0) + 1);
  return tfs;
}
