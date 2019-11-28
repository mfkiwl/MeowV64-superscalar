package exec

import chisel3._
import chisel3.util._
import instr.Decoder
import _root_.core._

class CSRExt(val XLEN: Int) extends Bundle {
  val rdata = UInt(XLEN.W)
}

class CSR(ADDR_WIDTH: Int, XLEN: Int)
  extends ExecUnit(0, new CSRExt(XLEN), ADDR_WIDTH, XLEN)
{
  val writer = IO(new CSRWriter(XLEN))
  writer.op := CSROp.rs
  writer.addr := 0.U
  writer.wdata := 0.U

  override def map(stage: Int, pipe: PipeInstr, ext: Option[CSRExt]): (CSRExt, Bool) = {
    // Asserts pipe.op === SYSTEM

    val ext = Wire(new CSRExt(XLEN))
    writer.addr := pipe.instr.instr.funct7 ## pipe.instr.instr.rs2

    switch(pipe.instr.instr.funct3) {
      is(Decoder.SYSTEM_FUNC("CSRRW")) {
        writer.op := CSROp.rw
        writer.wdata := pipe.rs1val
      }

      is(Decoder.SYSTEM_FUNC("CSRRWI")) {
        writer.op := CSROp.rw
        writer.wdata := pipe.instr.instr.imm.asUInt // Zero extends
      }

      is(Decoder.SYSTEM_FUNC("CSRRS")) {
        writer.op := CSROp.rs
        writer.wdata := pipe.rs1val
      }

      is(Decoder.SYSTEM_FUNC("CSRRSI")) {
        writer.op := CSROp.rs
        writer.wdata := pipe.instr.instr.imm.asUInt // Zero extends
      }

      is(Decoder.SYSTEM_FUNC("CSRRC")) {
        writer.op := CSROp.rc
        writer.wdata := pipe.rs1val
      }

      is(Decoder.SYSTEM_FUNC("CSRRCI")) {
        writer.op := CSROp.rc
        writer.wdata := pipe.instr.instr.imm.asUInt // Zero extends
      }
    }

    ext.rdata := writer.rdata

    (ext, false.B)
  }

  override def finalize(pipe: PipeInstr, ext: CSRExt): RetireInfo = {
    val info = Wire(new RetireInfo(ADDR_WIDTH, XLEN))
    info.branch.nofire()
    info.regWaddr := pipe.instr.instr.rd

    info.regWdata := ext.rdata

    info
  }

  init()
}