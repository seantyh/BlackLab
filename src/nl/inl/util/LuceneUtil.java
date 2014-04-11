package nl.inl.util;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import nl.inl.blacklab.analysis.BLDutchAnalyzer;

import org.apache.lucene.analysis.Analyzer;
import org.apache.lucene.index.AtomicReader;
import org.apache.lucene.index.DirectoryReader;
import org.apache.lucene.index.DocsAndPositionsEnum;
import org.apache.lucene.index.IndexReader;
import org.apache.lucene.index.Term;
import org.apache.lucene.index.TermsEnum;
import org.apache.lucene.queryparser.classic.ParseException;
import org.apache.lucene.queryparser.classic.QueryParser;
import org.apache.lucene.search.BooleanClause.Occur;
import org.apache.lucene.search.BooleanQuery;
import org.apache.lucene.search.DocIdSetIterator;
import org.apache.lucene.search.FuzzyQuery;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.highlight.QueryTermExtractor;
import org.apache.lucene.search.highlight.WeightedTerm;
import org.apache.lucene.util.BytesRef;
import org.apache.lucene.util.Version;

public class LuceneUtil {

	/**
	 * Test if a term occurs in the index
	 * @param reader the index
	 * @param term the term
	 * @return true iff it occurs in the index
	 */
	public static boolean termOccursInIndex(IndexReader reader, Term term) {
		try {
			return reader.docFreq(term) > 0;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Get all the terms in the index with low edit distance from the supplied term
	 * @param reader the index
	 * @param luceneName
	 *            the field to search in
	 * @param searchTerms
	 *            search terms
	 * @param maxEdits
	 *            maximum edit distance (Levenshtein algorithm) for matches
	 *            (i.e. lower is more similar)
	 * @return the set of terms in the index that are close to our search term
	 * @throws BooleanQuery.TooManyClauses
	 *             if the expansion resulted in too many terms
	 */
	public static Set<String> getMatchingTermsFromIndex(IndexReader reader, String luceneName,
			Collection<String> searchTerms, int maxEdits) {
		boolean doFuzzy = true;
		if (maxEdits == 0) {
			// Exact match; don't use fuzzy query (slow)
			Set<String> result = new HashSet<String>();
			try {
				for (String term: searchTerms) {
					if (reader.docFreq(new Term(luceneName, term)) > 0)
						result.add(term);
				}
			} catch (IOException e) {
				throw new RuntimeException(e);
			}
			return result;
		}

		BooleanQuery q = new BooleanQuery();
		for (String s: searchTerms) {
			FuzzyQuery fq = new FuzzyQuery(new Term(luceneName, s), maxEdits);
			q.add(fq, Occur.SHOULD);
		}

		try {
			Query rewritten = q.rewrite(reader);
			WeightedTerm[] wts = QueryTermExtractor.getTerms(rewritten);
			Set<String> terms = new HashSet<String>();
			for (WeightedTerm wt: wts) {
				if (doFuzzy || searchTerms.contains(wt.getTerm())) {
					terms.add(wt.getTerm());
				}
			}
			return terms;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

	/**
	 * Parse a query in the Lucene query language format (QueryParser supplied with Lucene).
	 *
	 * @param luceneQuery the query string
	 * @param analyzer analyzer to use
	 * @param defaultField default search field
	 * @return the query
	 * @throws ParseException on syntax error
	 */
	public static Query parseLuceneQuery(String luceneQuery, Analyzer analyzer, String defaultField)
			throws ParseException {
		QueryParser qp = new QueryParser(Version.LUCENE_42, defaultField, analyzer);
		return qp.parse(luceneQuery);
	}

	/**
	 * Parse a query in the Lucene query language format (QueryParser supplied with Lucene).
	 *
	 * NOTE: this uses the default Dutch analyzer. Use the version of this method that takes an analyzer
	 * if you need a different one.
	 *
	 * @param luceneQuery the query string
	 * @param defaultField default search field
	 * @return the query
	 * @throws ParseException on syntax error
	 */
	public static Query parseLuceneQuery(String luceneQuery, String defaultField)
			throws ParseException {
		return parseLuceneQuery(luceneQuery, new BLDutchAnalyzer(), defaultField);
	}

	/**
	 * Get all words between the specified start and end positions from the term vector.
	 *
	 * NOTE: this may return an array of less than the size requested, if the document ends before
	 * the requested end position.
	 * @param reader the index
	 * @param doc
	 *            doc id
	 * @param luceneName
	 *            the index field from which to use the term vector
	 * @param start
	 *            start position (first word we want to request)
	 * @param end
	 *            end position (last word we want to request)
	 * @return the words found, in order
	 */
	public static String[] getWordsFromTermVector(DirectoryReader reader, int doc,
			String luceneName, int start, int end) {

		// Vraag de term position vector van de contents van dit document op
		// NOTE: je kunt ook alle termvectors in 1x opvragen. Kan sneller zijn.

		// Versie MKS:
		try {
			org.apache.lucene.index.Terms terms = reader.getTermVector(doc, luceneName);
			if (terms == null) {
				throw new RuntimeException("Field " + luceneName + " has no Terms");
			}
			if (!terms.hasPositions())
				throw new RuntimeException("Field has no character postion information");
			// String[] docTerms = new String[(int) terms.size()];
			// final List<BytesRef> termsList = new ArrayList<BytesRef>();
			TermsEnum termsEnum = terms.iterator(null);
			DocsAndPositionsEnum docPosEnum = termsEnum.docsAndPositions(null, null);
			// Verzamel concordantiewoorden uit term vector
			String[] concordanceWords = new String[end - start + 1];
			int numFound = 0;
			while (docPosEnum.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
				if (docPosEnum.docID() != doc)
					throw new RuntimeException("Wrong doc id!");
				int position = -1;
				while ((position = docPosEnum.nextPosition()) < docPosEnum.freq() && position != -1) {
					if (position > start && position <= end) {
						concordanceWords[position - start] = docPosEnum.getPayload().utf8ToString();
						numFound++;
					}
				}
				if (numFound == concordanceWords.length)
					return concordanceWords;
			}

			if (numFound < concordanceWords.length) {
				String[] partial = new String[numFound];
				for (int i = 0; i < numFound; i++) {
					partial[i] = concordanceWords[i];
					if (partial[i] == null) {
						throw new RuntimeException("Not all words found (" + numFound + " out of "
								+ concordanceWords.length
								+ "); missing words in the middle of concordance!");
					}
				}
				return partial;
			}
			return concordanceWords;
		} catch (Exception e) {
			throw ExUtil.wrapRuntimeException(e);
		}

		/*
		try {
			TermPositionVector termPositionVector = (TermPositionVector) indexReader
					.getTermFreqVector(doc, luceneName);
			if (termPositionVector == null) {
				throw new RuntimeException("Field " + luceneName + " has no TermPositionVector");
			}

			// Vraag het array van terms (voor reconstructie text)
			String[] docTerms = termPositionVector.getTerms();

			// Verzamel concordantiewoorden uit term vector
			String[] concordanceWords = new String[end - start + 1];
			int numFound = 0;
			for (int k = 0; k < docTerms.length; k++) {
				int[] positions = termPositionVector.getTermPositions(k);
				for (int l = 0; l < positions.length; l++) {
					int p = positions[l];
					if (p >= start && p <= end) {
						concordanceWords[p - start] = docTerms[k];
						numFound++;
					}
				}
				if (numFound == concordanceWords.length)
					return concordanceWords;
			}
			if (numFound < concordanceWords.length) {
				String[] partial = new String[numFound];
				for (int i = 0; i < numFound; i++) {
					partial[i] = concordanceWords[i];
					if (partial[i] == null) {
						throw new RuntimeException("Not all words found (" + numFound + " out of "
								+ concordanceWords.length
								+ "); missing words in the middle of concordance!");
					}
				}
				return partial;
			}
			return concordanceWords;
		} catch (Exception e) {
			throw ExUtil.wrapRuntimeException(e);
		}*/
	}

	/**
	 * Get all words between the specified start and end positions from the term vector.
	 *
	 * NOTE: this may return an array of less than the size requested, if the document ends before
	 * the requested end position.
	 * @param reader the index
	 * @param doc
	 *            doc id
	 * @param luceneName
	 *            the index field from which to use the term vector
	 * @param start
	 *            start position (first word we want to request)
	 * @param end
	 *            end position (last word we want to request)
	 * @return the words found, in order
	 */
	public static List<String[]> getWordsFromTermVector(DirectoryReader reader, int doc,
			String luceneName, int[] start, int[] end) {
		try {
			// Get the term position vector of the requested field
			org.apache.lucene.index.Terms terms = reader.getTermVector(doc, luceneName);
			if (terms == null) {
				throw new RuntimeException("Field " + luceneName + " has no Terms");
			}
			if (!terms.hasPositions())
				throw new RuntimeException("Field has no character postion information");
			DocsAndPositionsEnum dpe = terms.iterator(null).docsAndPositions(null, null);
			List<String[]> results = new ArrayList<String[]>(start.length);
			for (int i = 0; i < start.length; i++) {
				while (dpe.nextDoc() != DocIdSetIterator.NO_MORE_DOCS) {
					String[] concordanceWords = new String[end[i] - start[i] + 1];
					int position = -1;
					int numFound = 0;

					while ((position = dpe.nextPosition()) < dpe.freq() && position != -1) {
						if (position > start[i] && position <= end[i]) {
							concordanceWords[position - start[i]] = dpe.getPayload().utf8ToString();
							numFound++;
						}
					}
					if (numFound < concordanceWords.length) {
						String[] partial = new String[numFound];
						for (int j = 0; j < numFound; j++) {
							partial[j] = concordanceWords[j];
							if (partial[j] == null) {
								throw new RuntimeException("Not all words found (" + numFound
										+ " out of " + concordanceWords.length
										+ "); missing words in the middle of concordance!");
							}
						}
						results.add(partial);
					} else {
						results.add(concordanceWords);
					}
				}
			}
			return results;
		} catch (Exception e) {
			throw ExUtil.wrapRuntimeException(e);
		}

		/*TermPositionVector termPositionVector = (TermPositionVector) indexReader
				.getTermFreqVector(doc, luceneName);
		if (termPositionVector == null) {
			throw new RuntimeException("Field " + luceneName + " has no TermPositionVector");
		}

		// Get the array of terms (for reconstructing text)
		String[] docTerms = termPositionVector.getTerms();

		List<String[]> results = new ArrayList<String[]>(start.length);
		for (int i = 0; i < start.length; i++) {
			// Gather concordance words from term vector
			String[] concordanceWords = new String[end[i] - start[i] + 1];
			int numFound = 0;
			for (int k = 0; k < docTerms.length; k++) {
				int[] positions = termPositionVector.getTermPositions(k);
				for (int l = 0; l < positions.length; l++) {
					int p = positions[l];
					if (p >= start[i] && p <= end[i]) {
						concordanceWords[p - start[i]] = docTerms[k];
						numFound++;
					}
				}
				if (numFound == concordanceWords.length)
					break;
			}
			if (numFound < concordanceWords.length) {
				String[] partial = new String[numFound];
				for (int j = 0; j < numFound; j++) {
					partial[j] = concordanceWords[j];
					if (partial[j] == null) {
						throw new RuntimeException("Not all words found (" + numFound
								+ " out of " + concordanceWords.length
								+ "); missing words in the middle of concordance!");
					}
				}
				results.add(partial);
			} else
				results.add(concordanceWords);
		}
		return results;
		} catch (Exception e) {
		throw ExUtil.wrapRuntimeException(e);
		}*/
	}

	/**
	 * Find terms in the index based on a prefix. Useful for autocomplete.
	 * @param index the index
	 * @param fieldName the field
	 * @param prefix the prefix we're looking for
	 * @param sensitive match case-sensitively or not?
	 * @return the matching terms
	 */
	public static List<String> findTermsByPrefix(AtomicReader index, String fieldName,
			String prefix, boolean sensitive) {
		try {
			if (!sensitive)
				prefix = StringUtil.removeAccents(prefix).toLowerCase();
			org.apache.lucene.index.Terms terms = index.terms(fieldName);
			List<String> results = new ArrayList<String>();
			TermsEnum termsEnum = terms.iterator(null);
			BytesRef brPrefix = new BytesRef(prefix.getBytes("utf-8"));
			termsEnum.seekCeil(brPrefix); // find the prefix in the terms list
			while (true) {
				BytesRef term = termsEnum.next();
				if (term == null)
					break;
				//Term term = termEnum.term();
				//if (!term.field().equals(fieldName))
				//	break;
				String termText = term.utf8ToString();
				String optDesensitized = termText;
				if (!sensitive)
					optDesensitized = StringUtil.removeAccents(termText).toLowerCase();
				if (!optDesensitized.substring(0, prefix.length()).equalsIgnoreCase(prefix)) {
					// Doesn't match prefix or different field; no more matches
					break;
				}
				// Match, add term
				results.add(termText);
			}
			return results;
		} catch (IOException e) {
			throw new RuntimeException(e);
		}
	}

}