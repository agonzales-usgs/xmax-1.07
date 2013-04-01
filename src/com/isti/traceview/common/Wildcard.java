package com.isti.traceview.common;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import org.apache.log4j.Logger;

import com.isti.traceview.TraceView;
import com.isti.traceview.TraceViewException;

/**
 * this class realize unix command line wildcard syntax
 * 
 * @author Max Kokoulin
 */
public class Wildcard {
	private static Logger lg = Logger.getLogger(Wildcard.class);

	List<String> path = null;
	List<File> lst = new ArrayList<File>();

	/**
	 * @param pattern
	 *            wildcard expression
	 * @param text
	 *            text to test
	 * @return flag if text matches pattern
	 */
	public static boolean matches(String pattern, String text) {
		boolean ret = text.matches(wildcardToRegex(pattern));
		//lg.debug("Match " + text + " with pattern " + wildcardToRegex(pattern) + ": " + ret);
		return ret;
	}

	/**
	 * Finds files by mask
	 * 
	 * @param mask
	 *            wildcard expression to find file
	 * @return list of found files
	 * @throws TraceViewException
	 */
	public List<File> getFilesByMask(String mask) throws TraceViewException {
		lst.clear();
		try {
			if (TraceView.osNameString.contains("Windows")) {
				String[] as = mask.replaceFirst(":\\\\\\\\", ":\\\\").split("\\\\");
				path = new ArrayList<String>(Arrays.asList(as));
				if (path.get(0).contains(":")) {
					// absolute path
					explore(path.get(0) + "\\\\", 0);
				} else if (path.get(0).equals(".") || path.get(0).equals("..") || path.get(0).equals("~")) {
					// relative path
					explore(path.get(0), 0);
				} else {
					// relative path without point
					path.add(0, ".");
					explore(path.get(0), 0);
				}
			} else {
				String[] as = mask.split(File.separator);
				path = new ArrayList<String>(Arrays.asList(as));
				if (path.get(0).equals(".") || path.get(0).equals("..") || path.get(0).equals("~")) {
					// relative path
					explore(path.get(0), 0);
				} else if (path.get(0).equals("")) {
					// absolute path
					explore("/", 0);
				} else {
					// relative path without point
					path.add(0, ".");
					explore(path.get(0), 0);
				}
			}
		} catch (Exception e) {
			throw new TraceViewException("Can't parse wildcarded path: '" + mask + "'");
		}
		return lst;
	}

	/**
	 * Recursive searching
	 * 
	 * @param String
	 *            partPath - currently exploring point of path
	 * @param String[]
	 *            path - full path divided by path separators into an array
	 * @param int
	 *            i - current depth
	 * @param List
	 *            <File> lst - list to store found files
	 */

	private void explore(String partPath, int i) {
		File f = new File(partPath);
		File[] dir = f.listFiles();
		if (dir.length > 0) {
			for (int j = 0; j < dir.length; j++) {
				if (path.size() > i + 1) {
					if (Wildcard.matches(path.get(i + 1), dir[j].getName())) {
						if (dir[j].isDirectory()) {
							explore(partPath + File.separator + dir[j].getName(), i + 1);
						} else {
							lst.add(dir[j]);
						}
					}
				} else {
					if (dir[j].isDirectory()) {
						explore(partPath + File.separator + dir[j].getName(), i + 1);
					} else {
						lst.add(dir[j]);
					}
				}
			}
		}
	}

	public static String wildcardToRegex(String wild) {
		if (wild == null)
			return null;

		StringBuffer buffer = new StringBuffer();
		char[] chars = wild.toCharArray();
		for (int i = 0; i < chars.length; ++i) {
			if (chars[i] == '*')
				buffer.append(".*");
			else if (chars[i] == '?')
				buffer.append(".");
			else if (chars[i] == '!')
				buffer.append("^");
			else if (chars[i] == '{')
				buffer.append("(");
			else if (chars[i] == '}')
				buffer.append(")");
			else if (chars[i] == ',')
				buffer.append("|");
			else if ("+()^$.|\\".indexOf(chars[i]) != -1)
				buffer.append('\\').append(chars[i]); // prefix all unused metacharacters with
			// backslash
			else
				buffer.append(chars[i]);
		}
		return buffer.toString();
	}
}
