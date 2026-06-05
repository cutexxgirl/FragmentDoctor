# Fragment Doctor

Fragment Doctor is a modular NeoForge 1.21.1 diagnostics mod by cutexxgirl.

The long-term goal is to inspect TPS, MSPT, FPS and other runtime signals in large RPG modpacks, then help identify the real source of lag or instability.

## Modules

- `scaner` - command-driven TPS/MSPT cause profiler.

## Layout

- `dev.cutexxgirl.fragmentdoctor` - mod entry point and shared constants.
- `dev.cutexxgirl.fragmentdoctor.modules` - module contracts and shared module code.
- `dev.cutexxgirl.fragmentdoctor.modules.scaner` - first diagnostics module.

## Scaner commands

- `/fragmentdoctor scaner start [seconds]` - start a profiling session, default 60 seconds.
- `/fragmentdoctor scaner stop` - stop the active session and write a summary.
- `/fragmentdoctor scaner status` - show whether a scan is running.
- `/fragmentdoctor scaner last` - show the last summary path.

Scaner writes AI-readable diagnostic files to `fragmentdoctor/scaner` in the server/game working directory:

- `scan-YYYYMMDD-HHMMSS.jsonl` - detailed event stream.
- `scan-YYYYMMDD-HHMMSS-summary.md` - ranked suspect summary.

The first version focuses on TPS/MSPT. It instruments server ticks, level ticks, chunk/block ticks, entity ticks and block entity ticks through diagnostic mixins. Deep tracing only records data while a scan session is active.
