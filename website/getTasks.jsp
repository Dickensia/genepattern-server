<%@ page import="java.util.Collection,
		 java.util.Iterator,
		 org.genepattern.analysis.TaskInfo,
		 org.genepattern.analysis.TaskInfoAttributes,
		 org.genepattern.util.GPConstants,
		 org.genepattern.util.LSID,
 		 org.genepattern.server.analysis.webservice.server.local.*"
	session="false" contentType="text/plain" language="Java" %><%

	// output a set of name value pairs: taskID/task name, taskID/LSID, and taskID/LSID-without-version-number

	String userID = request.getParameter(GPConstants.USERID);
	Collection tmTasks = new LocalAdminClient(userID).getTaskCatalog();
	TaskInfo taskInfo = null;
	TaskInfoAttributes taskInfoAttributes = null;
	int taskID;
	LSID lsid;

	for (Iterator itTasks = tmTasks.iterator(); itTasks.hasNext(); ) {
		try {
			taskInfo = (TaskInfo)itTasks.next();
			taskID = taskInfo.getID();
			taskInfoAttributes = taskInfo.giveTaskInfoAttributes();
			lsid = new LSID((String)taskInfoAttributes.get(GPConstants.LSID));

			// version 1.2
			out.println(taskID + "=" + taskInfo.getName());

			// version 1.3 adds LSIDs, both with and without a specific version number
			out.println(taskID + "=" + lsid.toString());
			out.println(taskID + "=" + lsid.toStringNoVersion());

		} catch (Exception e) {
			System.err.println("getTasks.jsp: code generation for " + taskInfo.getName() + " task failed:" + e.getMessage());
		}
	}
 %>