.. include:: _config.rst

Build Targets
===========================================

CKompiler can be built and run in multiple ways. This page is an overview, for instructions see
:ref:`here <BuildInstructions>`.

Kotlin Multiplatform Project
----------------------------

We target the Kotlin/JVM and the Kotlin/JS targets using `MPP <https://kotlinlang.org/docs/mpp-intro.html>`_.

The core of the compiler is part of the common code module, so it is easily shared, and can be used as a library.

For JVM, a :ref:`CLI <CLI>` similar to clang and gcc is available, packaged using Gradle's application plugin.

For JS, everything marked with `@JsExport` is available for use, though interacting directly with them is cumbersome.
There are dedicated functions for using JS code in :js-path:`JSCompile.kt`.
The Kotlin build also generates a TypeScript definitions file.

Internals Explorer
------------------

This is an Angular app that takes advantage of the JS-compiled version.
It shows output for arbitrary code at varying stages of the compilation process,
like the `--cfg-mode` option in the CLI.
