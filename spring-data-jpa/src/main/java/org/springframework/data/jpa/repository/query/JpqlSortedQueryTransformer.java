/*
 * Copyright 2022-2025 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      https://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.jpa.repository.query;

import static org.springframework.data.jpa.repository.query.QueryTokens.*;

import java.util.List;

import org.jspecify.annotations.Nullable;

import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.repository.query.QueryRenderer.QueryRendererBuilder;
import org.springframework.data.repository.query.ReturnedType;
import org.springframework.util.Assert;

/**
 * An ANTLR {@link org.antlr.v4.runtime.tree.ParseTreeVisitor} that transforms a parsed JPQL query by applying
 * {@link Sort}.
 *
 * @author Greg Turnquist
 * @author Mark Paluch
 * @since 3.1
 */
@SuppressWarnings("ConstantValue")
class JpqlSortedQueryTransformer extends JpqlQueryRenderer {

	private final JpaQueryTransformerSupport transformerSupport = new JpaQueryTransformerSupport();
	private final Sort sort;
	private final @Nullable String primaryFromAlias;
	private final @Nullable DtoProjectionTransformerDelegate dtoDelegate;

	JpqlSortedQueryTransformer(Sort sort, QueryInformation queryInformation, @Nullable ReturnedType returnedType) {

		Assert.notNull(sort, "Sort must not be null");
		Assert.notNull(queryInformation, "ParsedHqlQueryInformation must not be null");

		this.sort = sort;
		this.primaryFromAlias = queryInformation.getAlias();
		this.dtoDelegate = returnedType == null ? null : new DtoProjectionTransformerDelegate(returnedType);
	}

	@Override
	public QueryTokenStream visitSelectQuery(JpqlParser.SelectQueryContext ctx) {

		QueryRendererBuilder builder = QueryRenderer.builder();

		builder.appendExpression(visit(ctx.select_clause()));
		builder.appendExpression(visit(ctx.from_clause()));

		if (ctx.where_clause() != null) {
			builder.appendExpression(visit(ctx.where_clause()));
		}

		if (ctx.groupby_clause() != null) {
			builder.appendExpression(visit(ctx.groupby_clause()));
		}

		if (ctx.having_clause() != null) {
			builder.appendExpression(visit(ctx.having_clause()));
		}

		if (ctx.set_fuction() != null) {
			builder.appendExpression(visit(ctx.set_fuction()));
		} else {
			doVisitOrderBy(builder, ctx.orderby_clause());
		}

		return builder;
	}

	@Override
	public QueryTokenStream visitFromQuery(JpqlParser.FromQueryContext ctx) {

		QueryRendererBuilder builder = QueryRenderer.builder();

		builder.appendExpression(visit(ctx.from_clause()));

		if (ctx.where_clause() != null) {
			builder.appendExpression(visit(ctx.where_clause()));
		}

		if (ctx.groupby_clause() != null) {
			builder.appendExpression(visit(ctx.groupby_clause()));
		}

		if (ctx.having_clause() != null) {
			builder.appendExpression(visit(ctx.having_clause()));
		}

		if (ctx.set_fuction() != null) {
			builder.appendExpression(visit(ctx.set_fuction()));
		} else {
			doVisitOrderBy(builder, ctx.orderby_clause());
		}

		return builder;
	}

	@Override
	public QueryTokenStream visitSelect_clause(JpqlParser.Select_clauseContext ctx) {

		if (dtoDelegate == null) {
			return super.visitSelect_clause(ctx);
		}

		QueryRendererBuilder builder = prepareSelectClause(ctx);

		QueryTokenStream selectItems = QueryTokenStream.concat(ctx.select_item(), this::visit, TOKEN_COMMA);

		if (dtoDelegate != null && dtoDelegate.canRewrite()) {
			builder.append(dtoDelegate.getRewrittenSelectionList());
		} else {
			builder.append(selectItems);
		}

		return builder;
	}

	@Override
	public QueryTokenStream visitSelect_item(JpqlParser.Select_itemContext ctx) {

		QueryTokenStream tokens = super.visitSelect_item(ctx);

		if (ctx.result_variable() != null && !tokens.isEmpty()) {
			transformerSupport.registerAlias(ctx.result_variable().getText());
		}

		return tokens;
	}

	@Override
	public QueryTokenStream visitSelect_expression(JpqlParser.Select_expressionContext ctx) {

		QueryTokenStream selectItem = super.visitSelect_expression(ctx);

		if (dtoDelegate != null && dtoDelegate.applyRewriting() && ctx.constructor_expression() == null) {
			dtoDelegate.appendSelectItem(selectItem);
		}

		return selectItem;
	}

	@Override
	public QueryTokenStream visitJoin(JpqlParser.JoinContext ctx) {

		QueryTokenStream tokens = super.visitJoin(ctx);

		if (ctx.identification_variable() != null) {
			transformerSupport.registerAlias(ctx.identification_variable().getText());
		}

		return tokens;
	}

	private void doVisitOrderBy(QueryRendererBuilder builder, JpqlParser.Orderby_clauseContext ctx) {

		if (ctx != null) {
			QueryTokenStream existingOrder = visit(ctx);
			if (sort.isSorted()) {
				builder.appendInline(existingOrder);
			} else {
				builder.append(existingOrder);
			}
		}

		if (sort.isSorted()) {

			List<QueryToken> sortBy = transformerSupport.orderBy(primaryFromAlias, sort);

			if (ctx != null) {

				QueryRendererBuilder extension = QueryRenderer.builder().append(TOKEN_COMMA).append(sortBy);

				builder.appendInline(extension);
			} else {
				builder.append(TOKEN_ORDER_BY);
				builder.append(sortBy);
			}
		}
	}
}
