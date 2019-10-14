#!/usr/bin/env bash
dot -Tpng compiler-pipeline.dot > compiler-pipeline.png || exit 1
xdg-open compiler-pipeline.png
