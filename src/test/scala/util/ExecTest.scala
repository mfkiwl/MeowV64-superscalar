package util

import core._
import chisel3._
import chisel3.iotesters.PeekPokeTester
import exec.Exec
import chisel3.iotesters.TesterOptionsManager
import chisel3.iotesters.TesterOptions
import scala.collection.mutable
import java.nio.file.Path
import java.nio.file.Paths
import java.nio.ByteBuffer
import java.nio.ByteOrder
import multicore.MulticoreDef
import multicore.Multicore

object ExecDef extends MulticoreDef {
  override val INIT_VEC = BigInt(0x80000000L)
}

class ExecTest(dut: Multicore, file: String) extends PeekPokeTester(dut) {
  def doTest(bound: Int): Unit = {
    val mem: mutable.HashMap[Long, Long] = mutable.HashMap() // Addr to 4-byte
    var reading: Option[(Long, Long, Long)] = None // ID, Ptr, Left
    var writing: Option[(Long, Long)] = None // Ptr, Left
    var writingFinished = false

    // Load up memory
    val bytes = java.nio.file.Files.readAllBytes(Paths.get(file))
    val len = bytes.length
    val paddedLen = ((len + 7)/ 8) * 8
    val padded = bytes ++ Array.fill(paddedLen - len)(0.asInstanceOf[Byte])
    val buffer = ByteBuffer.wrap(padded)
    buffer.order(ByteOrder.LITTLE_ENDIAN)
    val longs = buffer.asLongBuffer()
    var idx = 0
    while(longs.hasRemaining()) {
      mem.put(idx * 8 + 0x80000000L, longs.get())
      idx += 1
    }
    println(s"Initialized: $idx longs")

    val axi = dut.io.axi

    // Test pass signal through tohost
    var finished = false
    for(i <- (0 until bound)) {
      // println("Cycle: " + i)
      step(1)

      // TODO: handles multicore
      if(peek(dut.io.debug(0).pc) == 0x100000 || finished) {
        println(s"> Process ended at cycle ${i}")

        val mcycle = peek(dut.io.debug(0).mcycle)
        val minstret = peek(dut.io.debug(0).minstret)

        println(s"> mcycle: ${mcycle}")
        println(s"> minstret: ${minstret}")

        return
      }

      // Simulate AXI
      // AR
      if(reading.isEmpty && peek(axi.ARVALID) == 1) {
        poke(axi.ARREADY, 1)
        reading = Some((
          peek(axi.ARID).longValue(),
          peek(axi.ARADDR).longValue(),
          peek(axi.ARLEN).longValue()
        ))
        // println(s"Read: 0x${reading.get._2.toHexString}")
      } else {
        poke(axi.ARREADY, 0)
      }

      // R
      if(reading.isDefined) {
        val (id, ptr, left) = reading.get
        poke(axi.RVALID, 1)
        poke(axi.RID, id)
        poke(axi.RDATA, mem.get(ptr).getOrElse(0L))
        poke(axi.RLAST, left == 0)
        // println(s"Returning: 0x${mem.get(ptr).getOrElse(0L).toHexString}")

        if(peek(axi.RREADY) == 1) {
          if(left == 0) reading = None
          else reading = Some(id, ptr + 8, left - 1)
        }
      } else {
        poke(axi.RVALID, 0)
      }

      // AW
      if(writing.isEmpty && peek(axi.AWVALID) == 1) {
        poke(axi.AWREADY, 1)
        writing = Some((
          peek(axi.AWADDR).longValue(),
          peek(axi.AWLEN).longValue()
        ))
        writingFinished = false
        // println(s"Write: 0x${writing.get._1.toHexString}")
      } else {
        poke(axi.AWREADY, 0)
      }

      // W
      if(writing.isDefined && !writingFinished) {
        val (ptr, left) = writing.get
        poke(axi.WREADY, 1)
        val wdata = peek(axi.WDATA)
        val wstrb = peek(axi.WSTRB)

        if(peek(axi.WVALID) == 1) {
          val muxed = ExecTest.longMux(mem.get(ptr).getOrElse(0), wdata.longValue(), wstrb.byteValue())
          mem.put(ptr, muxed)

          writing match {
            case Some((addr, _)) if addr == 0xFFFFFF10000000L => {
              // Print to serial
              print((wdata & 0xFF).toChar)
            }
            case Some((addr, _)) if addr == 0xFFFFFF80000000L => {
              // tohost in ISA testsuite
              val data = (wdata & 0xFFFFFFFF).toLong
              if(wdata == ((data & 0xFF) | BigInt("0101000000000000", 16))) {
                // Is simple print
                print((data & 0xFF).toChar)
              } else if(data == 1) {
                println("ISA Testsuite pass.")
                finished = true
              } else {
                println(s"ISA testsuite tohost: ${data}")
              }
            }
            case _ => {}
          }

          writing = Some(ptr + 8, left - 1)
          if(peek(axi.WLAST) == 1) {
            assume(left == 0)
            writingFinished = true
          }
        }
      } else {
        poke(axi.WREADY, 0)
      }

      if(writingFinished) {
        assume(writing.isDefined)
        poke(axi.BVALID, 1)
        poke(axi.BRESP, 0)
        poke(axi.BID, 0)

        if(peek(axi.BREADY) == 1) {
          writing = None
          writingFinished = false
        }
      } else {
        poke(axi.BVALID, 0)
      }
    }

    throw new Error(s"Did not finished within ${bound} cycles")
  }

  doTest(100000)
}

object ExecTest {
  var elaborated = false

  def runFile(file: String, args: Option[Array[String]] = None): Boolean = {
    println(s"------------\nRunning file $file")
    if(elaborated) {
      return chisel3.iotesters.Driver.run(
        () => new Multicore()(ExecDef),
        "./tests/VMulticore"
      ) {
        new ExecTest(_, file)
      }
    }
    println("Not elaborated. Running chisel generator...")

    val manager = new TesterOptionsManager {
      commonOptions = commonOptions.copy(
        targetDirName = "./tests",
        topName = "Core"
      )
      testerOptions = testerOptions.copy(
        backendName = "verilator",
        generateVcdOutput = "on"
      )
    }

    val parsed = args match {
      case None => manager
      case Some(args) => {
        if(!manager.parse(args)) return false
        manager
      }
    }

    elaborated = true

    chisel3.iotesters.Driver.execute(() => new Multicore()(ExecDef), manager) { new ExecTest(_, file) }
  }

  def longMux(base: Long, input: Long, be: Byte): Long = {
    var ret = 0L
    for(i <- (0 until 8)) {
      val sel = if(((be >>> i) & 1) == 1) {
        // Select input
        (input >>> (i*8)) & 0xFF
      } else {
        (base >>> (i*8)) & 0xFF
      }
      ret = (sel << (i*8)) | ret
    }

    ret
  }
}
