/*******************************************************************************
 * Copyright (c) 2010, 2012 Institute for Dutch Lexicology
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *******************************************************************************/
package nl.inl.blacklab.suggest;

import java.util.Comparator;

/**
 * For sorting words by descending similarity to a given target word
 */
final class LevenshteinComparator implements Comparator<String> {
	private Levenshtein levensthein;

	public LevenshteinComparator(String target) {
		levensthein = new Levenshtein(target);
	}

	@Override
	public int compare(String a, String b) {
		Float da = levensthein.similarity(a);
		Float db = levensthein.similarity(b);
		int result = -da.compareTo(db);
		if (result == 0)
			result = a.compareTo(b);
		return result;
	}
}