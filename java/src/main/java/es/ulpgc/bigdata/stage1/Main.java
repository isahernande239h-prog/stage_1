package es.ulpgc.bigdata.stage1;

import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;

/** Linea de comandos, con los mismos comandos y opciones que python -m searchengine. */
public class Main {
    static final String DEFAULT_MONGO_URI = "mongodb://127.0.0.1:27017";
    static final String DEFAULT_MONGO_DB = "searchengine";
    static final List<String> COMMANDS = List.of("pipeline", "search", "ingest", "bench-datalake", "bench-index", "canonical");

    static final String USAGE = String.join("\n",
            "usage: java -jar stage1.jar <command> [options]",
            "",
            "commands:",
            "  pipeline        --data D [--layout time|book|range] [--source URL|cache:DIR] [--candidates F]",
            "                  [--steps N] [--delay S] [--store json|hier|mongo|sqlite] [--mongo-uri U] [--mongo-db DB]",
            "  search          --data D [--store ...] [--mongo-uri U] [--mongo-db DB] query [query ...]",
            "  ingest          --data D --source S --ids F [--layout L] [--n N] [--clock real|synthetic]",
            "                  [--clock-offset-hours H] [--crash-after K] [--crash-at between_renames|before_control]",
            "                  [--reconcile] [--delay S]",
            "  bench-datalake  --data D --layout L --source S --ids F --n N [--new K] [--clock C] [--clock-offset-hours H]",
            "  bench-index     --data D --datalake DL --ids F --n N [--add K] [--queries F] [--query-reps R] [--store ...]",
            "  canonical       --data D --datalake DL --ids F --out PREFIX [--n N] [--add K] [--store ...]");

    public static void main(String[] argv) {
        // la salida siempre en UTF-8, como Python
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), true, StandardCharsets.UTF_8));
        int code;
        try {
            code = run(argv);
        } catch (Args.UsageException e) {
            String prog = argv.length > 0 && COMMANDS.contains(argv[0]) ? "searchengine " + argv[0] : "searchengine";
            System.err.println(USAGE.split("\n")[0]);
            System.err.println(prog + ": error: " + e.getMessage());
            code = 2;
        } catch (Throwable e) {
            e.printStackTrace();
            code = 1;
        }
        System.out.flush();
        System.exit(code);
    }

    static int run(String[] argv) throws Exception {
        if (argv.length == 0) {
            throw new Args.UsageException("the following arguments are required: command");
        }
        if (Arrays.asList(argv).contains("-h") || Arrays.asList(argv).contains("--help")) {
            System.out.println(USAGE);
            return 0;
        }
        String command = argv[0];
        if (!COMMANDS.contains(command)) {
            throw new Args.UsageException("argument command: invalid choice: '" + command + "' (choose from "
                    + String.join(", ", COMMANDS) + ")");
        }
        Args args = new Args(command, Arrays.copyOfRange(argv, 1, argv.length));
        switch (command) {
            case "pipeline":
                return cmdPipeline(args);
            case "search":
                return cmdSearch(args);
            case "ingest":
                return Bench.cmdIngest(args);
            case "bench-datalake":
                return Bench.cmdBenchDatalake(args);
            case "bench-index":
                return Bench.cmdBenchIndex(args);
            default:
                return Bench.cmdCanonical(args);
        }
    }

    static int cmdPipeline(Args args) throws Exception {
        Path data = Path.of(args.required("--data"));
        String layout = args.choice("--layout", "range", Datalake.LAYOUTS, false);
        String source = args.get("--source", Source.DEFAULT_BASE);
        String candidatesFile = args.get("--candidates", null);
        int steps = args.integer("--steps", 10, false);
        double delay = args.number("--delay", 1.0);
        String store = args.choice("--store", "sqlite", IndexStore.STORES, false);
        String mongoUri = args.get("--mongo-uri", DEFAULT_MONGO_URI);
        String mongoDb = args.get("--mongo-db", DEFAULT_MONGO_DB);
        args.finish(false);

        List<Integer> candidates = candidatesFile != null && !candidatesFile.isEmpty()
                ? Spec.loadIds(Path.of(candidatesFile)) : null;
        try (Pipeline pipe = new Pipeline(data, layout, store, source, candidates, delay, System.out::println,
                mongoUri, mongoDb)) {
            pipe.run(steps);
        }
        return 0;
    }

    static int cmdSearch(Args args) throws Exception {
        Path data = Path.of(args.required("--data"));
        String store = args.choice("--store", "sqlite", IndexStore.STORES, false);
        String mongoUri = args.get("--mongo-uri", DEFAULT_MONGO_URI);
        String mongoDb = args.get("--mongo-db", DEFAULT_MONGO_DB);
        args.finish(true);
        if (args.positional.isEmpty()) {
            throw new Args.UsageException("the following arguments are required: query");
        }

        Map<String, Object> result = Pipeline.search(data, store, String.join(" ", args.positional), mongoUri, mongoDb);
        System.out.println(Json.pretty(result));
        return 0;
    }
}
