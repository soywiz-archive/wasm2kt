package com.soywiz.wasm.exporter

import com.soywiz.wasm.*

data class ExportConfig(
    val className: String,
    val packageName: String = ""
)

open class Exporter(val module: WasmModule) {
    val moduleCtx = ModuleDumpContext()
    val functionsWithImport = module.functions.filter { it.import != null }
    val functionsByImport = functionsWithImport.map { it.import!!.importPair to it }.toMap()
    val handledFunctionsWithImport = LinkedHashSet<WasmFunc>()

    val globalsWithImport = module.globals.filter { it.import != null }
    val globalsByImport = globalsWithImport.map { it.import!!.importPair to it }.toMap()

    val handledGlobalsWithImport = LinkedHashSet<Wasm.WasmGlobal>()

    companion object {
        val JAVA_KEYWORDS = setOf("do", "while", "if", "else", "void", "int", "this") // ...
        val PHI_NAMES = setOf("phi_i32", "phi_i64", "phi_f32", "phi_f64", "java")
        val RESERVED_LOCALS = setOf("index")
        val JAVA_DEFINED_NAMES: Set<String> = JAVA_KEYWORDS + PHI_NAMES + RESERVED_LOCALS

        fun JavaNameAllocator() = NameAllocator {
            //var res = it.replace('$', '_').replace('-', '_')
            var res = it.replace('-', '_')
            while (res in JAVA_DEFINED_NAMES) res = "_$res"
            res
        }

        val O_RDONLY = 0x0000
        val O_WRONLY = 0x0001
        val O_RDWR = 0x0002
        val O_CREAT = 0x40
        val O_EXCL = 0x80
        val O_TRUNC = 0x200
        val O_APPEND = 0x400
        val O_DSYNC = 0x1000
    }

    // https://webassembly.github.io/spec/core/exec/numerics.html
    protected open fun writeOps(i: Indenter) {
    }

    protected open fun Indenter.syscall(syscall: WasmSyscall, handler: Indenter.() -> Unit) {
        getImportFunc("env", "___syscall${syscall.id}")?.let {
            line("private int $it(int syscall, int address)") {
                line("try") {
                    handler()
                }
                line("catch (Throwable e)") {
                    line("throw new RuntimeException(e);")
                }
            }
        }
    }

    fun Indenter.missingSyscalls() {
        for (func in functionsWithImport) {
            val import = func.import ?: continue
            if (import.moduleName == "env" && import.name.startsWith("___syscall") && func !in handledFunctionsWithImport) {
                val syscallId = import.name.removePrefix("___syscall").toInt()
                val syscall = WasmSyscall.SYSCALL_BY_ID[syscallId] ?: continue
                syscall(syscall) {
                    line("return TODO_i32(\"unimplemented syscall $syscallId\");")
                }
            }
        }
    }

    fun getImportFunc(ns: String, name: String): String? {
        val import = Pair(ns, name)
        val func = functionsByImport[import]
        return if (func != null) {
            handledFunctionsWithImport += func
            moduleCtx.getName(func)
        } else {
            null
        }
    }

    fun getImportGlobal(ns: String, name: String): String? {
        val import = Pair(ns, name)
        val global = globalsByImport[import]
        return if (global != null) {
            handledGlobalsWithImport += global
            moduleCtx.getName(global)
        } else {
            null
        }
    }

    open fun dump(config: ExportConfig): Indenter {
        TODO()
    }

    class Breaks() {
        val breaks = LinkedHashSet<AstLabel>()

        operator fun contains(label: AstLabel?) = label in breaks
        fun addLabel(label: AstLabel) {
            if (label.kind == FlowKind.BREAK) {
                breaks += label
            }
        }

        fun addLabelSure(label: AstLabel) {
            breaks += label
        }

        override fun toString(): String = "Breaks($breaks)"
    }

    class DumpResult(val indenter: Indenter, val breaks: Breaks, val unreachable: Boolean)

    fun Breaks.concatResult(result: DumpResult) {
        this.breaks += result.breaks.breaks
    }

    fun DumpResult.appendBreaks(breaks: Breaks) = this.apply { breaks.concatResult(this) }

    class NameAllocator(val fixer: (String) -> String = { it }) {
        val allocatedNames = LinkedHashSet<String>()

        fun allocate(name: String): String {
            var rname = fixer(name)
            while (rname in allocatedNames) {
                rname += "_" // Todo use numeric prefixes?
            }
            allocatedNames += rname
            return rname
        }
    }


    class ModuleDumpContext() {
        private val usedNames = JavaNameAllocator()
        private val globalNames = LinkedHashMap<AstGlobal, String>()
        private val functionNames = LinkedHashMap<String, String>()

        fun getName(func: WasmFuncRef): String = functionNames.getOrPut(func.name) { usedNames.allocate(func.name) }
        fun getName(func: WasmFuncWithType): String =
            functionNames.getOrPut(func.name) { usedNames.allocate(func.name) }

        //fun getName(func: WasmFunc): String = getName(func.ftype)
        fun getName(global: AstGlobal): String = globalNames.getOrPut(global) { usedNames.allocate(global.name) }

        fun getName(global: Wasm.WasmGlobal): String = getName(global.astGlobal)
    }

    class DumpContext(val moduleCtx: ModuleDumpContext, val func: WasmFunc?) {
        val phiTypes = LinkedHashSet<WasmType>()
        val debug get() = false
        //val debug get() = func?.name == "_memset"
        val usedNames = JavaNameAllocator()
        val localNames = LinkedHashMap<AstLocal, String>()
        val labelNames = LinkedHashMap<AstLabel, String>()

        fun getName(local: AstLocal): String = localNames.getOrPut(local) { usedNames.allocate(local.name) }
        fun getName(label: AstLabel): String = labelNames.getOrPut(label) { usedNames.allocate(label.name) }
        fun getName(global: AstGlobal): String = moduleCtx.getName(global)
    }

    fun dump(ctx: DumpContext, stm: Wast.Stm, out: Indenter = Indenter { }): DumpResult {
        return stm.dump(ctx, out)
    }

    // @TODO: We should clean-up the AST before in other phase instead of directly while generating code.
    fun Wast.Stm.dump(ctx: DumpContext, out: Indenter = Indenter { }): DumpResult {
        val breaks = Breaks()
        var unreachable = false
        when (this) {
            is Wast.Stms -> {
                for (e in stms) {
                    val result = e.dump(ctx, out).appendBreaks(breaks)
                    if (result.unreachable) {
                        unreachable = true
                        break // Stop
                    }
                }
            }
            is Wast.SetLocal -> out.line(writeSetLocal(ctx.getName(local), expr.dump(ctx)))
            is Wast.SetGlobal -> out.line(writeSetGlobal(ctx.getName(global), expr.dump(ctx)))
            is Wast.RETURN -> run { out.line(writeReturn(expr.dump(ctx))); unreachable = true }
            is Wast.RETURN_VOID -> run { out.line(writeReturnVoid()); unreachable = true }
            is Wast.BLOCK -> {
                lateinit var result: DumpResult
                val optLabel = if (label != null) "${ctx.getName(label)}: " else ""
                if (optLabel.isEmpty()) {
                    result = this.stm.dump(ctx, out).appendBreaks(breaks)
                } else {
                    //out.line("${optLabel}do") {
                    out.line(optLabel) {
                        result = this.stm.dump(ctx, out).appendBreaks(breaks)
                    }
                    //out.line("while (false);")
                }
                unreachable = result.unreachable && (label !in breaks)
                if (ctx.debug) println("BLOCK. ${ctx.func?.name} (block_label=${label?.name}). Unreachable: $unreachable, $breaks")
            }
            is Wast.LOOP -> {
                lateinit var result: DumpResult
                val optLabel = if (label != null) "${ctx.getName(label)}: " else ""
                out.line("${optLabel}while (true)") {
                    result = this.stm.dump(ctx, out).appendBreaks(breaks)
                    if (result.unreachable) {
                        out.line("//break;")
                    } else {
                        out.line("break;")
                        if (label != null) breaks.addLabelSure(label)
                    }
                }
                unreachable = label !in breaks
                if (ctx.debug) println("LOOP. ${ctx.func?.name} (loop_label=${label?.name}). Unreachable: $unreachable, $breaks")
            }
            is Wast.IF -> {
                out.line("if (${dumpBoolean(cond, ctx)})") {
                    val result = this.btrue.dump(ctx, out).appendBreaks(breaks)
                }
            }
            is Wast.IF_ELSE -> {
                out.line("if (${dumpBoolean(cond, ctx)})") {
                    val result = this.btrue.dump(ctx, out).appendBreaks(breaks)
                }
                out.line("else") {
                    val result = this.bfalse.dump(ctx, out).appendBreaks(breaks)
                }
            }
            is Wast.BR -> {
                out.line(writeGoto(label, ctx))
                breaks.addLabel(this.label)
                unreachable = true
            }
            is Wast.BR_IF -> {
                out.line("if (${dumpBoolean(cond, ctx)}) " + writeGoto(label, ctx))
                breaks.addLabel(label)
            }
            is Wast.BR_TABLE -> {
                out.line("switch (${this.subject.dump(ctx)})") {
                    for ((index, label) in this.labels) {
                        out.line("case $index: ${label.goto(ctx)};")
                        breaks.addLabel(label)
                    }
                    out.line("default: ${this.default.goto(ctx)};")
                    breaks.addLabel(default)
                }
            }
            is Wast.STM_EXPR -> {
                val exprStr = this.expr.dump(ctx)
                //var exprStr = this.expr.dump(ctx)
                //removeParen@while (exprStr.startsWith("(") && exprStr.endsWith(")")) {
                //    var openCount = 0
                //    for (n in 0 until exprStr.length) {
                //        val c = exprStr[n]
                //        if (c == '(') {
                //            openCount++
                //        } else if (c == ')') {
                //            if (openCount == 0 && n < exprStr.length - 1) {
                //                break@removeParen
                //            }
                //            openCount--
                //        }
                //    }
                //    exprStr = exprStr.substring(1, exprStr.length - 1)
                //}

                if (this.expr is Wast.Const || this.expr is Wast.Local || this.expr is Wast.Global || this.expr is Wast.Unop || this.expr is Wast.Binop || this.expr is Wast.Terop) {
                    out.line(writeExprStatementNoStm(exprStr))
                } else {
                    out.line(writeExprStatement(exprStr))
                }
            }
            is Wast.WriteMemory -> out.line(writeWriteMemory(op, address.dump(ctx), offset, align, value.dump(ctx)))
            is Wast.SetPhi -> out.line(writeSetPhi("phi_${this.blockType}", this.value.dump(ctx)))
            is Wast.Unreachable -> {
                out.line(writeUnreachable())
                unreachable = true
            }
            is Wast.NOP -> out.line(writeNop())
            else -> out.line("??? $this")
        }
        return DumpResult(out, breaks, unreachable)
    }

    protected open fun dumpBoolean(expr: Wast.Expr, ctx: DumpContext): String {
        when (expr) {
            is Wast.Unop -> when (expr.op) {
                WasmOp.Op_i32_eqz, WasmOp.Op_i64_eqz -> return "${expr.expr.dump(ctx)} == 0"
                else -> Unit
            }
            is Wast.Binop -> {
                val l = expr.l.dump(ctx)
                val r = expr.r.dump(ctx)
                when (expr.op) {
                    WasmOp.Op_i32_eq, WasmOp.Op_i64_eq, WasmOp.Op_f32_eq, WasmOp.Op_f64_eq -> return "$l == $r"
                    WasmOp.Op_i32_ne, WasmOp.Op_i64_ne, WasmOp.Op_f32_ne, WasmOp.Op_f64_ne -> return "$l != $r"
                    WasmOp.Op_i32_gt_s, WasmOp.Op_i64_gt_s, WasmOp.Op_f32_gt, WasmOp.Op_f64_gt -> return "$l > $r"
                    WasmOp.Op_i32_lt_s, WasmOp.Op_i64_lt_s, WasmOp.Op_f32_lt, WasmOp.Op_f64_lt -> return "$l < $r"
                    WasmOp.Op_i32_ge_s, WasmOp.Op_i64_ge_s, WasmOp.Op_f32_ge, WasmOp.Op_f64_ge -> return "$l >= $r"
                    WasmOp.Op_i32_le_s, WasmOp.Op_i64_le_s, WasmOp.Op_f32_le, WasmOp.Op_f64_le -> return "$l <= $r"
                    else -> Unit
                }
            }
        }
        return expr.dump(ctx) + " != 0"
    }

    protected open fun writeGoto(label: AstLabel, ctx: DumpContext) = Indenter(label.goto(ctx) + ";")
    protected open fun writeSetLocal(localName: String, expr: String) = Indenter("$localName = $expr;")
    protected open fun writeSetGlobal(globalName: String, expr: String) = Indenter("this.$globalName = $expr;")
    protected open fun writeReturn(expr: String) = Indenter("return $expr;")
    protected open fun writeReturnVoid() = Indenter("return;")
    protected open fun writeExprStatement(expr: String) = Indenter("$expr;")
    protected open fun writeExprStatementNoStm(expr: String) = Indenter("// $expr; // Not a statement")
    protected open fun writeUnreachable() = Indenter("// unreachable")
    protected open fun writeNop() = Indenter("// nop")
    protected open fun writeSetPhi(phiName: String, expr: String) = writeSetLocal(phiName, expr)

    protected open fun const(value: Int) = "$value"
    protected open fun const(value: Long) = "${value}L"
    protected open fun const(value: Float) = "${value}f"
    protected open fun const(value: Double) = "$value"
    protected open fun unop(op: WasmOp, vd: String) = when (op) {
        WasmOp.Op_f32_neg, WasmOp.Op_f64_neg -> "-($vd)"
        WasmOp.Op_f64_promote_f32 -> "((double)($vd))"
        WasmOp.Op_f32_demote_f64 -> "((float)($vd))"
        else -> "$op($vd)"
    }

    protected open fun binop(op: WasmOp, ld: String, rd: String) = when (op) {
        WasmOp.Op_i32_add, WasmOp.Op_i64_add, WasmOp.Op_f32_add, WasmOp.Op_f64_add -> "($ld + $rd)"
        WasmOp.Op_i32_sub, WasmOp.Op_i64_sub, WasmOp.Op_f32_sub, WasmOp.Op_f64_sub -> "($ld - $rd)"
        WasmOp.Op_i32_mul, WasmOp.Op_i64_mul, WasmOp.Op_f32_mul, WasmOp.Op_f64_mul -> "($ld * $rd)"
        WasmOp.Op_i32_div_s, WasmOp.Op_i64_div_s, WasmOp.Op_f32_div, WasmOp.Op_f64_div -> "($ld / $rd)"
        WasmOp.Op_i32_rem_s, WasmOp.Op_i64_rem_s -> "($ld % $rd)"
        WasmOp.Op_i32_and, WasmOp.Op_i64_and -> "($ld & $rd)"
        WasmOp.Op_i32_or, WasmOp.Op_i64_or -> "($ld | $rd)"
        WasmOp.Op_i32_xor, WasmOp.Op_i64_xor -> "($ld ^ $rd)"
        WasmOp.Op_i32_shl, WasmOp.Op_i64_shl -> "($ld << $rd)"
        WasmOp.Op_i32_shr_s, WasmOp.Op_i64_shr_s -> "($ld >> $rd)"
        WasmOp.Op_i32_shr_u, WasmOp.Op_i64_shr_u -> "($ld >>> $rd)"
        else -> "$op($ld, $rd)"
    }

    protected open fun terop(op: WasmOp, cond: String, strue: String, sfalse: String) =
        "((($cond)) ? ($strue) : ($sfalse))"

    protected open fun getGlobal(name: String) = "this.$name"
    protected open fun getLocal(name: String) = name
    protected open fun getPhi(name: String) = getLocal(name)
    protected open fun readMemory(op: WasmOp, address: String, offset: Int, align: Int): String {
        val raddr = if (offset != 0) "$address + $offset" else address
        return when (op) {
            WasmOp.Op_i32_load8_s -> "this.getByte($raddr)"
            WasmOp.Op_i32_load -> "this.getInt($raddr)"
            WasmOp.Op_i64_load -> "this.getLong($raddr)"
            WasmOp.Op_f32_load -> "this.getFloat($raddr)"
            WasmOp.Op_f64_load -> "this.getDouble($raddr)"
            else -> "$op($address, $offset, $align)"
        }
    }

    protected open fun writeWriteMemory(op: WasmOp, address: String, offset: Int, align: Int, expr: String): Indenter {
        val raddr = if (offset != 0) "$address + $offset" else address
        return when (op) {
            WasmOp.Op_i32_store -> Indenter("this.putInt($raddr, $expr);")
            WasmOp.Op_i32_store8 -> Indenter("this.putByte($raddr, $expr);")
            WasmOp.Op_i32_store16 -> Indenter("this.putShort($raddr, $expr);")
            WasmOp.Op_i64_store -> Indenter("this.putLong($raddr, $expr);")
            WasmOp.Op_f32_store -> Indenter("this.putFloat($raddr, $expr);")
            WasmOp.Op_f64_store -> Indenter("this.putDouble($raddr, $expr);")
            else -> Indenter("$op($address, $offset, $align, $expr);")
        }
    }

    fun Wast.Expr.dump(ctx: DumpContext): String {
        return when (this) {
            is Wast.Const -> when (this.type) {
                WasmType.i32 -> const(value as Int)
                WasmType.i64 -> const(value as Long)
                WasmType.f32 -> const(value as Float)
                WasmType.f64 -> const(value as Double)
                else -> "(${this.value})"
            }
            is Wast.TeeLocal -> "(${ctx.getName(local)} = ${this.expr.dump(ctx)})"
            is Wast.Local -> getLocal(ctx.getName(local))
            is Wast.Global -> getGlobal(ctx.getName(global))
            is Wast.Unop -> unop(op, expr.dump(ctx))
            is Wast.Terop -> terop(op, dumpBoolean(cond, ctx), etrue.dump(ctx), efalse.dump(ctx))
            is Wast.Binop -> binop(op, l.dump(ctx), r.dump(ctx))
            is Wast.CALL -> {
                "this.${ctx.moduleCtx.getName(func)}(${this.args.joinToString(", ") { it.dump(ctx) }})"
            }
        //is A.CALL_INDIRECT -> "((Op_getFunction(${this.address.dump()}) as (${this.type.type()}))" + "(" + this.args.map { it.dump() }.joinToString(
            is Wast.CALL_INDIRECT -> {
                "invoke_${type.signature}(${address.dump(ctx)}, " + args.joinToString(", ") { it.dump(ctx) } + ")"
            }
            is Wast.ReadMemory -> readMemory(op, address.dump(ctx), offset, align)
            is Wast.Phi -> run { ctx.phiTypes += this.type; getPhi("phi_${this.type}") }
            is Wast.BLOCK_EXPR -> "TODO_${this.type}(\"BLOCK_EXPR not implemented\")"
            else -> "???($this)"
        }
    }

    open fun WasmType.default(): String = when (this) {
        WasmType.i64 -> "0L"
        WasmType.f32 -> "0f"
        WasmType.f64 -> "0.0"
        else -> "0"
    }

    open fun WasmType.type(): String = when (this) {
        WasmType.void -> "void"
        WasmType.i32 -> "int"
        WasmType.i64 -> "long"
        WasmType.f32 -> "float"
        WasmType.f64 -> "double"
        is WasmType.Function -> "(${this.args.joinToString(", ") { it.type.type() }}) -> ${this.retType.type()}"
        else -> "$this"
    }

    fun AstLabel.goto(ctx: DumpContext) = "${this.kind.keyword} ${ctx.getName(this)}"
}
