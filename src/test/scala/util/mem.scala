package util

import chisel3.util.experimental.loadMemoryFromFile
import chisel3._
import chisel3.util._
import data.AXI
import firrtl.annotations.MemoryLoadFileType

class AXIMem(init: Option[String], depth: Int = 65536, addrWidth: Int = 48) extends Module {
  val io = IO(new Bundle {
    val axi = Flipped(new AXI(8, addrWidth))
  });

  // Async read, sync write mem
  val memory = Mem(depth, UInt(8.W))
  if(init.isDefined)
    loadMemoryFromFile(memory, init.get, MemoryLoadFileType.Binary)
  
  // default signals
  io.axi <> DontCare
  io.axi.ARREADY := false.B
  io.axi.AWREADY := false.B
  io.axi.WREADY := false.B
  io.axi.RVALID := false.B
  io.axi.BVALID := false.B

  val sIDLE :: sREADING :: sWRITING :: sRESP :: nil = Enum(4)

  val target = RegInit(0.U(addrWidth))
  val remaining: UInt = RegInit(0.U(log2Ceil(addrWidth)))
  val state = RegInit(sIDLE)

  // Assumes Burst = INCR
  switch(state) {
    is(sIDLE) {
      when(io.axi.ARVALID) {
        target := io.axi.ARADDR
        remaining := io.axi.ARLEN
        io.axi.ARREADY := true.B
        state := sREADING
      }

      when(io.axi.AWVALID) {
        target := io.axi.AWADDR
        remaining := io.axi.AWLEN
        io.axi.AWREADY := true.B
        state := sWRITING
      }
    }

    is(sREADING) {
      io.axi.RDATA := memory(target)
      io.axi.RVALID := true.B

      when(io.axi.RREADY) {
        target := target + 1.U
        remaining := remaining - 1.U
        when(remaining === 1.U) {
          state := sIDLE
        }
      }
    }

    is(sWRITING) {
      io.axi.WREADY := true.B
      when(io.axi.WVALID) {
        memory.write(target, io.axi.WDATA)
        target := target + 1.U
        remaining := remaining - 1.U
        when(remaining === 1.U) {
          state := sRESP
        }
      }
    }

    is(sRESP) {
      io.axi.BRESP := AXI.Constants.Resp.OKAY.U
      io.axi.BVALID := true.B

      when(io.axi.BREADY) {
        state := sIDLE
      }
    }
  }
}
