/*
 * Copyright 2008-2025 the original author or authors.
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

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import jakarta.persistence.Tuple;
import jakarta.persistence.TypedQuery;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.jspecify.annotations.Nullable;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.provider.QueryExtractor;
import org.springframework.data.jpa.repository.QueryRewriter;
import org.springframework.data.repository.query.Parameters;
import org.springframework.data.repository.query.QueryCreationException;
import org.springframework.data.repository.query.RepositoryQuery;
import org.springframework.data.repository.query.ResultProcessor;
import org.springframework.data.repository.query.ReturnedType;
import org.springframework.data.util.Lazy;
import org.springframework.util.StringUtils;

/**
 * Implementation of {@link RepositoryQuery} based on {@link jakarta.persistence.NamedQuery}s.
 *
 * @author Oliver Gierke
 * @author Thomas Darimont
 * @author Mark Paluch
 */
final class NamedQuery extends AbstractJpaQuery {

	private static final String CANNOT_EXTRACT_QUERY = "Your persistence provider does not support extracting the JPQL query from a "
			+ "named query thus you can't use Pageable inside your query method; Make sure you "
			+ "have a JpaDialect configured at your EntityManagerFactoryBean as this affects "
			+ "discovering the concrete persistence provider";

	private static final Log LOG = LogFactory.getLog(NamedQuery.class);

	private final String queryName;
	private final String countQueryName;
	private final @Nullable String countProjection;
	private final boolean namedCountQueryIsPresent;
	private final Lazy<EntityQuery> entityQuery;
	private final QueryRewriter queryRewriter;

	/**
	 * Creates a new {@link NamedQuery}.
	 */
	private NamedQuery(JpaQueryMethod method, EntityManager em, JpaQueryConfiguration queryConfiguration) {

		super(method, em);

		this.queryName = method.getNamedQueryName();
		this.countQueryName = method.getNamedCountQueryName();
		QueryExtractor extractor = method.getQueryExtractor();
		this.countProjection = method.getCountQueryProjection();
		this.queryRewriter = queryConfiguration.getQueryRewriter(method);

		Parameters<?, ?> parameters = method.getParameters();

		if (parameters.hasSortParameter()) {
			throw new IllegalStateException(String.format("Query method %s is backed by a NamedQuery and must "
					+ "not contain a sort parameter as we cannot modify the query; Use @%s(value=…) instead to apply sorting or remove the 'Sort' parameter.",
					method, method.isNativeQuery() ? "NativeQuery" : "Query"));
		}

		this.namedCountQueryIsPresent = hasNamedQuery(em, countQueryName);

		Query namedQuery = em.createNamedQuery(queryName);
		boolean weNeedToCreateCountQuery = !namedCountQueryIsPresent && method.getParameters().hasLimitingParameters();
		boolean cantExtractQuery = !extractor.canExtractQuery();

		if (weNeedToCreateCountQuery && cantExtractQuery) {
			throw QueryCreationException.create(method, CANNOT_EXTRACT_QUERY);
		}

		boolean nativeQuery = method.isNativeQuery() || extractor.isNativeQuery(namedQuery);
		String queryString = extractor.extractQueryString(namedQuery);

		if (parameters.hasPageableParameter()) {

			LOG.warn(String.format(
					"Query method %s is backed by a NamedQuery but contains a Pageable parameter; Sorting delivered via this Pageable will not be applied; Use @%s(value=…) instead to apply sorting.",
					method, nativeQuery ? "NativeQuery" : "Query"));
		}

		// || namedQuery.toString().contains("NativeQuery")
		DeclaredQuery declaredQuery;
		if (StringUtils.hasText(queryString)) {
			if (nativeQuery) {
				declaredQuery = DeclaredQuery.nativeQuery(queryString);
			} else {
				declaredQuery = DeclaredQuery.jpqlQuery(queryString);
			}
		}
		else {
			declaredQuery = new DeclaredQuery() {
				@Override
				public boolean isNative() {
					return false;
				}

				@Override
				public String getQueryString() {
					return "";
				}
			};
		}

		this.entityQuery = Lazy.of(() -> EntityQuery.create(declaredQuery, queryConfiguration.getSelector()));
	}

	/**
	 * Returns whether the named query with the given name exists.
	 *
	 * @param em must not be {@literal null}.
	 * @param queryName must not be {@literal null}.
	 */
	static boolean hasNamedQuery(EntityManager em, String queryName) {

		/*
		 * See DATAJPA-617, we have to use a dedicated em for the lookups to avoid a
		 * potential rollback of the running tx.
		 */

		try (EntityManager lookupEm = em.getEntityManagerFactory().createEntityManager()) {
			lookupEm.createNamedQuery(queryName);
			return true;
		} catch (IllegalArgumentException e) {

			if (LOG.isDebugEnabled()) {
				LOG.debug(String.format("Did not find named query %s", queryName));
			}
			return false;
		}
	}

	/**
	 * Looks up a named query for the given {@link org.springframework.data.repository.query.QueryMethod}.
	 *
	 * @param method must not be {@literal null}.
	 * @param em must not be {@literal null}.
	 * @param selector must not be {@literal null}.
	 * @param queryConfiguration must not be {@literal null}.
	 */
	public static @Nullable RepositoryQuery lookupFrom(JpaQueryMethod method, EntityManager em,
			JpaQueryConfiguration queryConfiguration) {

		String queryName = method.getNamedQueryName();

		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("Looking up named query '%s'", queryName));
		}

		if (!hasNamedQuery(em, queryName)) {
			return null;
		}

		if (method.isScrollQuery()) {
			throw QueryCreationException.create(method, String.format(
					"Scroll queries are not supported using String-based queries as we cannot rewrite the query string. Use @%s(value=…) instead.",
					method.isNativeQuery() ? "NativeQuery" : "Query"));
		}

		RepositoryQuery query = new NamedQuery(method, em, queryConfiguration);
		if (LOG.isDebugEnabled()) {
			LOG.debug(String.format("Found named query '%s'", queryName));
		}
		return query;
	}

	@Override
	public boolean hasDeclaredCountQuery() {
		return namedCountQueryIsPresent;
	}

	@Override
	protected Query doCreateQuery(JpaParametersParameterAccessor accessor) {

		EntityManager em = getEntityManager();

		JpaQueryMethod queryMethod = getQueryMethod();
		ResultProcessor processor = queryMethod.getResultProcessor().withDynamicProjection(accessor);

		Class<?> typeToRead = getTypeToRead(processor.getReturnedType());

		Query query = typeToRead == null //
				? em.createNamedQuery(queryName) //
				: em.createNamedQuery(queryName, typeToRead);

		return parameterBinder.get().bindAndPrepare(query, accessor);
	}

	@Override
	protected TypedQuery<Long> doCreateCountQuery(JpaParametersParameterAccessor accessor) {

		EntityManager em = getEntityManager();
		TypedQuery<Long> countQuery;

		if (namedCountQueryIsPresent) {
			countQuery = em.createNamedQuery(countQueryName, Long.class);
		} else {

			String countQueryString = entityQuery.get().deriveCountQuery(countProjection).getQueryString();
			countQueryString = potentiallyRewriteQuery(countQueryString, accessor.getSort(), accessor.getPageable());
			countQuery = em.createQuery(countQueryString, Long.class);
		}

		return parameterBinder.get().bind(countQuery, accessor);
	}

	@Override
	protected @Nullable Class<?> getTypeToRead(ReturnedType returnedType) {

		if (getQueryMethod().isNativeQuery()) {

			Class<?> type = returnedType.getReturnedType();
			Class<?> domainType = returnedType.getDomainType();

			// Domain or subtype -> use return type
			if (domainType.isAssignableFrom(type)) {
				return type;
			}

			// Domain type supertype -> use domain type
			if (type.isAssignableFrom(domainType)) {
				return domainType;
			}

			// Tuples for projection interfaces or explicit SQL mappings for everything else
			return type.isInterface() ? Tuple.class : null;
		}

		return entityQuery.get().hasConstructorExpression() //
				? null //
				: super.getTypeToRead(returnedType);
	}

	/**
	 * Use the {@link QueryRewriter}, potentially rewrite the query, using relevant {@link Sort} and {@link Pageable}
	 * information.
	 *
	 * @param originalQuery
	 * @param sort
	 * @param pageable
	 * @return
	 */
	private String potentiallyRewriteQuery(String originalQuery, Sort sort, @Nullable Pageable pageable) {

		return pageable != null && pageable.isPaged() //
				? queryRewriter.rewrite(originalQuery, pageable) //
				: queryRewriter.rewrite(originalQuery, sort);
	}
}
