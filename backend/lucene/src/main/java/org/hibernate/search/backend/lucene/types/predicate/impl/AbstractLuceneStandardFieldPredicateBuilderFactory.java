/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.lucene.types.predicate.impl;

import org.hibernate.search.backend.lucene.types.codec.impl.LuceneStandardFieldCodec;
import org.hibernate.search.engine.backend.types.converter.ToDocumentFieldValueConverter;
import org.hibernate.search.engine.search.predicate.spi.DslConverter;
import org.hibernate.search.util.common.impl.Contracts;

/**
 * @param <F> The field type exposed to the mapper.
 * @param <C> The codec type.
 * @see LuceneStandardFieldCodec
 */
abstract class AbstractLuceneStandardFieldPredicateBuilderFactory<F, C extends LuceneStandardFieldCodec<F, ?>>
		extends AbstractLuceneFieldPredicateBuilderFactory {

	private final ToDocumentFieldValueConverter<?, ? extends F> converter;
	private final ToDocumentFieldValueConverter<F, ? extends F> rawConverter;

	final C codec;

	AbstractLuceneStandardFieldPredicateBuilderFactory(ToDocumentFieldValueConverter<?, ? extends F> converter, ToDocumentFieldValueConverter<F, ? extends F> rawConverter,
			C codec) {
		Contracts.assertNotNull( converter, "converter" );
		Contracts.assertNotNull( rawConverter, "rawConverter" );
		Contracts.assertNotNull( codec, "codec" );
		this.converter = converter;
		this.rawConverter = rawConverter;
		this.codec = codec;
	}

	@Override
	public boolean isDslCompatibleWith(LuceneFieldPredicateBuilderFactory other, DslConverter dslConverter) {
		if ( !getClass().equals( other.getClass() ) ) {
			return false;
		}
		AbstractLuceneStandardFieldPredicateBuilderFactory<?, ?> castedOther =
				(AbstractLuceneStandardFieldPredicateBuilderFactory<?, ?>) other;
		if ( !codec.isCompatibleWith( castedOther.codec ) ) {
			return false;
		}
		return !dslConverter.isEnabled() || converter.isCompatibleWith( castedOther.converter );
	}

	protected ToDocumentFieldValueConverter<?, ? extends F> getConverter(DslConverter dslConverter) {
		return ( dslConverter.isEnabled() ) ? converter : rawConverter;
	}
}