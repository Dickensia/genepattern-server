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

package org.genepattern.server.webapp.jsf;

import java.net.MalformedURLException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import javax.faces.model.SelectItem;

import org.apache.log4j.Logger;
import org.genepattern.server.database.HibernateUtil;
import org.genepattern.server.genepattern.TaskInstallationException;
import org.genepattern.server.process.InstallTask;
import org.genepattern.server.process.InstallTasksCollectionUtils;
import org.genepattern.server.process.ModuleRepository;
import org.genepattern.server.util.AuthorizationManagerFactory;
import org.genepattern.server.util.IAuthorizationManager;
import org.genepattern.server.webservice.server.Status;
import org.genepattern.util.GPConstants;
import org.genepattern.util.LSID;

public class TaskCatalogBean {
    private InstallTask[] tasks;

    private List<MyTask> filteredTasks;

    private final String NEW_TEXT = "Search for new tasks to install";

    private final String UPDATED_TEXT = "Search for updates of the currently installed tasks";

    private final String PLATFORM_INDEPENDENT = "Platform Independent";

    private MySelectItem[] operatingSystems = new MySelectItem[] { new MySelectItem("any", PLATFORM_INDEPENDENT) };

    private MySelectItem[] states = new MySelectItem[] { new MySelectItem("new", NEW_TEXT),
            new MySelectItem("updated", UPDATED_TEXT) };;

    private HashSet missingLsids;

    private static Logger log = Logger.getLogger(TaskCatalogBean.class);

    private boolean error;

    private InstallTasksCollectionUtils collection;

    private Map<String, InstallTask> lsidToTaskMap;

    private Map<String, List> baseLsidToTasksMap;

    private static final Comparator DESC_COMPARATOR = new DescendingVersionComparator();

    public static class MySelectItem extends SelectItem {

        public MySelectItem(String value, String label) {
            super(value, label);

        }

        public MySelectItem(String value) {
            super(value);
        }

        private boolean selected;

        public boolean isSelected() {
            return selected;
        }

        public void setSelected(boolean selected) {
            this.selected = selected;
        }

    }

    public TaskCatalogBean() {
        collection = new InstallTasksCollectionUtils(UIBeanHelper.getUserId(), false);
        try {
            this.tasks = collection.getAvailableModules();
        } catch (Exception e) {
            log.error(e);
            error = true;
        }
        try {
            this.baseLsidToTasksMap = new LinkedHashMap<String, List>();
            for (InstallTask t : tasks) {
                try {
                    String baseLsid = new LSID(t.getLsid()).toStringNoVersion();
                    List<InstallTask> taskList = baseLsidToTasksMap.get(baseLsid);
                    if (taskList == null) {
                        taskList = new ArrayList<InstallTask>();
                        baseLsidToTasksMap.put(baseLsid, taskList);
                    }
                    taskList.add(t);
                } catch (MalformedURLException e) {
                    log.error(e);
                }
            }

        } catch (Exception e) {
            this.error = true;
            log.error(e);
        }
        lsidToTaskMap = new HashMap<String, InstallTask>();
        if (tasks != null) {
            for (InstallTask t : tasks) {
                lsidToTaskMap.put(t.getLsid(), t);
            }
        }

        String[] operatingSystemsArray = collection.getUniqueValues("os");
        operatingSystems = new MySelectItem[operatingSystemsArray.length];
        for (int i = 0; i < operatingSystemsArray.length; i++) {
            String os = operatingSystemsArray[i];
            if (os.equalsIgnoreCase("any")) {
                operatingSystems[i] = new MySelectItem(os, PLATFORM_INDEPENDENT);
            } else {
                operatingSystems[i] = new MySelectItem(os);
            }

        }
        String[] requestedOs = UIBeanHelper.getRequest().getParameterValues("os");
        if (requestedOs == null || requestedOs.length == 0) {
            requestedOs = getDefaultOperatingSystems();
        }
        updateSelectedItems(requestedOs, operatingSystems);

        String[] requestedStates = UIBeanHelper.getRequest().getParameterValues("state");
        if (requestedStates == null || requestedStates.length == 0) {
            requestedStates = getDefaultStates();
        }
        updateSelectedItems(requestedStates, states);

        if (UIBeanHelper.getRequest().getParameter("taskCatalogForm:taskCatalogSubmit") == null) {
            filter();
        }

    }

    private static void updateSelectedItems(String[] request, MySelectItem[] selectItems) {
        Set<String> set = new HashSet<String>(Arrays.asList(request));
        for (MySelectItem i : selectItems) {
            i.setSelected(set.contains(i.getValue()));
        }
    }

    public static class Patch {

        private String name;

        private long size;

        public String getFormattedSize() {
            return JobHelper.getFormattedSize(size);
        }

        public String getName() {
            return name;
        }

        public long getSize() {
            return size;
        }

    }

    private void updatePatches() {
        HashMap<String, InstallTask> lsidToPatchMap = new HashMap<String, InstallTask>();
        try {
            String reposUrl = System.getProperty("DefaultPatchRepositoryURL");
            InstallTask[] patches = new ModuleRepository().parse(reposUrl);
            if (patches != null) {
                for (InstallTask t : patches) {
                    lsidToPatchMap.put(t.getLsid(), t);
                }
            }
        } catch (Exception e) {
            log.error(e);
            e.printStackTrace();
        }
        List<MyTask> tasks = getTasks();
        String installedPatchLSIDsString = System.getProperty("installedPatchLSIDs");
        Set<String> installedPatches = new HashSet<String>();
        if (installedPatchLSIDsString != null) {
            installedPatches.addAll(Arrays.asList(installedPatchLSIDsString.split(",")));
        }
        System.out.println("lsids in repos " + Arrays.asList(lsidToPatchMap.keySet().toArray()));
        if (tasks != null) {

            for (MyTask t : tasks) {

                String temp = (String) t.getAttributes().get("requiredPatchLSIDs");
                String[] patchLsids = (temp != null && !"".equals(temp)) ? patchLsids = temp.split(",") : new String[0];

                List<Patch> patches = new ArrayList<Patch>();
                for (String patchLsid : patchLsids) {
                    patchLsid = patchLsid.trim();
                    if (installedPatches.contains(patchLsid)) {
                        // ignore patches that are already installed
                        continue;
                    }
                    InstallTask installTaskPatch = lsidToPatchMap.get(patchLsid);
                    if (installTaskPatch != null) {
                        Patch patch = new Patch();
                        patch.name = installTaskPatch.getName();
                        patch.size = installTaskPatch.getDownloadSize();
                        patches.add(patch);

                    } else {
                        log.info("Patch " + patchLsid + " required by " + t.getName()
                                + " not found in patch repository.");

                    }

                }
                t.setPatches(patches);

            }
        }

    }

    public List<MyTask> getTasks() {
        return filteredTasks;
    }

    public MySelectItem[] getOperatingSystems() {
        return this.operatingSystems;
    }

    public MySelectItem[] getStates() {
        return this.states;
    }

    public int getNumberOfTasks() {
        return filteredTasks != null ? filteredTasks.size() : 0;
    }

    public boolean isError() {
        return error;
    }

    public HashSet getMissingLsids() {
        return missingLsids;
    }

    public String install() {
        filter();
        IAuthorizationManager authManager = AuthorizationManagerFactory.getAuthorizationManager();
        final boolean taskInstallAllowed = authManager.checkPermission("createTask", UIBeanHelper.getUserId());
        if (!taskInstallAllowed) {
            UIBeanHelper.setInfoMessage("You don't have the required permissions to install tasks.");
            return "failure";
        }
        final String[] lsids = UIBeanHelper.getRequest().getParameterValues("installLsid");
        if (lsids != null) {
            final String username = UIBeanHelper.getUserId();
            final TaskInstallBean installBean = (TaskInstallBean) UIBeanHelper.getManagedBean("#{taskInstallBean}");
            installBean.setTasks(lsids, lsidToTaskMap);

            new Thread() {
                public void run() {

                    for (final String lsid : lsids) {
                        try {
                            HibernateUtil.beginTransaction();
                            InstallTask t = lsidToTaskMap.get(lsid);

                            if (t == null) {
                                installBean.setStatus(lsid, "error", lsid + " not found.");

                            } else {

                                t.install(username, GPConstants.ACCESS_PUBLIC, new Status() {

                                    public void beginProgress(String string) {
                                    }

                                    public void continueProgress(int percent) {
                                    }

                                    public void endProgress() {
                                    }

                                    public void statusMessage(String message) {
                                        if (message != null) {
                                            installBean.appendPatchProgressMessage(lsid, message + "<br />");
                                        }
                                    }

                                });
                                installBean.setStatus(lsid, "success");
                            }
                            HibernateUtil.commitTransaction();
                        } catch (TaskInstallationException e) {
                            installBean.setStatus(lsid, "error", e.getMessage());
                            HibernateUtil.rollbackTransaction();
                            log.error(e);
                        }
                    }
                }
            }.start();
        }
        return "install";

    }

    private void findMissingTasks(String[] requestedLsidsArray) {
        // if a specific list of LSIDs is requested, display just those
        if (requestedLsidsArray != null && requestedLsidsArray.length > 0) {
            this.missingLsids = new HashSet();
            missingLsids.addAll(Arrays.asList(requestedLsidsArray));
            for (InstallTask task : tasks) {
                String lsidStr = task.getLsid();
                missingLsids.remove(lsidStr); // to look for LSIDs
                // requested
                // but
                // absent
            }
        }
    }

    private String[] getDefaultOperatingSystems() {
        List<String> l = new ArrayList<String>();
        l.add("any");
        String os = getOS();
        if (os != null) {
            l.add(os);
        }
        return l.toArray(new String[0]);
    }

    private String[] getDefaultStates() {
        List<String> l = new ArrayList<String>();
        l.add(InstallTask.NEW);
        l.add(InstallTask.UPDATED);
        return l.toArray(new String[0]);
    }

    private String getOS() {
        boolean isWindows = System.getProperty("os.name").toLowerCase().startsWith("windows");
        boolean isMac = System.getProperty("mrj.version") != null;
        boolean isLinux = System.getProperty("os.name").toLowerCase().startsWith("linux");

        if (isWindows) { // remove all tasks that are not windows or
            // any
            return "Windows";
        } else if (isMac) {
            return "Mac OS X";
        } else if (isLinux) {
            return "Linux";
        }
        return null;
    }

    private static List<String> getSelection(MySelectItem[] items) {
        List<String> selection = new ArrayList<String>();
        for (MySelectItem i : items) {
            if (i.isSelected()) {
                selection.add(i.getValue().toString());
            }
        }
        return selection;
    }

    public void filter() {
        String[] lsids = UIBeanHelper.getRequest().getParameterValues("lsid");
        filteredTasks = new ArrayList<MyTask>();

        if (lsids == null) {
            Map<String, List<String>> filterKeyToValuesMap = new HashMap<String, List<String>>();
            filterKeyToValuesMap.put("os", getSelection(operatingSystems));
            filterKeyToValuesMap.put("state", getSelection(states));

            log.debug(filterKeyToValuesMap);
            List<InstallTask> allFilteredTasks = new ArrayList<InstallTask>();
            if (tasks != null) {
                for (int i = 0; i < tasks.length; i++) {
                    if (tasks[i].matchesAttributes(filterKeyToValuesMap)) {
                        allFilteredTasks.add(tasks[i]);
                    }
                }
            }

            for (int i = 0; i < allFilteredTasks.size(); i++) { // find latest
                // version of
                // tasks
                InstallTask t = allFilteredTasks.get(i);
                try {
                    List<InstallTask> taskList = baseLsidToTasksMap.remove(new LSID(t.getLsid()).toStringNoVersion());
                    if (taskList != null) {
                        Collections.sort(taskList, DESC_COMPARATOR);
                        MyTask latest = new MyTask(taskList.remove(0));
                        if (taskList.size() > 0) {
                            MyTask[] laterVersions = new MyTask[taskList.size()];
                            for (int j = 0; j < taskList.size(); j++) {
                                laterVersions[j] = new MyTask(taskList.get(j));
                            }
                            latest.setLaterVersions(laterVersions);
                        }
                        filteredTasks.add(latest);
                    }

                } catch (MalformedURLException e) {
                    log.error(e);
                }
            }
        } else if (tasks != null) {
            MyTask missingTask;
            for (int i = 0; i < tasks.length; i++) {
                for (String lsid : lsids) {
                    if (lsid.equals(tasks[i].getLsid())) {
                        missingTask = new MyTask(tasks[i]);
                        filteredTasks.add(missingTask);
                    }
                }
            }

        }
        Collections.sort(filteredTasks, new TaskNameComparator());
        updatePatches();

    }

    public void refilter(Set<String> lsids) {
        filteredTasks = new ArrayList<MyTask>();
        if (tasks != null) {
            MyTask missingTask;
            for (int i = 0; i < tasks.length; i++) {
                if (lsids.contains(tasks[i].getLsid())) {
                    missingTask = new MyTask(tasks[i]);
                    filteredTasks.add(missingTask);
                }
            }
        }
        Collections.sort(filteredTasks, new TaskNameComparator());
    }

    private static class TaskNameComparator implements Comparator<MyTask> {

        public int compare(MyTask t1, MyTask t2) {
            return t1.getName().compareToIgnoreCase(t2.getName());
        }

    }

    private static class DescendingVersionComparator implements Comparator<InstallTask> {

        public int compare(InstallTask t1, InstallTask t2) {
            return new Integer(Integer.parseInt(t2.getLsidVersion())).compareTo(Integer.parseInt(t1.getLsidVersion()));
        }

    }

    public static class MyTask {
        private InstallTask task;

        private MyTask[] laterVersions;

        private List<Patch> patches;

        public void setLaterVersions(MyTask[] laterVersions) {
            this.laterVersions = laterVersions;
        }

        public void setPatches(List<Patch> patches) {
            this.patches = patches;
        }

        public List<Patch> getPatches() {
            return this.patches;
        }

        public MyTask[] getLaterVersions() {
            return laterVersions;
        }

        public MyTask(InstallTask task) {
            this.task = task;
        }

        public boolean equals(Object obj) {
            return task.equals(obj);
        }

        public Map getAttributes() {
            return task.getAttributes();
        }

        public String getAuthor() {
            return task.getAuthor();
        }

        public String getDescription() {
            return task.getDescription();
        }

        public String getDocumentationUrl() {
            return task.getDocumentationUrl();
        }

        public String[] getDocUrls() {
            return task.getDocUrls();
        }

        public String getFormattedSize() {
            return JobHelper.getFormattedSize(task.getDownloadSize());
        }

        public long getSize() {
            return task.getDownloadSize();
        }

        public String getExternalSiteName() {
            return task.getExternalSiteName();
        }

        public Map getInstalledTaskInfoAttributes() {
            return task.getInstalledTaskInfoAttributes();
        }

        public String getLanguage() {
            return task.getLanguage();
        }

        public String getLanguageLevel() {
            return task.getLanguageLevel();
        }

        public String getLsid() {
            return task.getLsid();
        }

        public String getLsidVersion() {
            return task.getLsidVersion();
        }

        public long getModificationTimestamp() {
            return task.getModificationTimestamp();
        }

        public String getName() {
            return task.getName();
        }

        public String getOperatingSystem() {
            return task.getOperatingSystem();
        }

        public String getQuality() {
            return task.getQuality();
        }

        public String getRequirements() {
            return task.getRequirements();
        }

        public String getTaskType() {
            return task.getTaskType();
        }

        public String getUrl() {
            return task.getUrl();
        }

        public String getVersionComment() {
            String comment = task.getVersionComment();
            if (comment.length() > 100) {
                comment = comment.substring(0, 99) + "...";
            }
            return comment;
        }

        public int hashCode() {
            return task.hashCode();
        }

        public boolean install(String username, int access_id, Status status)
                throws TaskInstallationException {
            return task.install(username, access_id, status);
        }

        public boolean isAlreadyInstalled() {
            return task.isAlreadyInstalled();
        }

        public boolean isDeprecated() {
            return task.isDeprecated();
        }

        public boolean isNewer() {
            return task.isNewer();
        }

        public boolean matchesAttributes(Map attributes) {
            return task.matchesAttributes(attributes);
        }

        public void setInitialInstall(boolean initialInstall) {
            task.setInitialInstall(initialInstall);
        }

        public String toLongString() {
            return task.toLongString();
        }

        public String toString() {
            return task.toString();
        }
    }
}
