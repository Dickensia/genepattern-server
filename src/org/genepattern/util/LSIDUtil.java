/*
 * Created on Jan 31, 2005
 *
 * TODO To change the template for this generated file go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
package org.genepattern.util;

/**
 * @author Liefeld
 *
 * TODO To change the template for this generated type comment go to
 * Window - Preferences - Java - Code Style - Code Templates
 */
public class LSIDUtil {
	
		public static String AUTHORITY_MINE = "mine";
		public static String AUTHORITY_BROAD = "broad"; 
		public static String AUTHORITY_FOREIGN = "foreign";
		public static String BROAD_AUTHORITY = "broad.mit.edu";

		private static LSIDUtil inst = null;
		
		private static String authority = "broad-cancer-genomics";
		private static String namespace = "genepatternmodules";


		private LSIDUtil(){
			String auth = System.getProperty("lsid.authority");
			if (auth != null) {
				authority = auth;
			}
		}


		public static LSIDUtil getInstance(){
			if (inst == null){
				inst = new LSIDUtil();
			}
			return inst;
		}	
		
		public String getAuthority(){
			return authority;
		}
		public String getNamespace(){
			return namespace;
		}



		public String getAuthorityType(LSID lsid) {
			String authorityType;
			if (lsid == null) {
				authorityType = AUTHORITY_MINE;
			} else {
				String lsidAuthority = lsid.getAuthority();
				if (lsidAuthority.equals(authority)) {
					authorityType = AUTHORITY_MINE;
				} else if (lsidAuthority.equals(BROAD_AUTHORITY)) {
					authorityType = AUTHORITY_BROAD;
				} else {
					authorityType = AUTHORITY_FOREIGN;
				}
			}
			return authorityType;
		}

		// compare authority types: 1=lsid1 is closer, 0=equal, -1=lsid2 is closer
		// closer is defined as mine > Broad > foreign
		public int compareAuthorities(LSID lsid1, LSID lsid2) {
			String at1 = getAuthorityType(lsid1);
			String at2 = getAuthorityType(lsid2);
			if (!at1.equals(at2)) {
				if (at1.equals(AUTHORITY_MINE)) return 1;
				if (at2.equals(AUTHORITY_MINE)) return -1;
				if (at1.equals(AUTHORITY_BROAD)) return 1;
				return -1;
			} else {
				return 0;
			}
		}

		public LSID getNearerLSID(LSID lsid1, LSID lsid2) {
			int authorityComparison = compareAuthorities(lsid1, lsid2);
			if (authorityComparison < 0) return lsid2;
			if (authorityComparison > 0) {
				// closer authority than lsid2.getAuthority()
				return lsid1;
			}
			// same authority, check identifier
			int identifierComparison = lsid1.getIdentifier().compareTo(lsid2.getIdentifier());
			if (identifierComparison < 0) return lsid2;
			if (identifierComparison > 0) {
				// greater identifier than lsid2.getIdentifier()
				return lsid1;
			}
			// same authority and identifier, check version
			int versionComparison = lsid1.compareTo(lsid2);
			if (versionComparison < 0) return lsid2;
			if (versionComparison > 0) {
				// later version than lsid2.getVersion()
				return lsid1;
			}
			return lsid1; // equal???
	}
}

