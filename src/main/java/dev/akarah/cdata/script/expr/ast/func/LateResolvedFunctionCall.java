package dev.akarah.cdata.script.expr.ast.func;

import com.google.common.collect.Streams;
import com.mojang.datafixers.util.Pair;
import dev.akarah.cdata.registry.ExtBuiltInRegistries;
import dev.akarah.cdata.registry.ExtReloadableResources;
import dev.akarah.cdata.script.dsl.DslParser;
import dev.akarah.cdata.script.dsl.DslToken;
import dev.akarah.cdata.script.dsl.DslTokenizer;
import dev.akarah.cdata.script.exception.ParsingException;
import dev.akarah.cdata.script.exception.SpanData;
import dev.akarah.cdata.script.expr.Expression;
import dev.akarah.cdata.script.expr.SpannedExpression;
import dev.akarah.cdata.script.jvm.CodegenContext;
import dev.akarah.cdata.script.jvm.CodegenUtil;
import dev.akarah.cdata.script.params.ExpressionTypeSet;
import dev.akarah.cdata.script.params.ExpressionStream;
import dev.akarah.cdata.script.type.Type;
import dev.akarah.cdata.script.value.GlobalNamespace;
import net.minecraft.core.Holder;
import net.minecraft.resources.ResourceLocation;

import java.lang.constant.ClassDesc;
import java.lang.constant.MethodTypeDesc;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.stream.Stream;

public class LateResolvedFunctionCall implements Expression {
    String functionName;
    List<Expression> parameters;
    Expression fullyResolved;
    SpanData spanData;

    String declaringClass;
    String jvmFunctionName;
    MethodTypeDesc jvmFunctionType;
    Type<?> cachedReturnType;

    public LateResolvedFunctionCall(String functionName, List<Expression> parameters, SpanData spanData) {
        this.functionName = functionName;
        this.parameters = parameters;
        this.spanData = spanData;
    }

    @Override
    public void compile(CodegenContext ctx) {
        this.resolve(ctx).compile(ctx);
    }

    @Override
    public Type<?> type(CodegenContext ctx) {
        return this.resolve(ctx).type(ctx);
    }

    public Optional<Expression> resolveFromCache() {
        return Optional.ofNullable(this.fullyResolved);
    }

    public Type<?> virtualType(CodegenContext ctx) {
        if(this.parameters.isEmpty()) {
            return Type.any();
        }
        return this.parameters.getFirst().type(ctx);
    }

    @SuppressWarnings("unchecked")
    public Pair<Class<?>, String>[] functionLookupPossibilities(CodegenContext ctx) {
        return new Pair[]{
                Pair.of(this.virtualType(ctx).typeClass(), this.alternateWithVerboseTypeName(ctx)),
                Pair.of(this.virtualType(ctx).typeClass(), this.functionName),
                Pair.of(GlobalNamespace.class, this.functionName),
        };
    }

    public String alternateWithNormalTypeName(CodegenContext ctx) {
        return this.virtualType(ctx).typeName() + "__" + this.functionName;
    }

    public String alternateWithVerboseTypeName(CodegenContext ctx) {
        return (this.virtualType(ctx).verboseTypeName() + "__" + this.functionName)
                .replace("[", "$_")
                .replace("]", "_$")
                .replace(",", "_");
    }

    public Optional<Expression> resolveJvmAction(CodegenContext ctx) {
        Method method = null;
        for(var pair : this.functionLookupPossibilities(ctx)) {
            if(method != null) {
                continue;
            }
            method = Arrays.stream(pair.getFirst().getDeclaredMethods())
                    .filter(x -> x.getName().equals(pair.getSecond()))
                    .findFirst().orElse(null);
        }

        if(method == null) {
            return Optional.empty();
        }

        if(method.getAnnotations().length == 0) {
            System.out.println("Call " + method.getDeclaringClass() + "#" + this.functionName + " is valid, but lacks an annotation.");
            return Optional.empty();
        }
        if(!(method.getAnnotations()[0] instanceof MethodTypeHint methodTypeHint)) {
            System.out.println("Call " + method.getDeclaringClass() + "#" + this.functionName + " is valid, but lacks a MethodTypeHint annotation in first position.");
            return Optional.empty();
        }

        var typeSet = DslParser.parseExpressionTypeSet(
                DslTokenizer.tokenize(ResourceLocation.fromNamespaceAndPath("minecraft", "method_type_hint"), methodTypeHint.value())
                        .getOrThrow()
        );
        var newParameters = typeSet.typecheck(ctx, ExpressionStream.of(this.parameters, this.spanData));

        return Optional.of(new JvmFunctionAction(
                CodegenUtil.ofClass(method.getDeclaringClass()),
                method.getName(),
                MethodTypeDesc.of(
                        CodegenUtil.ofClass(method.getReturnType()),
                        Arrays.stream(method.getParameterTypes())
                                .map(CodegenUtil::ofClass)
                                .toList()
                ),
                newParameters,
                typeSet.returns()
        ));
    }


    public static String filterNameToMethodName(String input) {
        return input
                .replace('/', '_')
                .replace(':', '_')
                .replace('.', '_')
                .replace("-", "_");
    }

    public Optional<Expression> resolveFromUserCode(CodegenContext ctx) {
        var functionName = filterNameToMethodName(this.functionName);
        var functionSchema = ExtReloadableResources.actionManager().expressions().get(functionName);
        if(functionSchema == null) {
            functionName = filterNameToMethodName(this.alternateWithNormalTypeName(ctx));
            functionSchema = ExtReloadableResources.actionManager().expressions().get(functionName);
        }

        if(functionSchema != null) {
            var returnType = functionSchema.returnType().classDescType();
            var typeParameters = new ArrayList<ClassDesc>();
            for(var parameter : functionSchema.parameters()) {
                typeParameters.add(parameter.getSecond().classDescType());
            }

            Streams.zip(this.parameters.stream(), functionSchema.parameters().stream(), Pair::of)
                    .forEach(pair -> {
                        if(!ctx.getTypeOf(pair.getFirst()).typeEquals(pair.getSecond().getSecond())) {
                            throw new ParsingException(
                                    "Expected type of "
                                            + pair.getSecond().getSecond().verboseTypeName()
                                            + " for parameter "
                                            + pair.getSecond().getFirst()
                                            + ", got "
                                            + ctx.getTypeOf(pair.getFirst()).verboseTypeName(),
                                    pair.getFirst().span()
                            );
                        }
                    });

            return Optional.of(new UserFunctionAction(
                    functionName,
                    MethodTypeDesc.of(
                            returnType,
                            typeParameters
                    ),
                    this.parameters
            ));
        }
        return Optional.empty();
    }

    public Expression resolve(CodegenContext ctx) {
        return this.resolveFromCache()
                .or(() -> this.resolveJvmAction(ctx))
                .or(() -> this.resolveFromUserCode(ctx))
                .orElseThrow(() -> new ParsingException("no clue how to resolve `" + this.functionName + "` sorry", this.span()));
    }

    private static Expression[] toArray(List<Expression> list) {
        var array = new Expression[list.size()];
        for(int i = 0; i < list.size(); i++) {
            array[i] = list.get(i);
        }
        return array;
    }

    @SuppressWarnings("unchecked")
    private static Class<Expression>[] repeatInArray(int size) {
        var array = new Class[size];
        Arrays.fill(array, Expression.class);
        return array;
    }

    @Override
    public SpanData span() {
        return this.spanData;
    }

    @Override
    public String toString() {
        return "LRFC[name=" + this.functionName + ",parameters=" + this.parameters + "]";
    }
}
