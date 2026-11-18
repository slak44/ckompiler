.. include:: _config.rst

.. _BuildInstructions:

Build Instructions
==================

Java 11 or newer is required for any of the build targets.

Node (preferably 16+) is required for the JS target and the Angular app.

CLI
---

Run `./gradlew distZip`. This downloads gradle and required dependencies.
Output will be created in `build/distributions/ckompiler-$version.zip`.

Tests
-----

To run the tests: `./gradlew test`. This runs the JVM tests.

The JUnit tests can be found in the `slak.test` package, in `src/test/kotlin`.

JS Library
----------

Run `./gradlew jsBrowserProductionLibraryDistribution`.
Output will be created in `build/dist/js/productionLibrary`.

Internals Explorer Angular App
------------------------------

First, follow the instructions for building the JS library. Then, cd into the `internals-explorer` directory and run
`npm install && ng build`. Output will be created in `internals-explorer/dist/internals-explorer`.

Alternatively, `ng serve` will build and serve it using webpack's dev server.

Documentation (this site!)
--------------------------

Enter the `documentation` directory, and create a python venv. Then run `pip install -r requirements.txt` to install
sphinx and the theme. You can then run `sphinx-build -b html source/ build/html` to create the HTML you're reading right
now.

Dokka Kotlin documentation
--------------------------

Run `./gradlew dokkaHtml`.
