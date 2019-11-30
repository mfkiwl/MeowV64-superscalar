package exec
import chisel3._
import reg._
import data._
import instr._
import chisel3.util._
import _root_.core.StageCtrl
import instr.Decoder.InstrType
import _root_.core.CSRWriter
import _root_.core.CoreDef
import _root_.core.ExReq
import _root_.core.ExType
import cache.DCReader
import cache.DCWriter

class BranchResult(val ADDR_WIDTH: Int = 48) extends Bundle {
  val branch = Bool()
  val target = UInt(ADDR_WIDTH.W)

  val ex = ExReq()
  val extype = ExType()

  def nofire() = {
    branch := false.B
    target := DontCare

    ex := ExReq.none
    extype := DontCare
  }

  def fire(addr: UInt) = {
    branch := true.B
    target := addr

    ex := ExReq.none
    extype := DontCare
  }

  def ex(et: ExType.Type) {
    branch := false.B
    target := DontCare

    ex := ExReq.ex
    extype := et
  }

  def eret() {
    branch := false.B
    target := DontCare

    ex := ExReq.ret
    extype := DontCare
  }
}


class Exec(coredef: CoreDef) extends MultiIOModule {
  val io = IO(new Bundle {
    val regReaders = Vec(2, new RegReader)
    val regWriter = new RegWriter

    val ctrl = StageCtrl.stage()

    val branch = Output(new BranchResult(coredef.ADDR_WIDTH))
    val brSrc = Output(UInt(coredef.ADDR_WIDTH.W))

    val csrWriter = new CSRWriter(coredef.XLEN)
  })

  val toIF = IO(new InstrFifoReader(coredef))

  val toDC = IO(new Bundle {
    val r = new DCReader(coredef.L1D)
    val w = new DCWriter(coredef.L1D)
  })

  io.branch := 0.U.asTypeOf(io.branch)
  io.regWriter.addr := 0.U
  io.regWriter.data := 0.U

  toIF.pop := 0.U

  val default = VecInit(Seq.fill(coredef.ISSUE_NUM)(InstrExt.empty(coredef.ADDR_WIDTH)))
  val current = RegInit(default)
  val instr = RegInit(0.U(log2Ceil(coredef.ISSUE_NUM+1).W))
  val readRs1 = Wire(UInt(coredef.XLEN.W))
  val readRs2 = Wire(UInt(coredef.XLEN.W))

  io.branch.branch := false.B
  io.branch.target := DontCare

  io.branch.ex := ExReq.none
  io.branch.extype := DontCare

  io.brSrc := DontCare

  io.regReaders(0).addr := current(instr).instr.rs1
  io.regReaders(1).addr := current(instr).instr.rs2
  readRs1 := io.regReaders(0).data
  readRs2 := io.regReaders(1).data

  val alu = Module(new ALU(coredef.ADDR_WIDTH, coredef.XLEN))
  val imm = Module(new Imm(coredef.ADDR_WIDTH, coredef.XLEN))
  val lsu = Module(new LSU(coredef.ADDR_WIDTH, coredef.XLEN, coredef.L1D))
  val br = Module(new Branch(coredef.ADDR_WIDTH, coredef.XLEN))
  val mul = Module(new Mul(coredef.ADDR_WIDTH, coredef.XLEN))
  val div = Module(new Div(coredef.ADDR_WIDTH, coredef.XLEN, 32))
  val csr = Module(new CSR(coredef.ADDR_WIDTH, coredef.XLEN))

  lsu.reader <> toDC.r
  lsu.writer <> toDC.w

  csr.writer <> io.csrWriter

  val units = List(alu, imm, lsu, br, mul, div, csr)

  val placeholder = Wire(new PipeInstr(coredef.ADDR_WIDTH, coredef.XLEN))
  placeholder := 0.U.asTypeOf(placeholder)
  placeholder.instr.vacant := true.B

  var stall = false.B
  for(u <- units) {
    stall = stall || u.io.stall
  }

  val sIDLE :: sRUNNING :: nil = Enum(2)
  val unitState = RegInit(sIDLE)
  val unitStateNext = Wire(sIDLE.cloneType)
  unitStateNext := unitState

  when(!stall) {
    unitState := unitStateNext
  }

  val substall = (!current(instr).vacant) && (stall || unitStateNext =/= sIDLE)
  io.ctrl.stall := instr =/= coredef.ISSUE_NUM.U && !io.ctrl.flush

  when(!substall && instr === (coredef.ISSUE_NUM-1).U) {
    io.ctrl.stall := false.B
  }

  val ifReady = toIF.cnt === coredef.ISSUE_NUM.U

  when(io.ctrl.flush) {
    current := default
  }.elsewhen(ifReady && !io.ctrl.stall) {
    /*
    printf("[FETCH]: \n")
    printf(p"${io.instr}")
    printf("\n[FETCH]")
    */
    current := toIF.view
    toIF.pop := coredef.ISSUE_NUM.U
  }

  /*
  when(!substall && instr < ISSUE_NUM.U) {
    printf(p"EX:\n================\n")
    printf(p"Running: ${Hexadecimal(current(instr).addr)}\n")
    printf(p"readRs1: 0x${Hexadecimal(readRs1)}\n")
    printf(p"readRs2: 0x${Hexadecimal(readRs2)}\n")
    printf(p"Writing To: 0x${Hexadecimal(io.regWriter.addr)}\n")
    printf(p"Writing Data: 0x${Hexadecimal(io.regWriter.data)}\n")
  }
  */

  when(io.ctrl.flush) {
    instr := 0.U
  } .elsewhen(!io.ctrl.stall && ifReady) {
    instr := 0.U
  }.elsewhen(!substall && instr =/= coredef.ISSUE_NUM.U) {
    instr := instr + 1.U
  }

  val unitInput = Wire(new PipeInstr(coredef.ADDR_WIDTH, coredef.XLEN))
  unitInput.instr := current(instr)
  unitInput.rs1val := readRs1
  unitInput.rs2val := readRs2

  /*
  printf(p">>>>>>>>>>>>>>>\n")
  printf(p"nextup: ${unitInput}\n")
  */

  for(u <- units) {
    u.io.next := placeholder
  }

  when(instr =/= coredef.ISSUE_NUM.U && !current(instr).vacant && unitState === sIDLE) {
    unitStateNext := sRUNNING

    // printf(p"current instr: ${instr}\n")
    switch(current(instr).instr.op) {

      // Arith/Logical
      is(Decoder.Op("OP").ident,
        Decoder.Op("OP-IMM").ident) {
          when(
            current(instr).instr.op === Decoder.Op("OP").ident
            && current(instr).instr.funct7 === Decoder.MULDIV_FUNCT7
          ) {
            when(current(instr).instr.funct3(2)) { // funct3 >= 0b100 ==> DIV/REM
              div.io.next := unitInput
            }.otherwise {
              mul.io.next := unitInput
            }
          }.otherwise {
            alu.io.next := unitInput
          }
        }

      is(Decoder.Op("OP-32").ident,
        Decoder.Op("OP-IMM-32").ident) {
          when(
            current(instr).instr.op === Decoder.Op("OP-32").ident
            && current(instr).instr.funct7 === Decoder.MULDIV_FUNCT7
          ) {
            when(current(instr).instr.funct3(2)) { // funct3 >= 0b100 ==> DIV/REM
              div.io.next := unitInput
            }.otherwise {
              mul.io.next := unitInput
            }
          }.otherwise {
            alu.io.next := unitInput
          }
        }

      // Load immediate
      is(Decoder.Op("AUIPC").ident,
        Decoder.Op("LUI").ident) {
          imm.io.next := unitInput
        }

      // Load/Store
      is(Decoder.Op("LOAD").ident,
        Decoder.Op("STORE").ident) {
          lsu.io.next := unitInput
        }

      // Branch
      is(Decoder.Op("JAL").ident,
        Decoder.Op("JALR").ident,
        Decoder.Op("BRANCH").ident) {
          br.io.next := unitInput
        }
      
      // System
      is(Decoder.Op("SYSTEM").ident) {
        // ECALL and EBREAK actually falls into here.
        // TODO: impl ECALL/EBREAK

        when(unitInput.instr.instr.funct3 === Decoder.SYSTEM_FUNC("PRIV")) {
          // ECALL/EBREAK/xRET
          br.io.next := unitInput
        }.otherwise {
          csr.io.next := unitInput
        }
      }

    }
  }.otherwise {
    // printf("Vacant, skipped exec")
  }

  for(u <- units) {
    u.io.pause := false.B
    u.io.flush := io.ctrl.flush

    /*
    when(u.io.stall) {
      printf(p"[STALL ]: Stalled by ${u.name}\n")
    }
    */

    when(!u.io.stall && (u.io.retirement.branch.branch || u.io.retirement.branch.ex =/= ExReq.none)) {
      io.branch := u.io.retirement.branch
      io.brSrc := u.io.retired.instr.addr
      io.ctrl.stall := false.B // Forcefully unstall
      // printf(p"[BRANCH] ${Hexadecimal(branchedAddr)}\n")
    }

    when(u.io.retirement.regWaddr =/= 0.U) {
      io.regWriter.addr := u.io.retirement.regWaddr
      io.regWriter.data := u.io.retirement.regWdata
      // printf(p"[COMMIT]: By ${u.name}: ${io.regWriter.addr} <- 0x${Hexadecimal(io.regWriter.data)}\n")
    }

    when(!u.io.retired.instr.vacant && !u.io.stall) {
      unitStateNext := sIDLE
    }
  }

  /*
  when(io.regWriter.addr =/= 0.U) {
    when(io.regWriter.addr =/= RegNext(io.regWriter.addr) || io.regWriter.data =/= RegNext(io.regWriter.data)) {
      printf(p"[COMMIT]: ${Decimal(io.regWriter.addr)} <- ${Hexadecimal(io.regWriter.data)}\n")
    }
  }
  */
}
