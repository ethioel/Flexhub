package com.flexhub

/**
 * Minimal WebAssembly interpreter for the vidsrc per-window stream decryptor
 * modules (the vsdec.js family). The modules are tiny (6-8 KB), import-free
 * and i32-only: they embed per-5-minute-window key material in their data
 * segments and export `alloc(size) -> ptr` and `decrypt(ptr, len) -> outLen`.
 *
 * Encrypted blob layout: base64(nonce[12] || ciphertext); the plaintext is
 * found at ptr + 12 in module memory. Output is a newline-separated URL list.
 *
 * Supported subset (superset of what these modules use):
 *  - control: block, loop, if, else, end, br, br_if, br_table, return, call, nop, unreachable
 *  - vars: local.get/set/tee, global.get/set, drop, select
 *  - i32: const, eqz/eq/ne, comparisons (signed+unsigned), clz/ctz/popcnt,
 *         add/sub/mul/div/rem (signed+unsigned), and/or/xor/shl/shr/rotl/rotr
 *  - memory: i32.load, i32.load8_s/u, i32.load16_s/u, i32.store, i32.store8/16,
 *            memory.size, memory.grow
 * Anything outside the subset throws — fail closed rather than mis-decrypt.
 */
    object VsWasm {

        private const val MAX_MEMORY_BYTES = 64L * 1024 * 1024
        private const val MAX_INSTRUCTIONS = 50_000_000L


    fun decrypt(wasmBytes: ByteArray, encBytes: ByteArray): ByteArray {
        val inst = Instance(wasmBytes)
        val alloc = inst.exportedFunc("alloc")
        val decryptFn = inst.exportedFunc("decrypt")
        val ptr = inst.callFunction(alloc, intArrayOf(encBytes.size))[0]
        require(ptr >= 0 && ptr.toLong() + encBytes.size <= inst.memory.size.toLong()) {
            "decrypt: alloc returned out-of-range pointer $ptr"
        }
        System.arraycopy(encBytes, 0, inst.memory, ptr, encBytes.size)
        val outLen = inst.callFunction(decryptFn, intArrayOf(ptr, encBytes.size))[0]
        require(outLen >= 0 && ptr.toLong() + 12 + outLen <= inst.memory.size.toLong()) {
            "decrypt: bad outLen $outLen"
        }
        return inst.memory.copyOfRange(ptr + 12, ptr + 12 + outLen)
    }

    // ------------------------------------------------------------------
    //  Binary parsing
    // ------------------------------------------------------------------

    private class Reader(val buf: ByteArray, var pos: Int) {
        fun byte(): Int = buf[pos++].toInt() and 0xff
        fun uleb(): Int {
            var r = 0; var s = 0
            while (true) {
                val b = byte()
                r = r or ((b and 0x7f) shl s)
                if (b and 0x80 == 0) return r
                s += 7
            }
        }
        fun sleb(): Int {
            var r = 0L
            var s = 0
            var b: Int
            do {
                b = byte()
                r = r or ((b.toLong() and 0x7fL) shl s)
                s += 7
            } while (b and 0x80 != 0)
            if (b and 0x40 != 0) r = r or (-1L shl s)
            return r.toInt()
        }
        fun bytes(n: Int): ByteArray { val r = buf.copyOfRange(pos, pos + n); pos += n; return r }
    }

    private class Insn(val op: Int, val a: Int = 0, val b: Int = 0, val c: Int = 0) {
        // block/loop/if: a = matching end index, b = matching else index (if only; else-or-end)
        // br/br_if: a = label depth; br_table: a = index into brTables
        // call: a = function index; local/global ops: a = index
        // i32.const: a = value; memory ops: a = offset immediate
        // c = block result arity (0 for void blocktypes)
    }

    private class Func(
        val nParams: Int,
        val nResults: Int,
        val nLocals: Int,
        val code: List<Insn>,
    )

    private class Instance(wasmBytes: ByteArray) {
        var memory: ByteArray
        private val globals = IntArray(16)
        private val funcs = ArrayList<Func>()
        private val exportFuncs = HashMap<String, Int>()
        private val brTables = ArrayList<IntArray>()
        private var memPagesInit = 0

        init {
            val r = Reader(wasmBytes, 0)
            val magic = r.bytes(4)
            require(magic[0] == 0x00.toByte() && magic[1] == 0x61.toByte() &&
                magic[2] == 0x73.toByte() && magic[3] == 0x6D.toByte()) { "not a wasm module" }
            r.bytes(4) // version

            var typeSec: List<Pair<Int, Int>> = emptyList()
            var funcTypes = IntArray(0)
            var codeOffsets: List<Pair<Int, Int>> = emptyList()
            val dataSegs = ArrayList<Pair<Int, ByteArray>>()

            while (r.pos < wasmBytes.size) {
                val secId = r.byte()
                val size = r.uleb()
                val secEnd = r.pos + size
                when (secId) {
                    1 -> { // type
                        val n = r.uleb()
                        val list = ArrayList<Pair<Int, Int>>(n)
                        repeat(n) {
                            check(r.byte() == 0x60) { "bad type entry" }
                            val np = r.uleb()
                            repeat(np) { check(r.byte() == 0x7f) { "only i32 params supported" } }
                            val nr = r.uleb()
                            repeat(nr) { check(r.byte() == 0x7f) { "only i32 results supported" } }
                            list.add(np to nr)
                        }
                        typeSec = list
                    }
                    2 -> throw IllegalArgumentException("wasm imports unsupported")
                    3 -> { // function
                        val n = r.uleb()
                        funcTypes = IntArray(n) { r.uleb() }
                    }
                    5 -> { // memory
                        check(r.uleb() >= 1) { "no memory" }
                        val flags = r.uleb()
                        memPagesInit = r.uleb()
                        if (flags and 1 != 0) r.uleb() // max pages (ignored)
                    }
                    6 -> { // global
                        val n = r.uleb()
                        check(n <= globals.size) { "too many globals" }
                        repeat(n) {
                            check(r.byte() == 0x7f) { "only i32 globals supported" }
                            r.byte() // mutability
                            check(r.byte() == 0x41) { "global init must be i32.const" }
                            val v = r.sleb()
                            check(r.byte() == 0x0b) { "bad global init expr" }
                            globals[it] = v
                        }
                    }
                    7 -> { // export
                        val n = r.uleb()
                        repeat(n) {
                            val l = r.uleb()
                            val name = String(r.bytes(l), Charsets.UTF_8)
                            val kind = r.byte()
                            val idx = r.uleb()
                            if (kind == 0) exportFuncs[name] = idx
                        }
                    }
                    10 -> { // code
                        val n = r.uleb()
                        val list = ArrayList<Pair<Int, Int>>(n)
                        repeat(n) {
                            val bsize = r.uleb()
                            list.add(r.pos to bsize)
                            r.pos += bsize
                        }
                        codeOffsets = list
                    }
                    11 -> { // data
                        val n = r.uleb()
                        repeat(n) {
                            val flags = r.uleb()
                            check(flags == 0) { "only active data segments supported" }
                            check(r.byte() == 0x41) { "data offset must be i32.const" }
                            val off = r.sleb()
                            check(r.byte() == 0x0b) { "bad data offset expr" }
                            val len = r.uleb()
                            dataSegs.add(off to r.bytes(len))
                        }
                    }
                    else -> r.pos = secEnd // custom/unknown: skip
                }
                r.pos = secEnd
            }

            require(memPagesInit > 0) { "module has no memory" }
            require(memPagesInit.toLong() * 65536 <= MAX_MEMORY_BYTES) { "module memory too large" }
            memory = ByteArray(memPagesInit * 65536)
            for ((off, bytes) in dataSegs) {
                require(off >= 0 && off + bytes.size <= memory.size) { "data segment out of range" }
                System.arraycopy(bytes, 0, memory, off, bytes.size)
            }

            require(funcTypes.size == codeOffsets.size) { "function/code count mismatch" }
            for (i in funcTypes.indices) {
                val (np, nr) = typeSec[funcTypes[i]]
                val bodyStart = codeOffsets[i].first
                val bodyEnd = bodyStart + codeOffsets[i].second
                val cr = Reader(wasmBytes, bodyStart)
                val groups = cr.uleb()
                var nLocals = 0
                repeat(groups) {
                    val cnt = cr.uleb()
                    check(cr.byte() == 0x7f) { "only i32 locals supported" }
                    nLocals += cnt
                }
                check(nLocals <= 4096) { "too many locals" }
                funcs.add(Func(np, nr, nLocals, decodeBody(wasmBytes, cr.pos, bodyEnd)))
            }
        }

        fun exportedFunc(name: String): Int =
            exportFuncs[name] ?: throw IllegalArgumentException("export '$name' not found")

        // ------------------------------------------------------------------
        //  Decode: immediates + pre-computed jump targets
        // ------------------------------------------------------------------

        private fun decodeBody(buf: ByteArray, start: Int, end: Int): List<Insn> {
            val r = Reader(buf, start)

            // Pass 1: linear decode with immediates.
            data class Raw(val op: Int, val a: Int, val b: Int)
            val raw = ArrayList<Raw>(256)
            val tables = ArrayList<IntArray>()
            val open = ArrayList<Int>() // indices into raw of open block/loop/if
            val endMap = HashMap<Int, Int>()
            val elseMap = HashMap<Int, Int>()

            while (r.pos < end) {
                val op = r.byte()
                when (op) {
                    0x02, 0x03, 0x04 -> { // block, loop, if
                        val bt = r.byte()
                        check(bt == 0x40 || bt == 0x7f) { "unsupported blocktype $bt" }
                        open.add(raw.size)
                        raw.add(Raw(op, -1, if (bt == 0x7f) 1 else 0))
                    }
                    0x05 -> { // else
                        val owner = open.last()
                        check(raw[owner].op == 0x04) { "else outside if" }
                        elseMap[owner] = raw.size
                        raw.add(Raw(op, 0, 0))
                    }
                    0x0b -> { // end
                        val owner = if (open.isEmpty()) -1 else open.removeAt(open.size - 1)
                        if (owner >= 0) endMap[owner] = raw.size
                        raw.add(Raw(op, 0, 0))
                    }
                    0x0c, 0x0d -> raw.add(Raw(op, r.uleb(), 0)) // br, br_if
                    0x0e -> { // br_table
                        val n = r.uleb()
                        val t = IntArray(n + 1) { r.uleb() }
                        tables.add(t)
                        raw.add(Raw(op, tables.size - 1, 0))
                    }
                    0x0f -> raw.add(Raw(op, 0, 0)) // return
                    0x10 -> raw.add(Raw(op, r.uleb(), 0)) // call
                    0x11 -> throw IllegalArgumentException("call_indirect unsupported")
                    0x1a, 0x1b -> raw.add(Raw(op, 0, 0)) // drop, select
                    0x20, 0x21, 0x22, 0x23, 0x24 -> raw.add(Raw(op, r.uleb(), 0))
                    in 0x28..0x3e -> {
                        // memarg = align:u32, offset:u32 (offset is the SECOND immediate)
                        r.uleb()
                        val off = r.uleb()
                        raw.add(Raw(op, off, 0))
                    }
                    0x3f, 0x40 -> { r.byte(); raw.add(Raw(op, 0, 0)) }
                    0x41 -> raw.add(Raw(op, r.sleb(), 0))
                    0x42 -> throw IllegalArgumentException("i64.const unsupported")
                    0x43 -> { r.bytes(4); raw.add(Raw(op, 0, 0)) }
                    0x44 -> throw IllegalArgumentException("f64.const unsupported")
                    else -> raw.add(Raw(op, 0, 0))
                }
            }
            check(open.isEmpty()) { "unbalanced control flow" }

            // Pass 2: resolve block/loop/if jump targets.
            val out = ArrayList<Insn>(raw.size)
            for (i in raw.indices) {
                val ins = raw[i]
                when (ins.op) {
                    0x02, 0x03, 0x04 -> {
                        val e = endMap[i] ?: throw IllegalStateException("block without end")
                        val el = if (ins.op == 0x04) (elseMap[i] ?: e) else 0
                        out.add(Insn(ins.op, e, el, ins.b))
                    }
                    else -> out.add(Insn(ins.op, ins.a, ins.b))
                }
            }
            return out
        }

        // ------------------------------------------------------------------
        //  Execution
        // ------------------------------------------------------------------

        fun callFunction(funcIdx: Int, args: IntArray): IntArray {
            val f = funcs.getOrNull(funcIdx) ?: throw IllegalArgumentException("bad function index $funcIdx")
            require(args.size == f.nParams) { "argument count mismatch" }
            val ret = exec(f, args)
            return if (f.nResults == 1) intArrayOf(ret) else intArrayOf()
        }

        private var depthGuard = 0

        private fun exec(f: Func, params: IntArray): Int {
            check(++depthGuard < 32) { "call depth exceeded" }
            try {
                val locals = IntArray(f.nParams + f.nLocals)
                System.arraycopy(params, 0, locals, 0, params.size)

                val code = f.code
                val nIns = code.size
                var stack = IntArray(1024)
                var sp = 0

                fun push(v: Int) {
                    if (sp == stack.size) {
                        val grown = IntArray(stack.size * 2)
                        System.arraycopy(stack, 0, grown, 0, stack.size)
                        stack = grown
                    }
                    stack[sp++] = v
                }

                // Control frames (parallel arrays; frame 0 = function-level frame).
                val cap = 64
                val fEntry = IntArray(cap)
                val fEnd = IntArray(cap)
                val fStart = IntArray(cap) // loop body start (instruction after header)
                val fIsLoop = BooleanArray(cap)
                val fArity = IntArray(cap)
                var fp = 1
                fEntry[0] = 0; fEnd[0] = nIns; fStart[0] = 0; fIsLoop[0] = false; fArity[0] = f.nResults

                var ip = 0
                var steps = 0L
                while (ip < nIns) {
                    if (++steps > MAX_INSTRUCTIONS) {
                        throw IllegalStateException("wasm instruction budget exceeded")
                    }
                    val ins = code[ip]
                    when (ins.op) {
                        0x00 -> throw IllegalStateException("wasm unreachable trap")
                        0x01 -> ip++ // nop

                        0x02, 0x03 -> { // block, loop
                            check(fp < cap) { "control stack overflow" }
                            fEntry[fp] = sp; fEnd[fp] = ins.a; fStart[fp] = ip + 1
                            fIsLoop[fp] = ins.op == 0x03
                            fArity[fp] = if (ins.op == 0x03) 0 else ins.c
                            fp++
                            ip++
                        }
                        0x04 -> { // if
                            check(fp < cap) { "control stack overflow" }
                            val cond = stack[--sp]
                            fEntry[fp] = sp; fEnd[fp] = ins.a; fStart[fp] = ip + 1
                            fIsLoop[fp] = false; fArity[fp] = ins.c
                            fp++
                            // cond true -> then-branch; else exists -> else body;
                            // no else -> the end instruction (pops the frame)
                            ip = when {
                                cond != 0 -> ip + 1
                                ins.b != ins.a -> ins.b + 1
                                else -> ins.b
                            }
                        }
                        0x05 -> ip = fEnd[fp - 1] // else: then-branch fallthrough -> end

                        0x0b -> { fp--; ip++ } // end

                        0x0c -> { // br
                            val frame = fp - 1 - ins.a
                            check(frame >= 0) { "bad br depth" }
                            val arity = fArity[frame]
                            val kept = if (arity > 0) stack[sp - arity] else 0
                            sp = fEntry[frame] + arity
                            if (arity > 0) stack[sp - 1] = kept
                            if (fIsLoop[frame]) { fp = frame + 1; ip = fStart[frame] }
                            else { fp = frame; ip = fEnd[frame] + 1 }
                        }
                        0x0d -> { // br_if
                            val cond = stack[--sp]
                            if (cond != 0) {
                                val frame = fp - 1 - ins.a
                                check(frame >= 0) { "bad br_if depth" }
                                val arity = fArity[frame]
                                val kept = if (arity > 0) stack[sp - arity] else 0
                                sp = fEntry[frame] + arity
                                if (arity > 0) stack[sp - 1] = kept
                                if (fIsLoop[frame]) { fp = frame + 1; ip = fStart[frame] }
                                else { fp = frame; ip = fEnd[frame] + 1 }
                            } else ip++
                        }
                        0x0e -> { // br_table
                            val idx = stack[--sp]
                            val table = brTables[ins.a]
                            val depth = table[if (idx < 0 || idx >= table.size - 1) table.size - 1 else idx]
                            val frame = fp - 1 - depth
                            check(frame >= 0) { "bad br_table depth" }
                            val arity = fArity[frame]
                            val kept = if (arity > 0) stack[sp - arity] else 0
                            sp = fEntry[frame] + arity
                            if (arity > 0) stack[sp - 1] = kept
                            if (fIsLoop[frame]) { fp = frame + 1; ip = fStart[frame] }
                            else { fp = frame; ip = fEnd[frame] + 1 }
                        }
                        0x0f -> return if (f.nResults == 1) stack[sp - 1] else 0 // return

                        0x10 -> { // call
                            val callee = funcs[ins.a]
                            val cArgs = IntArray(callee.nParams)
                            for (i in callee.nParams - 1 downTo 0) cArgs[i] = stack[--sp]
                            val ret = exec(callee, cArgs)
                            if (callee.nResults == 1) push(ret)
                            ip++
                        }
                        0x11 -> throw IllegalStateException("call_indirect unsupported")

                        0x1a -> sp-- // drop
                        0x1b -> { // select
                            val cond = stack[--sp]
                            val v2 = stack[--sp]
                            val v1 = stack[--sp]
                            push(if (cond != 0) v1 else v2)
                            ip++
                        }

                        0x20 -> { push(locals[ins.a]); ip++ }
                        0x21 -> { locals[ins.a] = stack[--sp]; ip++ }
                        0x22 -> { locals[ins.a] = stack[sp - 1]; ip++ }
                        0x23 -> { push(globals[ins.a]); ip++ }
                        0x24 -> { globals[ins.a] = stack[--sp]; ip++ }

                        0x28 -> { val addr = stack[--sp] + ins.a; push(load32(addr)); ip++ }
                        0x2c -> { val addr = stack[--sp] + ins.a; push(load8(addr)); ip++ }
                        0x2d -> { val addr = stack[--sp] + ins.a; push(load8(addr) and 0xff); ip++ }
                        0x2e -> { val addr = stack[--sp] + ins.a; push(load16(addr).toShort().toInt()); ip++ }
                        0x2f -> { val addr = stack[--sp] + ins.a; push(load16(addr)); ip++ }

                        0x36 -> { val v = stack[--sp]; put32(stack[--sp] + ins.a, v); ip++ }
                        0x3a -> { val v = stack[--sp]; store8(stack[--sp] + ins.a, v); ip++ }
                        0x3b -> {
                            val v = stack[--sp]; val addr = stack[--sp] + ins.a
                            store16(addr, v)
                            ip++
                        }

                        0x3f -> { push(memory.size / 65536); ip++ } // memory.size
                        0x40 -> { // memory.grow
                            val delta = stack[--sp]
                            val old = memory.size / 65536
                            val newBytes = (old + delta) * 65536
                            require(newBytes in 0..(64 * 1024 * 1024)) { "memory grow out of range" }
                            val grown = ByteArray(newBytes)
                            System.arraycopy(memory, 0, grown, 0, minOf(memory.size, newBytes))
                            memory = grown
                            push(old)
                            ip++
                        }

                        0x41 -> { push(ins.a); ip++ }
                        0x43 -> throw IllegalStateException("f32 unsupported")

                        0x45 -> { push(if (stack[--sp] == 0) 1 else 0); ip++ }
                        0x46 -> { val b = stack[--sp]; push(if (stack[--sp] == b) 1 else 0); ip++ }
                        0x47 -> { val b = stack[--sp]; push(if (stack[--sp] != b) 1 else 0); ip++ }
                        0x48 -> { val b = stack[--sp]; push(if (stack[--sp] < b) 1 else 0); ip++ }
                        0x49 -> { val b = stack[--sp]; push(if (Integer.compareUnsigned(stack[--sp], b) < 0) 1 else 0); ip++ }
                        0x4a -> { val b = stack[--sp]; push(if (stack[--sp] > b) 1 else 0); ip++ }
                        0x4b -> { val b = stack[--sp]; push(if (Integer.compareUnsigned(stack[--sp], b) > 0) 1 else 0); ip++ }
                        0x4c -> { val b = stack[--sp]; push(if (stack[--sp] <= b) 1 else 0); ip++ }
                        0x4d -> { val b = stack[--sp]; push(if (Integer.compareUnsigned(stack[--sp], b) <= 0) 1 else 0); ip++ }
                        0x4e -> { val b = stack[--sp]; push(if (stack[--sp] >= b) 1 else 0); ip++ }
                        0x4f -> { val b = stack[--sp]; push(if (Integer.compareUnsigned(stack[--sp], b) >= 0) 1 else 0); ip++ }

                        0x67 -> { push(java.lang.Integer.numberOfLeadingZeros(stack[--sp])); ip++ }
                        0x68 -> { push(java.lang.Integer.numberOfTrailingZeros(stack[--sp])); ip++ }
                        0x69 -> { push(java.lang.Integer.bitCount(stack[--sp])); ip++ }

                        0x6a -> { val b = stack[--sp]; stack[sp - 1] += b; ip++ }
                        0x6b -> { val b = stack[--sp]; stack[sp - 1] -= b; ip++ }
                        0x6c -> { val b = stack[--sp]; stack[sp - 1] *= b; ip++ }
                        0x6d -> { val b = stack[--sp]; val a = stack[--sp]; push(if (b == 0) 0 else a / b); ip++ }
                        0x6e -> { val b = stack[--sp]; val a = stack[--sp]; push(if (b == 0) 0 else Integer.divideUnsigned(a, b)); ip++ }
                        0x6f -> { val b = stack[--sp]; val a = stack[--sp]; push(if (b == 0) 0 else a % b); ip++ }
                        0x70 -> { val b = stack[--sp]; val a = stack[--sp]; push(if (b == 0) 0 else Integer.remainderUnsigned(a, b)); ip++ }
                        0x71 -> { val b = stack[--sp]; stack[sp - 1] = stack[sp - 1] and b; ip++ }
                        0x72 -> { val b = stack[--sp]; stack[sp - 1] = stack[sp - 1] or b; ip++ }
                        0x73 -> { val b = stack[--sp]; stack[sp - 1] = stack[sp - 1] xor b; ip++ }
                        0x74 -> { val b = stack[--sp]; stack[sp - 1] = stack[sp - 1] shl b; ip++ }
                        0x75 -> { val b = stack[--sp]; stack[sp - 1] = stack[sp - 1] shr b; ip++ }
                        0x76 -> { val b = stack[--sp]; stack[sp - 1] = stack[sp - 1] ushr b; ip++ }
                        0x77 -> { val b = stack[--sp]; stack[sp - 1] = Integer.rotateLeft(stack[sp - 1], b); ip++ }
                        0x78 -> { val b = stack[--sp]; stack[sp - 1] = Integer.rotateRight(stack[sp - 1], b); ip++ }

                        else -> throw IllegalStateException("unsupported wasm op 0x${ins.op.toString(16)}")
                    }
                }
                return if (f.nResults == 1 && sp > 0) stack[sp - 1] else 0
            } finally {
                depthGuard--
            }
        }

        private fun load32(addr: Int): Int {
            check(addr >= 0 && addr.toLong() + 4 <= memory.size.toLong()) { "memory load out of range" }
            return (memory[addr].toInt() and 0xff) or
                ((memory[addr + 1].toInt() and 0xff) shl 8) or
                ((memory[addr + 2].toInt() and 0xff) shl 16) or
                ((memory[addr + 3].toInt() and 0xff) shl 24)
        }

        private fun load8(addr: Int): Int {
            check(addr >= 0 && addr.toLong() + 1 <= memory.size.toLong()) { "memory load out of range" }
            return memory[addr].toInt()
        }

        private fun load16(addr: Int): Int {
            check(addr >= 0 && addr.toLong() + 2 <= memory.size.toLong()) { "memory load out of range" }
            return (memory[addr].toInt() and 0xff) or ((memory[addr + 1].toInt() and 0xff) shl 8)
        }

        private fun store8(addr: Int, v: Int) {
            check(addr >= 0 && addr.toLong() + 1 <= memory.size.toLong()) { "memory store out of range" }
            memory[addr] = (v and 0xff).toByte()
        }

        private fun store16(addr: Int, v: Int) {
            check(addr >= 0 && addr.toLong() + 2 <= memory.size.toLong()) { "memory store out of range" }
            memory[addr] = (v and 0xff).toByte()
            memory[addr + 1] = ((v shr 8) and 0xff).toByte()
        }

        private fun put32(addr: Int, v: Int) {
            check(addr >= 0 && addr.toLong() + 4 <= memory.size.toLong()) { "memory store out of range" }
            memory[addr] = (v and 0xff).toByte()
            memory[addr + 1] = ((v shr 8) and 0xff).toByte()
            memory[addr + 2] = ((v shr 16) and 0xff).toByte()
            memory[addr + 3] = ((v shr 24) and 0xff).toByte()
        }
    }
}
