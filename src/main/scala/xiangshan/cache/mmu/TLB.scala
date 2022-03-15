/***************************************************************************************
* Copyright (c) 2020-2021 Institute of Computing Technology, Chinese Academy of Sciences
* Copyright (c) 2020-2021 Peng Cheng Laboratory
*
* XiangShan is licensed under Mulan PSL v2.
* You can use this software according to the terms and conditions of the Mulan PSL v2.
* You may obtain a copy of Mulan PSL v2 at:
*          http://license.coscl.org.cn/MulanPSL2
*
* THIS SOFTWARE IS PROVIDED ON AN "AS IS" BASIS, WITHOUT WARRANTIES OF ANY KIND,
* EITHER EXPRESS OR IMPLIED, INCLUDING BUT NOT LIMITED TO NON-INFRINGEMENT,
* MERCHANTABILITY OR FIT FOR A PARTICULAR PURPOSE.
*
* See the Mulan PSL v2 for more details.
***************************************************************************************/

package xiangshan.cache.mmu

import chipsalliance.rocketchip.config.Parameters
import chisel3._
import chisel3.internal.naming.chiselName
import chisel3.util._
import freechips.rocketchip.util.SRAMAnnotation
import xiangshan._
import utils._
import xiangshan.backend.fu.{PMPChecker, PMPReqBundle}
import xiangshan.backend.rob.RobPtr
import xiangshan.backend.fu.util.HasCSRConst
import firrtl.FirrtlProtos.Firrtl.Module.ExternalModule.Parameter

/** TLB module
  * support block request and non-block request io at the same time
  * return paddr at next cycle, then go for pmp/pma check
  * @param Width: The number of requestors
  * @param NonBlock: The number of non-block requestors (part of Width)
  * @param q: TLB Parameters, like entry number, each TLB has its own parameters
  * @param p: XiangShan Paramemters, like XLEN
  */

@chiselName
class TLB(Width: Int, Block: Seq[Boolean], q: TLBParameters)(implicit p: Parameters) extends TlbModule
  with HasCSRConst
  with HasPerfEvents
{
  val io = IO(new TlbIO(Width, q))

  require(q.superAssociative == "fa")

  val req = io.requestor.map(_.req)
  val resp = io.requestor.map(_.resp)
  val ptw = io.ptw
  val pmp = io.pmp

  val flush = io.sfence.valid
  val ifecth = if (q.fetchi) true.B else false.B
  val mode = if (q.useDmode) io.csr.priv.dmode else io.csr.priv.imode
  // val vmEnable = satp.mode === 8.U // && (mode < ModeM) // FIXME: fix me when boot xv6/linux...
  val vmEnable = if (EnbaleTlbDebug) (io.csr.satp.mode === 8.U)
    else (io.csr.satp.mode === 8.U && (mode < ModeM))

  val req_in = req
  val req_out = req.map(a => RegEnable(a.bits, a.fire()))
  val req_out_v = (0 until Width).map(i => ValidHold(req_in(i).fire, resp(i).fire, flush))

  val entries = Module(new TlbStorageWrapper(Width, q))
  entries.io.base_connect(io.sfence, io.csr)
  if (q.outReplace) { io.replace <> entries.io.replace }
  val refill = ptw.resp.fire() && !io.sfence.valid && !io.csr.satp.changed
  (0 until Width).foreach{i =>
    entries.io.r_req_apply(
      valid = io.requestor(i).req.valid,
      vpn = get_pn(req_in(i).bits.vaddr),
      i = i
    )
    entries.io.w_apply(
      valid = refill,
      data = ptw.resp.bits,
      data_replenish = io.ptw_replenish
    )
  }

  // read TLB, get hit/miss, paddr, perm bits
  val readResult = (0 until Width).map(TLBRead(_))
  val hitVec = readResult.map(_._1)
  val missVec = readResult.map(_._2)
  val pmp_addr = readResult.map(_._3)
  val static_pm = readResult.map(_._4)
  val static_pm_v = readResult.map(_._5)
  val perm = readResult.map(_._6)

  // check pmp use paddr (for timing optization, use pmp_addr here)
  // check permisson
  (0 until Width).foreach{i =>
    pmp_check(pmp_addr(i), req_out(i).size, req_out(i).cmd, i)
    perm_check(perm(i), req_out(i).cmd, static_pm(i), static_pm_v(i), i)
  }

  // handle block or non-block io
  // for non-block io, just return the above result, send miss to ptw
  // for block io, hold the request, send miss to ptw,
  //   when ptw back, return the result
  (0 until Width) foreach {i =>
    if (Block(i)) handle_block(i)
    else handle_nonblock(i)
  }
  io.ptw.resp.ready := true.B

  /****  main body above | method/log/perf below ****/

  def TLBRead(i: Int) = {
    val (hit, ppn, perm, super_hit, super_ppn, static_pm) = entries.io.r_resp_apply(i)

    /** *************** next cycle when two cycle is false******************* */
    val miss = !hit && vmEnable
    val fast_miss = !super_hit && vmEnable
    hit.suggestName(s"hit_${i}")
    miss.suggestName(s"miss_${i}")

    XSDebug(req_out_v(i), p"(${i.U}) hit:${hit} miss:${miss} ppn:${Hexadecimal(ppn)} perm:${perm}\n")

    val paddr = Cat(ppn, get_off(req_out(i).vaddr))
    val vaddr = SignExt(req_out(i).vaddr, PAddrBits)

    resp(i).bits.paddr := Mux(vmEnable, paddr, vaddr)
    resp(i).bits.miss := miss
    resp(i).bits.fast_miss := fast_miss
    resp(i).bits.ptwBack := io.ptw.resp.fire()

    // val pmp_paddr = Mux(vmEnable, Cat(super_ppn, get_off(req_out(i).vaddr)), vaddr)
    // pmp_paddr seems same to paddr functionally. It abandons normal_ppn for timing optimization.
    val pmp_paddr = Mux(vmEnable, paddr, vaddr)
    val static_pm_valid = !super_hit && vmEnable && q.partialStaticPMP.B

    (hit, miss, pmp_paddr, static_pm, static_pm_valid, perm)
  }

  def pmp_check(addr: UInt, size: UInt, cmd: UInt, idx: Int): Unit = {
    pmp(idx).valid := resp(idx).valid
    pmp(idx).bits.addr := addr
    pmp(idx).bits.size := size
    pmp(idx).bits.cmd := cmd
  }

  def perm_check(perm: TlbPermBundle, cmd: UInt, spm: TlbPMBundle, spm_v: Bool, idx: Int) = {
    // for timing optimization, pmp check is divided into dynamic and static
    // dynamic: superpage (or full-connected reg entries) -> check pmp when translation done
    // static: 4K pages (or sram entries) -> check pmp with pre-checked results
    val af = perm.af
    val pf = perm.pf
    val ldUpdate = !perm.a && TlbCmd.isRead(cmd) && !TlbCmd.isAmo(cmd) // update A/D through exception
    val stUpdate = (!perm.a || !perm.d) && (TlbCmd.isWrite(cmd) || TlbCmd.isAmo(cmd)) // update A/D through exception
    val instrUpdate = !perm.a && TlbCmd.isExec(cmd) // update A/D through exception
    val modeCheck = !(mode === ModeU && !perm.u || mode === ModeS && perm.u && (!io.csr.priv.sum || ifecth))
    val ldPermFail = !(modeCheck && (perm.r || io.csr.priv.mxr && perm.x))
    val stPermFail = !(modeCheck && perm.w)
    val instrPermFail = !(modeCheck && perm.x)
    val ldPf = (ldPermFail || pf) && (TlbCmd.isRead(cmd) && !TlbCmd.isAmo(cmd))
    val stPf = (stPermFail || pf) && (TlbCmd.isWrite(cmd) || TlbCmd.isAmo(cmd))
    val instrPf = (instrPermFail || pf) && TlbCmd.isExec(cmd)
    val fault_valid = vmEnable
    resp(idx).bits.excp.pf.ld := (ldPf || ldUpdate) && fault_valid && !af
    resp(idx).bits.excp.pf.st := (stPf || stUpdate) && fault_valid && !af
    resp(idx).bits.excp.pf.instr := (instrPf || instrUpdate) && fault_valid && !af
    // NOTE: pf need && with !af, page fault has higher priority than access fault
    // but ptw may also have access fault, then af happens, the translation is wrong.
    // In this case, pf has lower priority than af

    // for tlb without sram, tlb will miss, pm should be ignored outsize
    resp(idx).bits.excp.af.ld    := (af || (spm_v && !spm.r)) && TlbCmd.isRead(cmd) && fault_valid
    resp(idx).bits.excp.af.st    := (af || (spm_v && !spm.w)) && TlbCmd.isWrite(cmd) && fault_valid
    resp(idx).bits.excp.af.instr := (af || (spm_v && !spm.x)) && TlbCmd.isExec(cmd) && fault_valid
    resp(idx).bits.static_pm.valid := spm_v && fault_valid // ls/st unit should use this mmio, not the result from pmp
    resp(idx).bits.static_pm.bits := !spm.c
  }

  def handle_nonblock(idx: Int): Unit = {
    io.requestor(idx).resp.valid := req_out_v(idx)
    io.requestor(idx).req.ready := io.requestor(idx).resp.ready // should always be true
    io.ptw.req(idx).valid :=  RegNext(req_out_v(idx) && missVec(idx), false.B)
      !RegNext(refill, init = false.B) &&
      !RegNext(RegNext(refill, init = false.B), init = false.B)
    io.ptw.req(idx).bits.vpn := RegNext(get_pn(req_out(idx).vaddr))
  }

  def handle_block(idx: Int): Unit = {
    // three valid: 1. if exist a entry 2. if sent to ptw 3. unset resp.valid
    val miss_req_v = Reg(Bool()) // this valid for if req (not) sent to ptw
    val miss_v = Reg(Bool()) // this valid for if miss, try to unset resp.valid
    io.requestor(idx).req.ready := !req_out_v(idx) || io.requestor(idx).resp.fire()
    // req_out_v for if there is a request, may long latency, fixme

    // miss request entries
    val miss_req_vpn = get_pn(req_out(idx).vaddr)
    resp(idx).valid := req_out_v(idx) && !missVec(idx) && !miss_v
    when (missVec(idx)) { // TODO: bypass ptw's resp
      miss_v := true.B
      miss_req_v := true.B
    }
    // when ptw resp, check if hit, reset miss_v, resp to lsu/ifu
    when (io.ptw.resp.fire()) {
      val hit = io.ptw.resp.bits.entry.hit(miss_req_vpn, io.csr.satp.asid, allType = true)
      when (hit && req_out_v(idx)) {
        resp(idx).valid := true.B
        resp(idx).bits.miss := false.B // for blocked tlb, this is useless
      }
      // NOTE: the unfiltered req would be handled by Repeater
    }
    val ptw_req = io.ptw.req(idx)
    ptw_req.valid := miss_req_v
    ptw_req.bits.vpn := miss_req_vpn
    when (ptw_req.fire()) { miss_req_v := false.B }

    when (flush) {
      miss_req_v := false.B
      miss_v := false.B
    }
  }

  // perf event
  val result_ok = req_in.map(a => RegNext(a.fire()))
  val perfEvents =
    Seq(
      ("access", PopCount((0 until Width).map{i => if (Block(i)) io.requestor(i).req.fire() else vmEnable && result_ok(i) })),
      ("miss  ", PopCount((0 until Width).map{i => if (Block(i)) vmEnable && result_ok(i) && missVec(i) else ptw.req(i).fire() })),
    )
  generatePerfEvent()

  // perf log
  for (i <- 0 until Width) {
    if (Block(i)) {
      XSPerfAccumulate(s"access${i}",result_ok(i)  && vmEnable)
      XSPerfAccumulate(s"miss${i}", result_ok(i) && missVec(i))
    } else {
      XSPerfAccumulate("first_access" + Integer.toString(i, 10), result_ok(i) && vmEnable && RegNext(req(i).bits.debug.isFirstIssue))
      XSPerfAccumulate("access" + Integer.toString(i, 10), result_ok(i) && vmEnable)
      XSPerfAccumulate("first_miss" + Integer.toString(i, 10), result_ok(i) && vmEnable && missVec(i) && RegNext(req(i).bits.debug.isFirstIssue))
      XSPerfAccumulate("miss" + Integer.toString(i, 10), result_ok(i) && vmEnable && missVec(i))
    }
  }
  //val reqCycleCnt = Reg(UInt(16.W))
  //reqCycleCnt := reqCycleCnt + BoolStopWatch(ptw.req(0).fire(), ptw.resp.fire || sfence.valid)
  //XSPerfAccumulate("ptw_req_count", ptw.req.fire())
  //XSPerfAccumulate("ptw_req_cycle", Mux(ptw.resp.fire(), reqCycleCnt, 0.U))
  XSPerfAccumulate("ptw_resp_count", ptw.resp.fire())
  XSPerfAccumulate("ptw_resp_pf_count", ptw.resp.fire() && ptw.resp.bits.pf)

  // Log
  for(i <- 0 until Width) {
    XSDebug(req(i).valid, p"req(${i.U}): (${req(i).valid} ${req(i).ready}) ${req(i).bits}\n")
    XSDebug(resp(i).valid, p"resp(${i.U}): (${resp(i).valid} ${resp(i).ready}) ${resp(i).bits}\n")
  }

  XSDebug(io.sfence.valid, p"Sfence: ${io.sfence}\n")
  XSDebug(ParallelOR(req_out_v) || ptw.resp.valid, p"vmEnable:${vmEnable} hit:${Binary(VecInit(hitVec).asUInt)} miss:${Binary(VecInit(missVec).asUInt)}\n")
  for (i <- ptw.req.indices) {
    XSDebug(ptw.req(i).fire(), p"PTW req:${ptw.req(i).bits}\n")
  }
  XSDebug(ptw.resp.valid, p"PTW resp:${ptw.resp.bits} (v:${ptw.resp.valid}r:${ptw.resp.ready}) \n")

  println(s"${q.name}: normal page: ${q.normalNWays} ${q.normalAssociative} ${q.normalReplacer.get} super page: ${q.superNWays} ${q.superAssociative} ${q.superReplacer.get}")

}

class TLBNonBlock(Width: Int, q: TLBParameters)(implicit p: Parameters) extends TLB(Width, Seq.fill(Width)(false), q)
class TLBBLock(Width: Int, q: TLBParameters)(implicit p: Parameters) extends TLB(Width, Seq.fill(Width)(true), q)

class TlbReplace(Width: Int, q: TLBParameters)(implicit p: Parameters) extends TlbModule {
  val io = IO(new TlbReplaceIO(Width, q))

  if (q.normalAssociative == "fa") {
    val re = ReplacementPolicy.fromString(q.normalReplacer, q.normalNWays)
    re.access(io.normalPage.access.map(_.touch_ways))
    io.normalPage.refillIdx := re.way
  } else { // set-acco && plru
    val re = ReplacementPolicy.fromString(q.normalReplacer, q.normalNSets, q.normalNWays)
    re.access(io.normalPage.access.map(_.sets), io.normalPage.access.map(_.touch_ways))
    io.normalPage.refillIdx := { if (q.normalNWays == 1) 0.U else re.way(io.normalPage.chosen_set) }
  }

  if (q.superAssociative == "fa") {
    val re = ReplacementPolicy.fromString(q.superReplacer, q.superNWays)
    re.access(io.superPage.access.map(_.touch_ways))
    io.superPage.refillIdx := re.way
  } else { // set-acco && plru
    val re = ReplacementPolicy.fromString(q.superReplacer, q.superNSets, q.superNWays)
    re.access(io.superPage.access.map(_.sets), io.superPage.access.map(_.touch_ways))
    io.superPage.refillIdx := { if (q.superNWays == 1) 0.U else re.way(io.superPage.chosen_set) }
  }
}
