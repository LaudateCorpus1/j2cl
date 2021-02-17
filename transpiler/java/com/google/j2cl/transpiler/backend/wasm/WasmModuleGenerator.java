/*
 * Copyright 2020 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package com.google.j2cl.transpiler.backend.wasm;

import static com.google.common.base.Predicates.not;
import static com.google.j2cl.transpiler.backend.wasm.GenerationEnvironment.getWasmTypeForPrimitive;
import static java.lang.String.format;

import com.google.common.base.Predicates;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Streams;
import com.google.j2cl.common.OutputUtils;
import com.google.j2cl.common.Problems;
import com.google.j2cl.transpiler.ast.AbstractVisitor;
import com.google.j2cl.transpiler.ast.CompilationUnit;
import com.google.j2cl.transpiler.ast.DeclaredTypeDescriptor;
import com.google.j2cl.transpiler.ast.Field;
import com.google.j2cl.transpiler.ast.Method;
import com.google.j2cl.transpiler.ast.MethodDescriptor;
import com.google.j2cl.transpiler.ast.PrimitiveTypes;
import com.google.j2cl.transpiler.ast.Type;
import com.google.j2cl.transpiler.ast.TypeDeclaration;
import com.google.j2cl.transpiler.ast.TypeDescriptor;
import com.google.j2cl.transpiler.ast.TypeDescriptors;
import com.google.j2cl.transpiler.ast.Variable;
import com.google.j2cl.transpiler.backend.common.SourceBuilder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

/** Generates a WASM module containing all the code for the application. */
public class WasmModuleGenerator {

  private final Problems problems;
  private final Path outputPath;
  private final Set<String> pendingEntryPoints;
  private final SourceBuilder builder = new SourceBuilder();
  private GenerationEnvironment environment;

  public WasmModuleGenerator(Path outputPath, ImmutableSet<String> entryPoints, Problems problems) {
    this.outputPath = outputPath;
    this.pendingEntryPoints = new HashSet<>(entryPoints);
    this.problems = problems;
  }

  public void generateOutputs(List<CompilationUnit> compilationUnits) {
    environment = new GenerationEnvironment(compilationUnits);
    builder.append(";;; Code generated by J2WASM");
    emitRttHierarchy(compilationUnits);
    emitDynamicDispatchMethodTypes(compilationUnits);
    emitTypes(compilationUnits);
    emitRuntimeInitialization(compilationUnits);
    OutputUtils.writeToFile(outputPath.resolve("module.wat"), builder.build(), problems);
    if (!pendingEntryPoints.isEmpty()) {
      problems.error("Entry points %s not found.", pendingEntryPoints);
    }
  }

  /** Emit the type for all function signatures that will be needed to reference vtable methods. */
  private void emitDynamicDispatchMethodTypes(List<CompilationUnit> compilationUnits) {
    Set<String> emittedFunctionTypeNames = new HashSet<>();
    compilationUnits.stream()
        .flatMap(cu -> cu.getTypes().stream())
        .filter(Predicates.not(Type::isInterface))
        .flatMap(
            t ->
                environment
                    .getWasmTypeLayout(t.getDeclaration())
                    .getAllPolymorphicMethods()
                    .stream())
        .map(Method::getDescriptor)
        .forEach(
            m -> {
              String typeName = environment.getFunctionTypeName(m);
              if (!emittedFunctionTypeNames.add(typeName)) {
                return;
              }
              builder.newLine();
              builder.append(String.format("(type %s (func", typeName));
              Streams.concat(
                      // Add the implicit parameter
                      Stream.of(TypeDescriptors.get().javaLangObject),
                      m.getDispatchParameterTypeDescriptors().stream())
                  .forEach(
                      t ->
                          builder.append(String.format(" (param %s)", environment.getWasmType(t))));

              TypeDescriptor returnTypeDescriptor = m.getDispatchReturnTypeDescriptor();
              if (!TypeDescriptors.isPrimitiveVoid(returnTypeDescriptor)) {
                builder.append(
                    String.format(" (result %s)", environment.getWasmType(returnTypeDescriptor)));
              }
              builder.append("))");
            });
  }

  private void emitArrayTypes() {
    builder.newLine();
    builder.append(";;; Code for Array types.");

    emitArrayType("Object", environment.getWasmType(TypeDescriptors.get().javaLangObject));

    // TODO(dramaix): consider using packed type i8 and i16 for some primitives
    PrimitiveTypes.TYPES.stream()
        .filter(p -> p != PrimitiveTypes.VOID)
        .forEach(p -> emitArrayType(p.getSimpleSourceName(), getWasmTypeForPrimitive(p)));
  }

  private void emitArrayType(String javaType, String wasmType) {
    builder.newLine();
    builder.appendLines(
        format(
            "(global $%s.array.elements.rtt "
                + "(rtt 1 $%s.array.elements) (rtt.canon $%s.array.elements))",
            javaType, javaType, javaType),
        format("(type $%s.array.elements (array (mut %s)))", javaType, wasmType),
        format(
            "(global $%s.array.rtt (rtt 2 $%s.array) (rtt.sub $%s.array  (global.get %s)))",
            javaType,
            javaType,
            javaType,
            environment.getRttGlobalName(TypeDescriptors.get().javaLangObject)),
        format("(type $%s.array", javaType),
        "  (struct",
        format(
            "    (field $vtable (ref null %s))",
            environment.getWasmVtableTypeName(TypeDescriptors.get().javaLangObject)),
        format("    (field $elements (ref null $%s.array.elements)))", javaType),
        ")");
    // TODO(b/179726089): implement array methods
  }

  private void emitTypes(List<CompilationUnit> compilationUnits) {
    emitArrayTypes();

    for (CompilationUnit j2clCompilationUnit : compilationUnits) {
      builder.newLine();
      builder.append(
          ";;; Code for "
              + j2clCompilationUnit.getPackageName()
              + "."
              + j2clCompilationUnit.getName());
      for (Type type : j2clCompilationUnit.getTypes()) {
        renderType(type);
      }
    }
  }

  /** Emits the rtt hierarchy by assigning a global to each rtt. */
  private void emitRttHierarchy(List<CompilationUnit> compilationUnits) {
    // TODO(b/174715079): Consider tagging or emitting together with the rest of the type
    // to make the rtts show in the readables.
    Set<TypeDeclaration> emittedRtts = new HashSet<>();
    compilationUnits.stream()
        .flatMap(c -> c.getTypes().stream())
        .filter(not(Type::isInterface)) // Interfaces do not have rtts.
        .forEach(t -> emitRttGlobal(t.getDeclaration(), emittedRtts));
  }

  private void emitRttGlobal(TypeDeclaration typeDeclaration, Set<TypeDeclaration> emittedRtts) {
    if (!emittedRtts.add(typeDeclaration)) {
      return;
    }
    DeclaredTypeDescriptor superTypeDescriptor = typeDeclaration.getSuperTypeDescriptor();
    if (superTypeDescriptor != null) {
      // Supertype rtt needs to be emitted before the subtype since globals can only refer to
      // globals that are initialized before.
      emitRttGlobal(superTypeDescriptor.getTypeDeclaration(), emittedRtts);
    }
    int depth = typeDeclaration.getClassHierarchyDepth();
    String wasmTypeName = environment.getWasmTypeName(typeDeclaration) + "";
    String superTypeRtt =
        superTypeDescriptor == null
            ? "(rtt.canon " + wasmTypeName + ")"
            : format(
                "(rtt.sub %s (global.get %s))",
                wasmTypeName,
                environment.getRttGlobalName(superTypeDescriptor.getTypeDeclaration()) + "");
    builder.newLine();
    builder.append(
        format(
            "(global %s (rtt %d %s) %s)",
            environment.getRttGlobalName(typeDeclaration) + "", depth, wasmTypeName, superTypeRtt));
  }

  private void renderType(Type type) {
    builder.newLine();
    builder.newLine();
    builder.append(";;; " + type.getKind() + "  " + type.getReadableDescription());
    if (!type.isInterface()) {
      // Interfaces at runtime are treated as java.lang.Object; they don't have an empty structure
      // nor rtts.
      renderTypeStruct(type);
      renderVtableStruct(type);
      renderVtableGlobal(type);
    }

    renderStaticFields(type);
    renderTypeMethods(type);
  }

  private void renderStaticFields(Type type) {
    for (Field field : type.getStaticFields()) {
      builder.newLine();
      builder.append("(global " + environment.getFieldName(field));

      if (field.isCompileTimeConstant()) {
        // TODO(b/180439833): revisit compile time-constant initialization for String typed
        // constants when String is implemented.
        builder.append(
            String.format(
                " %s ", environment.getWasmType(field.getDescriptor().getTypeDescriptor())));
        ExpressionTranspiler.render(field.getInitializer(), builder, environment);
      } else {
        builder.append(
            String.format(
                " (mut %s) ", environment.getWasmType(field.getDescriptor().getTypeDescriptor())));
        ExpressionTranspiler.render(
            field.getDescriptor().getTypeDescriptor().getDefaultValue(), builder, environment);
      }

      builder.append(")");
    }
  }

  private void renderTypeMethods(Type type) {
    type.getMethods().stream().filter(not(Method::isAbstract)).forEach(this::renderMethod);
  }

  private void renderMethod(Method method) {
    builder.newLine();
    builder.newLine();
    builder.append(";;; " + method.getReadableDescription());
    builder.newLine();
    builder.append("(func " + environment.getMethodImplementationName(method.getDescriptor()));

    if (pendingEntryPoints.remove(method.getQualifiedBinaryName())) {
      if (!method.isStatic()) {
        problems.error("Entry point [%s] is not a static method.", method.getQualifiedBinaryName());
      }
      builder.append(" (export \"" + method.getDescriptor().getName() + "\")");
    }
    MethodDescriptor methodDescriptor = method.getDescriptor();
    DeclaredTypeDescriptor enclosingTypeDescriptor = methodDescriptor.getEnclosingTypeDescriptor();

    // Emit parameters
    builder.indent();
    if (!method.isStatic()) {
      // Add the implicit "this" parameter to instance methods and constructors.
      // Note that constructors and private methods can declare the parameter type to be the
      // enclosing type because they are not overridden but normal instance methods have to
      // declare the parameter more generically as java.lang.Object, since all the overrides need
      // to have matching signatures.
      // TODO(rluble): revisit once the wasm gc spec and implementation have function subtyping.
      builder.newLine();
      if (methodDescriptor.isClassDynamicDispatch()) {
        builder.append(
            String.format(
                "(param $this.untyped %s)",
                environment.getWasmType(TypeDescriptors.get().javaLangObject)));
      } else {
        builder.append(
            String.format("(param $this %s)", environment.getWasmType(enclosingTypeDescriptor)));
      }
    }
    for (Variable parameter : method.getParameters()) {
      builder.newLine();
      builder.append(
          "(param "
              + environment.getDeclarationName(parameter)
              + " "
              + environment.getWasmType(parameter.getTypeDescriptor())
              + ")");
    }
    // Emit return type
    TypeDescriptor returnTypeDescriptor = methodDescriptor.getDispatchReturnTypeDescriptor();
    if (method.isConstructor()) {
      // TODO(rluble): Remove after constructor normalization is in place, constructors should not
      // reach the back end.
      // Constructors are modelled for now as if they return the object that was created.
      builder.newLine();
      builder.append("(result " + environment.getWasmType(enclosingTypeDescriptor) + ")");
    } else if (!TypeDescriptors.isPrimitiveVoid(returnTypeDescriptor)) {
      builder.newLine();
      builder.append("(result " + environment.getWasmType(returnTypeDescriptor) + ")");
      // TODO(rluble): Add a pass to make all methods return from the top block.
      // Define a local variable to hold the result value to allow for returns that appear in
      // the inner blocks.
      builder.newLine();
      builder.append("(local $return.value " + environment.getWasmType(returnTypeDescriptor) + ")");
    }

    // Emit locals.
    for (Variable variable : collectLocals(method)) {
      builder.newLine();
      builder.append(
          "(local "
              + environment.getDeclarationName(variable)
              + " "
              + environment.getWasmType(variable.getTypeDescriptor())
              + ")");
    }
    // Introduce the actual $this variable for polymorphic methods and cast the parameter to
    // the right type.
    if (methodDescriptor.isClassDynamicDispatch()) {
      builder.newLine();
      builder.append(
          String.format("(local $this %s)", environment.getWasmType(enclosingTypeDescriptor)));
      builder.newLine();
      builder.append(
          String.format(
              "(local.set $this (ref.cast %s %s (local.get $this.untyped) (global.get %s)))",
              environment.getWasmTypeName(TypeDescriptors.get().javaLangObject),
              environment.getWasmTypeName(enclosingTypeDescriptor),
              environment.getRttGlobalName(enclosingTypeDescriptor)));
    }
    builder.newLine();
    builder.append("(block $return.label");
    builder.indent();
    builder.newLine();

    StatementTranspiler.render(method.getBody(), builder, environment);
    builder.unindent();
    builder.newLine();
    builder.append(")");
    if (method.isConstructor()) {
      // TODO(rluble): Add a pass to transform constructors into static methods.
      builder.newLine();
      builder.append("(local.get $this)");
    } else if (!TypeDescriptors.isPrimitiveVoid(method.getDescriptor().getReturnTypeDescriptor())) {
      builder.newLine();
      builder.append("(local.get $return.value)");
    }
    builder.unindent();
    builder.newLine();
    builder.append(")");

    // Declare a function that will be target of dynamic dispatch.
    if (methodDescriptor.isPolymorphic()) {
      builder.newLine();
      builder.append(
          String.format(
              "(elem declare func %s)",
              environment.getMethodImplementationName(method.getDescriptor())));
    }
  }

  private static List<Variable> collectLocals(Method method) {
    List<Variable> locals = new ArrayList<>();
    method
        .getBody()
        .accept(
            new AbstractVisitor() {
              @Override
              public void exitVariable(Variable variable) {
                locals.add(variable);
              }
            });
    return locals;
  }

  private void renderTypeStruct(Type type) {
    builder.newLine();
    builder.append("(type " + environment.getWasmTypeName(type.getTypeDescriptor()) + " (struct");
    builder.indent();
    renderTypeFields(type);
    builder.unindent();
    builder.newLine();
    builder.append("))");
  }

  private void renderTypeFields(Type type) {
    builder.newLine();
    // The first field is always the vtable for class dynamic dispatch.
    builder.append(
        String.format(
            "(field $vtable (ref null %s))",
            environment.getWasmVtableTypeName(type.getTypeDescriptor())));
    WasmTypeLayout wasmType = environment.getWasmTypeLayout(type.getDeclaration());
    for (Field field : wasmType.getAllInstanceFields()) {
      builder.newLine();
      builder.append(
          "(field "
              + environment.getFieldName(field)
              + " (mut "
              + environment.getWasmType(field.getDescriptor().getTypeDescriptor())
              + "))");
    }
  }

  private void renderVtableStruct(Type type) {
    builder.newLine();
    builder.append(
        String.format(
            "(type %s (struct", environment.getWasmVtableTypeName(type.getTypeDescriptor())));
    builder.indent();
    renderVtableTypeFields(type);
    builder.unindent();
    builder.newLine();
    builder.append("))");
  }

  private void renderVtableTypeFields(Type type) {
    WasmTypeLayout wasmTypeLayout = environment.getWasmTypeLayout(type.getDeclaration());
    builder.newLine();
    for (Method method : wasmTypeLayout.getAllPolymorphicMethods()) {
      String functionTypeName = environment.getFunctionTypeName(method.getDescriptor());
      builder.newLine();
      builder.append(
          String.format(
              "(field $%s (mut (ref %s)))",
              method.getDescriptor().getMangledName(), functionTypeName));
    }
  }

  private void renderVtableGlobal(Type type) {
    DeclaredTypeDescriptor typeDescriptor = type.getTypeDescriptor();
    builder.newLine();
    builder.append(
        String.format(
            "(global %s (mut (ref null %s)) (ref.null %s))",
            environment.getWasmVtableGlobalName(typeDescriptor),
            environment.getWasmVtableTypeName(typeDescriptor),
            environment.getWasmVtableTypeName(typeDescriptor)));
  }

  /** Emit a function that will be used to initialize the runtime at module instantiation time. */
  private void emitRuntimeInitialization(List<CompilationUnit> compilationUnits) {
    builder.newLine();
    builder.newLine();
    builder.append(";;; Code for runtime initialization.");
    builder.newLine();
    builder.append("(func $.runtime.init (block ");
    builder.indent();
    builder.newLine();
    builder.append(";;; Initialize vtables structures.");
    // Populate all vtables.
    compilationUnits.stream()
        .flatMap(cu -> cu.getTypes().stream())
        .filter(Predicates.not(Type::isInterface))
        .map(Type::getDeclaration)
        .filter(Predicates.not(TypeDeclaration::isAbstract))
        .forEach(this::emitVtableInitialization);
    builder.unindent();
    builder.newLine();
    builder.append("))");
    builder.newLine();
    builder.append("(start $.runtime.init)");
  }

  /** Emits the code to initialize the vtable structure for {@code typeDeclaration}. */
  private void emitVtableInitialization(TypeDeclaration typeDeclaration) {
    builder.unindent();
    builder.newLine();
    builder.append(
        String.format(";;; Code for %s [vtable]", typeDeclaration.getQualifiedSourceName()));
    builder.indent();
    builder.newLine();
    DeclaredTypeDescriptor td = typeDeclaration.toUnparameterizedTypeDescriptor();
    builder.append(
        String.format(
            "(global.set %s (struct.new_with_rtt %s ",
            environment.getWasmVtableGlobalName(td), environment.getWasmVtableTypeName(td)));
    WasmTypeLayout wasmTypeLayout = environment.getWasmTypeLayout(typeDeclaration);
    wasmTypeLayout
        .getAllPolymorphicMethods()
        .forEach(
            m ->
                builder.append(
                    String.format(
                        " (ref.func %s)",
                        environment.getMethodImplementationName(m.getDescriptor()))));
    builder.append(String.format(" (rtt.canon %s)))", environment.getWasmVtableTypeName(td)));
  }
}
