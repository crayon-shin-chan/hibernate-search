/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.backend.lucene.search.extraction.impl;

import java.io.IOException;
import java.util.HashMap;
import java.util.Map;

import org.hibernate.search.backend.lucene.lowlevel.reader.impl.IndexReaderMetadataResolver;
import org.hibernate.search.backend.lucene.search.impl.LuceneDocumentReference;
import org.hibernate.search.backend.lucene.util.impl.LuceneFields;
import org.hibernate.search.engine.backend.common.DocumentReference;

import org.apache.lucene.index.BinaryDocValues;
import org.apache.lucene.index.DocValues;
import org.apache.lucene.index.LeafReaderContext;
import org.apache.lucene.search.ScoreMode;
import org.apache.lucene.search.SimpleCollector;

public final class DocumentReferenceCollector extends SimpleCollector {

	public static final LuceneCollectorFactory<DocumentReferenceCollector> FACTORY = DocumentReferenceCollector::new;

	private final IndexReaderMetadataResolver metadataResolver;

	private String currentLeafIndexName;
	private BinaryDocValues currentLeafIdDocValues;
	private int currentLeafDocBase;

	private Map<Integer, DocumentReference> collected = new HashMap<>();

	private DocumentReferenceCollector(LuceneCollectorExecutionContext executionContext) {
		this.metadataResolver = executionContext.getMetadataResolver();
	}

	@Override
	public void collect(int doc) throws IOException {
		currentLeafIdDocValues.advance( doc );
		collected.put( currentLeafDocBase + doc, new LuceneDocumentReference(
				currentLeafIndexName,
				currentLeafIdDocValues.binaryValue().utf8ToString()
		) );
	}

	@Override
	public ScoreMode scoreMode() {
		return ScoreMode.COMPLETE_NO_SCORES;
	}

	public DocumentReference get(int doc) {
		return collected.get( doc );
	}

	@Override
	protected void doSetNextReader(LeafReaderContext context) throws IOException {
		this.currentLeafIndexName = metadataResolver.resolveIndexName( context );
		this.currentLeafIdDocValues = DocValues.getBinary( context.reader(), LuceneFields.idFieldName() );
		this.currentLeafDocBase = context.docBase;
	}
}