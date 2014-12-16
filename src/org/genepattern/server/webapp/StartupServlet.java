/*
 The Broad Institute
 SOFTWARE COPYRIGHT NOTICE AGREEMENT
 This software and its documentation are copyright (2003-2011) by the
 Broad Institute/Massachusetts Institute of Technology. All rights are
 reserved.

 This software is supplied without any warranty or guaranteed support
 whatsoever. Neither the Broad Institute nor MIT can be responsible for its
 use, misuse, or functionality.
 */

package org.genepattern.server.webapp;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.InetAddress;
import java.net.UnknownHostException;
import java.security.Security;
import java.util.Enumeration;
import java.util.Iterator;
import java.util.Properties;
import java.util.TreeMap;
import java.util.Vector;

import javax.net.ssl.HttpsURLConnection;
import javax.servlet.ServletConfig;
import javax.servlet.ServletContext;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;

import org.apache.log4j.Logger;
import org.apache.log4j.PropertyConfigurator;
import org.genepattern.server.config.GpConfig;
import org.genepattern.server.config.GpContext;
import org.genepattern.server.config.ServerConfigurationFactory;
import org.genepattern.server.database.HibernateUtil;
import org.genepattern.server.database.HsqlDbUtil;
import org.genepattern.server.dm.userupload.MigrationTool;
import org.genepattern.server.executor.CommandManagerFactory;
import org.genepattern.server.message.SystemAlertFactory;
import org.genepattern.server.purger.PurgerFactory;
import org.genepattern.server.util.JobResultsFilenameFilter;
import org.genepattern.server.webapp.jsf.AboutBean;
import org.genepattern.webservice.TaskInfoCache;

import com.google.common.base.Strings;

/*
 * GenePattern startup servlet
 * 
 * This servlet performs periodic maintenance, based on definitions in the <genepattern.properties>/genepattern.properties file
 * @author Jim Lerner
 */
public class StartupServlet extends HttpServlet {

    private static Vector<Thread> vThreads = new Vector<Thread>();

    private Logger log=null;
    private Logger getLog() {
        if (this.log==null) {
            return Logger.getLogger(StartupServlet.class);
        }
        return this.log;
    }

    public StartupServlet() {
    }
    
    public String getServletInfo() {
        return "GenePatternStartupServlet";
    }

    // initialize this on ServletStartup
    protected String databaseVendor="HSQL";
    
    /**
     * Initialize the 'working directory' from which to resolve relative file paths.
     * In previous versions (GP <= 3.9.0) relative paths were resolved relative to the working directory
     * from which the GenePattern Server (or application server) is launched.
     *     <pre>System.getProperty("user.dir");</pre>
     * 
     * In GP >=3.9.1, the default location is computed from the webappDir.
     * E.g.
     *     webappDir=<GenePatternServer>/Tomcat/webapps/gp
     *     workingDir=<GenePatternServer>/Tomcat
     *     <pre></pre>
     *     
     * For debugging/developing, you can override the default settings with a system property, 
     *     -DGENEPATTERN_WORKING_DIR=/fully/qualified/path
     */
    protected File initWorkingDir(final ServletConfig servletConfig) {
        String gpWorkingDir=System.getProperty("GENEPATTERN_WORKING_DIR");
        if (gpWorkingDir==null) {
            gpWorkingDir=GpConfig.normalizePath(servletConfig.getServletContext().getRealPath("../../"));
        }
        return new File(gpWorkingDir);
    }
    
    /**
     * Initialize the path to the GENEPATTERN_HOME directory for the web application.
     * 
     * If it's set as a system property, then use that value.
     * If it's not already set as a system property then ...
     *     Check the config.initParmater
     * If it's not set as an initParameter then ...
     *     Assume it's relative to the web application directory.
     *     
     * @param config
     * @return
     */
    protected File initGpHomeDir(ServletConfig config) {
        String gpHome=System.getProperty("GENEPATTERN_HOME", System.getProperty("gp.home", null));
        return initGpHomeDir(gpHome, config);
    }

    protected File initGpHomeDir(final String gpHomeProp, final ServletConfig config) {
        String gpHome=gpHomeProp;
        
        if (Strings.isNullOrEmpty(gpHome)) {
            gpHome = config.getInitParameter("GENEPATTERN_HOME");
        }
        if (Strings.isNullOrEmpty(gpHome)) {
            gpHome = config.getInitParameter("gp.home");
        }
        
        if (Strings.isNullOrEmpty(gpHome)) {
            return null;
        }

//        if (Strings.isNullOrEmpty(gpHome)) {
//            //legacy, assume it's relative to the web application
//            gpHome=config.getServletContext().getRealPath("../../../");
//        }
//        
//        if (Strings.isNullOrEmpty(gpHome)) {
//            //ERROR: unexpected, default to working directory
//            gpHome=System.getProperty("user.dir");
//        }
        
        //normalize
        gpHome=GpConfig.normalizePath(gpHome);
        
        File gpHomeDir=new File(gpHome);
        if (gpHomeDir.isAbsolute()) {
            return gpHomeDir;
        }
        else { 
            // special-case: handle relative path
            System.err.println("GENEPATTERN_HOME='"+gpHome+"': Should not be a relative path");
            gpHomeDir=new File(config.getServletContext().getRealPath("../../../"), gpHome).getAbsoluteFile();
            System.err.println("Setting GENEPATTERN_HOME="+gpHomeDir);
            return new File(gpHome);
        } 
    }
    
    /**
     * Get the path to the 'resources' directory for the web application.
     * 
     * @param gpWorkingDir, the working director for the GenePattern Server.
     * @return
     */
    protected File initResourcesDir(final File gpWorkingDir) {
        File resourcesDir=new File(gpWorkingDir, "../resources");
        if (!resourcesDir.exists()) {
            // check for a path relative to working dir
            resourcesDir=new File("../resources");
        } 
        return new File(GpConfig.normalizePath(resourcesDir.getPath())).getAbsoluteFile();
    }
    
    protected void initLogDir(final File workingDir, final File resourcesDir) {
        // By default, logDir is '<gpWorkingDir>../logs'
        try {
            File logDir=new File(workingDir, "../logs");
            logDir=new File(GpConfig.normalizePath(logDir.getPath()));
            if (!logDir.exists()) {
                boolean success=logDir.mkdirs();
                if (success) {
                    System.out.println("Created log directory: "+logDir);
                }
            }
            System.setProperty("gp.log", logDir.getAbsolutePath());

            File log4jProps=new File(GpConfig.normalizePath(new File(resourcesDir, "log4j.properties").getPath()));
            PropertyConfigurator.configure(log4jProps.getAbsolutePath());
            this.log=Logger.getLogger(StartupServlet.class);
            ServerConfigurationFactory.setLogDir(logDir);
        }
        catch (Throwable t) {
            System.err.println("Error initializing logger");
            t.printStackTrace();
        }
    }
    
    public void init(ServletConfig servletConfig) throws ServletException {
        super.init(servletConfig);
        final File workingDir=initWorkingDir(servletConfig);
        ServerConfigurationFactory.setGpWorkingDir(workingDir);
        
        // must init resourcesDir ...
        File resourcesDir=initResourcesDir(workingDir);
        ServerConfigurationFactory.setResourcesDir(resourcesDir);
        // ...  before initializing logDir 
        initLogDir(workingDir, resourcesDir);

        // must initialize logger before calling any methods which output to the log
        announceStartup();

        getLog().info("\tinitializing properties...");
        ServletContext application = servletConfig.getServletContext();
        String genepatternProperties = servletConfig.getInitParameter("genepattern.properties");
        application.setAttribute("genepattern.properties", genepatternProperties);
        String customProperties = servletConfig.getInitParameter("custom.properties");
        if (customProperties == null) {
            customProperties = genepatternProperties;
        }
        application.setAttribute("custom.properties", customProperties);
        loadProperties(servletConfig, workingDir);
        setServerURLs(servletConfig);

        final GpConfig gpConfig=ServerConfigurationFactory.instance();
        GpContext gpContext=GpContext.getServerContext();
        String gpVersion=gpConfig.getGenePatternVersion();
        String schemaPrefix=null;
        
        Properties dbProperties=HibernateUtil.initDbProperties(gpConfig, gpContext);
        if (dbProperties != null) {
            this.databaseVendor=dbProperties.getProperty("database.vendor", "HSQL");
            // override default schemaPrefix in database properties file
            schemaPrefix=dbProperties.getProperty("schemaPrefix");
        }
        else {
            databaseVendor=gpConfig.getGPProperty(gpContext, "database.vendor", "HSQL");
        }
        
        if (databaseVendor.equals("HSQL")) {
            schemaPrefix="analysis_hypersonic";
            try {
                String[] hsqlArgs=HsqlDbUtil.initHsqlArgs(gpConfig, gpContext); 
                getLog().info("\tstarting HSQL database...");
                HsqlDbUtil.startDatabase(hsqlArgs);
            }
            catch (Throwable t) {
                getLog().error("Unable to start HSQL Database!", t);
                return;
            }
        }
        else {
            schemaPrefix="analysis_"+databaseVendor.toLowerCase();
        }
        
        getLog().info("\tchecking database connection...");
        try {
            HibernateUtil.beginTransaction();
        }
        catch (Throwable t) {
            getLog().debug("Error connecting to the database", t);
            Throwable cause = t.getCause();
            if (cause == null) {
                cause = t;
            }
            getLog().error("Error connecting to the database: " + cause);
            getLog().error("Error starting GenePatternServer, abandoning servlet init, throwing servlet exception.");
            throw new ServletException(t);
        }
        finally {
            HibernateUtil.closeCurrentSession();
        }

        try {
            getLog().info("\tinitializing database schema ...");
            HsqlDbUtil.updateSchema(resourcesDir, schemaPrefix, gpVersion);
        }
        catch (Throwable t) {
            getLog().error("Error initializing DB schema", t);
        }
        
        
        //load the configuration file
        try {
            getLog().info("\tinitializing ServerConfiguration...");
            String configFilepath = ServerConfigurationFactory.instance().getConfigFilepath();
        }
        catch (Throwable t) {
            getLog().error("error initializing ServerConfiguration", t);
        }
        
        //initialize the taskInfoCache
        try {
            getLog().info("\tinitializing TaskInfoCache...");
            TaskInfoCache.instance();
        }
        catch (Throwable t) {
            getLog().error("error initializing taskInfo cache", t);
        }
        
        CommandManagerFactory.startJobQueue();
        
        Security.addProvider(new com.sun.net.ssl.internal.ssl.Provider());
        HttpsURLConnection.setDefaultHostnameVerifier(new SessionHostnameVerifier());

        //clear system alert messages
        getLog().info("\tinitializing system messages...");
        try {
            SystemAlertFactory.getSystemAlert().deleteOnRestart();
        }
        catch (Exception e) {
            getLog().error("Error clearing system messages on restart: " + e.getLocalizedMessage(), e);
        }
        
        //attempt to migrate user upload files from GP 3.3.2 to GP 3.3.3
        try {
            getLog().info("\tinitializing user upload directories ...");
            MigrationTool.migrateUserUploads();
        }
        catch (Throwable t) {
            getLog().error("Error initializing user upload directories: " + t.getLocalizedMessage(), t);
        }
        
        //attempt to migrate job upload files from GP 3.3.2 (and earlier) to GP 3.3.3
        try {
            getLog().info("\tmigrating job upload directories ...");
            MigrationTool.migrateJobUploads();
        }
        catch (Throwable t) {
            getLog().error("Error migrating job upload directories: " + t.getLocalizedMessage(), t);
        }
        
        //start the JobPurger
        PurgerFactory.instance().start();

        announceReady();
    }
    
    /**
     * Set the GenePatternURL property dynamically using
     * the current canonical host name and servlet context path.
     * Dynamic lookup works for Tomcat but may not work on other containers... 
     * Define GP_Path (to be used as the 'servletContextPath') in the genepattern.properties file to avoid dynamic lookup.
     * 
     * @param config
     */
    private void setServerURLs(ServletConfig config) {
        //set the GP_Path property if it has not already been set
        String contextPath = System.getProperty("GP_Path");
        if (contextPath == null) {
            contextPath = "/gp";
        }
        else {
            if (!contextPath.startsWith("/")) {
                contextPath = "/" + contextPath;
            }
            if (contextPath.endsWith("/")) {
                contextPath = contextPath.substring(0, contextPath.length()-1);
            }
        }
        System.setProperty("GP_Path", "/gp");
        String genePatternURL = System.getProperty("GenePatternURL", "");
        if (genePatternURL == null || genePatternURL.trim().length() == 0) {
            try {
                getLog().error("Error, GenePatternURL not set, initializing from canonical host name ... ");
                InetAddress addr = InetAddress.getLocalHost();
                String host_address = addr.getCanonicalHostName();
                String portStr = System.getProperty("GENEPATTERN_PORT", "");
                portStr = portStr.trim();
                if (portStr.length()>0) {
                    portStr = ":"+portStr;
                }
                contextPath = System.getProperty("GP_Path", "/gp");
                String genePatternServerURL = "http://" + host_address + portStr + contextPath + "/";
                System.setProperty("GenePatternURL", genePatternServerURL);
                getLog().error("setting GenePatternURL to " + genePatternServerURL);
            } 
            catch (UnknownHostException e) {
                e.printStackTrace();
            }
        }
    }

    private void announceStartup() {
        final String NL = System.getProperty("line.separator");
        final String STARS = "****************************************************************************";
        StringBuffer startupMessage = new StringBuffer();
        startupMessage.append(NL + STARS + NL);
        startupMessage.append("Starting GenePatternServer ... ");
        getLog().info(startupMessage);
    }
 
    protected void announceReady() {
        AboutBean about = new AboutBean();
        String message = "GenePattern server version " + about.getFull() + 
            " build " + about.getBuildTag() + 
            " built " + about.getDate() + " is ready.";

        String defaultRootJobDir = "";
        GpContext serverContext = GpContext.getServerContext();

        try {
            File rootJobDir = ServerConfigurationFactory.instance().getRootJobDir(serverContext);
            defaultRootJobDir = rootJobDir.getAbsolutePath();
        }
        catch (Throwable t) {
            defaultRootJobDir = "Server configuration error: "+t.getLocalizedMessage();
        }

        GpConfig gpConfig=ServerConfigurationFactory.instance();
        String stars = "******************************************************************************************************************************************"
            .substring(0, message.length());
        StringBuffer startupMessage = new StringBuffer();
        final String NL = System.getProperty("line.separator");
        startupMessage.append(""+NL);
        startupMessage.append(stars + NL);
        startupMessage.append(message + NL);
        startupMessage.append("\tGenePatternURL: " + gpConfig.getGpUrl() + NL );
        startupMessage.append("\tJava Version: " + System.getProperty("java.version") + NL );
        startupMessage.append("\tuser.dir: " + System.getProperty("user.dir") + NL);
        startupMessage.append("\ttasklib: " + System.getProperty("tasklib") + NL);
        startupMessage.append("\tjobs: " + defaultRootJobDir + NL);
        startupMessage.append("\tjava.io.tmpdir: " + System.getProperty("java.io.tmpdir") + NL );
        startupMessage.append("\t" + GpConfig.PROP_GP_TMPDIR+": "+ gpConfig.getTempDir(serverContext).getAbsolutePath() + NL);
        startupMessage.append("\t" + GpConfig.PROP_SOAP_ATT_DIR+": "+ gpConfig.getSoapAttDir(serverContext) + NL);
        startupMessage.append("\tconfig.file: " + gpConfig.getConfigFilepath() + NL);
        startupMessage.append(stars);

        getLog().info(startupMessage);
    }

    public void destroy() {
        getLog().info("StartupServlet: destroy called");
        
        //stop the job purger
        PurgerFactory.instance().stop();

        try {
            getLog().info("stopping job queue ...");
            CommandManagerFactory.stopJobQueue();
            getLog().info("done!");
        }
        catch (Throwable t) {
            getLog().error("Error stopping job queue: " + t.getLocalizedMessage(), t);
        }

        if (this.databaseVendor.equals("HSQL")) {
            try {
                getLog().info("stopping HSQLDB ...");
                HsqlDbUtil.shutdownDatabase();
                getLog().info("done!");
            }
            catch (Throwable t) {
                getLog().error("Error stopoping HSQLDB: " + t.getLocalizedMessage(), t);
            }
        }

        for (Enumeration<Thread> eThreads = vThreads.elements(); eThreads.hasMoreElements();) {
            Thread t = eThreads.nextElement();
            try {
                if (t.isAlive()) {
                    getLog().info("Interrupting " + t.getName());
                    t.interrupt();
                    t.setPriority(Thread.NORM_PRIORITY);
                    t.join();
                    getLog().info(t.getName() + " exited");
                }
            } 
            catch (Throwable e) {
                getLog().error(e);
            }
        }
        vThreads.removeAllElements();

        getLog().info("StartupServlet: destroy, calling dumpThreads...");
        dumpThreads();
        getLog().info("StartupServlet: destroy done");
    }

    /**
     * Set System properties to the union of all settings in:
     * servlet init parameters
     * resources/genepattern.properties
     * resources/build.properties
     * 
     * @param config
     * @param workingDir, the root directory for resolving relative paths defined in the 'genepattern.properties' file
     * 
     * @throws ServletException
     */
    protected void loadProperties(final ServletConfig config, final File workingDir) throws ServletException {
        File propFile = null;
        File customPropFile = null;
        FileInputStream fis = null;
        FileInputStream customFis = null;
        try {
            for (Enumeration<String> eConfigProps = config.getInitParameterNames(); eConfigProps.hasMoreElements();) {
                String propName = eConfigProps.nextElement();
                String propValue = config.getInitParameter(propName);
                if (propValue.startsWith(".")) {
                    //propValue = new File(propValue).getCanonicalPath();
                    propValue = new File(workingDir, propValue).getAbsolutePath();
                    propValue=GpConfig.normalizePath(propValue);
                }
                System.setProperty(propName, propValue);
            }
            Properties sysProps = System.getProperties();
            String dir = sysProps.getProperty("genepattern.properties");
            propFile = new File(dir, "genepattern.properties");
            customPropFile = new File(dir, "custom.properties");
            Properties props = new Properties();
            fis = new FileInputStream(propFile);
            props.load(fis);
            getLog().info("\tloaded GP properties from " + propFile.getCanonicalPath());

            if (customPropFile.exists()) {
                customFis = new FileInputStream(customPropFile);
                props.load(customFis);
                getLog().info("\tloaded Custom GP properties from " + customPropFile.getCanonicalPath());
            }

            // copy all of the new properties to System properties
            for (Iterator<?> iter = props.keySet().iterator(); iter.hasNext();) {
                String key = (String) iter.next();
                String val = props.getProperty(key);
                if (val.startsWith(".")) {
                    //HACK: don't rewrite my value
                    if (! key.equals(JobResultsFilenameFilter.KEY)) {
                        val = new File(workingDir, val).getAbsolutePath();
                        val=GpConfig.normalizePath(val);
                    }
                }
                sysProps.setProperty(key, val);
            }

            propFile = new File(dir, "build.properties");
            fis = new FileInputStream(propFile);
            props.load(fis);
            fis.close();
            fis = null;
            // copy all of the new properties to System properties
            for (Iterator<?> iter = props.keySet().iterator(); iter.hasNext();) {
                String key = (String) iter.next();
                String val = props.getProperty(key);
                if (key.equals("HSQL.args")) {
                    //special-case for default path to the HSQL database
                    //   replace 'file:../resources/GenePatternDB' with 'file:<workingDir>/resources/GenePatternDB'
                    String dbPath=new File(workingDir,"../resources/GenePatternDB").getAbsolutePath();
                    dbPath=GpConfig.normalizePath(dbPath);
                    val = val.replace("file:../resources/GenePatternDB", "file:"+dbPath);
                }
                else if (val.startsWith(".")) {
                    //HACK: don't rewrite my value
                    if (! key.equals(JobResultsFilenameFilter.KEY)) {
                        val = new File(workingDir, val).getAbsolutePath();
                        val=GpConfig.normalizePath(val);
                    }
                }
                sysProps.setProperty(key, val);
            }

            System.setProperty("serverInfo", config.getServletContext().getServerInfo());
	    
            TreeMap tmProps = new TreeMap(sysProps);
            for (Iterator<?> iProps = tmProps.keySet().iterator(); iProps.hasNext();) {
                String propName = (String) iProps.next();
                String propValue = (String) tmProps.get(propName);
                getLog().debug(propName + "=" + propValue);
            }
        } 
        catch (IOException ioe) {
            ioe.printStackTrace();
            String path = null;
            try {
                path = propFile.getCanonicalPath();
            } 
            catch (IOException ioe2) {
            }
            throw new ServletException(path + " cannot be loaded.  " + ioe.getMessage());
        } 
        finally {
            try {
                if (fis != null)
                    fis.close();
            } 
            catch (IOException ioe) {
            }
        }
    }

    protected void dumpThreads() {
        getLog().info("StartupServlet.dumpThreads: what's still running...");
        ThreadGroup tg = Thread.currentThread().getThreadGroup();
        while (tg.getParent() != null) {
            tg = tg.getParent();
        }
        int MAX_THREADS = 100;
        Thread threads[] = new Thread[MAX_THREADS];
        int numThreads = tg.enumerate(threads, true);
        Thread t = null;
        for (int i = 0; i < numThreads; i++) {
            t = threads[i];
            if (t == null) {
                continue;
            } 
            if (!t.isAlive()) {
                continue;
            }
            getLog().info(t.getName() + " is running at " + t.getPriority() + " priority.  " + (t.isDaemon() ? "Is" : "Is not")
                    + " daemon.  " + (t.isInterrupted() ? "Is" : "Is not") + " interrupted.  ");

            //for debugging            
            //if (t.getName().startsWith("Thread-")) {
            //    getLog().info("what is this thread?");
            //    t.dumpStack();
            //    
            //    for(StackTraceElement e : t.getStackTrace()) {
            //        String m = ""+e.getClassName()+"."+e.getMethodName();
            //        String f = ""+e.getFileName()+":"+ e.getLineNumber();
            //        getLog().info(""+m+", "+f);
            //    }
            //
            //    getLog().info("calling Thread.stop()...");
            //    t.stop();
            //}
            
        }
        if (numThreads == MAX_THREADS) {
            getLog().info("Possibly more than " + MAX_THREADS + " are running.");
        }
    }
}

class LaunchThread extends Thread {
    Method mainMethod;
    String[] args;
    StartupServlet parent;

    private static Logger getLog()
    {
        return Logger.getLogger(LaunchThread.class);
    }

    public LaunchThread(String taskName, Method mainMethod, String[] args, StartupServlet parent) {
        super(taskName);
        this.mainMethod = mainMethod;
        this.args = args;
        this.parent = parent;
        this.setDaemon(true);
    }

    public void run() {
        try {
            getLog().debug("invoking " + mainMethod.getDeclaringClass().getName() + "." + mainMethod.getName());
            mainMethod.invoke(null, new Object[] { args });
            parent.log(getName() + " " + mainMethod.getDeclaringClass().getName() + "." + mainMethod.getName()
                    + " returned from execution");
        } 
        catch (IllegalAccessException iae) {
            getLog().error("Can't invoke main in " + getName(), iae);
        } 
        catch (IllegalArgumentException iae2) {
            getLog().error("Bad args for " + getName(), iae2);
        } 
        catch (InvocationTargetException ite) {
            getLog().error("InvocationTargetException for " + getName(), ite.getTargetException());
        } 
        catch (Exception e) {
            getLog().error("Exception for " + getName(), e);
        }
    }
}
