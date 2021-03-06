/*
 * Copyright 2017 the original author or authors.
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
package org.springframework.data.cassandra;

import com.datastax.driver.core.Session;

/**
 * A factory for Apache Cassandra sessions.
 * <p>
 * An alternative to the {@link com.datastax.driver.core.Cluster} facility, a {@link SessionFactory} object is the
 * preferred means of getting a connection. The {@link SessionFactory} interface is implemented by a {@link Session}
 * provider.
 * <p>
 * A {@link SessionFactory} object can have properties that can be modified when necessary. For example, if the
 * {@link Session} is moved to a different server, the property for the server can be changed. The benefit is that
 * because the data source's properties can be changed, any code accessing that {@link SessionFactory} does not need to
 * be changed.
 *
 * @author Mark Paluch
 * @since 2.0
 */
public interface SessionFactory {

	/**
	 * Attempts to establish a {@link Session} with the connection infrastructure that this {@link SessionFactory} object
	 * represents.
	 *
	 * @return a {@link Session} to Apache Cassandra.
	 */
	Session getSession();
}
