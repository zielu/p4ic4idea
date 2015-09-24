/*
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package net.groboclown.idea.p4ic.v2.server;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.intellij.openapi.vfs.VirtualFile;
import com.perforce.p4java.core.file.IExtendedFileSpec;
import com.perforce.p4java.core.file.IFileSpec;
import net.groboclown.idea.p4ic.P4Bundle;
import net.groboclown.idea.p4ic.config.ServerConfig;
import net.groboclown.idea.p4ic.server.FileSpecUtil;
import net.groboclown.idea.p4ic.server.ServerExecutor;
import net.groboclown.idea.p4ic.server.exceptions.P4DisconnectedException;
import net.groboclown.idea.p4ic.server.exceptions.P4Exception;
import net.groboclown.idea.p4ic.server.exceptions.P4FileException;
import net.groboclown.idea.p4ic.v2.server.cache.ClientServerId;
import net.groboclown.idea.p4ic.v2.server.cache.P4ChangeListValue;
import net.groboclown.idea.p4ic.v2.server.cache.state.PendingUpdateState;
import net.groboclown.idea.p4ic.v2.server.cache.sync.ClientCacheManager;
import net.groboclown.idea.p4ic.v2.server.connection.*;
import net.groboclown.idea.p4ic.v2.server.connection.ServerConnection.CacheQuery;
import net.groboclown.idea.p4ic.v2.server.connection.ServerConnection.CreateUpdate;
import net.groboclown.idea.p4ic.v2.server.util.FilePathUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.*;

/**
 * Top-level manager for handling communication with the Perforce server
 * for a single client/server connection.
 * <p/>
 * The owner of this object needs to be aware of config changes; those
 * signal that the server instances are no longer valid.
 * It should listen to {@link net.groboclown.idea.p4ic.v2.events.BaseConfigUpdatedListener#TOPIC} events, which
 * are generated by {@link net.groboclown.idea.p4ic.config.P4ConfigProject}.
 * <p/>
 * The owner should also only save the state for valid server objects.
 * <p/>
 * This is a future replacement for {@link ServerExecutor}.  It connects
 * to a {@link ServerConnection}.
 */
public class P4Server {
    private static final Logger LOG = Logger.getInstance(P4Server.class);

    private final Project project;
    private final ServerConnection connection;
    private final AlertManager alertManager;
    private final ProjectConfigSource source;

    private boolean valid = true;


    P4Server(@NotNull final Project project, @NotNull final ProjectConfigSource source) {
        this.project = project;
        this.alertManager = AlertManager.getInstance();
        this.source = source;
        //this.clientState = AllClientsState.getInstance().getStateForClient(clientServerId);
        this.connection = ServerConnectionManager.getInstance().getConnectionFor(
                source.getClientServerId(), source.getServerConfig());
        connection.postSetup(project);

        // Do not reload the caches early.
        // TODO figure out if this is the right behavior.
    }


    @NotNull
    public Project getProject() {
        return project;
    }


    public boolean isValid() {
        return valid;
    }

    public boolean isWorkingOnline() {
        return valid && connection.isWorkingOnline();
    }
    public boolean isWorkingOffline() {
        return ! valid || connection.isWorkingOffline();
    }

    /**
     * This does not perform link expansion (get absolute path).  We
     * assume that if you have a file under a path in a link, you want
     * it to be at that location, and not at its real location.
     *
     * @param file file to match against this client's root directories.
     * @return the directory depth at which this file is in the client.  This is the shallowest depth for all
     *      the client roots.  It returns -1 if there is no match.
     */
    int getFilePathMatchDepth(@NotNull FilePath file) throws InterruptedException {
        if (LOG.isDebugEnabled()) {
            LOG.debug("Finding depth for " + file + " in " + getClientName());
        }

        final List<File> inputParts = getPathParts(file);

        boolean hadMatch = false;
        int shallowest = Integer.MAX_VALUE;
        for (List<File> rootParts: getRoots()) {
            if (LOG.isDebugEnabled()) {
                LOG.debug("- checking " + rootParts.get(rootParts.size() - 1));
            }

            if (inputParts.size() < rootParts.size()) {
                // input is at a higher ancestor level than the root parts,
                // so there's no way it could be in this root.

                LOG.debug("-- input is parent of root");

                continue;
            }

            // See if input is under the root.
            // We should be able to just call input.isUnder(configRoot), but
            // that seems to be buggy - it reported that "/a/b/c" was under "/a/b/d".

            final File sameRootDepth = inputParts.get(rootParts.size() - 1);
            if (FileUtil.filesEqual(sameRootDepth, rootParts.get(rootParts.size() - 1))) {
                LOG.debug("-- matched");

                // it's a match.  The input file ancestor path that is
                // at the same directory depth as the config root is the same
                // path.
                if (shallowest > rootParts.size()) {
                    shallowest = rootParts.size();
                    LOG.debug("--- shallowest");
                    hadMatch = true;
                }

                // Redundant - no code after this if block
                //continue;
            } else if (LOG.isDebugEnabled()) {
                LOG.debug("-- not matched " + rootParts.get(rootParts.size() - 1) + " vs " + file + " (" + sameRootDepth + ")");
            }

            // Not under the same path, so it's not a match.  Advance to next root.
        }
        return hadMatch ? shallowest : -1;
    }

    /**
     * The root directories that this perforce client covers in this project.
     * It starts with the client workspace directories, then those are stripped
     * down to just the files in the project, then those are limited by the
     * location of the perforce config directory.
     *
     * @return the actual client root directories used by the workspace,
     *      split by parent directories.
     * @throws InterruptedException
     */
    @NotNull
    public List<List<File>> getRoots() throws InterruptedException {
        // use the ProjectConfigSource as the lowest level these can be under.
        final Set<List<File>> ret = new HashSet<List<File>>();
        final List<VirtualFile> projectRoots = source.getProjectSourceDirs();
        List<List<File>> projectRootsParts = new ArrayList<List<File>>(projectRoots.size());
        for (VirtualFile projectRoot: projectRoots) {
            projectRootsParts.add(getPathParts(FilePathUtil.getFilePath(projectRoot)));
        }

        if (LOG.isDebugEnabled()) {
            LOG.debug("- project roots: " + projectRoots);
            LOG.debug("- client roots: " + getProjectClientRoots());
        }

        // VfsUtilCore.isAncestor seems to bug out at times.
        // Use the File, File version instead.

        for (VirtualFile root : getProjectClientRoots()) {
            final List<File> rootParts = getPathParts(FilePathUtil.getFilePath(root));
            for (List<File> projectRootParts : projectRootsParts) {
                if (projectRootParts.size() >= rootParts.size()) {
                    // projectRoot could be a child of (or is) root
                    if (FileUtil.filesEqual(
                            projectRootParts.get(rootParts.size() - 1),
                            rootParts.get(rootParts.size() - 1))) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("-- projectRoot " + projectRootParts.get(projectRootParts.size() - 1) +
                                    " child of " + root + ", so using the project root");
                        }
                        ret.add(projectRootParts);
                    }
                } else if (rootParts.size() >= projectRootParts.size()) {
                    // root could be a child of (or is) projectRoot
                    if (FileUtil.filesEqual(
                            projectRootParts.get(projectRootParts.size() - 1),
                            rootParts.get(projectRootParts.size() - 1))) {
                        if (LOG.isDebugEnabled()) {
                            LOG.debug("-- root " + root +
                                    " child of " + projectRootParts
                                    .get(projectRootParts.size() - 1) + ", so using the root");
                        }
                        ret.add(rootParts);
                    }
                }
            }

            // If it is not in any project root, then ignore it.
        }

        // The list could be further simplified, but this should
        // be sufficient.  (Simplification: remove directories that
        // are children of existing directories in the list)

        return new ArrayList<List<File>>(ret);
    }


    /**
     * Returns the client workspace roots limited to the project.  These may be
     * wider than what should be used.
     *
     * @return project-based roots
     * @throws InterruptedException
     */
    private List<VirtualFile> getProjectClientRoots() throws InterruptedException {
        return connection.cacheQuery(new CacheQuery<List<VirtualFile>>() {
            @Override
            public List<VirtualFile> query(@NotNull final ClientCacheManager mgr) throws InterruptedException {
                if (isWorkingOnline()) {
                    LOG.debug("working online; loading the client roots cache");
                    connection.query(project, mgr.createWorkspaceRefreshQuery());
                } else {
                    LOG.debug("working offline; using cached files.");
                }
                return mgr.getClientRoots(project, alertManager);
            }
        });
    }


    /**
     *
     * @param files files to grab server status
     * @return null if working disconnected, otherwise the server status of the files.
     */
    @Nullable
    public Map<FilePath, IExtendedFileSpec> getFileStatus(@NotNull final Collection<FilePath> files)
            throws InterruptedException {
        if (files.isEmpty()) {
            return Collections.emptyMap();
        }
        if (isWorkingOffline()) {
            return null;
        }
        List<FilePath> filePathList = new ArrayList<FilePath>(files);
        final Iterator<FilePath> iter = filePathList.iterator();
        while (iter.hasNext()) {
            // Strip out directories, to ensure we have a valid mapping
            final FilePath next = iter.next();
            if (next.isDirectory()) {
                iter.remove();
            }
        }

        final List<IFileSpec> fileSpecs;
        try {
            fileSpecs = FileSpecUtil.getFromFilePaths(filePathList);
        } catch (P4Exception e) {
            alertManager.addWarning(P4Bundle.message("error.file-status.fetch", files), e);
            return null;
        }
        final List<IExtendedFileSpec> extended = connection.query(project, new ServerQuery<List<IExtendedFileSpec>>() {
            @Nullable
            @Override
            public List<IExtendedFileSpec> query(@NotNull final P4Exec2 exec,
                    @NotNull final ClientCacheManager cacheManager,
                    @NotNull final ServerConnection connection, @NotNull final AlertManager alerts)
                    throws InterruptedException {
                try {
                    return exec.getFileStatus(fileSpecs);
                } catch (VcsException e) {
                    alertManager.addWarning(P4Bundle.message("error.file-status.fetch", files), e);
                    return null;
                }
            }
        });
        if (extended == null) {
            return null;
        }

        // FIXME perform better matching

        // should be a 1-to-1 mapping
        if (filePathList.size() != extended.size()) {
            StringBuilder sb = new StringBuilder("did not match ");
            sb.append(filePathList).append(" against [");
            for (IExtendedFileSpec extendedFileSpec: extended) {
                sb
                    .append(" {")
                    .append(extendedFileSpec.getOpStatus()).append(":")
                    .append(extendedFileSpec.getStatusMessage()).append("::")
                    .append(extendedFileSpec.getDepotPath())
                    .append("} ");
            }
            sb.append("]");
            throw new IllegalStateException(sb.toString());
        }

        Map<FilePath, IExtendedFileSpec> ret = new HashMap<FilePath, IExtendedFileSpec>();
        for (int i = 0; i < filePathList.size(); i++) {
            LOG.info("Mapped " + filePathList.get(i) + " to " + extended.get(i));
            ret.put(filePathList.get(i), extended.get(i));
        }
        return ret;
    }


    /**
     * Return all files open for edit (or move, delete, etc) on this client.
     *
     * @return opened files state
     */
    @NotNull
    public Collection<P4FileAction> getOpenFiles() throws InterruptedException {
        return connection.cacheQuery(new CacheQuery<Collection<P4FileAction>>() {
            @Override
            public Collection<P4FileAction> query(@NotNull final ClientCacheManager mgr) throws InterruptedException {
                if (isWorkingOnline()) {
                    connection.query(project, mgr.createFileActionsRefreshQuery());
                }
                return mgr.getCachedOpenFiles();
            }
        });
    }

    /**
     * Needs to be run immediately.
     *
     * @param files
     * @param changelistId
     */
    public void addOrEditFiles(@NotNull final List<VirtualFile> files, final int changelistId) {
        LOG.info("Add or edit to " + changelistId + " files " + files);
        connection.queueUpdates(project, new CreateUpdate() {
            @Override
            public Collection<PendingUpdateState> create(@NotNull final ClientCacheManager mgr) {
                List<PendingUpdateState> updates = new ArrayList<PendingUpdateState>();
                for (VirtualFile file : files) {
                    final PendingUpdateState update = mgr.editFile(FilePathUtil.getFilePath(file), changelistId);
                    if (update != null) {
                        LOG.info("add pending update " + update);
                        updates.add(update);
                    } else {
                        LOG.info("add/edit caused no update: " + file);
                    }
                }
                return updates;
            }
        });
    }


    public Collection<P4ChangeListValue> getOpenChangeLists() throws InterruptedException {
        return connection.cacheQuery(new CacheQuery<Collection<P4ChangeListValue>>() {
            @Override
            public Collection<P4ChangeListValue> query(@NotNull final ClientCacheManager mgr) throws InterruptedException {
                if (isWorkingOnline()) {
                    connection.query(project, mgr.createChangeListRefreshQuery());
                }
                return mgr.getCachedOpenedChanges();
            }
        });
    }


    /**
     * Set by the owning manager.
     *
     * @param isValid valid state
     */
    void setValid(boolean isValid) {
        valid = isValid;
    }


    public void dispose() {
        valid = false;
    }

    @NotNull
    public ClientServerId getClientServerId() {
        return source.getClientServerId();
    }

    @NotNull
    public ServerConfig getServerConfig() {
        return source.getServerConfig();
    }

    @Nullable
    public String getClientName() {
        return source.getClientName();
    }

    /**
     * Check if the given file is ignored by version control.
     *
     * @param fp file or directory to check
     * @return true if ignored, which includes directories.
     */
    public boolean isIgnored(@Nullable final FilePath fp) throws InterruptedException {
        if (fp == null || fp.isDirectory()) {
            return true;
        }
        return connection.cacheQuery(new CacheQuery<Boolean>() {
            @Override
            public Boolean query(@NotNull final ClientCacheManager mgr) throws InterruptedException {
                return mgr.isIgnored(fp);
            }
        });
    }

    @NotNull
    private List<File> getPathParts(@NotNull final FilePath child) {
        List<File> ret = new ArrayList<File>();
        FilePath next = child;
        while (next != null) {
            ret.add(next.getIOFile());
            next = next.getParentPath();
        }
        Collections.reverse(ret);
        return ret;
    }

    /**
     * Fetch the file spec's contents.  If the file does not exist or is deleted,
     * it returns null.  If the filespec is invalid or the server is not connected,
     * an exception is thrown.
     *
     * @param fileSpec file spec to read
     * @return the file contents
     * @throws P4FileException
     * @throws P4DisconnectedException
     */
    @Nullable
    public String loadFileAsString(@NotNull IFileSpec spec)
            throws P4FileException, P4DisconnectedException {
        // FIXME
        throw new IllegalStateException("not implemented");
    }

    @Nullable
    public String loadFileAsString(@NotNull FilePath file, final int rev)
            throws P4FileException, P4DisconnectedException {
        // FIXME
        throw new IllegalStateException("not implemented");
    }

    @Nullable
    public byte[] loadFileAsBytes(@NotNull IFileSpec spec) {
        // FIXME
        throw new IllegalStateException("not implemented");
    }

    @Nullable
    public byte[] loadFileAsBytes(@NotNull FilePath file, final int rev) {
        // FIXME
        throw new IllegalStateException("not implemented");
    }


    @Override
    public String toString() {
        return getClientServerId().toString();
    }
}
