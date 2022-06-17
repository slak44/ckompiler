#!/bin/bash

rm a.out 2>/dev/null
rm time 2>/dev/null

function test() {
    for (( i = 0; i < 20; i++ )); do
        /usr/bin/time -f '%e' ./a.out > /tmp/values.txt 2>> ./time
    done

    echo "$1"
    cat ./time

    rm a.out
    rm time
}

ckompiler -isystem ../stdlib/include sin-taylor-all.c
test ckompiler

gcc sin-taylor-all.c
test gcc

gcc -O3 sin-taylor-all.c
test "gcc -O3"

clang sin-taylor-all.c
test clang

clang -O3 sin-taylor-all.c
test "clang -O3"
