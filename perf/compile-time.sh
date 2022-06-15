#!/bin/bash

echo ckompiler

for (( i = 0; i < 20; i++ )); do
    time ckompiler -isystem ../stdlib/include ./generated-c/*.c
done

echo
echo gcc

for (( i = 0; i < 20; i++ )); do
    time gcc ./generated-c/*.c
done

echo
echo clang

for (( i = 0; i < 20; i++ )); do
    time clang -Wno-tautological-compare ./generated-c/*.c
done

rm a.out
