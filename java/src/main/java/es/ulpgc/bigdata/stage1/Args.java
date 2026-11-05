package es.ulpgc.bigdata.stage1;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Simple parsing of "--option value" (or "--option=value"), with the same names as argparse in Python. */
class Args {
    static final Set<String> FLAGS = Set.of("--reconcile");

    static class UsageException extends RuntimeException {
        UsageException(String message) {
            super(message);
        }
    }

    final String command;
    final List<String> positional = new ArrayList<>();
    private final Map<String, String> values = new LinkedHashMap<>();
    private final Set<String> used = new HashSet<>();

    Args(String command, String[] argv) {
        this.command = command;
        for (int i = 0; i < argv.length; i++) {
            String arg = argv[i];
            if (arg.equals("--")) {
                for (i++; i < argv.length; i++) {
                    positional.add(argv[i]);
                }
                break;
            }
            if (!arg.startsWith("--")) {
                positional.add(arg);
                continue;
            }
            int eq = arg.indexOf('=');
            if (eq > 0) {
                values.put(arg.substring(0, eq), arg.substring(eq + 1));
            } else if (FLAGS.contains(arg)) {
                values.put(arg, "");
            } else if (i + 1 < argv.length && !argv[i + 1].startsWith("--")) {
                values.put(arg, argv[++i]);
            } else {
                throw new UsageException("argument " + arg + ": expected one argument");
            }
        }
    }

    String get(String name, String def) {
        used.add(name);
        return values.getOrDefault(name, def);
    }

    String required(String name) {
        String value = get(name, null);
        if (value == null) {
            throw new UsageException("the following arguments are required: " + name);
        }
        return value;
    }

    String choice(String name, String def, List<String> choices, boolean required) {
        String value = required ? required(name) : get(name, def);
        if (value != null && !choices.contains(value)) {
            throw new UsageException("argument " + name + ": invalid choice: '" + value + "' (choose from "
                    + String.join(", ", choices) + ")");
        }
        return value;
    }

    Integer integer(String name, Integer def, boolean required) {
        String value = required ? required(name) : get(name, null);
        if (value == null) {
            return def;
        }
        try {
            return Integer.parseInt(value.strip());
        } catch (NumberFormatException e) {
            throw new UsageException("argument " + name + ": invalid int value: '" + value + "'");
        }
    }

    double number(String name, double def) {
        String value = get(name, null);
        if (value == null) {
            return def;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException e) {
            throw new UsageException("argument " + name + ": invalid float value: '" + value + "'");
        }
    }

    boolean flag(String name) {
        used.add(name);
        return values.containsKey(name);
    }

    /** Called after reading all the options of the command: anything left over is an error, like in argparse. */
    void finish(boolean takesPositional) {
        List<String> extra = new ArrayList<>();
        for (String name : values.keySet()) {
            if (!used.contains(name)) {
                extra.add(name);
            }
        }
        if (!takesPositional) {
            extra.addAll(positional);
        }
        if (!extra.isEmpty()) {
            throw new UsageException("unrecognized arguments: " + String.join(" ", extra));
        }
    }
}
