package org.genepattern.server.dm.userupload;

import java.io.File;
import java.util.List;

import org.genepattern.server.config.ServerConfiguration;
import org.genepattern.server.config.ServerConfiguration.Context;
import org.genepattern.server.database.HibernateUtil;
import org.genepattern.server.dm.GpDirectory;
import org.genepattern.server.dm.GpFileObjFactory;
import org.genepattern.server.dm.GpFilePath;
import org.genepattern.server.dm.userupload.dao.UserUpload;
import org.genepattern.server.dm.userupload.dao.UserUploadDao;

public class UserUploadManager {
    /**
     * Create an instance of a GpFilePath object.
     * 
     * @param userContext, must contain the valid userId of the user who is uploading the file.
     * @param relativePath, must be a relative file path, relative to the current user's upload directory.
     * @return
     * @throws Exception
     */
    static public GpFilePath getUploadFileObj(Context userContext, File relativePath) throws Exception {
        return GpFileObjFactory.getUserUploadFile(userContext, relativePath);
    }

    /**
     * Add a record of the user upload file into the database. 
     * 
     * Iff the current thread is not already in a DB transaction, this method will commit the transaction. 
     * Otherwise, it is up to the calling method to commit or rollback the transaction.
     * 
     * @param userContext, requires a valid userId,
     * @param gpFilePath, a GpFilePath to the upload file
     * @param numParts, the number of parts this file is broken up into, based on the jumploader applet.
     * @throws Exception if a duplicate entry for the file is found in the database
     */
    static public UserUpload createUploadFile(Context userContext, GpFilePath gpFileObj, int numParts) throws Exception {
        UserUpload uu = UserUpload.initFromGpFileObj(userContext, gpFileObj);
        uu.setNumParts(numParts);
        
        UserUploadDao dao = new UserUploadDao();
        try {
            dao.save( uu );
            return uu;
        }
        catch (RuntimeException e) {
            throw new Exception("Duplicate entry found in the database for file: " + gpFileObj.getRelativePath());
        }
    }

    /**
     * Update the record of the user upload file in the database.

     * Iff the current thread is not already in a DB transaction, this method will commit the transaction. 
     * Otherwise, it is up to the calling method to commit or rollback the transaction.
     * 
     * @param userContext
     * @param gpFilePath
     * @param partNum, part numbers start with 1, e.g. a single chunk upload would have partNum=1 and totalParts=1.
     * @param totalParts
     * @throws Exception, if a part is received out of order.
     */
    static public void updateUploadFile(Context userContext, GpFilePath gpFilePath, int partNum, int totalParts) throws Exception {
        boolean inTransaction = HibernateUtil.isInTransaction();
        try {
            UserUploadDao dao = new UserUploadDao();
            UserUpload uu = dao.selectUserUpload(userContext.getUserId(), gpFilePath);
            if (uu.getNumParts() != totalParts) {
                throw new Exception("Expecting numParts to be "+uu.getNumParts()+" but it was "+totalParts);
            }
            if (uu.getNumPartsRecd() != (partNum - 1)) {
                throw new Exception("Received partial upload out of order, partNum="+partNum+", expecting partNum to be "+ (uu.getNumPartsRecd() + 1));
            }        
            uu.setNumPartsRecd(partNum);
            dao.saveOrUpdate( uu );
            if (!inTransaction) {
                HibernateUtil.commitTransaction();
            }
        }
        catch (Throwable t) {
            HibernateUtil.rollbackTransaction();
            throw new Exception("Error updating upload file record for file '"+gpFilePath.getRelativePath()+"': "+t.getLocalizedMessage(), t);
        }
        finally {
            if (!inTransaction) {
                HibernateUtil.closeCurrentSession();
            }
        }
    }
    
    /**
     * Get the entire tree of user upload files, rooted at the upload directory for the given user.
     * The root element is the user's upload directory, which typically is not displayed.
     * 
     * @param userContext
     * @param inDir
     * @return
     */
    static public GpDirectory getFileTree(ServerConfiguration.Context userContext) throws Exception {
        GpFilePath userDir = GpFileObjFactory.getUserUploadDir(userContext);
        GpDirectory root = new GpDirectory(userDir);
        
        List<UserUpload> all = getAllFiles(userContext.getUserId());
        for(UserUpload uploadFile : all) {
            GpFilePath uploadFilePath = GpFileObjFactory.getUserUploadFile(userContext, new File(uploadFile.getPath()));
            root.add( userContext, uploadFilePath );
        }
        
        return root;
    }
    
    /**
     * query all files from the DB, for the given user.
     * @param userId
     * @return
     */
    static private List<UserUpload> getAllFiles(String userId) {
        UserUploadDao dao = new UserUploadDao();
        return dao.selectAllUserUpload(userId);
    }
    
    /**
     * Converts an absolute file path to a path relative to the user's upload directory. 
     * Throws an exception if the absolute path not in that directory.
     * @param context
     * @param absolute The absolute path
     * @return
     * @throws Exception Thrown is the path provided is not in the user's upload dir
     */
    static public String absoluteToRelativePath(Context context, String absolute) throws Exception {
        File userUploadDir = ServerConfiguration.instance().getUserUploadDir(context);
        String uploadPath = userUploadDir.getCanonicalPath();
        if (!absolute.contains(uploadPath)) {
            throw new Exception("Absolute path provided is not in the user's upload directory");
        }
        String[] parts = absolute.split(userUploadDir.getCanonicalPath());
        String relative = null;
        if (parts.length == 2) {
            relative = "." + parts[1];
        }
        else {
            relative = ".";
        }
        return relative;
    }
}

