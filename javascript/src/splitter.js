// Los marcadores cambian un poco entre libros (THE/THIS, EBOOK/E-BOOK, con o sin espacio
// despues de los asteriscos), por eso no se busca la cadena exacta del enunciado.
// Sin el flag u, la i solo junta mayusculas y minusculas ASCII (igual que re.ASCII en python).
const START_RE = /^\*\*\*[ \t]*START OF (THE|THIS) PROJECT GUTENBERG E-?BOOK/i;
const END_RE = /^\*\*\*[ \t]*END OF (THE|THIS) PROJECT GUTENBERG E-?BOOK/i;

export class NoMarkersError extends Error {}

// trim() tambien quita espacios unicode (NBSP...) y daria resultados distintos a python/java
export function stripAscii(text) {
  let start = 0;
  let end = text.length;
  while (start < end && isAsciiSpace(text.charCodeAt(start))) start++;
  while (end > start && isAsciiSpace(text.charCodeAt(end - 1))) end--;
  return text.slice(start, end);
}

function isAsciiSpace(c) {
  // espacio, \t, \n, \v, \f, \r
  return c === 32 || (c >= 9 && c <= 13);
}

export function normalizeNewlines(text) {
  return text.replaceAll('\r\n', '\n').replaceAll('\r', '\n');
}

// Devuelve [header, body]. Lanza NoMarkersError si no estan los dos marcadores.
export function splitBook(text) {
  text = normalizeNewlines(text);
  let startLine = null; // [inicio, fin] de la linea de START
  let endOffset = null;
  let pos = 0;
  // solo se separa por \n (el flag m de JS tambien cortaria en U+2028 y U+2029)
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
