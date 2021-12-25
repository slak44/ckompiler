.. include:: _config.rst

.. _CLI:

The CLI
=====================================

For an extensive list of options, run `ckompiler --help`.

The command line interface is intended to emulate `gcc`/`clang` where possible.
Many common flags like `-o`, `-c`, `-D`, `-I`, `-l` do exactly what they're expected to do.

The CLI also supports various debugging options, such as the `--cfg-mode`-related opts, or the `--print-asm-comm` flag.

The argument parsing is done using an older version of `kotlinx.cli <https://github.com/Kotlin/kotlinx.cli>`_
(see the kotlinx.cli package in the `jvmMain` module) along with a bunch of custom extensions that can be
found in :jvm-path:`CLIExtensions.kt`. The actual code that powers the command line can be found in the
:jvm-path:`CLI <CLI.kt>` class.

