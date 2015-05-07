/*******************************************************************************
 * Copyright (c) 2003, 2015 Broad Institute, Inc. and Massachusetts Institute of Technology.  All rights reserved.
 *******************************************************************************/
package org.genepattern.server.job.input.cache;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.URL;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.commons.io.FileUtils;
import org.apache.log4j.Logger;
import org.genepattern.server.config.GpConfig;
import org.genepattern.server.config.GpContext;
import org.genepattern.server.config.ServerConfigurationFactory;
import org.genepattern.server.dm.GpFilePath;
import org.genepattern.server.job.input.JobInputHelper;
import org.genepattern.server.job.input.choice.DirFilter;
import org.genepattern.server.job.input.choice.ftp.FtpDirLister;
import org.genepattern.server.job.input.choice.ftp.FtpDirListerCommonsNet_3_3;
import org.genepattern.server.job.input.choice.ftp.FtpDirListerEdtFtpJ;
import org.genepattern.server.job.input.choice.ftp.FtpEntry;
import org.genepattern.server.job.input.choice.ftp.ListFtpDirException;

/**
 * For sync'ing a remote directory.
 * Example use-case, a user selects a remote ftp directory from a dynamic drop-down menu, e.g.
 *     bowtie.index=ftp://gpftp.broadinstitute.org/module_support_files/bowtie2/index/by_genome/Mus_musculus_UCSC_mm10
 * @author pcarr
 *
 */
public class CachedFtpDir implements CachedFile {
    private static Logger log = Logger.getLogger(CachedFtpDir.class);

    /**
     * Initialize an ftp directory lister. 
     * Customization options can be set in the config_yaml file.
     * 
     * <pre>
    # By default use EDT_FTP_J client, optionally revert to COMMONS_NET_3_3 client.
    ftpDownloader.type: EDT_FTP_J | COMMONS_NET_3_3
    gp.server.choice.ftp_username: "anonymous"
    gp.server.choice.ftp_password: "gp-help@broadinstitute.org"
    # amount of time in milliseconds
    gp.server.choice.ftp_socketTimeout: 15000
    # only for COMMONS_NET_3_3 client
    gp.server.choice.ftp_dataTimeout: 15000
     * </pre>
     * 
     * @param gpConfig
     * @param gpContext
     * @return
     */
    public static FtpDirLister initDirListerFromConfig(GpConfig gpConfig, GpContext gpContext) {
        if (gpConfig==null) {
            gpConfig=ServerConfigurationFactory.instance();
        }
        // special-case, initialize default passiveMode from gpConfig
        boolean passiveMode=gpConfig.getGPBooleanProperty(gpContext, FtpDirLister.PROP_FTP_PASV, true);
        return initDirListerFromConfig(gpConfig, gpContext, passiveMode);
    }

    public static FtpDirLister initDirListerFromConfig(GpConfig gpConfig, GpContext gpContext, final boolean passiveMode) {
        if (gpConfig==null) {
            gpConfig=ServerConfigurationFactory.instance();
        }
        final String clientType=gpConfig.getGPProperty(gpContext, CachedFtpFile.Type.PROP_FTP_DOWNLOADER_TYPE, CachedFtpFile.Type.EDT_FTP_J.name());
        if (log.isDebugEnabled()) {
            log.debug("Initializing ftpDirLister from clientType="+clientType);
        }
        if (clientType.startsWith("EDT_FTP_J")) {
            if (log.isDebugEnabled()) {
                log.debug("initializing EDT_FTP_J directory lister");
            }
            // use EDT_FTP_J directory lister
            return FtpDirListerEdtFtpJ.createFromConfig(gpConfig, gpContext, passiveMode);
        }
        else {
            if (log.isDebugEnabled()) {
                log.debug("initializing CommonsNet directory lister");
            }
            FtpDirLister dirLister = FtpDirListerCommonsNet_3_3.createFromConfig(gpConfig, gpContext, passiveMode);
            return dirLister;
        }
    }
    
    /**
     * Utility method for creating a new file with the given message as it's content.
     * @param message
     * @param toFile
     */
    private static void writeToFile(final String message, final File toFile) {
        toFile.getParentFile().mkdirs();
        BufferedWriter writer = null;
        try {
            writer = new BufferedWriter(new FileWriter(toFile));
            writer.write(message);
        } 
        catch (IOException e) {
            log.error("Error writing file="+toFile, e);
        } 
        finally {
            if (writer != null) {
                try {
                    writer.close();
                } 
                catch (IOException e) {
                    e.printStackTrace();
                }
            }
        }
    }

    private final GpConfig gpConfig;
    private final GpContext jobContext;
    private final URL url;
    private final GpFilePath localPath;
    // the tmpDir contains a file indicating the status of the file transfer, so that we can
    // check for completed downloads after a server restart
    private final GpFilePath tmpDir;

    public CachedFtpDir(final GpConfig gpConfigIn, final GpContext jobContextIn, final String urlString) {
        if (log.isDebugEnabled()) {
            log.debug("Initializing CachedFtpFile, type="+this.getClass().getName());
        }
        this.url=JobInputHelper.initExternalUrl(urlString);
        if (url==null) {
            throw new IllegalArgumentException("value is not an external url: "+urlString);
        }
        
        if (gpConfigIn==null) {
            this.gpConfig=ServerConfigurationFactory.instance();
        }
        else {
            this.gpConfig=gpConfigIn;
        }
        if (jobContextIn==null) {
            this.jobContext=GpContext.getServerContext();
        }
        else {
            this.jobContext=jobContextIn;
        }

        this.localPath=CachedFtpFile.getLocalPath(this.gpConfig, url);
        if (this.localPath==null) {
            throw new IllegalArgumentException("error initializing local path for external url: "+urlString);
        }
        if (log.isDebugEnabled()) {
            log.debug("url="+url.toExternalForm());
            log.debug("localPath.serverFile="+localPath.getServerFile());
        }
        this.tmpDir=CachedFtpFile.getLocalPathForDir(gpConfig, url);
    }

    @Override
    public URL getUrl() {
        return url;
    }

    @Override
    public GpFilePath getLocalPath() {
        return localPath;
    }

    /**
     * Download the file. This method blocks until cancelled or the transfer is complete.
     * @return
     * @throws DownloadException
     */
    @Override
    public GpFilePath download() throws DownloadException {
        try {
            doDownload();
            return localPath;
        }
        catch (DownloadException e) {
            throw e;
        }
        catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        catch (ListFtpDirException e) {
            log.error(e);
            throw new DownloadException(e);
        }
        return localPath;
    }

    private GpFilePath doDownload() throws ListFtpDirException, DownloadException, InterruptedException {
        setStatusToDownloading();
        
        final List<FtpEntry> ftpEntries = getFilesToDownload();
        // loop through all of the files and start downloading ...
        try {
            for(final FtpEntry ftpEntry : ftpEntries) {
                try {
                    final Future<?> f = FileCache.instance().getFutureObj(gpConfig, jobContext, ftpEntry.getValue(), ftpEntry.isDir());
                    f.get(100, TimeUnit.MILLISECONDS);
                }
                catch (TimeoutException e) {
                    //skip, it means the file is still downloading
                }
                Thread.yield();
            }
            // now loop through all of the files and wait for each download to complete
            for(final FtpEntry ftpEntry : ftpEntries) {
                final Future<?> f = FileCache.instance().getFutureObj(gpConfig, jobContext, ftpEntry.getValue(), ftpEntry.isDir());
                f.get();
            }
        }
        catch (ExecutionException e) {
            log.error(e);
            throw new DownloadException(e.getLocalizedMessage());
        }
        setStatusToFinished();
        return localPath;
    }

    @Override
    public boolean isDownloaded() {
        if (tmpDir.getServerFile().exists()) {
            final String[] files=tmpDir.getServerFile().list();
            if (files != null) {
                for(final String file : files) {
                    if (file.equalsIgnoreCase("complete")) {
                        return true;
                    }
                }
            }
        }
        return false;
    }

    private void setStatusToDownloading() throws DownloadException {
        try {
            FileUtils.deleteDirectory(tmpDir.getServerFile());
        }
        catch (Throwable t) {
            log.error("Error preparing tmp dir before directory download from url="+url+" to "+localPath, t);
            throw new DownloadException(t.getLocalizedMessage());
        }
    }

    private void setStatusToFinished() {
        //create a file in the tmpDir called "complete"
        writeToFile("Finished", new File(tmpDir.getServerFile(), "complete"));
    }
    
    public List<FtpEntry> getFilesToDownload() throws DownloadException, ListFtpDirException {
        FtpDirLister dirLister = initDirListerFromConfig(gpConfig, jobContext);
        final String ftpDir=url.toExternalForm();
        DirFilter filter=new DirFilter();
        return dirLister.listFiles(ftpDir, filter);
    }
}
