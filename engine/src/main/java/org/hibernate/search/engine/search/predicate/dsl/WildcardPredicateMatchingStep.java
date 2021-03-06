/*
 * Hibernate Search, full-text search for your domain model
 *
 * License: GNU Lesser General Public License (LGPL), version 2.1 or later
 * See the lgpl.txt file in the root directory or <http://www.gnu.org/licenses/lgpl-2.1.html>.
 */
package org.hibernate.search.engine.search.predicate.dsl;

/**
 * The step in a "wildcard" predicate definition where the pattern to match can be set.
 *
 * @param <N> The type of the next step.
 */
public interface WildcardPredicateMatchingStep<N extends WildcardPredicateOptionsStep<?>> {

	/**
	 * Require at least one of the targeted fields to match the given wildcard pattern.
	 *
	 * @param wildcardPattern The pattern to match. Supported wildcards are {@code *},
	 * which matches any character sequence (including the empty one), and {@code ?},
	 * which matches any single character. {@code \} is the escape character.
	 * @return The next step.
	 */
	N matching(String wildcardPattern);

}
