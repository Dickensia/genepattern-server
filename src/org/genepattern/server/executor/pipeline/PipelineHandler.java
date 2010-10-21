package org.genepattern.server.executor.pipeline;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.StringTokenizer;
import java.util.Vector;

import org.apache.log4j.Logger;
import org.genepattern.data.pipeline.JobSubmission;
import org.genepattern.data.pipeline.PipelineModel;
import org.genepattern.server.JobManager;
import org.genepattern.server.database.HibernateUtil;
import org.genepattern.server.domain.AnalysisJob;
import org.genepattern.server.domain.JobStatus;
import org.genepattern.server.executor.AnalysisJobScheduler;
import org.genepattern.server.executor.JobSubmissionException;
import org.genepattern.server.executor.JobTerminationException;
import org.genepattern.server.executor.pipeline.PipelineUtil.PipelineModelException;
import org.genepattern.server.executor.pipeline.RunPipelineAsynchronously.MissingTasksException;
import org.genepattern.server.genepattern.GenePatternAnalysisTask;
import org.genepattern.server.webservice.server.AdminService;
import org.genepattern.server.webservice.server.dao.AnalysisDAO;
import org.genepattern.util.GPConstants;
import org.genepattern.webservice.JobInfo;
import org.genepattern.webservice.ParameterInfo;
import org.genepattern.webservice.TaskInfo;
import org.genepattern.webservice.WebServiceException;
import org.hibernate.Query;

/**
 * Refactor code from GP 3.2.3 PipelineExecutor. This uses almost the same code base, but makes some changes
 * so that pipelines can be executed asynchronously rather than within a single thread.
 * 
 * TODO: add support for stopAfterTask
 * TODO: handle exceptions
 *     a) server error in startPipeline
 *     b) server error in prepareNextStep
 * 
 * @author pcarr
 */
public class PipelineHandler {
    private static Logger log = Logger.getLogger(PipelineHandler.class);
    
    /**
     * Initialize the pipeline and add the first job to the queue.
     */
    public static void startPipeline(String[] commandLine, JobInfo pipelineJobInfo, int stopAfterTask) throws Exception {
        boolean success = false;
        Integer firstStep = null;
        try {
            Map additionalArgs = getAdditionalArgs(commandLine);
            firstStep = runPipeline(pipelineJobInfo, stopAfterTask, additionalArgs);
            HibernateUtil.commitTransaction();
            success = true;
        }
        catch (Throwable t) {
            HibernateUtil.rollbackTransaction();
            throw new Exception("Server error starting pipeline, for job #"+pipelineJobInfo.getJobNumber(), t);
        }
        finally {
            HibernateUtil.closeCurrentSession();
        }
    }
    
    public static void terminatePipeline(JobInfo jobInfo) {
        if (jobInfo == null) {
            log.error("Ignoring null arg");
            return;
        }
        // handle special-case: pipeline already finished before user terminated event was processed
        if (JobStatus.ERROR.equals(jobInfo.getStatus()) || JobStatus.FINISHED.equals(jobInfo.getStatus())) {
            log.info("job #"+jobInfo.getJobNumber()+" already finished, status="+jobInfo.getStatus());
            return;
        }
        
        //get current job and terminate it ... if all steps have already completed ... log an error but do nothing
        int processingJobId = -1;
        HibernateUtil.beginTransaction();
        List<Object[]> jobInfoObjs = getChildJobObjs(jobInfo.getJobNumber());
        HibernateUtil.closeCurrentSession();
        for(Object[] row : jobInfoObjs) {
            int jobId = (Integer) row[0];
            int statusId = (Integer) row[1];
            if (JobStatus.JOB_PROCESSING == statusId) {
                processingJobId = jobId;
            }
        }
        if (processingJobId >= 0) {
            try {
                AnalysisJobScheduler.terminateJob(processingJobId);
            }
            catch (JobTerminationException e) {
                log.error("Error terminating job #"+processingJobId+" in pipeline "+jobInfo.getJobNumber(), e);
                return;
            }
            return;
        }
        
        //very likely corner-case
        terminatePipelineSteps(jobInfo.getJobNumber());
        handlePipelineJobCompletion(jobInfo.getJobNumber(), -1, JobStatus.JOB_ERROR);
    }

    /**
     * Call this before running the next step in the given pipeline. Must be called after all dependent steps are completed.
     * Note: circa GP 3.2.3 and earlier this code was part of a loop which ran all steps in the same thread.
     * Note: circa GP 3.2.4 this method makes changes to the jobInfo which are saved to the DB.
     *
     * @param jobInfo - the job which is about to run
     */
    public static void prepareNextStep(int parentJobId, JobInfo jobInfo) throws PipelineException { 
        try {
            AnalysisDAO ds = new AnalysisDAO();
            JobInfo[] results = ds.getChildren(parentJobId);
            ParameterInfo[] parameterInfo = jobInfo.getParameterInfoArray();
            setInheritedJobParameters(parameterInfo, results);
            updateParameterInfo(jobInfo);
            HibernateUtil.commitTransaction();
        }
        catch (FileNotFoundException e) {
            HibernateUtil.rollbackTransaction();
            throw new PipelineException("Error setting inherited job parameters in pipeline "+parentJobId+" for job "+jobInfo.getJobNumber(), e);
        }
        finally {
            HibernateUtil.closeCurrentSession();
        }
    }
    
    /**
     * Helper method (could go into AnalysisDAO) for saving edits to the ANALYSIS_JOB.PARAMETER_INFO for a given job.
     */
    private static void updateParameterInfo(JobInfo jobInfo) {
        int jobId = jobInfo.getJobNumber();
        String paramString = jobInfo.getParameterInfo();
        AnalysisJob aJob = (AnalysisJob) HibernateUtil.getSession().get(AnalysisJob.class, jobId);
        aJob.setParameterInfo(paramString);
        HibernateUtil.getSession().update(aJob);
    }
    
    //DAO helpers
    
    /**
     * Get all of the immediate child jobs for the given pipeline.
     * 
     * @return a List of [jobNo(Integer),statusId(Integer)]
     */
    private static List<Object[]> getChildJobObjs(int parentJobId) {
        final int maxChildJobs = 1000; //limit total number of results to 1000
        String hql = "select a.jobNo, jobStatus.statusId from org.genepattern.server.domain.AnalysisJob a where a.parent = :jobNo ";
        hql += " ORDER BY jobNo ASC";
        Query query = HibernateUtil.getSession().createQuery(hql);
        query.setInteger("jobNo", parentJobId);
        query.setFetchSize(maxChildJobs);
        
        List<Object[]> rval = query.list();
        return rval;
    }
    
    public static boolean startNextJob(int parentJobNumber) {
        Integer nextJobId = getNextJobId(parentJobNumber);
        if (nextJobId != null) {
            //if there is another job to run, change the status of the next job to pending
            int rval = AnalysisJobScheduler.changeJobStatus(nextJobId, JobStatus.JOB_WAITING, JobStatus.JOB_PENDING);
            //indicate there is another step waiting on the queue
            return true;
        }
        return false;
    }
    
    /**
     * Called when a step in a pipeline job has completed, put the next job on the queue.
     * Check for and handle pipeline termination.
     * 
     * @param jobInfo
     * @param parentJobInfo
     * @param jobStatus
     * @param completionDate
     * @return
     */
    public static boolean handleJobCompletion(int childJobNumber) {
        if (childJobNumber < 0) {
            log.error("Invalid jobNumber: "+childJobNumber);
        }
        JobInfo childJobInfo = null;
        int parentJobNumber = -1;
        try {
            AnalysisDAO ds = new AnalysisDAO();
            childJobInfo = ds.getJobInfo(childJobNumber);
            parentJobNumber = childJobInfo.getParentJobNumber();
        }
        finally {
            HibernateUtil.closeCurrentSession();
        }

        if (parentJobNumber < 0) {
            log.error("Invalid parentJobNumber: "+parentJobNumber);
            return false;
        } 
        //add any output files from the completed job to the parent
        collectChildJobResults(parentJobNumber, childJobInfo);
        
        //check the status of the job
        if (JobStatus.FINISHED.equals(childJobInfo.getStatus())) { 
            //get the next step in the pipeline, by jobId
            HibernateUtil.beginTransaction();
            Integer nextJobId = getNextJobId(parentJobNumber);
            HibernateUtil.closeCurrentSession();
            if (nextJobId != null) {
                //if there is another job to run, change the status of the next job to pending
                startNextStep(nextJobId);
                //indicate there is another step waiting on the queue
                return true;
            }
            else {
                //it's the last step in the pipeline, update its status
                handlePipelineJobCompletion(parentJobNumber, 0, JobStatus.JOB_FINISHED);
                return false;
            }
        }
        else {
            //handle an error in a pipeline step
            terminatePipelineSteps(parentJobNumber);
            handlePipelineJobCompletion(parentJobNumber, -1, JobStatus.JOB_ERROR);
        }
        return false;
    }
    
    public static void handleRunningPipeline(JobInfo pipeline) {
        if (pipeline == null) {
            log.error("Invalid null arg");
            return;
        }
        if (pipeline.getJobNumber() < 0) {
            log.error("Invalid arg: pipeline.jobNumber="+pipeline.getJobNumber());
        }

        //the number of child steps which have not yet finished
        int numStepsToGo = 0;
        //true if at least one of the steps finished with an error
        boolean errorFlag = false;
        List<Object[]> jobInfoObjs = null;
        try {
            HibernateUtil.beginTransaction();
            jobInfoObjs = getChildJobObjs(pipeline.getJobNumber());
        }
        catch (Throwable t) {
            jobInfoObjs = new ArrayList<Object[]>();
        }
        finally {
            HibernateUtil.closeCurrentSession();
        }
        for(Object[] row : jobInfoObjs) {
            int jobId = (Integer) row[0];
            int statusId = (Integer) row[1];
            if (JobStatus.JOB_FINISHED == statusId) {
            }
            else if (JobStatus.JOB_ERROR == statusId) {
                errorFlag = true;
            }
            else {
                ++numStepsToGo;
            }
        }
        
        if (numStepsToGo == 0 || errorFlag) {
            if (errorFlag) {
                //handle an error in a pipeline step
                terminatePipelineSteps(pipeline.getJobNumber());
                handlePipelineJobCompletion(pipeline.getJobNumber(), -1, JobStatus.JOB_ERROR);
            }
            else {
                PipelineHandler.handlePipelineJobCompletion(pipeline.getJobNumber(), 0, JobStatus.JOB_FINISHED);
            }
        }
    }
    
    private static void startNextStep(int nextJobId) {
        try {
            HibernateUtil.beginTransaction();
            int rval = AnalysisJobScheduler.changeJobStatus(nextJobId, JobStatus.JOB_WAITING, JobStatus.JOB_PENDING);
            HibernateUtil.commitTransaction();
        }
        catch (Throwable t) {
            HibernateUtil.rollbackTransaction();
        }
    }
    
    private static void terminatePipelineSteps(int parentJobNumber) {
        try {
            HibernateUtil.beginTransaction();
            List<Integer> incompleteJobIds = getIncompleteJobs(parentJobNumber);
            for(Integer jobId : incompleteJobIds) {
                AnalysisJobScheduler.setJobStatus(jobId, JobStatus.JOB_ERROR);
            }
            HibernateUtil.commitTransaction();
        }
        catch (Throwable t) {
            log.error("Error updating job status for child jobs in pipeline #"+parentJobNumber, t);
            HibernateUtil.rollbackTransaction();
        }
    }

    /**
     * For the given pipeline, set its status to complete (and notify any interested listeners).
     * Delegated to the GenePatternAnalysisTask.
     * 
     * @param parentJobNumber
     * @param errorCode
     * @param jobStatus
     */
    private static void handlePipelineJobCompletion(int parentJobNumber, int errorCode, int jobStatus) {
        try {
            GenePatternAnalysisTask.handleJobCompletion(parentJobNumber, GPConstants.STDOUT, GPConstants.STDERR, errorCode, jobStatus, GenePatternAnalysisTask.JOB_TYPE.PIPELINE);
        }
        catch (Throwable t) {
            log.error("Error recording pipeline job completion for job #"+parentJobNumber, t);
        }
    }
    
    /**
     * For the given pipeline, get the next 'WAITING' job id.
     * 
     * @param parentJobId, the jobId of the pipeline
     * @return the jobId if the first job whose status is WAITING, or null if no waiting jobs are found
     */
    private static Integer getNextJobId(final int parentJobId) {
        List<Object[]> jobInfoObjs = getChildJobObjs(parentJobId);
        for(Object[] row : jobInfoObjs) {
            int jobNo = (Integer) row[0];
            int statusId = (Integer) row[1];
            if (JobStatus.JOB_WAITING == statusId) {
                return jobNo;
            }
        }
        return null;
    }

    /**
     * For the given pipeline, get the list of child jobs which have not yet finished.
     * Only get immediate child jobs.
     * 
     * @param parentJobId
     * @return
     */
    private static List<Integer> getIncompleteJobs(final int parentJobId) {
        List<Object[]> jobInfoObjs = getChildJobObjs(parentJobId);
        List<Integer> waitingJobs = new ArrayList<Integer>();
        for(Object[] row : jobInfoObjs) {
            int jobId = (Integer) row[0];
            int statusId = (Integer) row[1];
            //ignore completed jobs
            if (JobStatus.JOB_FINISHED != statusId && JobStatus.JOB_ERROR != statusId) {
                waitingJobs.add(jobId);
            }
        }
        return waitingJobs;
    }
    
    private static Map getAdditionalArgs(String[] commandTokens) {
        Properties additionalArguments = new Properties();
        //HACK: remove all args up to org.genepattern.server.webapp.RunPipelineSoap
        List<String> modifiedCommandTokens = new ArrayList<String>();
        int startIdx = 0;
        for(int i=0; i<commandTokens.length; ++i) {
            if ("org.genepattern.server.webapp.RunPipelineSoap".equals(commandTokens[i])) {
                startIdx = i+1;
                break;
            }
        }
        for(int i=startIdx; i<commandTokens.length; ++i) {
            modifiedCommandTokens.add(commandTokens[i]);
        }
        String[] args = new String[modifiedCommandTokens.size()];
        args = modifiedCommandTokens.toArray(args);
        if (args.length > 2) {
            for (int i = 2; i < args.length; i++) {
                // assume args are in the form name=value
                String arg = args[i];
                StringTokenizer strtok = new StringTokenizer(arg, "=");
                String key = strtok.nextToken();
                StringBuffer valbuff = new StringBuffer("");
                int count = 0;
                while (strtok.hasMoreTokens()) {
                    valbuff.append(strtok.nextToken());
                    if ((strtok.hasMoreTokens()))
                        valbuff.append("=");
                    count++;
                }
                additionalArguments.put(key, valbuff.toString());
            }
        }
        return additionalArguments;
    }

    /**
     * 
     * @param pipelineJobId
     * @param userID
     * @param model
     * @param stopAfterTask
     * @param additionalArgs
     * 
     * @return the jobNumber of the first step of the pipeline
     * 
     * @throws MissingTasksException
     * @throws WebServiceException
     * @throws JobSubmissionException
     */
    private static Integer runPipeline(JobInfo pipelineJobInfo, int stopAfterTask, Map additionalArgs) 
    throws PipelineModelException, MissingTasksException, WebServiceException, JobSubmissionException
    {  
        PipelineModel pipelineModel = PipelineUtil.getPipelineModel(pipelineJobInfo);
        pipelineModel.setLsid(pipelineJobInfo.getTaskLSID());

        checkForMissingTasks(pipelineJobInfo.getUserId(), pipelineModel);

        int stepNum = 0;
        Vector<JobSubmission> tasks = pipelineModel.getTasks();
        Integer firstStep = null;
        for(JobSubmission jobSubmission : tasks) { 
            if (stepNum >= stopAfterTask) {
                break; // stop and execute no further
            }
            
            ParameterInfo[] parameterInfo = jobSubmission.giveParameterInfoArray();
            substituteLsidInInputFiles(pipelineJobInfo.getTaskLSID(), parameterInfo);
            ParameterInfo[] params = parameterInfo;
            params = setJobParametersFromArgs(jobSubmission.getName(), stepNum + 1, params, additionalArgs);

            int jobStatusId = JobStatus.JOB_WAITING;
            JobInfo submittedJob = addJobToPipeline(pipelineJobInfo.getJobNumber(), pipelineJobInfo.getUserId(), jobSubmission, params, jobStatusId);
            if (firstStep == null) {
                firstStep = submittedJob.getJobNumber();
            }
            ++stepNum;
        }
        return firstStep;
    }
    
    private static List<ParameterInfo> getChildJobOutputs(JobInfo child) {
        List<ParameterInfo> outs = new ArrayList<ParameterInfo>();
        ParameterInfo[] childParams = child.getParameterInfoArray();
        for (ParameterInfo childParam : childParams) {
            if (childParam.isOutputFile()) {
                File f = new File(childParam.getValue());
                if (!f.getName().equals(GPConstants.TASKLOG)) {
                    outs.add(childParam);
                }
            }
        }
        return outs;
    }
    
    /**
     * Add output files from the given completed child job to the parent job.
     */
    private static void collectChildJobResults(int parentJobNumber, JobInfo childJobInfo) {
        List<ParameterInfo> childJobOutputs = getChildJobOutputs(childJobInfo);
        if (childJobOutputs.size() > 0) {
            try {
                HibernateUtil.beginTransaction();
                AnalysisDAO ds = new AnalysisDAO();
                JobInfo parentJobInfo = ds.getJobInfo(parentJobNumber);
                for(ParameterInfo childJobOutput : childJobOutputs) {
                    parentJobInfo.addParameterInfo(childJobOutput);
                }
                
                String paramString = parentJobInfo.getParameterInfo();
                ds.updateParameterInfo(parentJobInfo.getJobNumber(), paramString);
                HibernateUtil.commitTransaction();
            }
            catch (Throwable t) {
                log.error("error updating parameter_info for parent job #"+parentJobNumber, t);
                HibernateUtil.rollbackTransaction();
            }
        }
    }

    /**
     * submit the job and wait for it to complete
     * 
     * @throws WebServiceException
     */
    private static JobInfo addJobToPipeline(int parentJobId, String userID, JobSubmission jobSubmission, ParameterInfo[] params, int jobStatusId)
    throws WebServiceException, JobSubmissionException 
    {
        log.debug("Begin executeTask");
        if (jobSubmission == null) {
            log.error("ignoring executeTask, jobSubmission is null");
            return null;
        }

        String lsidOrTaskName = jobSubmission.getLSID();
        if (lsidOrTaskName == null || lsidOrTaskName.equals("")) {
            lsidOrTaskName = jobSubmission.getName();
        }

        int taskId = 0;
        if (jobSubmission.getTaskInfo() != null) {
            taskId = jobSubmission.getTaskInfo().getID();
        }
        if (taskId <= 0) {
            log.error("jobSubmission.taskInfo.ID not set ... calling adminService.getTask("+lsidOrTaskName+")");
            AdminService adminService = new AdminService(userID);
            TaskInfo task = adminService.getTask(lsidOrTaskName);
            if (task == null) {
                log.error("Module " + lsidOrTaskName + " not found.");
                return new JobInfo();
            }
            taskId = task.getID();
            log.debug("taskInfo: " + task.getName() + ", " + task.getLsid());
        }

        if (params != null) {
            for (int i = 0; i < params.length; i++) {
                if (params[i].isInputFile()) {
                    String file = params[i].getValue(); // bug 724
                    if (file != null && file.trim().length() != 0) {
                        String val = file;
                        try {
                            new URL(file);
                        } 
                        catch (MalformedURLException e) {
                            val = new File(file).toURI().toString();
                        }
                        params[i].setValue(val);
                        params[i].getAttributes().remove("TYPE");
                        params[i].getAttributes().remove("MODE");
                    }
                }
            }
        }
        
        JobInfo jobInfo = JobManager.addJobToQueue(taskId, userID, params, parentJobId, jobStatusId);
        return jobInfo;
    }

    
    private static void checkForMissingTasks(String userID, PipelineModel model) throws MissingTasksException {
        MissingTasksException ex = null;
        AdminService adminService = new AdminService(userID);
        for(JobSubmission jobSubmission : model.getTasks()) {
            String lsid = jobSubmission.getLSID();
            TaskInfo taskInfo = null;
            try {
                taskInfo = adminService.getTask(lsid);
            }
            catch (Exception e) {
            }
            if (taskInfo == null) {
                if (ex == null) {
                    ex = new MissingTasksException();
                }
                ex.addError(jobSubmission);
            }
        }
        if (ex != null) {
            throw ex;
        }
    }
    
    private static void setInheritedJobParameters(ParameterInfo[] parameterInfos, JobInfo[] results) throws FileNotFoundException {
        for (ParameterInfo param : parameterInfos) {
            boolean isInheritTaskName = false;
            HashMap attributes = param.getAttributes();
            if (attributes != null) {
                isInheritTaskName = attributes.get(PipelineModel.INHERIT_TASKNAME) != null;
            }
            if (isInheritTaskName) {
                String url = getInheritedFilename(param.getAttributes(), results);
                param.setValue(url);
            }
        }
    }
    
    private static String server = null;
    private static String getServer() {
        if (server != null) {
            return server;
        }
        //1) set server
        String gpUrl = System.getProperty("GenePatternURL");
        URL serverFromFile = null;
        try {
            serverFromFile = new URL(gpUrl);
        } 
        catch (MalformedURLException e) {
            log.error("Invalid GenePatternURL: " + gpUrl, e);
        }
        String host = serverFromFile.getHost();
        String port = "";
        int portNum = serverFromFile.getPort();
        if (portNum >= 0) {
            port = ":" + portNum;
        }
        server = serverFromFile.getProtocol() + "://" + host + port;
        return server;
    }

    private static String getInheritedFilename(Map attributes, JobInfo[] results) throws FileNotFoundException {
        // these params must be removed so that the soap lib doesn't try to send the file as an attachment
        String taskStr = (String) attributes.get(PipelineModel.INHERIT_TASKNAME);
        String fileStr = (String) attributes.get(PipelineModel.INHERIT_FILENAME);
        attributes.remove("TYPE");
        attributes.put(ParameterInfo.MODE, ParameterInfo.URL_INPUT_MODE);

        int task = Integer.parseInt(taskStr);
        JobInfo job = results[task];
        String fileName = getOutputFileName(job, fileStr);

        String context = System.getProperty("GP_Path", "/gp");
        String url = getServer() + context + "/jobResults/" + fileName;
        return url;
    }

    /**
     * return the file name for the previously run job by index or name
     */
    private static String getOutputFileName(JobInfo job, String fileStr) throws FileNotFoundException {
        String fileName = null;
        String fn = null;
        int j;
        ParameterInfo[] jobParams = job.getParameterInfoArray();
        String jobDir = System.getProperty("jobs");
        // try semantic match on output files first
        // For now, just match on filename extension
        semantic_search_loop: for (j = 0; j < jobParams.length; j++) {
            if (jobParams[j].isOutputFile()) {
                fn = jobParams[j].getValue(); // get the filename
                File aFile = new File(fn);
                if (!aFile.exists()) {
                    aFile = new File("../", fn);
                }
                if (!aFile.exists()) {
                    aFile = new File("../" + jobDir + "/", fn);
                }
                if (!aFile.exists()) {
                    aFile = new File(jobDir + "/", fn);
                }

                if (isFileType(aFile, fileStr)) {
                    fileName = fn;
                    break semantic_search_loop;
                }
            }
        }

        if (fileName == null) {
            // no match on extension, try assuming that it is an integer number
            // (1..5)
            try {
                int fileIdx = Integer.parseInt(fileStr);
                // success, find the nth output file
                int jobFileIdx = 1;
                for (j = 0; j < jobParams.length; j++) {
                    if (jobParams[j].isOutputFile()) {
                        if (jobFileIdx == fileIdx) {
                            fileName = jobParams[j].getValue();
                            break;
                        }
                        jobFileIdx++;
                    }
                }
            } 
            catch (NumberFormatException nfe) {
                // not an extension, not a number, look for stdout or stderr
                // fileStr is stderr or stdout instead of an index
                if (fileStr.equals(GPConstants.STDOUT) || fileStr.equals(GPConstants.STDERR)) {
                    fileName = fileStr;
                }
            }
        }

        if (fileName != null) {
            int lastIdx = fileName.lastIndexOf(File.separator);
            lastIdx = fileName.lastIndexOf(File.separator, lastIdx - 1); // get the job # too

            if (lastIdx != -1) {
                fileName = fileName.substring(lastIdx + 1);
            }
        }
        if (fileName == null) {
            throw new FileNotFoundException("Unable to find output file from job " + job.getJobNumber() + " that matches " + fileStr + ".");
        }
        return fileName;
    }

    private static boolean isFileType(File file, String fileFormat) {
        if (file.getName().toLowerCase().endsWith(".odf")) {
            return ODFModelType(file).equalsIgnoreCase(fileFormat);
        }
        // when fileFormat does not contain the '.' character, 
        // assume that fileFormat refers to a file extension and prepend '.'. 
        // For example if value of fileFormat is 'gct', fileFormat becomes '.gct' when testing if file.getName() ends with fileFormat
        // when the file format does contain the '.' character, 
        // assume fileFormat can refer to a complete filename (e.g. all_aml_train.gct).
        if (fileFormat.indexOf('.') == -1) {
            fileFormat = "." + fileFormat;
        }
        return file.getName().toLowerCase().endsWith(fileFormat.toLowerCase());
    }

    private static String ODFModelType(File file) {
        String model = "";
        BufferedReader inputB = null;
        try {
            if (!file.exists()) {
                log.error("Can't find " + file.getCanonicalPath());
            }
            // System.out.println(file.getCanonicalPath());
            inputB = new BufferedReader(new FileReader(file));
            String modelLine = inputB.readLine();
            while (modelLine != null && !modelLine.startsWith("Model")) {
                modelLine = inputB.readLine();
            }

            if (modelLine != null) {
                model = modelLine.substring(modelLine.indexOf("=") + 1).trim();
            }
        } 
        catch (IOException e) {
            log.error("Error reading " + file);
        } 
        finally {
            if (inputB != null) {
                try {
                    inputB.close();
                } 
                catch (IOException x) {
                }
            }
        }
        return model;
    }
    
    /**
     * Substitute '<LSID>' in input files. 
     * This is a special case for steps in a pipeline which use input files from the pipeline or from a previous step in the pipeline.
     *
     * Note: this substitution used to depend on a call to System.setProperty(LSID)
     *    This won't work now that pipelines are no longer executing in their own JVM 
     *    This property seems to have been set in GenePatternAnalysisTask 
     *    For some reason, pipelineModel.getLsid() is null
     *
     * Note: must be called after {@link #setInheritedJobParameters(ParameterInfo[], JobInfo[])}
     * 
     * @param parameterInfos
     */
    private static void substituteLsidInInputFiles(String lsidValue, ParameterInfo[] parameterInfos) {
        final String lsidTag = "<LSID>";
        final String gpUrlTag = "<GenePatternURL>";
        for (ParameterInfo param : parameterInfos) {
            String value = param.getValue();
            if (value != null && value.startsWith(gpUrlTag)) {
                // substitute <LSID> flags for pipeline files
                value = value.replace(lsidTag, lsidValue);
                param.setValue(value);
            }
        }        
    }
    
    private static ParameterInfo[] removeEmptyOptionalParams(ParameterInfo[] parameterInfo) {
        ArrayList<ParameterInfo> params = new ArrayList<ParameterInfo>();
        for (int i = 0; i < parameterInfo.length; i++) {
            ParameterInfo aParam = parameterInfo[i];
            if (aParam.getAttributes() != null) {
                String value = aParam.getValue();
                if (value != null) {
                    if ((value.trim().length() == 0) && aParam.isOptional()) {
                        log.debug("Removing Param " + aParam.getName() + " has null value. Opt= " + aParam.isOptional());
                    } 
                    else {
                        params.add(aParam);
                    }
                }
            }
        }
        return params.toArray(new ParameterInfo[params.size()]);
    }
    
    /**
     * Look for parameters that are passed in on the command line and put them into the ParameterInfo array
     */
    private static ParameterInfo[] setJobParametersFromArgs(
            String name, 
            int taskNum, //this is the step number
            ParameterInfo[] parameterInfo, 
            Map args) 
    {
        for (int i = 0; i < parameterInfo.length; i++) {
            ParameterInfo aParam = parameterInfo[i];
            HashMap attributes = aParam.getAttributes();
            if (attributes != null) {
                if (attributes.get(PipelineModel.RUNTIME_PARAM) != null) {
                    attributes.remove(PipelineModel.RUNTIME_PARAM);
                    String key = name + taskNum + "." + aParam.getName();
                    String val = (String) args.get(key);                    
                    if ((val != null)) { 
                        //We don't want to double prefix the arguments.  If this RunPipelineSoap was
                        //run from the GenePattern webpage, the arguments will have been prefixed as
                        //the "run pipeline" job was run to get us here.
                        if (attributes.containsKey(GPConstants.PARAM_INFO_PREFIX[0])){
                            String prefix = (String) attributes.get(GPConstants.PARAM_INFO_PREFIX[0]);
                            if (val.startsWith(prefix)){
                                val = val.substring(prefix.length());
                            }
                        }
                        aParam.setValue(val);
                    }
                }
            }
        }
        return parameterInfo;
    }
}