package exec
import core.CoreDef
import chisel3._
import chisel3.util.MuxLookup
import chisel3.util.log2Ceil
import chisel3.util.MuxCase

class ResStationExgress(implicit val coredef: CoreDef) extends Bundle {
  val instr = Output(new ReservedInstr)
  val valid = Output(Bool())
  val pop = Input(Bool())
}

/**
 * Reservation station
 * 
 * For every cycle, only one instr maybe issued into this station,
 * and only one ready instr may start executing
 */
class ResStation(implicit val coredef: CoreDef) extends MultiIOModule {

  // Sorry, I was unable to come up with a good naming for the ports
  val ingress = IO(new Bundle {
    val instr = Input(new ReservedInstr)

    /**
     * This is not a simple decoupled valid-ready handshake
     * 
     * free should always be asserted before commit, because the issuer may need
     * to decide between multiple applicable ready exec units
     */
    val free = Output(Bool())
    val push = Input(Bool())
  })

  val exgress = IO(new ResStationExgress)

  val cdb = IO(Input(new CDB))

  val store = RegInit(VecInit(Seq.fill(coredef.RESERVATION_STATION_DEPTH)(ReservedInstr.empty)))
  val occupied = RegInit(VecInit(Seq.fill(coredef.RESERVATION_STATION_DEPTH)(false.B)))

  // Ingress part
  ingress.free := occupied.foldLeft(false.B)((acc, valid) => acc || !valid)
  val ingIdx = MuxCase(
    DontCare.asTypeOf(UInt(log2Ceil(coredef.RESERVATION_STATION_DEPTH).W)),
    occupied.zipWithIndex.map({
      case (valid, idx) => (!valid, idx.U(log2Ceil(coredef.RESERVATION_STATION_DEPTH).W))
    })
  )
  when(ingress.push) {
    occupied(ingIdx) := true.B
    store(ingIdx) := ingress.instr
  }

  // Exgress part
  exgress.valid := store.zip(occupied).foldLeft(false.B)((acc, grp) => grp match {
    case (instr, valid) => acc || (valid && instr.ready)
  })
  // MuxCase can handle multiple enabled cases
  val exgIdx = MuxCase(
    DontCare.asTypeOf(UInt(log2Ceil(coredef.RESERVATION_STATION_DEPTH).W)),
    store.zip(occupied).zipWithIndex.map({
      case ((instr, valid), idx) => (valid && instr.ready, idx.U(log2Ceil(coredef.RESERVATION_STATION_DEPTH).W))
    })
  )
  exgress.instr := store(exgIdx)
  when(exgress.pop) {
    occupied(exgIdx) := false.B
  }

  assert(!(
    ingress.push &&
    exgress.pop &&
    ingIdx === exgIdx
  ))

  // CDB data fetch
  for((instr, valid) <- store.zip(occupied)) {
    // Later entries takes priority
    for(ent <- cdb.entries) {
      when(valid) {
        when(ent.name =/= 0.U && ent.name === instr.rs1name) {
          // This cannot happen because we limit the inflight instr count,
          // so that reg names should not wrap around for in-flight instrs

          assert(!instr.rs1ready)
          instr.rs1ready := true.B
          instr.rs1val := ent.data
        }

        when(ent.name =/= 0.U && ent.name === instr.rs2name) {
          assert(!instr.rs2ready)
          instr.rs2ready := true.B
          instr.rs2val := ent.data
        }
      }
    }
  }

  // TODO: optimize: exgress.pop && ingress.push at the same cycle
}