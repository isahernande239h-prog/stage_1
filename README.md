# Stage 1 - Capa de datos del buscador

Primera fase del proyecto de Big Data (Grado en Ciencia e Ingeniería de Datos, ULPGC). El objetivo es
montar la capa de datos de un buscador de libros de [Project Gutenberg](https://www.gutenberg.org):

- **Datalake**: los libros descargados, separados en cabecera y cuerpo.
- **Datamarts**: metadatos (título, autor, idioma, año) en SQLite y el índice invertido.
- **Capa de control**: ficheros que dicen qué libros están descargados e indexados.

Todo está implementado **tres veces, en Python, Java y JavaScript (Node.js)**, con las mismas reglas,
para comparar lenguajes. También se comparan **tres organizaciones del datalake** (`time`, `book`,
`range`) y **cuatro estructuras del índice** (`json`, `hier`, `mongo`, `sqlite`).

Las reglas comunes (descarga, separación, tokenizador, layouts, formato del índice...) están en
[spec/pipeline_spec.md](spec/pipeline_spec.md). El informe está en [report/](report/).

## Estructura

```
spec/            reglas comunes + stopwords, tabla de idiomas, consultas y ids del corpus
python/          implementacion en Python (paquete searchengine) + tests (pytest)
java/            implementacion en Java 17 (Maven) + tests (JUnit 5)
javascript/      implementacion en Node.js + tests (node:test)
sample_data/     12 libros de ejemplo (pg<id>.txt) para probar sin internet
scripts/         descarga del corpus de los benchmarks
benchmarks/      orquestador de benchmarks, espejo HTTP local, analisis y resultados
report/          informe (markdown) y script para generar el PDF
```

Dentro de una carpeta de datos (`--data`) cada implementación crea:

```
datalake/        layout range: 001000/1342.header.txt, 1342.body.txt
datamarts/       metadata.db, inverted_index.json | inverted_index/ | inverted_index.db
control/         downloaded_books.txt, indexed_books.txt, failed_books.txt
```

## Requisitos

- Python 3.10 o superior
- Java 17 y Maven 3.9
- Node.js 22.13 o superior (se ha probado con Node 24; usa el módulo `node:sqlite` integrado)
- MongoDB 7 en `127.0.0.1:27017`, solo para la estructura `mongo` (las demás no lo necesitan)

Instalación:

```bash
python -m venv .venv && source .venv/bin/activate      # en Windows: .venv\Scripts\activate
pip install -r python/requirements.txt -r benchmarks/requirements.txt
mvn -f java/pom.xml package                            # genera java/target/stage1.jar
(cd javascript && npm install)
```

Para la estructura `mongo` hace falta tener `mongod` arrancado, por ejemplo:

```bash
mongod --dbpath ./mongo-data --bind_ip 127.0.0.1
```

## Uso

Los tres lenguajes tienen los mismos comandos y opciones:

| | |
|---|---|
| Python | `cd python && python -m searchengine <comando> ...` |
| Java | `java -jar java/target/stage1.jar <comando> ...` |
| JavaScript | `node javascript/src/cli.js <comando> ...` |

### Probar con los datos de ejemplo (sin internet)

```bash
cd python
python -m searchengine pipeline --data ../data --source cache:../sample_data/raw \
    --candidates ../sample_data/ids.txt --steps 30 --delay 0
python -m searchengine search --data ../data king england
```

`pipeline` ejecuta pasos de la capa de control: en cada paso, si hay un libro descargado y sin
indexar lo indexa, y si no, descarga el siguiente candidato (sin repetir los que ya se descargaron o
fallaron). Al empezar hace una reconciliación por si la ejecución anterior se cortó a mitad.

Lo mismo con Java o JavaScript (desde la raíz del repo):

```bash
java -jar java/target/stage1.jar pipeline --data data-java --source cache:sample_data/raw \
    --candidates sample_data/ids.txt --steps 30 --delay 0
node javascript/src/cli.js search --data data-java king england
```

Los tres lenguajes generan exactamente los mismos ficheros, así que pueden leer los datos de los otros.

### Con gutenberg.org

```bash
python -m searchengine pipeline --data ../data --steps 20          # 1 s de pausa entre descargas
```

Sin `--candidates` se usan los ids de `spec/corpus_ids.txt`. Otras opciones:

| opción | valores | por defecto |
|---|---|---|
| `--layout` | `time`, `book`, `range` | `range` |
| `--store` | `json`, `hier`, `mongo`, `sqlite` | `sqlite` |
| `--source` | URL base (o un espejo con las mismas rutas) o `cache:<carpeta>` | `https://www.gutenberg.org` |
| `--delay` | segundos entre descargas | 1 |
| `--mongo-uri`, `--mongo-db` | conexión a MongoDB | `mongodb://127.0.0.1:27017`, `searchengine` |

## Tests

```bash
cd python && python -m pytest          # 45 tests
mvn -f java/pom.xml test               # 62 tests
cd javascript && npm test              # 55 tests
```

Los tests de la estructura `mongo` se saltan si no hay un MongoDB local.

## Benchmarks

Los comandos `ingest`, `bench-datalake`, `bench-index` y `canonical` de cada lenguaje ejecutan una
configuración y escriben un JSON con las medidas. El orquestador `benchmarks/bench.py` los lanza para
los tres lenguajes, cada configuración en un proceso nuevo y con la carpeta de trabajo vacía, mide
desde fuera el pico de memoria (psutil) y guarda una fila por ejecución en `benchmarks/results/raw/`.

1. Descargar una vez el corpus (400 libros, ~145 MB, unos 10 minutos por la pausa de 1 s):

   ```bash
   python scripts/download_corpus.py --cache ~/bd_work/raw_cache --count 400
   ```

   Los ids se sacan con semilla fija, así que sale siempre el mismo corpus (`spec/corpus_ids.txt`).

2. Lanzar las campañas (con el jar compilado, `npm install` hecho y MongoDB arrancado):

   ```bash
   python benchmarks/bench.py env                    # datos del equipo
   python benchmarks/bench.py equivalence            # los 12 casos dan la misma salida
   python benchmarks/bench.py datalake --reps 5      # escritura, localizacion, deteccion, espacio
   python benchmarks/bench.py recovery --reps 3      # caida a mitad de una ingesta y reanudacion
   python benchmarks/bench.py mirror --reps 3        # ingesta con el cliente HTTP de cada lenguaje
   python benchmarks/bench.py live                   # 10 libros contra gutenberg.org (orientativo)
   python benchmarks/bench.py index --reps 3         # construccion, consultas, actualizacion, RAM y disco
   python benchmarks/analyze.py                      # tablas y graficas
   ```

   Opciones: `--cache` (carpeta del corpus) y `--work` (carpeta de trabajo, por defecto `~/bd_work/runs`).
   Si una campaña se corta, al relanzarla se salta lo que ya está en el CSV.

Resultados:

- `benchmarks/results/raw/*.csv`: una fila por ejecución.
- `benchmarks/results/equivalence.txt`: comprobación de que los 3 lenguajes x 4 estructuras dan los
  mismos metadatos y el mismo índice.
- `benchmarks/results/tables.md` y `benchmarks/results/figures/`: lo que sale en el informe.
- `benchmarks/results/env.json`: equipo y versiones.

## Informe

`report/informe.md` es el texto del informe. Para generar el PDF:

```bash
python report/build_report.py          # necesita Chrome o Chromium (variable CHROME_BIN si no lo encuentra)
```
