/*
 * Copyright 2015 Google Inc.
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
package com.google.j2cl.ast;

import static com.google.common.base.Preconditions.checkNotNull;

import com.google.j2cl.ast.annotations.Visitable;
import com.google.j2cl.common.SourcePosition;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.List;

/** Block Statement. */
@Visitable
public class Block extends Statement {
  @Visitable List<Statement> statements = new ArrayList<>();

  public Block(SourcePosition sourcePosition, List<Statement> statements) {
    super(sourcePosition);
    this.statements.addAll(checkNotNull(statements));
  }

  public Block(SourcePosition sourcePosition) {
    super(sourcePosition);
  }

  public List<Statement> getStatements() {
    return statements;
  }

  public boolean isEmpty() {
    return getStatements().isEmpty();
  }

  @Override
  public Block clone() {
    Block block =
        Block.newBuilder()
            .setSourcePosition(getSourcePosition())
            .setStatements(AstUtils.clone(statements))
            .build();
    block.setSourcePosition(this.getSourcePosition());
    return block;
  }

  @Override
  public Node accept(Processor processor) {
    return Visitor_Block.visit(processor, this);
  }

  public static Builder newBuilder() {
    return new Builder();
  }

  /** A Builder for Block. */
  public static class Builder {
    private final List<Statement> statements = new ArrayList<>();
    private SourcePosition sourcePosition;

    public Builder from(Block block) {
      return newBuilder()
          .setSourcePosition(block.getSourcePosition())
          .setStatements(block.getStatements());
    }

    public Builder setStatements(Statement... statements) {
      return setStatements(Arrays.asList(statements));
    }

    public Builder setStatements(Collection<Statement> statements) {
      this.statements.clear();
      this.statements.addAll(statements);
      return this;
    }

    public Builder addStatement(Statement statement) {
      this.statements.add(statement);
      return this;
    }

    public Builder setSourcePosition(SourcePosition sourcePosition) {
      this.sourcePosition = sourcePosition;
      return this;
    }

    public Block build() {
      return new Block(sourcePosition, statements);
    }
  }
}
