/**
 * CellProfiler is distributed under the GNU General Public License.
 * See the accompanying file LICENSE for details.
 *
 * Copyright (c) 2003-2009 Massachusetts Institute of Technology
 * Copyright (c) 2009-2014 Broad Institute
 * All rights reserved.
 * 
 * Please see the AUTHORS file for credits.
 * 
 * Website: http://www.cellprofiler.org
 */
package org.cellprofiler.imageset;

import java.io.IOException;
import java.io.Reader;
import java.text.Collator;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;


import au.com.bytecode.opencsv.CSVReader;

/**
 * @author Lee Kamentsky
 * 
 * A metadata extractor that matches metadata entries in a .csv file
 * to those in an IPD.
 *
 */
public class ImportedMetadataExtractor implements MetadataExtractor<ImagePlaneDetails> {

	/**
	 * @author Lee Kamentsky
	 *
	 * An ImportedMetadataExtractor.KeyPair represents a pair
	 * of metadata keys used for matching. The ImportedMetadataExtractor
	 * matches sets of values in the CSV to sets of values in the
	 * ImagePlaneDetails's metadata. The metadata key names in the
	 * CSV can be different than in the IPD, for instance "PlateName"
	 * in the CSV and "Plate" in the IPD. The KeyPair allows arbitrary
	 * matching of metadata keys between the two.
	 */
	static public class KeyPair {
		final public String csvKey;
		final public String ipdKey;
		final public Comparator<String> comparator;
		public KeyPair(String csvKey, String ipdKey, Comparator<String> comparator){
			this.csvKey = csvKey;
			this.ipdKey = ipdKey;
			this.comparator = comparator;
		}
		/**
		 * Create a comparator of strings from one of objects
		 * @param c
		 * @return
		 */
		static private Comparator<String> adapt(final Comparator<Object> c) {
			return new Comparator<String> () {

				public int compare(String o1, String o2) {
					return c.compare(o1, o2);
				}
				
			};
		}
		/**
		 * Make a key pair that requires exact case comparison
		 * @param csvKey
		 * @param ipdKey
		 * @return
		 */
		static public KeyPair makeCaseSensitiveKeyPair(String csvKey, String ipdKey) {
			Collator c = Collator.getInstance();
			c.setStrength(Collator.IDENTICAL);
			return new KeyPair(csvKey, ipdKey, adapt(c));
		}
		/**
		 * Make a key pair that allows case-insensitive comparison
		 * @param csvKey
		 * @param ipdKey
		 * @return
		 */
		static public KeyPair makeCaseInsensitiveKeyPair(String csvKey, String ipdKey) {
			Collator c = Collator.getInstance();
			c.setStrength(Collator.SECONDARY);
			return new KeyPair(csvKey, ipdKey, adapt(c));
		}
		/**
		 * Make a key pair that does numeric comparison
		 * @param csvKey
		 * @param ipdKey
		 * @return
		 */
		static public KeyPair makeNumericKeyPair(String csvKey, String ipdKey) {
			Comparator<String> c = new Comparator<String> () {

				public int compare(String o1, String o2) {
					if (o1.equals(o2)) return 0;
					return Double.valueOf(o1).compareTo(Double.valueOf(o2));
				}
			};
			return new KeyPair(csvKey, ipdKey, c);
		}
	}
	/**
	 * @author Lee Kamentsky
	 *
	 * Compares CSV keys to IPD keys using the
	 * correct comparators.
	 */
	protected class MetadataComparator implements Comparator<List<String>> {

		/* (non-Javadoc)
		 * @see java.util.Comparator#compare(java.lang.Object, java.lang.Object)
		 */
		public int compare(List<String> o1, List<String> o2) {
			for (int i=0; i< matchingKeys.length; i++) {
				final int result = matchingKeys[i].comparator.compare(o1.get(i), o2.get(i));
				if (result != 0) return result;
			}
			return 0;
		}
		
	}
		
	/**
	 * The CSV metadata is stored in a map of matching values to metadata to be added.
	 */
	final private Map<List<String>, Map<String, String>> importedMetadata = 
		new TreeMap<List<String>, Map<String, String>>(new MetadataComparator());
	/**
	 * The keys to be matched against IPD metadata, in the order they appear
	 * in the importedMetadata map key list.
	 */
	final private KeyPair [] matchingKeys;
	/**
	 * The column positions of those matching keys
	 */
	final private int [] matchingPositions;
	/**
	 * The names of the metadata keys
	 */
	final private String [] metadataKeys;
	/**
	 * The keys' positions in the CSV file
	 */
	final private int [] metadataPositions;
	/**
	 * csvReader is non-null if we have yet to read the
	 * rows following the header.
	 */
	private CSVReader csvReader;
	/**
	 * Constructor: initialize the ImportedMetadataExtractor by
	 * passing it a reader of the .csv file and an array of keys
	 * in the .csv file header that are to be used to match IPD
	 * metadata against the metadata values in the file.
	 * 
	 * @param rdr a reader of the csv file
	 * @param matchingKeys the keys to use to match rows in the CSV against rows in the ipd metadata.
	 * @param caseInsensitive true if case-insensitive matching should be used when matching values
	 * @throws IOException 
	 */
	public ImportedMetadataExtractor(Reader rdr, KeyPair [] matchingKeys) 
	throws IOException {
		this.matchingKeys = matchingKeys;
		csvReader = new CSVReader(rdr);
		String [] allKeys = csvReader.readNext();
		if (allKeys == null)
			throw new IOException("The CSV file has no header line");
		matchingPositions = new int [matchingKeys.length];
		Arrays.fill(matchingPositions, -1);
		metadataKeys = new String [allKeys.length - matchingKeys.length];
		metadataPositions = new int[metadataKeys.length];
		int mpIdx = 0;
		key_loop:
		for (int kidx = 0; kidx < allKeys.length; kidx++) {
			String key = allKeys[kidx];
			for (int i=0; i<this.matchingKeys.length; i++) {
				if (key.equals(matchingKeys[i].csvKey)) {
					if (matchingPositions[i] != -1) {
						throw new IOException("Duplicate key in CSV header: " + key);
					}
					matchingPositions[i] = kidx;
					continue key_loop;
				}
			}
			if (mpIdx >= metadataPositions.length) {
				/* Exception logic handled outside of loop:
				 * some matching key will not be set.
				 */
				break;
			}
			metadataKeys[mpIdx] = StringCache.intern(key);
			metadataPositions[mpIdx++] = kidx;
		}
		for (int i=0; i<matchingPositions.length; i++) {
			if (matchingPositions[i] == -1) {
				throw new IOException(String.format("Key, \"%s\", is missing from CSV header.", matchingKeys[i].csvKey));
			}
		}
		readData();
	}
	/**
	 * Read the metadata from the CSV file
	 * 
	 * @throws IOException
	 */
	private void readData() throws IOException
	{
		int line = 0;
		int nFields = metadataKeys.length + matchingKeys.length;
		while(true) {
			String [] fields = csvReader.readNext();
			line++;
			if (fields == null) break;
			if (fields.length < nFields) {
				throw new IOException(String.format("Line # %d: only %d values defined, expected %d", line, fields.length, nFields));
			}
			final ArrayList<String> key = new ArrayList<String>(matchingKeys.length);
			for (int i=0; i<matchingPositions.length; i++) {
				key.add(StringCache.intern(fields[matchingPositions[i]]));
			}
			final Map<String, String> values = new HashMap<String, String>(metadataPositions.length);
			for (int i=0; i<metadataPositions.length; i++) {
				values.put(metadataKeys[i], StringCache.intern(fields[metadataPositions[i]]));
			}
			importedMetadata.put(key, values);
		}
	}
	/* (non-Javadoc)
	 * @see org.cellprofiler.imageset.MetadataExtractor#extract(java.lang.Object)
	 */
	public Map<String, String> extract(ImagePlaneDetails source) {
		final ArrayList<String> key = new ArrayList<String>(matchingKeys.length);
		for (int i=0; i<matchingKeys.length; i++) {
			final String value = source.get(matchingKeys[i].ipdKey); 
			if (value == null)
				return emptyMap;
			key.add(value);
		}
		if (importedMetadata.containsKey(key))
			return importedMetadata.get(key);
		return emptyMap;
	}
	/* (non-Javadoc)
	 * @see org.cellprofiler.imageset.MetadataExtractor#getMetadataKeys()
	 */
	public List<String> getMetadataKeys() {
		return Arrays.asList(metadataKeys);
	}
}
