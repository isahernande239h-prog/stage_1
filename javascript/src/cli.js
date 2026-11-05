// Usage: node src/cli.js <command> [options]   (same commands and options as python -m searchengine)
import { benchCommands } from './bench.js';
import { LAYOUTS } from './datalake.js';
import { STORES, DEFAULT_MONGO_URI } from './index_stores.js';
import { Pipeline, search } from './pipeline.js';
import { DEFAULT_BASE } from './source.js';
import { loadIds } from './spec.js';

const STORE_ARGS = {
  store: { choices: STORES, default: 'sqlite' },
  'mongo-uri': { default: DEFAULT_MONGO_URI },
  'mongo-db': { default: 'searchengine' },
};

function storeOptions(args) {
  return { mongoUri: args.mongoUri, mongoDb: args.mongoDb };
}

async function runPipeline(args) {
  const candidates = args.candidates ? loadIds(args.candidates) : null;
  const pipe = await Pipeline.create(args.data, {
    layout: args.layout, store: args.store, source: args.source, candidates, delay: args.delay, ...storeOptions(args),
  });
  await pipe.run(args.steps);
  await pipe.close();
}

async function runSearch(args) {
  const result = await search(args.data, args.store, args.query.join(' '), storeOptions(args));
  console.log(JSON.stringify(result, null, 2));
}

const COMMANDS = {
  pipeline: {
    help: 'run steps of the control layer',
    options: {
      data: { required: true },
      layout: { choices: LAYOUTS, default: 'range' },
      source: { default: DEFAULT_BASE, help: 'base URL or cache:<folder>' },
      candidates: { help: 'file with candidate ids (default: spec/corpus_ids.txt)' },
      steps: { type: 'int', default: 10 },
      delay: { type: 'float', default: 1.0, help: 'seconds between downloads' },
      ...STORE_ARGS,
    },
    run: runPipeline,
  },
  search: {
    help: 'find books that contain all the terms',
    options: { data: { required: true }, ...STORE_ARGS },
    positional: 'query',
    run: runSearch,
  },
  ...benchCommands(STORE_ARGS, LAYOUTS),
};

function usage(command) {
  if (!command) {
    const lines = Object.entries(COMMANDS).map(([name, def]) => `  ${name.padEnd(16)}${def.help ?? ''}`);
    return `usage: node src/cli.js <command> [options]\n\ncommands:\n${lines.join('\n')}`;
  }
  const def = COMMANDS[command];
  const lines = Object.entries(def.options).map(([name, opt]) => {
    let text = `  --${name}`;
    if (opt.choices) text += ` {${opt.choices.join(',')}}`;
    if (opt.required) text += ' (required)';
    else if (opt.default !== undefined) text += ` (default: ${opt.default})`;
    return opt.help ? `${text}  ${opt.help}` : text;
  });
  const positional = def.positional ? ` ${def.positional} [${def.positional} ...]` : '';
  return `usage: node src/cli.js ${command} [options]${positional}\n\noptions:\n${lines.join('\n')}`;
}

// like argparse: message on stderr and exit code 2
function fail(message, command) {
  console.error(`${usage(command)}\n\nerror: ${message}`);
  process.exit(2);
}

function camelCase(name) {
  return name.replace(/-([a-z])/g, (_, c) => c.toUpperCase());
}

function convert(name, opt, value, command) {
  if (opt.choices && !opt.choices.includes(value)) {
    fail(`--${name}: '${value}' is not valid (choices: ${opt.choices.join(', ')})`, command);
  }
  if (opt.type === 'int') {
    if (!/^\s*[+-]?[0-9]+\s*$/.test(value)) fail(`--${name}: '${value}' is not an integer`, command);
    return Number(value);
  }
  if (opt.type === 'float') {
    const number = Number(value);
    if (value.trim() === '' || Number.isNaN(number)) fail(`--${name}: '${value}' is not a number`, command);
    return number;
  }
  return value;
}

// Accepts "--name value" and "--name=value". The keys of the result are in camelCase.
function parseArgs(command, argv) {
  const def = COMMANDS[command];
  const args = { command };
  const positionals = [];
  for (let i = 0; i < argv.length; i++) {
    const arg = argv[i];
    if (arg === '-h' || arg === '--help') {
      console.log(usage(command));
      process.exit(0);
    }
    if (!arg.startsWith('--')) {
      positionals.push(arg);
      continue;
    }
    let name = arg.slice(2);
    let value = null;
    const eq = name.indexOf('=');
    if (eq >= 0) {
      value = name.slice(eq + 1);
      name = name.slice(0, eq);
    }
    const opt = def.options[name];
    if (!opt) fail(`unknown option: ${arg}`, command);
    if (opt.type === 'flag') {
      if (value !== null) fail(`--${name} does not take a value`, command);
      args[camelCase(name)] = true;
      continue;
    }
    if (value === null) {
      if (i + 1 >= argv.length) fail(`missing value for --${name}`, command);
      value = argv[++i];
    }
    args[camelCase(name)] = convert(name, opt, value, command);
  }

  for (const [name, opt] of Object.entries(def.options)) {
    const key = camelCase(name);
    if (key in args) continue;
    if (opt.required) fail(`missing option --${name}`, command);
    args[key] = opt.type === 'flag' ? false : (opt.default ?? null);
  }
  if (def.positional) {
    if (positionals.length === 0) fail(`missing ${def.positional}`, command);
    args[def.positional] = positionals;
  } else if (positionals.length > 0) {
    fail(`unrecognized arguments: ${positionals.join(' ')}`, command);
  }
  return args;
}

async function main(argv) {
  const [command, ...rest] = argv;
  if (command === '-h' || command === '--help') {
    console.log(usage());
    return;
  }
  if (!command) fail('missing command');
  if (!COMMANDS[command]) fail(`unknown command: ${command}`);
  const args = parseArgs(command, rest);
  await COMMANDS[command].run(args);
}

await main(process.argv.slice(2));
