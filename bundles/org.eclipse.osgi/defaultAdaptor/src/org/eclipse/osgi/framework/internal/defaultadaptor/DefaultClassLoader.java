/*******************************************************************************
 * Copyright (c) 2004 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials 
 * are made available under the terms of the Common Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/cpl-v10.html
 * 
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/

package org.eclipse.osgi.framework.internal.defaultadaptor;

import java.io.*;
import java.net.URL;
import java.security.ProtectionDomain;
import java.util.*;

import org.eclipse.osgi.framework.adaptor.ClassLoaderDelegate;
import org.eclipse.osgi.framework.adaptor.core.AbstractBundleData;
import org.eclipse.osgi.framework.adaptor.core.BundleEntry;
import org.eclipse.osgi.framework.adaptor.core.BundleFile;
import org.eclipse.osgi.framework.debug.Debug;
import org.eclipse.osgi.framework.internal.core.Msg;
import org.eclipse.osgi.framework.util.SecureAction;
import org.osgi.framework.BundleException;
import org.osgi.framework.FrameworkEvent;

/**
 * A concrete implementation of BundleClassLoader.  This implementation 
 * consolidates all Bundle-ClassPath entries into a single ClassLoader.
 */
public class DefaultClassLoader extends org.eclipse.osgi.framework.adaptor.BundleClassLoader {
	/** Development ClassPath entries */
	static protected String[] devCP;

	static {
		// Check the osgi.dev property to see if dev classpath entries have been defined.
		String osgiDev = SecureAction.getProperty("osgi.dev");
		if (osgiDev != null) {
			// Add each dev classpath entry
			Vector devClassPath = new Vector(6);
			StringTokenizer st = new StringTokenizer(osgiDev, ",");
			while (st.hasMoreTokens()) {
				String tok = st.nextToken();
				if (!tok.equals("")) {
					devClassPath.addElement(tok);
				}
			}
			devCP = new String[devClassPath.size()];
			devClassPath.toArray(devCP);
		}
	}


	/**
	 * The BundleData object for this BundleClassLoader
	 */
	protected AbstractBundleData hostdata;

	/**
	 * The ClasspathEntries for this BundleClassLoader.  Each ClasspathEntry object
	 * represents on Bundle-ClassPath entry.
	 */
	protected ClasspathEntry[] classpathEntries;

	protected Vector fragClasspaths;

	/**
	 * The buffer size to use when loading classes.  This value is used 
	 * only if we cannot determine the size of the class we are loading.
	 */
	protected int buffersize = 8 * 1024;

	/**
	 * BundleClassLoader constructor.
	 * @param delegate The ClassLoaderDelegate for this ClassLoader.
	 * @param domain The ProtectionDomain for this ClassLoader.
	 * @param bundleclasspath An array of Bundle-ClassPath entries to
	 * use for loading classes and resources.  This is specified by the 
	 * Bundle-ClassPath manifest entry.
	 * @param bundledata The BundleData for this ClassLoader
	 */
	public DefaultClassLoader(ClassLoaderDelegate delegate, ProtectionDomain domain, String[] classpath, AbstractBundleData bundledata) {
		this(delegate, domain, classpath, null, bundledata);
	}

	/**
	 * BundleClassLoader constructor.
	 * @param delegate The ClassLoaderDelegate for this ClassLoader.
	 * @param domain The ProtectionDomain for this ClassLoader.
	 * @param bundleclasspath An array of Bundle-ClassPath entries to
	 * use for loading classes and resources.  This is specified by the 
	 * Bundle-ClassPath manifest entry.
	 * @param parent The parent ClassLoader.
	 * @param bundledata The BundleData for this ClassLoader
	 */
	public DefaultClassLoader(ClassLoaderDelegate delegate, ProtectionDomain domain, String[] classpath, ClassLoader parent, AbstractBundleData bundledata) {
		super(delegate, domain, classpath, parent);
		this.hostdata = bundledata;

		try {
			hostdata.open(); /* make sure the BundleData is open */
		} catch (IOException e) {
			hostdata.getAdaptor().getEventPublisher().publishFrameworkEvent(FrameworkEvent.ERROR, hostdata.getBundle(), e);
		}
	}

	public void initialize() {
		classpathEntries = buildClasspath(hostclasspath, hostdata, hostdomain);
	}

	/**
	 * Attaches the BundleData for a fragment to this BundleClassLoader.
	 * The Fragment BundleData resources must be appended to the end of
	 * this BundleClassLoader's classpath.  Fragment BundleData resources 
	 * must be searched ordered by Bundle ID's.  
	 * @param bundledata The BundleData of the fragment.
	 * @param domain The ProtectionDomain of the resources of the fragment.
	 * Any classes loaded from the fragment's BundleData must belong to this
	 * ProtectionDomain.
	 * @param classpath An array of Bundle-ClassPath entries to
	 * use for loading classes and resources.  This is specified by the 
	 * Bundle-ClassPath manifest entry of the fragment.
	 */
	public void attachFragment(org.eclipse.osgi.framework.adaptor.BundleData bundledata, ProtectionDomain domain, String[] classpath) {
		AbstractBundleData abstractbundledata = (AbstractBundleData) bundledata;
		try {
			bundledata.open(); /* make sure the BundleData is open */
		} catch (IOException e) {

			abstractbundledata.getAdaptor().getEventPublisher().publishFrameworkEvent(FrameworkEvent.ERROR, abstractbundledata.getBundle(), e);
		}
		ClasspathEntry[] fragEntries = buildClasspath(classpath, abstractbundledata, domain);
		FragmentClasspath fragClasspath = new FragmentClasspath(fragEntries, abstractbundledata, domain);
		insertFragment(fragClasspath);
	}

	/**
	 * Inserts a fragment classpath to into the list of fragments for this host.
	 * Fragments are inserted into the list according to the fragment's 
	 * Bundle ID.
	 * @param fragClasspath The FragmentClasspath to insert.
	 */
	protected synchronized void insertFragment(FragmentClasspath fragClasspath) {
		if (fragClasspaths == null) {
			// First fragment to attach.  Simply create the list and add the fragment.
			fragClasspaths = new Vector(10);
			fragClasspaths.addElement(fragClasspath);
			return;
		}

		// Find a place in the fragment list to insert this fragment.
		int size = fragClasspaths.size();
		long fragID = fragClasspath.bundledata.getBundleID();
		for (int i = 0; i < size; i++) {
			long otherID = ((FragmentClasspath) fragClasspaths.elementAt(i)).bundledata.getBundleID();
			if (fragID < otherID) {
				fragClasspaths.insertElementAt(fragClasspath, i);
				return;
			}
		}
		// This fragment has the highest ID; put it at the end of the list.
		fragClasspaths.addElement(fragClasspath);
	}

	/**
	 * Gets a ClasspathEntry object for the specified ClassPath entry.
	 * @param cp The ClassPath entry to get the ClasspathEntry for.
	 * @param bundledata The BundleData that the ClassPath entry is for.
	 * @param domain The ProtectionDomain for the ClassPath entry.
	 * @return The ClasspathEntry object for the ClassPath entry.
	 */
	protected ClasspathEntry getClasspath(String cp, AbstractBundleData bundledata, ProtectionDomain domain) {
		BundleFile bundlefile = null;
		File file = bundledata.getBaseBundleFile().getFile(cp);
		if (file != null && file.exists()) {
			try {
				bundlefile = BundleFile.createBundleFile(file, bundledata);
			} catch (IOException e) {
				bundledata.getAdaptor().getEventPublisher().publishFrameworkEvent(FrameworkEvent.ERROR, bundledata.getBundle(), e);
			}
		} else {
			if (bundledata.getBaseBundleFile() instanceof BundleFile.ZipBundleFile) {
				// the classpath entry may be a directory in the bundle jar file.
				if (bundledata.getBaseBundleFile().containsDir(cp))
					bundlefile = BundleFile.createBundleFile((BundleFile.ZipBundleFile) bundledata.getBaseBundleFile(), cp);
			}
		}
		if (bundlefile != null)
			return new ClasspathEntry(bundlefile, domain);
		else
			return null;
	}

	protected synchronized Class findClass(String name) throws ClassNotFoundException {
		Class result = findLoadedClass(name);
		if (result != null)
			return result;
		for (int i = 0; i < classpathEntries.length; i++) {
			if (classpathEntries[i] != null) {
				result = findClassImpl(name, classpathEntries[i].bundlefile, classpathEntries[i].domain);
				if (result != null) {
					return result;
				}
			}
		}
		// look in fragments.
		if (fragClasspaths != null) {
			int size = fragClasspaths.size();
			for (int i = 0; i < size; i++) {
				FragmentClasspath fragCP = (FragmentClasspath) fragClasspaths.elementAt(i);
				for (int j = 0; j < fragCP.classpathEntries.length; j++) {
					result = findClassImpl(name, fragCP.classpathEntries[j].bundlefile, fragCP.classpathEntries[j].domain);
					if (result != null) {
						return result;
					}
				}
			}
		}
		throw new ClassNotFoundException(name);
	}

	/**
	 * Finds a class in the BundleFile.  If a class is found then the class
	 * is defined using the ProtectionDomain bundledomain.
	 * @param name The name of the class to find.
	 * @param bundleFile The BundleFile to find the class in.
	 * @param bundledomain The ProtectionDomain to use to defind the class if
	 * it is found.
	 * @return The loaded class object or null if the class is not found.
	 */
	protected Class findClassImpl(String name, BundleFile bundlefile, ProtectionDomain bundledomain) {
		if (Debug.DEBUG && Debug.DEBUG_LOADER) {
			Debug.println("BundleClassLoader[" + hostdata + "].findClass(" + name + ")");
		}

		String filename = name.replace('.', '/').concat(".class");

		BundleEntry entry = bundlefile.getEntry(filename);

		if (entry == null) {
			return null;
		}

		InputStream in;
		try {
			in = entry.getInputStream();
		} catch (IOException e) {
			return null;
		}

		int length = (int) entry.getSize();
		byte[] classbytes;
		int bytesread = 0;
		int readcount;

		if (Debug.DEBUG && Debug.DEBUG_LOADER) {
			Debug.println("  about to read " + length + " bytes from " + filename);
		}

		try {
			try {
				if (length > 0) {
					classbytes = new byte[length];

					readloop : for (; bytesread < length; bytesread += readcount) {
						readcount = in.read(classbytes, bytesread, length - bytesread);

						if (readcount <= 0) /* if we didn't read anything */ {
							break readloop; /* leave the loop */
						}
					}
				} else /* BundleEntry does not know its own length! */ {
					length = buffersize;
					classbytes = new byte[length];

					readloop : while (true) {
						for (; bytesread < length; bytesread += readcount) {
							readcount = in.read(classbytes, bytesread, length - bytesread);

							if (readcount <= 0) /* if we didn't read anything */ {
								break readloop; /* leave the loop */
							}
						}

						byte[] oldbytes = classbytes;
						length += buffersize;
						classbytes = new byte[length];
						System.arraycopy(oldbytes, 0, classbytes, 0, bytesread);
					}
				}
			} catch (IOException e) {
				if (Debug.DEBUG && Debug.DEBUG_LOADER) {
					Debug.println("  IOException reading " + filename + " from " + hostdata);
				}

				return null;
			}
		} finally {
			try {
				in.close();
			} catch (IOException ee) {
			}
		}

		if (Debug.DEBUG && Debug.DEBUG_LOADER) {
			Debug.println("  read " + bytesread + " bytes from " + filename);
			Debug.println("  defining class " + name);
		}

		try {
			return (defineClass(name, classbytes, 0, bytesread, bundledomain, bundlefile));
		} catch (Error e) {
			if (Debug.DEBUG && Debug.DEBUG_LOADER) {
				Debug.println("  error defining class " + name);
			}

			throw e;
		}
	}

	protected Class defineClass(String name, byte[] classbytes, int off, int len, ProtectionDomain bundledomain, BundleFile bundlefile) throws ClassFormatError {
		return defineClass(name,classbytes,off,len,bundledomain);
	}

	/** 
	 * @see org.eclipse.osgi.framework.adaptor.BundleClassLoader#findResource(String)
	 */
	protected URL findResource(String name) {
		URL result = null;
		for (int i = 0; i < classpathEntries.length; i++) {
			if (classpathEntries[i] != null) {
				result = findResourceImpl(name, classpathEntries[i].bundlefile);
				if (result != null) {
					return result;
				}
			}
		}
		// look in fragments
		if (fragClasspaths != null) {
			int size = fragClasspaths.size();
			for (int i = 0; i < size; i++) {
				FragmentClasspath fragCP = (FragmentClasspath) fragClasspaths.elementAt(i);
				for (int j = 0; j < fragCP.classpathEntries.length; j++) {
					result = findResourceImpl(name, fragCP.classpathEntries[j].bundlefile);
					if (result != null) {
						return result;
					}
				}
			}
		}
		return null;
	}

	/**
	 * Looks in the specified BundleFile for the resource.
	 * @param name The name of the resource to find,.
	 * @param bundlefile The BundleFile to look in.
	 * @return A URL to the resource or null if the resource does not exist.
	 */
	protected URL findResourceImpl(String name, BundleFile bundlefile) {
		return bundlefile.getResourceURL(name);
	}

	/**
	 * @see org.eclipse.osgi.framework.adaptor.BundleClassLoader#findLocalResources(String)
	 */
	public Enumeration findLocalResources(String resource) {
		Vector resources = new Vector(6);
		for (int i = 0; i < classpathEntries.length; i++) {
			if (classpathEntries[i] != null) {
				URL url = findResourceImpl(resource, classpathEntries[i].bundlefile);
				if (url != null) {
					resources.addElement(url);
				}
			}
		}
		// look in fragments
		if (fragClasspaths != null) {
			int size = fragClasspaths.size();
			for (int i = 0; i < size; i++) {
				FragmentClasspath fragCP = (FragmentClasspath) fragClasspaths.elementAt(i);
				for (int j = 0; j < fragCP.classpathEntries.length; j++) {
					URL url = findResourceImpl(resource, fragCP.classpathEntries[j].bundlefile);
					if (url != null) {
						resources.addElement(url);
					}
				}
			}
		}
		if (resources.size() > 0) {
			return resources.elements();
		}
		return null;
	}

	public Object findLocalObject(String object) {
		BundleEntry result = null;
		for (int i = 0; i < classpathEntries.length; i++) {
			if (classpathEntries[i] != null) {
				result = findObjectImpl(object, classpathEntries[i].bundlefile);
				if (result != null) {
					return result;
				}
			}
		}
		// look in fragments
		if (fragClasspaths != null) {
			int size = fragClasspaths.size();
			for (int i = 0; i < size; i++) {
				FragmentClasspath fragCP = (FragmentClasspath) fragClasspaths.elementAt(i);
				for (int j = 0; j < fragCP.classpathEntries.length; j++) {
					result = findObjectImpl(object, fragCP.classpathEntries[j].bundlefile);
					if (result != null) {
						return result;
					}
				}
			}
		}
		return null;
	}

	protected BundleEntry findObjectImpl(String object,BundleFile bundleFile){
		return bundleFile.getEntry(object);
	}

	/**
	 * Closes all the BundleFile objects for this BundleClassLoader.
	 */
	public void close() {
		if (!closed) {
			super.close();
			if (classpathEntries != null) {
				for (int i = 0; i < classpathEntries.length; i++) {
					if (classpathEntries[i] != null) {
						try {
							if (classpathEntries[i].bundlefile != hostdata.getBaseBundleFile()) {
								classpathEntries[i].bundlefile.close();
							}
						} catch (IOException e) {
							hostdata.getAdaptor().getEventPublisher().publishFrameworkEvent(FrameworkEvent.ERROR, hostdata.getBundle(), e);
						}
					}
				}
			}
			if (fragClasspaths != null) {
				int size = fragClasspaths.size();
				for (int i = 0; i < size; i++) {
					FragmentClasspath fragCP = (FragmentClasspath) fragClasspaths.elementAt(i);
					fragCP.close();
				}
			}
		}
	}

	protected ClasspathEntry[] buildClasspath(String[] classpath, AbstractBundleData bundledata, ProtectionDomain domain) {
		ArrayList result = new ArrayList(10);

		// If not in dev mode then just add the regular classpath entries and return
		if (devCP == null) {
			for (int i = 0; i < classpath.length; i++)
				findClassPathEntry(result, classpath[i], bundledata, domain);
			return (ClasspathEntry[]) result.toArray(new ClasspathEntry[result.size()]);
		}

		// Otherwise, add the legacy entries for backwards compatibility and
		// then for each classpath entry add the dev entries as spec'd in the 
		// corresponding properties file.  If none are spec'd, add the 
		// classpath entry itself
		addDefaultDevEntries(result, bundledata, domain);
		for (int i = 0; i < classpath.length; i++) {
			String[] devEntries = getDevEntries(classpath[i], bundledata);
			if (devEntries != null && devEntries.length > 0) {
				for (int j = 0; j < devEntries.length; j++)
					findClassPathEntry(result, devEntries[j], bundledata, domain);
			} else
				findClassPathEntry(result, classpath[i], bundledata, domain);
		}
		return (ClasspathEntry[]) result.toArray(new ClasspathEntry[result.size()]);
	}

	protected void addDefaultDevEntries(ArrayList result, AbstractBundleData bundledata, ProtectionDomain domain) {
		if (devCP == null)
			return;
		if (devCP != null)
			for (int i = 0; i < devCP.length; i++)
				findClassPathEntry(result, devCP[i], bundledata, domain);
	}

	protected void findClassPathEntry(ArrayList result, String entry, AbstractBundleData bundledata, ProtectionDomain domain) {
		if (!addClassPathEntry(result, entry, bundledata, domain)) {
//			if (devCP == null) {
//				BundleException be = new BundleException(Msg.formatter.getString("BUNDLE_CLASSPATH_ENTRY_NOT_FOUND_EXCEPTION", entry, hostdata.getLocation()));
//				bundledata.getAdaptor().getEventPublisher().publishFrameworkEvent(FrameworkEvent.ERROR, bundledata.getBundle(), be);
//			}
		}
	}

	protected boolean addClassPathEntry(ArrayList result, String entry, AbstractBundleData bundledata, ProtectionDomain domain) {
		if (entry.equals(".")) {
			result.add(new ClasspathEntry(bundledata.getBaseBundleFile(), domain));
			return true;
		} else {
			Object element = getClasspath(entry, bundledata, domain);
			if (element != null) {
				result.add(element);
				return true;
			} else {
				// need to check in fragments for the classpath entry.
				// only check for fragments if the bundledata is the hostdata.
				if (fragClasspaths != null && hostdata == bundledata) {
					int size = fragClasspaths.size();
					for (int i = 0; i < size; i++) {
						FragmentClasspath fragCP = (FragmentClasspath) fragClasspaths.elementAt(i);
						element = getClasspath(entry, fragCP.bundledata, fragCP.domain);
						if (element != null) {
							result.add(element);
							return true;
						}
					}
				}
			}
		}
		return false;
	}

	protected String[] getDevEntries(String classpathEntry, AbstractBundleData bundledata) {
		Properties devProps = null;
		File propLocation = bundledata.getBaseBundleFile().getFile(classpathEntry + ".properties");
		if (propLocation == null)
			return null;
		try {
			InputStream in = new FileInputStream(propLocation);
			try {
				devProps = new Properties();
				devProps.load(in);
				return getArrayFromList(devProps.getProperty("bin"));
			} finally {
				in.close();
			}
		} catch (IOException e) {
			// TODO log the failures but ignore and try to keep going
		}
		return null;
	}

	/**
	 * Returns the result of converting a list of comma-separated tokens into an array
	 * 
	 * @return the array of string tokens
	 * @param prop the initial comma-separated string
	 */
	protected String[] getArrayFromList(String prop) {
		if (prop == null || prop.trim().equals("")) //$NON-NLS-1$
			return new String[0];
		Vector list = new Vector();
		StringTokenizer tokens = new StringTokenizer(prop, ","); //$NON-NLS-1$
		while (tokens.hasMoreTokens()) {
			String token = tokens.nextToken().trim();
			if (!token.equals("")) //$NON-NLS-1$
				list.addElement(token);
		}
		return list.isEmpty() ? new String[0] : (String[]) list.toArray(new String[list.size()]);
	}

	/**
	 * A data structure to hold information about a fragment classpath.
	 */
	protected class FragmentClasspath {
		/** The ClasspathEntries of the fragments Bundle-Classpath */
		protected ClasspathEntry[] classpathEntries;
		/** The BundleData of the fragment */
		protected AbstractBundleData bundledata;
		/** The ProtectionDomain of the fragment */
		protected ProtectionDomain domain;

		protected FragmentClasspath(ClasspathEntry[] classpathEntries, AbstractBundleData bundledata, ProtectionDomain domain) {
			this.classpathEntries = classpathEntries;
			this.bundledata = bundledata;
			this.domain = domain;
		}

		protected void close() {
			for (int i = 0; i < classpathEntries.length; i++) {
				try {
					if (classpathEntries[i].bundlefile != bundledata.getBaseBundleFile()) {
						classpathEntries[i].bundlefile.close();
					}
				} catch (IOException e) {
					bundledata.getAdaptor().getEventPublisher().publishFrameworkEvent(FrameworkEvent.ERROR, bundledata.getBundle(), e);
				}
			}
		}
	}

	protected class ClasspathEntry {
		protected BundleFile bundlefile;
		protected ProtectionDomain domain;
		protected ClasspathEntry(BundleFile bundlefile, ProtectionDomain domain) {
			this.bundlefile = bundlefile;
			this.domain = domain;
		}
	}
}
