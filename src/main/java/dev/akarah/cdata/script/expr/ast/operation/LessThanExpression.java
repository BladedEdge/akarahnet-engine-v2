package dev.akarah.cdata.script.expr.ast.operation;

import dev.akarah.cdata.script.expr.Expression;
import dev.akarah.cdata.script.jvm.CodegenContext;
import dev.akarah.cdata.script.type.Type;

import java.lang.classfile.CodeBuilder;
import java.lang.classfile.Opcode;

public record LessThanExpression(
        Expression lhs,
        Expression rhs
) implements Expression {
    @Override
    public void compile(CodegenContext ctx) {
        ctx
                .pushValue(lhs)
                .typecheck(Double.class)
                .unboxNumber()
                .pushValue(rhs)
                .typecheck(Double.class)
                .unboxNumber()
                .bytecodeUnsafe(CodeBuilder::dcmpg)
                .ifThenElse(
                        Opcode.IFLT,
                        () -> ctx.constant(1),
                        () -> ctx.constant(0)
                );
    }

    @Override
    public Type<?> type(CodegenContext ctx) {
        return Type.bool();
    }
}
