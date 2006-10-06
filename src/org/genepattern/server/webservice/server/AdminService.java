/*
  The Broad Institute
  SOFTWARE COPYRIGHT NOTICE AGREEMENT
  This software and its documentation are copyright (2003-2006) by the
  Broad Institute/Massachusetts Institute of Technology. All rights are
  reserved.

  This software is supplied without any warranty or guaranteed support
  whatsoever. Neither the Broad Institute nor MIT can be responsible for its
  use, misuse, or functionality.
*/


package org.genepattern.server.webservice.server;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.util.Calendar;
import java.util.HashMap;
import java.util.Map;
import java.util.Properties;
import java.util.Vector;

import javax.activation.DataHandler;
import javax.activation.FileDataSource;

import org.apache.axis.MessageContext;
import org.genepattern.server.webservice.server.dao.*;
import org.genepattern.util.GPConstants;
import org.genepattern.util.LSID;
import org.genepattern.webservice.*;

/**
 * AdminService Web Service. Do a Thread.yield at beginning of each method-
 * fixes BUG in which responses from AxisServlet are sometimes empty
 * 
 * @author Joshua Gould
 */

public class AdminService implements IAdminService {
	static Map serviceInfoMap;

	AdminDAO dataService;

	public AdminService() {
        dataService = new AdminDAO();
	}

	protected String getUserName() {
		MessageContext context = MessageContext.getCurrentContext();
		String username = context.getUsername();
		if (username == null) {
			username = "";
		}
		return username;
	}

	public Map getServiceInfo() throws WebServiceException {
		Thread.yield();
		return serviceInfoMap;
	}

	public DataHandler getServerLog() throws WebServiceException {
		Thread.yield();
		return getLog(false);
	}

	public DataHandler getGenePatternLog() throws WebServiceException {
		Thread.yield();
		return getLog(true);
	}

	private DataHandler getLog(boolean doGP) throws WebServiceException {
		Calendar cal = Calendar.getInstance();
		java.text.SimpleDateFormat df = new java.text.SimpleDateFormat(
				"yyyy-MM-dd");

		String what = doGP ? "GenePattern" : "Tomcat";
		String filename;
		File f = null;

		// if the date has rolled over but there is not yet an entry in today's
		// log, look backward in time
		for (int i = 0; i < 10; i++) {
			filename = (doGP ? "genepattern.log" : ("localhost_log."
					+ df.format(cal.getTime()) + ".txt"));
			f = new File("logs", filename);
			if (doGP) {
				break;
			}
			if (f.exists()) {
				break;
			}
			cal.add(Calendar.DATE, -1); // backup up one day
		}

		if (!f.exists()) {
			throw new WebServiceException("Log not found.");
		}
		return new DataHandler(new FileDataSource(f));
	}

	public TaskInfo[] getAllTasks() throws WebServiceException {
		Thread.yield();
		try {
			return dataService.getAllTasksForUser(getUserName());
		} catch (OmnigeneException e) {
			throw new WebServiceException(e);
		}
	}

	public TaskInfo getTask(String lsidOrTaskName) throws WebServiceException {
		Thread.yield();
		try {
			return dataService.getTask(lsidOrTaskName, getUserName());
		} catch (OmnigeneException e) {
			throw new WebServiceException(e);
		}
	}

	public TaskInfo[] getLatestTasks() throws WebServiceException {
		Thread.yield();
		try {
			return dataService.getLatestTasks(getUserName());
		} catch (Exception e) {
			throw new WebServiceException(e);
		}
	}
	
	public TaskInfo[] getLatestTasksByName() throws WebServiceException {
		Thread.yield();
		try {
			return dataService.getLatestTasksByName(getUserName());
		} catch (AdminDAOSysException e) {
			throw new WebServiceException(e);
		}
	}

	public Map getSuiteLsidToVersionsMap() throws WebServiceException {
		Map suiteLsid2VersionsMap = new HashMap();
		SuiteInfo[] allSuites;
		try {
			allSuites = dataService.getAllSuites();
		} catch (AdminDAOSysException e1) {
			throw new WebServiceException(e1);
		}

		for (int i = 0, length = allSuites.length; i < length; i++) {
			SuiteInfo suite = allSuites[i];
			String suiteLsid = suite.getLsid();
			if (suiteLsid != null) {
				try {
					LSID lsid = new LSID(suiteLsid);
					String lsidNoVersion = lsid.toStringNoVersion();
					Vector versions = (Vector) suiteLsid2VersionsMap
							.get(lsidNoVersion);
					if (versions == null) {
						versions = new Vector();
						suiteLsid2VersionsMap.put(lsidNoVersion, versions);
					}
					versions.add(lsid.getVersion());
				} catch (java.net.MalformedURLException e) {
				}
			}
		}
		return suiteLsid2VersionsMap;
	}
	
	public Map getLSIDToVersionsMap() throws WebServiceException {
		Thread.yield();
		Map lsid2VersionsMap = new HashMap();
		TaskInfo[] tasks = null;
		try {
			tasks = dataService.getAllTasksForUser(getUserName());
		} catch (OmnigeneException e) {
			throw new WebServiceException(e);
		}
		for (int i = 0, length = tasks.length; i < length; i++) {
			TaskInfo task = tasks[i];
			String _taskLSID = (String) task.getTaskInfoAttributes().get(
					GPConstants.LSID);
			if (_taskLSID != null) {
				try {
					LSID lsid = new LSID(_taskLSID);
					String lsidNoVersion = lsid.toStringNoVersion();
					Vector versions = (Vector) lsid2VersionsMap
							.get(lsidNoVersion);
					if (versions == null) {
						versions = new Vector();
						lsid2VersionsMap.put(lsidNoVersion, versions);
					}
					versions.add(lsid.getVersion());
				} catch (java.net.MalformedURLException e) {
				}
			}
		}
		return lsid2VersionsMap;
	}

	public SuiteInfo getSuite(String lsid) throws WebServiceException {
		try {	
			return dataService.getSuite(lsid);
		} catch (OmnigeneException e) {
			throw new WebServiceException(e);
		}
	}

	/**
	 * Gets the latest versions of all suites
	 * 
	 * @return The latest suites
	 * @exception WebServiceException
	 *                If an error occurs
	 */
	public SuiteInfo[] getLatestSuites() throws WebServiceException {
		try {
			return dataService.getLatestSuites(getUserName());
		} catch (AdminDAOSysException e) {
			throw new WebServiceException(e);
		}

	}

	/**
	 * Gets all versions of all suites
	 * 
	 * @return The suites
	 * @exception WebServiceException
	 *                If an error occurs
	 */
	public SuiteInfo[] getAllSuites() throws WebServiceException{
		try {
			return dataService.getAllSuites(getUserName());
		} catch (AdminDAOSysException e) {
			throw new WebServiceException(e);
		}

	}

	/**
	 * Gets all suites this task is a part of
	 * 
	 * @return The suites
	 * @exception WebServiceException
	 *                If an error occurs
	 */
	public SuiteInfo[] getSuiteMembership(String taskLsid) throws WebServiceException{
		try {
			return  dataService.getSuiteMembership(taskLsid);
		} catch (OmnigeneException e) {
			throw new WebServiceException(e);
		}
	}


	static {
		serviceInfoMap = new HashMap();
		String gpPropsFilename = System.getProperty("genepattern.properties");
		File gpProps = new File(gpPropsFilename, "genepattern.properties");
		if (gpProps.exists()) {
			FileInputStream fis = null;
			Properties props = new Properties();
			try {
				fis = new FileInputStream(gpProps);
				props.load(fis);
			} catch (IOException ioe) {
				ioe.printStackTrace();
			} finally {
				try {
					if (fis != null) {
						fis.close();
					}
				} catch (IOException ioe) {
				}
			}
			serviceInfoMap.put("genepattern.version", props
					.getProperty("GenePatternVersion"));
			serviceInfoMap.put("lsid.authority", props
					.getProperty("lsid.authority"));
		}
	}

}