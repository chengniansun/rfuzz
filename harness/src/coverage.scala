package pynq

import chisel3._
import chisel3.util._

class SaturatingCounter(width : Int) extends Module {
	val io = this.IO(new Bundle {
		val enable = Input(Bool())
		val value = Output(UInt(width.W))
	})
	val count = RegInit(0.U(width.W))
	io.value := count
	val max = ((1 << width) - 1).U
	count := Mux(!io.enable || count === max, count, count + 1.U)
}

class CoverageControl extends Bundle {
	val start_next = Input(Bool())
	val done_next = Output(Bool())
}


// 1) reset with DUT
// 2) assert `do_collect` in first cycle after test, keep asserted!
// 3) wait for `collect_done` to be asserted
// 4) goto (1)
class Coverage(conf: CoverageConfig) extends Module {
	val out_width = 64
	// all counter output values concatenated
	val coverage_width = conf.counters.map{ case(n,w) => w }.reduce(_+_)
	// output(0) is the test id!
	val output_count = div2Ceil(coverage_width, out_width)
	val test_id_width = 64

	val io = this.IO(new Bundle {
		val control = new CoverageControl
		// from DUT
		val coverage_signals = Input(UInt(conf.counters.size.W))
		// simple axi stream producer
		val axis_ready = Input(Bool())
		val axis_valid = Output(Bool())
		val axis_data  = Output(UInt(out_width.W))
	})
	val axis_fire = io.axis_ready && io.axis_valid

	val collecting = RegInit(false.B)
	when(io.control.start_next) { collecting := true.B }
	when(io.control.done_next) { collecting := false.B }

	val connect_coverage = !collecting
	val coverage = {
		var left = conf.counters.size - 1
		Cat(conf.counters.map{ case(n,w) => {
			val counter = Module(new SaturatingCounter(w))
			counter.io.enable := Mux(connect_coverage, io.coverage_signals(left), false.B)
			left = left - 1
			counter.io.value
		}}.toSeq)
	}

	val output_ii = Module(new WrappingCounter(log2Ceil(output_count)))
	output_ii.io.max := (output_count - 1).U
	output_ii.io.enable := axis_fire
	io.control.done_next := axis_fire && output_ii.io.last

	// axis
	io.axis_valid := collecting
	io.axis_data := MuxLookup(output_ii.io.value, 0.U, {
		var left = coverage_width - 1
		(0 until output_count).map{ case(ii) => ii.U -> {
			val right = left - out_width + 1
			val out = if(right >= 0) { coverage(left, right) } else {
				Cat(coverage(left, 0), 0.U((-right).W)) }
			left = left - out_width
			out }}
	})
}

