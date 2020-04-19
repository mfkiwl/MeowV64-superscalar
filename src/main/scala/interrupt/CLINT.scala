package interrupt

import chisel3._
import chisel3.experimental._
import chisel3.util._
import multicore.MulticoreDef

object CLINT {
  val CLINT_REGION_START = BigInt("FFFF000002000000", 16)
  val CLINT_REGION_SIZE = 0x10000
  val CLINT_ADDR_WIDTH = log2Ceil(CLINT_REGION_SIZE)
}

object CLINTReqOp extends ChiselEnum {
  val read, write = Value
}

class CLINTReq extends Bundle {
  val addr = UInt(CLINT.CLINT_ADDR_WIDTH.W)
  val wdata = UInt(64.W)
  val op = CLINTReqOp()
}

class LocalInt extends Bundle {
  val msip = Bool()
  val mtip = Bool()
}

class CLINT(implicit mcdef: MulticoreDef) extends MultiIOModule {
  val req = IO(DecoupledIO(new CLINTReq))
  val resp = IO(ValidIO(UInt(64.W)))

  val ints = IO(Vec(mcdef.CORE_COUNT, new LocalInt))

  // Timer
  val mtime = RegInit(0.U(64.W))
  mtime := mtime +% 1.U

  val mtimecmp = RegInit(VecInit(Seq.fill(mcdef.CORE_COUNT)(0.U(64.W))))
  for((c, m) <- ints.zip(mtimecmp)) {
    c.mtip := m < mtime
  }

  val msip = RegInit(VecInit(Seq.fill(mcdef.CORE_COUNT)(false.B)))
  for((c, s) <- ints.zip(msip)) {
    c.msip := s
  }

  object State extends ChiselEnum {
    val idle, commit = Value
  }

  object Seg extends ChiselEnum {
    val msip, mtimecmp, mtime = Value
  }

  val state = RegInit(State.idle)
  val seg = Reg(Seg())
  val idx = Reg(UInt(log2Ceil(mcdef.CORE_COUNT).W))
  val wdata = Reg(UInt(64.W))
  val write = RegInit(false.B)

  switch(state) {
    is(State.idle) {
      val cur = req.deq()
      seg := DontCare
      idx := DontCare
      wdata := cur.wdata
      write := cur.op === CLINTReqOp.write

      resp.bits := DontCare
      resp.valid := false.B

      when(cur.addr < 0x4000.U) {
        seg := Seg.msip
        idx := cur.addr(11, 0) >> 2
      }.elsewhen(cur.addr =/= 0xBFF8.U) {
        seg := Seg.mtimecmp
        idx := cur.addr(11, 0) >> 3
      }.otherwise {
        seg := Seg.mtime
      }

      when(req.fire()) {
        state := State.commit
      }
    }

    is(State.commit) {
      req.nodeq()

      state := State.idle

      resp.bits := DontCare
      resp.valid := true.B

      switch(seg) {
        is(Seg.msip) {
          resp.bits := msip(idx)
        }

        is(Seg.mtimecmp) {
          resp.bits := mtimecmp(idx)
        }

        is(Seg.mtime) {
          resp.bits := mtime
        }
      }

      when(write) {
        switch(seg) {
          is(Seg.msip) {
            msip(idx) := wdata(0)
          }

          is(Seg.mtimecmp) {
            mtimecmp(idx) := wdata
          }

          is(Seg.mtime) {
            mtime := wdata
          }
        }
      }
    }
  }
}
