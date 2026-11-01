# Reglas comunes del pipeline

Las tres implementaciones (Python, Java y JavaScript) tienen que seguir estas reglas al pie de la letra.
Si no, el benchmark compararía programas que hacen cosas distintas. Los ficheros de esta carpeta
(`stopwords.txt`, `languages.tsv`, `queries.txt`, `corpus_ids.txt`) los leen los tres lenguajes.

## 1. Descarga

- URL: `{base}/cache/epub/{id}/pg{id}.txt`, con `base = https://www.gutenberg.org` por defecto.
  La base se puede cambiar (espejo local para los benchmarks).
- También se puede leer de una caché local con `cache:<dir>`, que contiene `pg{id}.txt`.
- Timeout de 10 s para conectar y 30 s para leer. Hasta 3 intentos, esperando 1 s y 2 s, si hay
  error de red, HTTP 5xx o 429.
- 404 -> el libro no existe (`not_found`) y no se reintenta.
- Si llega `Content-Length` y no coincide con los bytes recibidos, la descarga está incompleta y se reintenta.
- Se decodifica como UTF-8 (los bytes inválidos se sustituyen por U+FFFD) y se quita el BOM inicial.

## 2. Cabecera, cuerpo y pie

1. Se normalizan los saltos de línea: `\r\n` y `\r` pasan a `\n`.
2. Se recorre el texto línea a línea (separando solo por `\n`).
3. Línea de inicio: la primera que cumple `^\*\*\*[ \t]*START OF (THE|THIS) PROJECT GUTENBERG E-?BOOK`
   sin distinguir mayúsculas (solo ASCII).
4. Línea de fin: la primera **posterior** a la de inicio que cumple lo mismo con `END`.
5. Cabecera = todo lo anterior a la línea de inicio. Cuerpo = lo que hay entre las dos líneas.
   El pie (desde la línea de fin) se descarta.
6. Si falta alguna de las dos líneas, el libro se rechaza con el motivo `no_markers`.
7. Cabecera y cuerpo se recortan quitando solo espacios ASCII (` `, `\t`, `\n`, `\r`, `\f`, `\v`) al
   principio y al final. Se guardan en UTF-8 tal cual, sin añadir salto de línea final.

## 3. Datalake

Cada libro son dos ficheros, `header` y `body`. Hay tres organizaciones:

| layout | ruta |
|---|---|
| `time`  | `YYYYMMDD/HH/<id>.header.txt` y `<id>.body.txt` (fecha y hora UTC de la escritura) |
| `book`  | `<id>/header.txt` y `<id>/body.txt` |
| `range` | `<bucket>/<id>.header.txt` y `<id>.body.txt`, con `bucket = floor(id/1000)*1000` en 6 cifras (`001000`) |

En `time` la ruta depende del momento de la descarga y no del id. Por eso se guarda un registro
`_registry.tsv` (`id<TAB>YYYYMMDD/HH`) en la raíz del datalake.

Orden de escritura de un libro:

1. `body` a `<ruta>.tmp`
2. `header` a `<ruta>.tmp`
3. renombrar el body
4. renombrar el header
5. (solo `time`) añadir la línea al registro
6. añadir el id a `control/downloaded_books.txt`

Un libro está **completo** si existen sus dos ficheros finales.

**Reconciliación** (al arrancar el pipeline): borrar los `.tmp`, borrar las mitades huérfanas
(header sin body o al revés), en `time` dejar una sola copia si un libro está en dos particiones
(la registrada, o si no la más reciente) y rehacer el registro. Por último, alinear
`downloaded_books.txt` con los libros completos.

## 4. Metadatos

La cabecera se lee línea a línea:

- Una línea `Nombre: valor` (el nombre empieza por una letra y solo tiene letras, espacios o `-`) abre un campo.
  El nombre se compara en minúsculas.
- Una línea que empieza por espacio o tabulador continúa el último campo abierto.
- Una línea vacía o cualquier otra línea cierra el campo.
- Los valores se limpian: se juntan los espacios repetidos en uno y se recortan los extremos.

Campos:

- `title`: campo `title`. Las líneas de continuación se unen con un espacio.
- `author`: campo `author`. Cada línea de continuación es otro autor. Si hay varios campos `author`,
  se juntan todos con `"; "`.
- `language`: el primer idioma del campo `language` (se corta por `,`, `;` o ` and `), pasado a código con
  `languages.tsv`. Si no está en la tabla, `null`.
- `year`: año de publicación original. Es el **último** número de 4 cifras del campo `original publication`.
  Si no hay, `null`. Nunca se rellena con otro campo.
- `release_year`: el **primer** número de 4 cifras del campo `release date`, quitando antes lo que va entre
  corchetes (`[eBook #1342]`).

Se guardan en `datamarts/metadata.db` (SQLite), tabla `books`:
`book_id INTEGER PRIMARY KEY, title, author, language, year, release_year, header_path, body_path`.
Las rutas son relativas al datalake. Se usa `INSERT OR REPLACE`, así que reindexar no duplica.

## 5. Tokenizador

1. Pasar todo el cuerpo a minúsculas con el mapeo Unicode por defecto
   (`str.lower()`, `toLowerCase(Locale.ROOT)`, `toLowerCase()`).
2. Sacar las secuencias máximas de letras Unicode (categoría general `L*`, es decir `\p{L}`).
3. Quedarse con las de 2 o más caracteres (contando code points, no unidades UTF-16).
4. Quitar las stop words de `stopwords.txt`.

## 6. Índice invertido

Para cada término se guarda `{book_id: tf}`, donde tf es el número de veces que aparece en el libro.
No se guardan posiciones.

Estructuras:

| nombre | formato |
|---|---|
| `json` | `datamarts/inverted_index.json`: `{"termino": {"id": tf, ...}, ...}` |
| `hier` | `datamarts/inverted_index/<L>/<termino>.txt` con líneas `id<TAB>tf`. `<L>` es la primera letra en mayúscula si es a-z, si no `_`. En el nombre del fichero se dejan a-z y el resto se codifica como `%XX` (bytes UTF-8). A los nombres reservados de Windows (`con`, `prn`, `aux`, `nul`, `com1`-`com9`, `lpt1`-`lpt9`) se les añade `_`. |
| `mongo` | base `searchengine`, colección `inverted_index`, un documento por término: `{_id: termino, postings: {"id": tf}}` |
| `sqlite` | `datamarts/inverted_index.db`, tabla `postings(term, book_id, tf)` con clave `(term, book_id)` |

Las cuatro tienen la misma interfaz: `build(libros)`, `add_book(id, frecuencias)` y `postings(termino)`.
Añadir un libro que ya estaba lo sobrescribe (no se duplica). En `hier` el fichero solo crece, así que al
leer, si un id aparece varias veces, vale la última línea.

## 7. Consultas

La consulta se tokeniza igual que los libros. El resultado son los libros que contienen **todos** los
términos, ordenados por id. Si la consulta se queda sin términos (por ejemplo, solo stop words), el
resultado está vacío.

## 8. Capa de control

En `control/`:

- `downloaded_books.txt`: un id por línea, libros descargados y completos.
- `indexed_books.txt`: un id por línea, libros indexados.
- `failed_books.txt`: `id<TAB>motivo` de los que no se pueden descargar (`not_found`, `no_markers`, ...).

Solo se escribe una línea **después** de que la operación haya terminado bien. Al leer, una última línea
sin `\n` se considera incompleta y se ignora.

Un paso del pipeline:

1. Si hay libros descargados y no indexados, se indexa el de menor id.
2. Si no, se coge el siguiente candidato (de `corpus_ids.txt` o de una lista que se pase) que no esté
   descargado ni fallado, y se descarga.
3. Se actualiza el fichero de control que toque.
