.. include:: _config.rst

Overview
=======================

The compiler is implemented as a processing pipeline of primarily immutable data structures.
Certain steps in the pipeline have dedicated class hierarchies (eg, `ASTNode` subclasses are for the parser), but much
of the code attaches various "stuff" to existing structures (eg variable renaming sets a version field on `Variable`).
Care has been taken to minimize the need for mutability, but sometimes it's just impractical to do so.

The following graph shows the path code takes through the compiler, from the source code to the output assembly:

.. image:: ../../readme-resources/compiler-pipeline.png
   :width: 100%
   :align: center
   :alt: Compiler Pipeline

`(graph source) <https://github.com/slak44/ckompiler/tree/master/readme-resources/compiler-pipeline.dot>`_
