package slak.test.backend

import org.junit.jupiter.api.Test
import slak.ckompiler.analysis.IntBinary
import slak.ckompiler.analysis.IntConstant
import slak.ckompiler.analysis.IntegralBinaryOps
import slak.ckompiler.analysis.VirtualRegister
import slak.ckompiler.backend.x64.Imm
import slak.ckompiler.backend.x64.ModRM
import slak.ckompiler.backend.x64.X64InstrTemplate
import slak.ckompiler.backend.x64.X64Target
import slak.ckompiler.parser.SignedIntType
import slak.test.int
import kotlin.test.assertEquals

class X64Tests {
  @Test
  fun `Instruction Selection On Simple Adds`() {
    val add = IntBinary(
        VirtualRegister(1, SignedIntType),
        IntegralBinaryOps.ADD,
        VirtualRegister(1, SignedIntType),
        IntConstant(int(4))
    )
    val mi = X64Target.expandMacroFor(add)
    assert(mi.template is X64InstrTemplate)
    assert(mi.template in slak.ckompiler.backend.x64.add)
    val instr = mi.template as X64InstrTemplate
    assertEquals(2, instr.operands.size)
    assert(instr.operands[0] is ModRM)
    assert(instr.operands[1] is Imm)
  }
}
