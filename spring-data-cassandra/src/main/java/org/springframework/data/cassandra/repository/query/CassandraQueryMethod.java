/*
 * Copyright 2016-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.cassandra.repository.query;

import java.lang.reflect.Method;
import java.util.Optional;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.cassandra.core.mapping.CassandraPersistentEntity;
import org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty;
import org.springframework.data.cassandra.repository.Query;
import org.springframework.data.mapping.context.MappingContext;
import org.springframework.data.projection.ProjectionFactory;
import org.springframework.data.repository.core.RepositoryMetadata;
import org.springframework.data.repository.query.QueryMethod;
import org.springframework.data.util.ClassTypeInformation;
import org.springframework.data.util.TypeInformation;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import com.datastax.driver.core.ResultSet;

/**
 * Cassandra specific implementation of {@link QueryMethod}.
 *
 * @author Matthew Adams
 * @author Oliver Gierke
 * @author Mark Paluch
 * @author John Blum
 */
public class CassandraQueryMethod extends QueryMethod {

	private final Method method;

	private final MappingContext<? extends CassandraPersistentEntity<?>, ? extends CassandraPersistentProperty> mappingContext;

	private final Optional<Query> query;

	private CassandraEntityMetadata<?> entityMetadata;

	/**
	 * Create a new {@link CassandraQueryMethod} from the given {@link Method}.
	 *
	 * @param method must not be {@literal null}.
	 * @param repositoryMetadata must not be {@literal null}.
	 * @param projectionFactory must not be {@literal null}.
	 * @param mappingContext must not be {@literal null}.
	 */
	public CassandraQueryMethod(Method method, RepositoryMetadata repositoryMetadata, ProjectionFactory projectionFactory,
			MappingContext<? extends CassandraPersistentEntity<?>, ? extends CassandraPersistentProperty> mappingContext) {

		super(method, repositoryMetadata, projectionFactory);

		Assert.notNull(mappingContext, "MappingContext must not be null");

		verify(method, repositoryMetadata);

		this.method = method;
		this.mappingContext = mappingContext;
		this.query = Optional.ofNullable(AnnotatedElementUtils.findMergedAnnotation(method, Query.class));
	}

	/**
	 * Validates that this query is not a page or slice query.
	 */
	@SuppressWarnings("unused")
	public void verify(Method method, RepositoryMetadata metadata) {

		// TODO: support Page & Slice queries
		if (isSliceQuery() || isPageQuery()) {
			throw new InvalidDataAccessApiUsageException("Slice and Page queries are not supported");
		}
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.query.QueryMethod#getEntityInformation()
	 */
	@Override
	@SuppressWarnings("unchecked")
	public CassandraEntityMetadata<?> getEntityInformation() {

		if (entityMetadata == null) {

			Class<?> returnedObjectType = getReturnedObjectType();
			Class<?> domainClass = getDomainClass();

			if (ClassUtils.isPrimitiveOrWrapper(returnedObjectType)) {
				this.entityMetadata = new SimpleCassandraEntityMetadata<>((Class<Object>) domainClass,
						mappingContext.getRequiredPersistentEntity(domainClass));

			} else {
				CassandraPersistentEntity<?> entity = mappingContext.getPersistentEntity(returnedObjectType);
				CassandraPersistentEntity<?> managedEntity = mappingContext.getRequiredPersistentEntity(domainClass);

				CassandraPersistentEntity<?> returnedEntity =
						entity != null && entity.getType().isInterface() ? entity : managedEntity;

				// TODO collectionEntity?
				CassandraPersistentEntity<?> collectionEntity =
						domainClass.isAssignableFrom(returnedObjectType) ? returnedEntity : managedEntity;

				this.entityMetadata =
						new SimpleCassandraEntityMetadata<>((Class<Object>) returnedEntity.getType(), collectionEntity);
			}
		}

		return this.entityMetadata;
	}

	/*
	 * (non-Javadoc)
	 * @see org.springframework.data.repository.query.QueryMethod#getParameters()
	 */
	@Override
	public CassandraParameters getParameters() {
		return (CassandraParameters) super.getParameters();
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.repository.query.QueryMethod#createParameters(java.lang.reflect.Method)
	 */
	@Override
	protected CassandraParameters createParameters(Method method) {
		return new CassandraParameters(method);
	}

	/**
	 * Returns whether the method has an annotated query.
	 */
	public boolean hasAnnotatedQuery() {
		return query.map(Query::value).filter(StringUtils::hasText).isPresent();
	}

	/**
	 * Returns the query string declared in a {@link Query} annotation or {@literal null} if neither the annotation found
	 * nor the attribute was specified.
	 *
	 * @return
	 */
	public String getAnnotatedQuery() {
		return query.map(Query::value).orElse(null);
	}

	/**
	 * Returns the {@link Query} annotation that is applied to the method or {@code null} if none available.
	 *
	 * @return
	 */
	Optional<Query> getQueryAnnotation() {
		return query;
	}

	@Override
	protected Class<?> getDomainClass() {
		return super.getDomainClass();
	}

	/**
	 * @return the return type for this {@link QueryMethod}.
	 */
	public TypeInformation<?> getReturnType() {
		return ClassTypeInformation.fromReturnTypeOf(method);
	}

	/**
	 * @return true is the method returns a {@link ResultSet}.
	 */
	public boolean isResultSetQuery() {
		return ResultSet.class.isAssignableFrom(getReturnType().getActualType().getType());
	}
}
