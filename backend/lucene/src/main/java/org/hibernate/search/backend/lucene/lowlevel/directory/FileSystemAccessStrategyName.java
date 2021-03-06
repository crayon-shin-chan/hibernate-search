/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.lucene.lowlevel.directory;

import java.lang.invoke.MethodHandles;

import org.hibernate.search.backend.lucene.logging.impl.Log;
import org.hibernate.search.util.common.impl.StringHelper;
import org.hibernate.search.util.common.logging.impl.LoggerFactory;

public enum FileSystemAccessStrategyName {

	AUTO( "auto" ),
	/**
	 * @deprecated This strategy will be removed in a future version of Lucene.
	 */
	@Deprecated
	SIMPLE( "simple" ),
	NIO( "nio" ),
	MMAP( "mmap" );

	private static final Log log = LoggerFactory.make( Log.class, MethodHandles.lookup() );

	// This method conforms to the MicroProfile Config specification. Do not change its signature.
	public static FileSystemAccessStrategyName of(String value) {
		return StringHelper.parseDiscreteValues(
				FileSystemAccessStrategyName.values(),
				FileSystemAccessStrategyName::externalRepresentation,
				log::invalidFileSystemAccessStrategyName,
				value
		);
	}

	private final String externalRepresentation;

	FileSystemAccessStrategyName(String externalRepresentation) {
		this.externalRepresentation = externalRepresentation;
	}

	/**
	 * @return The expected string representation in configuration properties.
	 */
	public String externalRepresentation() {
		return externalRepresentation;
	}

	/**
	 * @return The expected string representation in configuration properties.
	 * @deprecated Use {@link #externalRepresentation()} instead.
	 */
	@Deprecated
	private String getExternalRepresentation() {
		return externalRepresentation();
	}
}
