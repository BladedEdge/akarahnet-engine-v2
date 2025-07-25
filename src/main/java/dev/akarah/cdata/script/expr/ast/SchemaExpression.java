package dev.akarah.cdata.script.expr.ast;

import com.mojang.datafixers.util.Pair;
import dev.akarah.cdata.script.expr.Expression;
import dev.akarah.cdata.script.jvm.CodegenContext;
import dev.akarah.cdata.script.type.Type;

import java.lang.invoke.MethodType;
import java.util.List;

public record SchemaExpression(
        List<Pair<String, Type<?>>> parameters,
        Type<?> returnType,
        AllOfAction body
) implements Expression {
    @Override
    public void compile(CodegenContext ctx) {
        this.body().compile(ctx);
    }

    @Override
    public Type<?> type(CodegenContext ctx) {
        return this.returnType;
    }

    public MethodType methodType() {
        return MethodType.methodType(
                this.returnType.typeClass(),
                this.parameters.stream()
                        .map(Pair::getSecond)
                        .map(Type::typeClass)
                        .toArray(Class[]::new)
        );
    }
}
