package cache

import chisel3._
import _root_.data._
import chisel3.util.log2Ceil
import chisel3.experimental.ChiselEnum
import chisel3.util._
import Chisel.experimental.chiselName

class ICPort(val opts: L1Opts) extends Bundle {
  val addr = Input(UInt(opts.ADDR_WIDTH.W))
  val read = Input(Bool())

  val stall = Output(Bool())
  val flush = Input(Bool()) // Branch missperdict, flushing all running requests
  val rst = Input(Bool())

  val data = Output(UInt(opts.TRANSFER_SIZE.W)) // Data delay is 1 cycle
  val vacant = Output(Bool())
}

object S2State extends ChiselEnum {
  val rst, idle, refill, refillWait = Value
}

class Line(val opts: L1Opts) extends Bundle {
  val INDEX_OFFSET_WIDTH = log2Ceil(opts.SIZE)
  val TAG_WIDTH = opts.ADDR_WIDTH - INDEX_OFFSET_WIDTH
  val TRANSFER_COUNT = opts.LINE_WIDTH * 8 / opts.TRANSFER_SIZE

  val tag = UInt(TAG_WIDTH.W)
  val valid = Bool()
  val data = Vec(TRANSFER_COUNT, UInt(opts.TRANSFER_SIZE.W))
}

// TODO: Change to xpm_tdpmem
@chiselName
class L1IC(opts: L1Opts) extends MultiIOModule {
  val toCPU = IO(new ICPort(opts))
  val toL2 = IO(new L1ICPort(opts))

  toCPU.data := DontCare
  toL2.addr := DontCare
  toL2.read := false.B

  val LINE_COUNT = opts.SIZE / opts.LINE_WIDTH
  val LINE_PER_ASSOC = LINE_COUNT / opts.ASSOC

  val ASSOC_IDX_WIDTH = log2Ceil(opts.ASSOC)

  val OFFSET_WIDTH = log2Ceil(opts.LINE_WIDTH)
  val INDEX_OFFSET_WIDTH = log2Ceil(opts.SIZE / opts.ASSOC)
  val INDEX_WIDTH = INDEX_OFFSET_WIDTH - OFFSET_WIDTH
  val IGNORED_WIDTH = log2Ceil(opts.TRANSFER_SIZE / 8)

  val stores = Seq.fill(opts.ASSOC)({ SyncReadMem(LINE_PER_ASSOC, new Line(opts)) })

  def getTransferOffset(addr: UInt) = addr(OFFSET_WIDTH-1, IGNORED_WIDTH)
  def getIndex(addr: UInt) = addr(INDEX_OFFSET_WIDTH-1, OFFSET_WIDTH)
  def getTag(addr: UInt) = addr(opts.ADDR_WIDTH-1, INDEX_OFFSET_WIDTH)
  def toAligned(addr: UInt) = getTag(addr) ## getIndex(addr) ## 0.U(OFFSET_WIDTH.W)

  // Stage 1, tag fetch, data fetch
  val pipeRead = RegInit(false.B)
  val pipeAddr = RegInit(0.U(opts.ADDR_WIDTH.W))

  val readingAddr = Wire(UInt(opts.ADDR_WIDTH.W))
  when(toCPU.stall) {
    readingAddr := pipeAddr
  }.otherwise {
    readingAddr := toCPU.addr
  }
  val rawReadouts = stores.map(s => s.read(getIndex(readingAddr)))
  val savedReadouts = Seq.fill(opts.ASSOC)(Reg(new Line(opts)))

  val readouts = Seq.fill(opts.ASSOC)(Wire(new Line(opts)))

  // Stage 2, data mux, refilling, reset
  val state = RegInit(S2State.rst)
  val nstate = Wire(S2State())

  // To prevents RAW
  for((r, (raw, saved)) <- readouts.iterator.zip(rawReadouts.iterator.zip(savedReadouts.iterator))) {
    r := Mux(RegNext(state) === S2State.refill && state === S2State.idle, raw, raw)
  }

  val rand = chisel3.util.random.LFSR(8)
  val victim = RegInit(0.U(log2Ceil(opts.ASSOC)))

  val rstCnt = RegInit(0.U(INDEX_WIDTH.W))
  val rstLine = Wire(new Line(opts))
  rstLine.data := 0.U.asTypeOf(rstLine.data)
  rstLine.tag := 0.U
  rstLine.valid := false.B

  nstate := state
  state := nstate

  val stalled = nstate =/= S2State.idle
  toCPU.stall := stalled
  toCPU.vacant := true.B

  when(!toCPU.stall) {
    pipeRead := toCPU.read
    pipeAddr := toCPU.addr
  }

  val waitBufAddr = RegInit(0.U(opts.ADDR_WIDTH.W))
  val waitBufFull = RegInit(false.B)

  when(state === S2State.rst) {
    for(assoc <- stores) {
      assoc.write(rstCnt, rstLine)
    }

    rstCnt := rstCnt +% 1.U

    when(rstCnt.andR()) {
      // Omit current request
      toCPU.vacant := true.B
      nstate := S2State.idle
    }
  }.elsewhen(toCPU.flush) {
    when(toCPU.rst) {
      nstate := S2State.rst
    }.otherwise {
      toCPU.vacant := true.B
      toCPU.stall := false.B
      // Omit current request
      // FIXME: may break refiller

      nstate := S2State.idle

      when(state === S2State.refillWait) {
        when(toL2.stall) {
          waitBufFull := false.B
        }.otherwise {
          nstate := S2State.idle
        }
      }

      when(state === S2State.refill) {
        when(toL2.stall) {
          nstate := S2State.refillWait
          waitBufFull := false.B
        }.otherwise {
          nstate := S2State.idle
        }
      }
    }
  }.otherwise {
    switch(state) {
      is(S2State.idle) {
        val hit = readouts.foldLeft(false.B)((acc, line) => acc || line.valid && line.tag === getTag(pipeAddr))
        val rdata = MuxCase(0.U.asTypeOf(new Line(opts).data), readouts.map(line => (line.valid && getTag(pipeAddr) === line.tag, line.data)))

        when(pipeRead) {
          when(hit) {
            toCPU.vacant := false.B
            toCPU.data := rdata(getTransferOffset(pipeAddr))
          }.otherwise {
            nstate := S2State.refill
          }
        }.otherwise {
          toCPU.vacant := true.B
          nstate := S2State.idle
        }
      }

      is(S2State.refill) {
        toL2.addr := toAligned(pipeAddr)
        toL2.read := true.B

        when(!toL2.stall) {
          val written = Wire(new Line(opts))
          written.tag := getTag(pipeAddr)
          written.data := toL2.data.asTypeOf(written.data)
          written.valid := true.B

          for((store, idx) <- stores.zipWithIndex) {
            savedReadouts(idx) := rawReadouts(idx)
            when(rand(ASSOC_IDX_WIDTH-1, 0) === idx.U(ASSOC_IDX_WIDTH.W)) {
              store.write(getIndex(pipeAddr), written)
              savedReadouts(idx) := written
            }
          }

          toCPU.data := written.data(getTransferOffset(pipeAddr))
          toCPU.vacant := false.B

          nstate := S2State.idle
        }
      }

      is(S2State.refillWait) {
        toL2.addr := toAligned(pipeAddr)
        toL2.read := true.B

        // Override stall signal, so that IF doesn't proceed
        toCPU.vacant := true.B
        toCPU.stall := waitBufFull

        when(!toL2.stall) {
          nstate := S2State.idle
          // Ignore returned value
          when(waitBufFull) {
            pipeAddr := waitBufAddr
          }
        }.elsewhen(toCPU.read) {
          waitBufAddr := toCPU.addr
          waitBufFull := true.B
        }
      }
    }
  }
}