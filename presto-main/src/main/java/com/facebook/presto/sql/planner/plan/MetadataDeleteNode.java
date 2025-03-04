/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.facebook.presto.sql.planner.plan;

import com.facebook.presto.spi.plan.PlanNodeId;
import com.facebook.presto.spi.relation.VariableReferenceExpression;
import com.facebook.presto.sql.planner.plan.TableWriterNode.DeleteHandle;
import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.google.common.collect.ImmutableList;

import javax.annotation.concurrent.Immutable;

import java.util.List;

import static java.util.Objects.requireNonNull;

@Immutable
public class MetadataDeleteNode
        extends InternalPlanNode
{
    private final DeleteHandle target;
    private final VariableReferenceExpression output;

    @JsonCreator
    public MetadataDeleteNode(
            @JsonProperty("id") PlanNodeId id,
            @JsonProperty("target") DeleteHandle target,
            @JsonProperty("output") VariableReferenceExpression output)
    {
        super(id);

        this.target = requireNonNull(target, "target is null");
        this.output = requireNonNull(output, "output is null");
    }

    @JsonProperty
    public DeleteHandle getTarget()
    {
        return target;
    }

    @JsonProperty
    public VariableReferenceExpression getOutput()
    {
        return output;
    }

    @Override
    public List<VariableReferenceExpression> getOutputVariables()
    {
        return ImmutableList.of(output);
    }

    @Override
    public List<PlanNode> getSources()
    {
        return ImmutableList.of();
    }

    @Override
    public <R, C> R accept(InternalPlanVisitor<R, C> visitor, C context)
    {
        return visitor.visitMetadataDelete(this, context);
    }

    @Override
    public PlanNode replaceChildren(List<PlanNode> newChildren)
    {
        return new MetadataDeleteNode(getId(), target, output);
    }
}
