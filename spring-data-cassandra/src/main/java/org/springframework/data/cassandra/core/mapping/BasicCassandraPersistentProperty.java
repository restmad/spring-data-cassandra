/*
 * Copyright 2013-2017 the original author or authors
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.cassandra.core.mapping;

import static org.springframework.data.cassandra.core.cql.CqlIdentifier.*;

import java.lang.annotation.Annotation;
import java.lang.reflect.AnnotatedParameterizedType;
import java.lang.reflect.AnnotatedType;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import org.springframework.context.ApplicationContext;
import org.springframework.context.ApplicationContextAware;
import org.springframework.context.expression.BeanFactoryAccessor;
import org.springframework.context.expression.BeanFactoryResolver;
import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.data.cassandra.core.cql.CqlIdentifier;
import org.springframework.data.cassandra.core.cql.Ordering;
import org.springframework.data.cassandra.core.cql.PrimaryKeyType;
import org.springframework.data.cassandra.util.SpelUtils;
import org.springframework.data.mapping.Association;
import org.springframework.data.mapping.MappingException;
import org.springframework.data.mapping.model.AnnotationBasedPersistentProperty;
import org.springframework.data.mapping.model.Property;
import org.springframework.data.mapping.model.SimpleTypeHolder;
import org.springframework.data.util.TypeInformation;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.util.Assert;
import org.springframework.util.ClassUtils;
import org.springframework.util.StringUtils;

import com.datastax.driver.core.DataType;
import com.datastax.driver.core.DataType.Name;
import com.datastax.driver.core.UserType;

/**
 * Cassandra specific {@link org.springframework.data.mapping.model.AnnotationBasedPersistentProperty} implementation.
 *
 * @author Alex Shvid
 * @author Matthew T. Adams
 * @author Antoine Toulme
 * @author Mark Paluch
 * @author John Blum
 */
public class BasicCassandraPersistentProperty extends AnnotationBasedPersistentProperty<CassandraPersistentProperty>
		implements CassandraPersistentProperty, ApplicationContextAware {

	private final UserTypeResolver userTypeResolver;

	private StandardEvaluationContext spelContext;

	/**
	 * Whether this property has been explicitly instructed to force quote column names.
	 */
	private Optional<Boolean> forceQuote = Optional.empty();

	/**
	 * An unmodifiable list of this property's column names.
	 */
	private CqlIdentifier columnName;

	/**
	 * Create a new {@link BasicCassandraPersistentProperty}.
	 *
	 * @param property the actual {@link Property} in the domain entity corresponding to this persistent entity.
	 * @param owner the containing object or {@link CassandraPersistentEntity} of this persistent property.
	 * @param simpleTypeHolder mapping of Java [simple|wrapper] types to Cassandra data types.
	 */
	public BasicCassandraPersistentProperty(Property property, CassandraPersistentEntity<?> owner,
			SimpleTypeHolder simpleTypeHolder) {
		this(property, owner, simpleTypeHolder, null);
	}

	/**
	 * Create a new {@link BasicCassandraPersistentProperty}.
	 *
	 * @param property the actual {@link Property} in the domain entity corresponding to this persistent entity.
	 * @param owner the containing object or {@link CassandraPersistentEntity} of this persistent property.
	 * @param simpleTypeHolder mapping of Java [simple|wrapper] types to Cassandra data types.
	 * @param userTypeResolver resolver for user-defined types.
	 */
	public BasicCassandraPersistentProperty(Property property, CassandraPersistentEntity<?> owner,
			SimpleTypeHolder simpleTypeHolder, UserTypeResolver userTypeResolver) {

		super(property, owner, simpleTypeHolder);

		this.userTypeResolver = userTypeResolver;
	}

	/* (non-Javadoc)
	 * @see org.springframework.context.ApplicationContextAware#setApplicationContext(org.springframework.context.ApplicationContext)
	 */
	@Override
	public void setApplicationContext(ApplicationContext context) {

		Assert.notNull(context, "ApplicationContext must not be null");

		spelContext = new StandardEvaluationContext();
		spelContext.addPropertyAccessor(new BeanFactoryAccessor());
		spelContext.setBeanResolver(new BeanFactoryResolver(context));
		spelContext.setRootObject(context);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.mapping.model.AbstractPersistentProperty#getOwner()
	 */
	@Override
	public CassandraPersistentEntity<?> getOwner() {
		return (CassandraPersistentEntity<?>) super.getOwner();
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty#getColumnName()
	 */
	@Override
	public CqlIdentifier getColumnName() {

		if (this.columnName == null) {
			this.columnName = determineColumnName();
		}

		return this.columnName;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty#getPrimaryKeyOrdering()
	 */
	@Override
	public Ordering getPrimaryKeyOrdering() {

		PrimaryKeyColumn annotation = findAnnotation(PrimaryKeyColumn.class);

		if (annotation != null) {
			return annotation.ordering();
		}

		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty#getDataType()
	 */
	@Override
	public DataType getDataType() {

		DataType dataType = findDataType();

		if (dataType == null) {
			throw new InvalidDataAccessApiUsageException(String.format(
					"Unknown type [%s] for property [%s] in entity [%s]; only primitive types and Collections or Maps of primitive types are allowed",
					getType(), getName(), getOwner().getName()));
		}

		return dataType;
	}

	private DataType findDataType() {

		CassandraType cassandraType = findAnnotation(CassandraType.class);

		if (cassandraType != null) {
			return getDataTypeFor(cassandraType);
		}

		if (isMap()) {

			List<TypeInformation<?>> args = getTypeInformation().getTypeArguments();

			ensureTypeArguments(args.size(), 2);

			return DataType.map(getDataTypeFor(args.get(0).getType()), getDataTypeFor(args.get(1).getType()));
		}

		if (isCollectionLike()) {

			List<TypeInformation<?>> args = getTypeInformation().getTypeArguments();

			ensureTypeArguments(args.size(), 1);

			if (Set.class.isAssignableFrom(getType())) {
				return DataType.set(getDataTypeFor(args.get(0).getType()));
			}

			if (List.class.isAssignableFrom(getType())) {
				return DataType.list(getDataTypeFor(args.get(0).getType()));
			}
		}

		return CassandraSimpleTypeHolder.getDataTypeFor(getType());
	}

	private DataType getDataTypeFor(CassandraType annotation) {

		DataType.Name type = annotation.type();

		switch (type) {
			case MAP:
				ensureTypeArguments(annotation.typeArguments().length, 2);
				return DataType.map(getDataTypeFor(annotation.typeArguments()[0]),
						getDataTypeFor(annotation.typeArguments()[1]));
			case LIST:
				ensureTypeArguments(annotation.typeArguments().length, 1);
				if (annotation.typeArguments()[0] == Name.UDT) {
					return DataType.list(getUserType(annotation));
				}
				return DataType.list(getDataTypeFor(annotation.typeArguments()[0]));
			case SET:
				ensureTypeArguments(annotation.typeArguments().length, 1);
				if (annotation.typeArguments()[0] == Name.UDT) {
					return DataType.set(getUserType(annotation));
				}
				return DataType.set(getDataTypeFor(annotation.typeArguments()[0]));
			case UDT:
				return getUserType(annotation);
			default:
				return CassandraSimpleTypeHolder.getDataTypeFor(type);
		}
	}

	private DataType getUserType(CassandraType annotation) {

		if (!StringUtils.hasText(annotation.userTypeName())) {
			throw new InvalidDataAccessApiUsageException(
					String.format("Expected user type name in property ['%s'] of type ['%s'] in entity [%s]", getName(),
							getType(), getOwner().getName()));
		}

		CqlIdentifier identifier = CqlIdentifier.cqlId(annotation.userTypeName());
		UserType userType = userTypeResolver.resolveType(identifier);

		if (userType == null) {
			throw new MappingException(String.format("User type [%s] not found", identifier));
		}

		return userType;
	}

	private DataType getDataTypeFor(DataType.Name dataTypeName) {

		DataType dataType = CassandraSimpleTypeHolder.getDataTypeFor(dataTypeName);

		if (dataType == null) {
			throw new InvalidDataAccessApiUsageException(String.format(
					"Only primitive types are allowed inside Collections for property [%1$s] of type [%2$s] in entity [%3$s]",
					getName(), getType(), getOwner().getName()));
		}

		return dataType;
	}

	private DataType getDataTypeFor(Class<?> javaType) {

		DataType dataType = CassandraSimpleTypeHolder.getDataTypeFor(javaType);

		if (dataType == null) {
			throw new InvalidDataAccessApiUsageException(String.format(
					"Only primitive types are allowed inside Collections for property [%1$s] of type ['%2$s'] in entity [%3$s]",
					getName(), getType(), getOwner().getName()));
		}

		return dataType;
	}

	private void ensureTypeArguments(int args, int expected) {
		if (args != expected) {
			throw new InvalidDataAccessApiUsageException(
					String.format("Expected [%1$s] typed arguments for property ['%2$s'] of type ['%3$s'] in entity [%4$s]",
							expected, getName(), getType(), getOwner().getName()));
		}
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty#isCompositePrimaryKey()
	 */
	@Override
	public boolean isCompositePrimaryKey() {
		return (AnnotatedElementUtils.findMergedAnnotation(getType(), PrimaryKeyClass.class) != null);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty#isPrimaryKeyColumn()
	 */
	@Override
	public boolean isPrimaryKeyColumn() {
		return isAnnotationPresent(PrimaryKeyColumn.class);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty#isPartitionKeyColumn()
	 */
	@Override
	public boolean isPartitionKeyColumn() {

		PrimaryKeyColumn annotation = findAnnotation(PrimaryKeyColumn.class);
		return annotation != null && PrimaryKeyType.PARTITIONED.equals(annotation.type());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty#isClusterKeyColumn()
	 */
	@Override
	public boolean isClusterKeyColumn() {

		PrimaryKeyColumn annotation = findAnnotation(PrimaryKeyColumn.class);
		return annotation != null && PrimaryKeyType.CLUSTERED.equals(annotation.type());
	}

	private CqlIdentifier determineColumnName() {

		if (isCompositePrimaryKey()) { // then the id type has @PrimaryKeyClass
			return null;
		}

		String defaultName = getName(); // TODO: replace with naming strategy class
		String overriddenName = null;
		boolean forceQuote = false;

		if (isIdProperty()) { // then the id is of a simple type (since it's not a composite primary key)
			PrimaryKey primaryKey = findAnnotation(PrimaryKey.class);

			if (primaryKey != null) {
				overriddenName = primaryKey.value();
				forceQuote = primaryKey.forceQuote();
			}

		} else if (isPrimaryKeyColumn()) { // then it's a simple type
			PrimaryKeyColumn primaryKeyColumn = findAnnotation(PrimaryKeyColumn.class);

			if (primaryKeyColumn != null) {
				overriddenName = primaryKeyColumn.value();
				forceQuote = primaryKeyColumn.forceQuote();
			}

		} else { // then it's a vanilla column with the assumption that it's mapped to a single column

			Column column = findAnnotation(Column.class);

			if (column != null) {
				overriddenName = column.value();
				forceQuote = column.forceQuote();
			}
		}

		return createColumnName(defaultName, overriddenName, forceQuote);
	}

	private CqlIdentifier createColumnName(String defaultName, String overriddenName, boolean forceQuote) {

		String name = defaultName;

		if (StringUtils.hasText(overriddenName)) {
			name = (spelContext != null ? SpelUtils.evaluate(overriddenName, spelContext) : overriddenName);
		}

		return cqlId(name, forceQuote);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty#setColumnName(org.springframework.data.cassandra.core.cql.CqlIdentifier)
	 */
	@Override
	public void setColumnName(CqlIdentifier columnName) {

		Assert.notNull(columnName, "ColumnName must not be null");

		this.columnName = columnName;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty#setForceQuote(boolean)
	 */
	@Override
	public void setForceQuote(boolean forceQuote) {

		boolean changed = !this.forceQuote.isPresent() || this.forceQuote.filter(v -> v != forceQuote).isPresent();

		this.forceQuote = Optional.of(forceQuote);

		if (changed) {

			CqlIdentifier columnName = getColumnName();
			if (columnName != null) {
				setColumnName(cqlId(columnName.getUnquoted(), forceQuote));
			}
		}
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.mapping.model.AbstractPersistentProperty#getAssociation()
	 */
	@Override
	public Association<CassandraPersistentProperty> getAssociation() {
		return null;
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.mapping.model.AbstractPersistentProperty#createAssociation()
	 */
	@Override
	protected Association<CassandraPersistentProperty> createAssociation() {
		return new Association<>(this, null);
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty#isMapLike()
	 */
	@Override
	public boolean isMapLike() {
		return ClassUtils.isAssignable(Map.class, getType());
	}

	/* (non-Javadoc)
	 * @see org.springframework.data.cassandra.core.mapping.CassandraPersistentProperty#findAnnotatedType(java.lang.Class)
	 */
	@Override
	public AnnotatedType findAnnotatedType(Class<? extends Annotation> annotationType) {

		if (getField() != null) {

			AnnotatedType type = getField().getAnnotatedType();

			if (hasAnnotation(type, annotationType, getTypeInformation())) {
				return type;
			}
		}

		if (getGetter() != null) {

			AnnotatedType type = getGetter().getAnnotatedReturnType();

			if (hasAnnotation(type, annotationType, getTypeInformation())) {
				return type;
			}
		}

		if (getSetter() != null && getSetter().getParameterCount() == 1) {

			AnnotatedType type = getSetter().getParameters()[0].getAnnotatedType();

			if (hasAnnotation(type, annotationType, getTypeInformation())) {
				return type;
			}
		}

		return null;
	}

	private static boolean hasAnnotation(AnnotatedType type, Class<? extends Annotation> annotationType,
			TypeInformation<?> typeInformation) {

		if (AnnotatedElementUtils.hasAnnotation(type, annotationType)) {
			return true;
		}

		AnnotatedParameterizedType parameterizedType = (AnnotatedParameterizedType) type;
		AnnotatedType[] arguments = parameterizedType.getAnnotatedActualTypeArguments();

		if (typeInformation.isCollectionLike() && arguments.length == 1) {
			return AnnotatedElementUtils.hasAnnotation(arguments[0], annotationType);
		}

		if (typeInformation.isMap() && arguments.length == 2) {
			return AnnotatedElementUtils.hasAnnotation(arguments[0], annotationType)
					|| AnnotatedElementUtils.hasAnnotation(arguments[1], annotationType);
		}

		return false;
	}
}
