/*
 * Copyright 2013-2017 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.springframework.data.cassandra.core.cql.keyspace;

import static org.springframework.data.cassandra.core.cql.CqlIdentifier.*;

import org.springframework.data.cassandra.core.cql.CqlIdentifier;
import org.springframework.util.Assert;

import com.datastax.driver.core.DataType;

/**
 * Base value object class for column changes that include {@link DataType} information.
 *
 * @author Matthew T. Adams
 * @see ColumnChangeSpecification
 */
public abstract class ColumnTypeChangeSpecification extends ColumnChangeSpecification {

	private final DataType type;

	/**
	 * Create a new {@link ColumnTypeChangeSpecification} for the given {@code name} and {@link type}
	 *
	 * @param name must not be empty or {@literal null}.
	 * @param type must not be {@literal null}.
	 */
	public ColumnTypeChangeSpecification(String name, DataType type) {
		this(cqlId(name), type);
	}

	/**
	 * Create a new {@link ColumnTypeChangeSpecification} for the given {@code name} and {@link type}
	 *
	 * @param name must not be {@literal null}.
	 * @param type must not be {@literal null}.
	 */
	public ColumnTypeChangeSpecification(CqlIdentifier name, DataType type) {
		super(name);

		Assert.notNull(type, "DataType must not be null");

		this.type = type;
	}

	/**
	 * @return the {@literal DataType}.
	 */
	public DataType getType() {
		return type;
	}
}
