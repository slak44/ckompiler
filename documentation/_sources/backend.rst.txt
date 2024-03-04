.. include:: _config.rst

Back-end
========

Target-Specific Information
---------------------------

Certain constants depend on the target ISA and/or the machine (the size of an `int`, for example).
`MachineTargetData` instances contain all this information.

`MachineTargetData` is also responsible for generating various macros used in stdlib headers (such as
`__PTRDIFF_T_TYPE` for `stddef.h`).

This class is technically part of the backend, but certain features handled in the front-end (eg `sizeof`) also depend
on this target-specific data.

For now, only x64 Linux is supported, but the infrastructure for x86 and other platforms exists.

The `MachineInstruction` class
------------------------------

This class represents an instruction along with its operands (`IRValue`), so it is still target-independent.
All the target-dependent aspects of an instruction are contained within the `InstructionTemplate` stored by the MI (see
below for more).

The Instruction Graph
---------------------

.. _CFG: https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/analysis/ControlFlowGraph.kt
.. _InstructionGraph:
   https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/backend/InstructionGraph.kt

A `CFG`_ is sufficient to represent the control flow, but in order to more
cleanly separate responsibilities, and to avoid turning the CFG class into a god
object, a new graph representation is introduced, `InstructionGraph`_.

This class is the CFG's analogue in the backend (and is actually created by copying the structure of the existing CFG).
Similarly, `InstrBlock` is the analogue for `BasicBlock`.

`InstrBlock` implements the `MutableList<MachineInstruction>` interface, for easily interacting with the instructions in
a block.

The Code Generation Interfaces
------------------------------

.. _MachineTarget.kt:
   https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/backend/MachineTarget.kt

These interfaces describe the code generation process in a target-independent
manner. The code can be found in `MachineTarget.kt`_.

1. `MachineTarget`: This interface describes a particular compilation target.
   It contains the relevant `MachineTargetData` instance, and data about the register file of the target ISA.
2. `TargetOptions`: Represents the set of CLI options passed to a target.
3. `MachineRegisterClass`: Describes a type of register in the ISA. For example,
   x64 distinguishes between general purpose integer registers (`rax`, `rbp`,
   etc) and SSE/AVX registers (`xmm4`, `ymm1`, etc).
4. `MachineRegister`: A description of a particular register in the ISA. Stores
   size, class, aliases.
5. `TargetFunGenerator`: Implementors of this interface are code generators for
   a single function graph, for a particular `MachineTarget`. An instance of this
   class holds all the state required for all the code generation stages before
   emission.
6. `AsmEmitter`: Takes the result of all the function generators in a
   translation unit and emits the actual assembly string. Currently, this means
   it emits NASM.
7. `PeepholeOptimizer`: Takes a list of `AsmInstruction`, and optimizes them in isolation. Used by `AsmEmitter`.
8. `InstructionTemplate`: Represents a particular instruction from an ISA. For
   example: `mov r/m64, r64` or `add r32, imm32`.
9. `AsmInstruction`: Final generated instruction, ready for emission.

Code Generation Process
-----------------------

.. _RegisterAllocation.kt:
   https://github.com/slak44/ckompiler/tree/master/src/main/kotlin/slak/ckompiler/backend/RegisterAllocation.kt

First, `TargetFunGenerator` is created for each function in the
translation unit. They are passed to an `AsmEmitter`, which returns the
assembled code. The emitter is the one who triggers the actual generation
process for each function's generator.

Generation starts with instruction selection. Instruction selection creates
`MachineInstruction` lists for each block, which are stored in the corresponding
`InstrBlock`. The block has overridden methods for adding to the list: they
also incrementally update the last uses map to account for the operands of the
instruction.

The next step is register allocation. Note that the code is still in SSA form: φ
functions have not been removed, and variables are only assigned once per
version. The register allocator is based on `this paper <hack_>`_, and is
target-independent (code in `RegisterAllocation.kt`_).

The allocator is a graph coloring register allocator (GCRA), but it allocates
directly over SSA. Other GCRAs run an SSA destruction algorithm before
allocation, but this actually introduces additional complexity: the coloring
influences spilling, and coalescing influences both. A graph that is not
k-colorable will have to be rebuilt after a spill. Both coloring and coalescing
will have to run after spills.

Allocation over SSA allows this process to be decoupled: SSA interference graphs
are chordal (also from `here <hack_>`_), so k-colorability can be determined in
polynomial time. That is, register pressure at all labels in the program can be
known beforehand. That information is used to figure out spill locations before
coloring (all labels where the register pressure is higher than the available
registers). The resulting graph can be then colored (also in polynomial time),
and it is guaranteed that coloring will not fail, because we forcefully reduced
the chromatic number via spilling. Coalescing runs after coloring, but before
implementing φs.

The register allocator alters the blocks of the `InstructionGraph`. Copies
and/or spills are inserted, even new blocks might be inserted. The allocator also
produces a list of spilled variables, as well its primary purpose, the actual
allocation of registers to variables/temporaries.

After this is done, the function generator creates the function prologue/epilogue, and the final `AsmInstruction` list
by replacing every `IRValue` with actual registers and memory locations.

Finally, the `AsmEmitter` emits the actual assembly from the `AsmInstruction` lists created by the generator.

`--mi-debug` mode
-----------------

Like the `--cfg-mode` option for the graphviz CFG, the backend has a debugging option.
By default, it prints the code at various execution points (`MachineInstruction`, `InstrBlock` and/or `AsmInstruction`).
It also prints the list of allocations made, and any allocation violations (eg same register used for multiple live
values).

The `--mi-html` prints this information in pretty HTML instead: `example page <_static/example-mi-debug.html>`_.
