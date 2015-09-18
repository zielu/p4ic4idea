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
package net.groboclown.idea.p4ic.v2.server.connection;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.vcs.VcsConnectionProblem;
import com.intellij.openapi.vcs.VcsException;
import com.perforce.p4java.client.IClient;
import com.perforce.p4java.client.IClientSummary;
import com.perforce.p4java.core.*;
import com.perforce.p4java.core.file.IExtendedFileSpec;
import com.perforce.p4java.core.file.IFileAnnotation;
import com.perforce.p4java.core.file.IFileRevisionData;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.*;
import com.perforce.p4java.impl.generic.core.Changelist;
import com.perforce.p4java.impl.generic.core.file.FilePath;
import com.perforce.p4java.option.changelist.SubmitOptions;
import com.perforce.p4java.option.client.IntegrateFilesOptions;
import com.perforce.p4java.option.client.SyncOptions;
import com.perforce.p4java.option.server.GetExtendedFilesOptions;
import com.perforce.p4java.option.server.GetFileAnnotationsOptions;
import com.perforce.p4java.option.server.GetFileContentsOptions;
import com.perforce.p4java.option.server.OpenedFilesOptions;
import com.perforce.p4java.server.IOptionsServer;
import net.groboclown.idea.p4ic.P4Bundle;
import net.groboclown.idea.p4ic.config.ServerConfig;
import net.groboclown.idea.p4ic.server.*;
import net.groboclown.idea.p4ic.server.exceptions.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.TimeoutException;

import static net.groboclown.idea.p4ic.server.P4StatusMessage.getErrors;

/**
 * A project-aware command executor against the server/client.
 */
public class P4Exec2 {
    private static final Logger LOG = Logger.getInstance(P4Exec2.class);

    // Default list of status, in case of a problem.
    public static final List<String> DEFAULT_JOB_STATUS = Arrays.asList(
            "open", "suspended", "closed"
    );

    private final Project project;
    private final ClientExec exec;

    private final Object sync = new Object();

    private boolean disposed = false;


    public P4Exec2(@NotNull Project project, @NotNull ClientExec exec) {
        this.project = project;
        this.exec = exec;
    }


    @Nullable
    public String getClientName() {
        return exec.getClientName();
    }


    @NotNull
    public String getUsername() {
        return getServerConfig().getUsername();
    }


    @NotNull
    public ServerConfig getServerConfig() {
        return exec.getServerConfig();
    }

    @NotNull
    public Project getProject() {
        return project;
    }


    public void dispose() {
        // in the future, this may clean up open connections
        synchronized (sync) {
            disposed = true;
            // Note: this does NOT dispose the ClientExec; this class borrows that.
        }
    }


    @Override
    protected void finalize() throws Throwable {
        dispose();
        super.finalize();
    }


    public List<IClientSummary> getClientsForUser() throws VcsConnectionProblem, CancellationException {
        try {
            return exec.runWithServer(project, new ClientExec.WithServer<List<IClientSummary>>() {
                @Override
                public List<IClientSummary> run(@NotNull IOptionsServer server, @NotNull ClientExec.ServerCount count)
                        throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException {
                    count.invoke("getClients");
                    List<IClientSummary> ret = server.getClients(getUsername(), null, 0);
                    assert ret != null;
                    return ret;
                }
            });
        } catch (VcsConnectionProblem e) {
            throw e;
        } catch (VcsException e) {
            LOG.warn("Raised a general VCS exception", e);
            throw new P4DisconnectedException(e);
        }
    }


    @NotNull
    public IClient getClient() throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<IClient>() {
            @Override
            public IClient run(@NotNull IOptionsServer server, @NotNull IClient client, @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                client.setServer(null);
                return client;
            }
        });
    }


    @Nullable
    public IChangelist getChangelist(final int id)
            throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<IChangelist>() {
            @Override
            public IChangelist run(@NotNull IOptionsServer server, @NotNull IClient client, @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                count.invoke("getChangelist");
                IChangelist cl = server.getChangelist(id);
                if (id != cl.getId()) {
                    LOG.warn("Perforce Java API error: returned changelist with id " + cl.getId() + " when requested " + id);
                    cl.setId(id);
                }

                // The server connection cannot leave this context.
                cl.setServer(null);
                if (cl instanceof Changelist) {
                    ((Changelist) cl).setServerImpl(null);
                }

                return cl;
            }
        });

    }


    /**
     *
     * @param openedSpecs query file specs, expected to be a "..." style.
     * @param fast runs with the "-s" argument, which means the revision and file type is not returned.
     * @return messages and results
     * @throws VcsException
     * @throws CancellationException
     */
    @NotNull
    public MessageResult<List<IFileSpec>> loadOpenedFiles(@NotNull final List<IFileSpec> openedSpecs, final boolean fast)
            throws VcsException, CancellationException {
        LOG.debug("loading open files " + openedSpecs);
        return exec.runWithClient(project, new ClientExec.WithClient<MessageResult<List<IFileSpec>>>() {
            @Override
            public MessageResult<List<IFileSpec>> run(@NotNull final IOptionsServer server, @NotNull final IClient client,
                    @NotNull final ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException,
                    P4Exception {
                OpenedFilesOptions options = new OpenedFilesOptions(
                        false, // all clients
                        client.getName(),
                        -1,
                        null,
                        -1).setShortOutput(fast);
                final List<IFileSpec> files = client.openedFiles(openedSpecs, options);
                return MessageResult.create(files);
            }
        });
    }


    @NotNull
    public List<P4StatusMessage> revertFiles(@NotNull final List<IFileSpec> files)
            throws VcsException, CancellationException {
        if (files.isEmpty()) {
            return Collections.emptyList();
        }
        return exec.runWithClient(project, new ClientExec.WithClient<List<P4StatusMessage>>() {
            @Override
            public List<P4StatusMessage> run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException {
                count.invoke("revertFiles");
                List<IFileSpec> ret = client.revertFiles(files, false, -1, false, false);
                return getErrors(ret);
            }
        });
    }


    @NotNull
    public List<P4StatusMessage> integrateFiles(@NotNull final IFileSpec src,
            @NotNull final IFileSpec target, final int changelistId, final boolean dontCopyToClient)
            throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<List<P4StatusMessage>>() {
            @Override
            public List<P4StatusMessage> run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                List<IFileSpec> ret = new ArrayList<IFileSpec>();
                count.invoke("integrateFiles");
                ret.addAll(client.integrateFiles(
                        src, target,
                        null,
                        new IntegrateFilesOptions(
                                changelistId,
                                false, // bidirectionalInteg,
                                true, // integrateAroundDeletedRevs
                                false, // rebranchSourceAfterDelete,
                                true, // deleteTargetAfterDelete,
                                true, // integrateAllAfterReAdd,
                                false, // branchResolves,
                                false, // deleteResolves,
                                false, // skipIntegratedRevs,
                                true, // forceIntegration,
                                false, // useHaveRev,
                                true, // doBaselessMerge,
                                false, // displayBaseDetails,
                                false, // showActionsOnly,
                                false, // reverseMapping,
                                true, // propagateType,
                                dontCopyToClient,
                                0// maxFiles
                        )));
                return getErrors(ret);
            }
        });
    }


    @NotNull
    public List<P4StatusMessage> addFiles(@NotNull List<IFileSpec> files,
            final int changelistId) throws VcsException, CancellationException {
        // Adding files with wildcards in their name:
        // To add files with filenames that contain wildcard characters, specify
        // the -f flag. Filenames that contain the special characters '@', '#',
        // '%' or '*' are reformatted to encode the characters using ASCII
        // hexadecimal representation.  After the files are added, you must
        // refer to them using the reformatted file name, because Perforce
        // does not recognize the local filesystem name.
        //
        // (ASCII hexadecimal representation: "a@b" is turned into
        // "a%40b")
        //
        // Note that this escaping is addressed universally in all the
        // access classes.  Unfortunately, this means that all the filespecs
        // coming into this method are already escaped.  So, we'll have to
        // undo that.

        final List<IFileSpec> unescapedFiles = new ArrayList<IFileSpec>(files.size());
        for (IFileSpec file: files) {
            String original = file.getOriginalPathString();
            if (original != null) {
                File f = new File(FileSpecUtil.unescapeP4Path(original));
                if (! f.exists()) {
                    throw new P4Exception(P4Bundle.message("error.add.file-not-found", f));
                }
                // We must set the original path the hard way, to avoid the FilePath
                // stripping off the stuff after the '#' or '@', if it was escaped originally.
                file.setPath(new FilePath(FilePath.PathType.ORIGINAL, f.getAbsolutePath(), true));
            } else if (file.getLocalPathString() == null) {
                throw new IllegalStateException(P4Bundle.message("error.add.no-local-file", file));
            }
            unescapedFiles.add(file);
        }

        // debug for issue #6
        //LOG.info("Opening for add: " + unescapedFiles, new Throwable());

        return exec.runWithClient(project, new ClientExec.WithClient<List<P4StatusMessage>>() {
            @Override
            public List<P4StatusMessage> run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                List<IFileSpec> ret = new ArrayList<IFileSpec>();
                count.invoke("addFiles");
                ret.addAll(client.addFiles(unescapedFiles, false, changelistId, null,
                        // Use wildcards = true to allow file names that contain wildcards
                        true));
                return getErrors(ret);
            }
        });
    }


    @NotNull
    public List<P4StatusMessage> editFiles(@NotNull final List<IFileSpec> files,
            final int changelistId) throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<List<P4StatusMessage>>() {
            @Override
            public List<P4StatusMessage> run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                List<IFileSpec> ret = new ArrayList<IFileSpec>();
                // this allows for wildcard characters (*, #, @) in the file name,
                // if they were properly escaped.
                count.invoke("editFiles");
                ret.addAll(client.editFiles(files,
                    false, false, changelistId, null));
                return getErrors(ret);
            }
        });
    }


    public void updateChangelistDescription(final int changelistId,
            @NotNull final String description) throws VcsException, CancellationException {
        exec.runWithClient(project, new ClientExec.WithClient<Void>() {
            @Override
            public Void run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException {
                // Note that, because the server connection can be closed after an
                // invocation, we must perform all the changelist updates within this
                // call, and we can't go outside this method.

                count.invoke("getChangelist");
                IChangelist changelist = server.getChangelist(changelistId);
                if (changelist != null && changelist.getStatus() == ChangelistStatus.PENDING) {
                    changelist.setDescription(description);
                    count.invoke("changelist.update");
                    changelist.update();
                }
                return null;
            }
        });
    }


    @NotNull
    public List<IChangelistSummary> getPendingClientChangelists()
            throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<List<IChangelistSummary>>() {
            @Override
            public List<IChangelistSummary> run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count) throws P4JavaException {
                count.invoke("getChangelists");
                return server.getChangelists(0,
                        Collections.<IFileSpec>emptyList(),
                        client.getName(), null, false, false, true, true);
            }
        });
    }


    @NotNull
    public IChangelist createChangeList(@NotNull final String comment)
            throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<IChangelist>() {
            @Override
            public IChangelist run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                Changelist newChange = new Changelist();
                newChange.setUsername(getUsername());
                newChange.setClientId(client.getName());
                newChange.setDescription(comment);

                count.invoke("createChangelist");
                IChangelist ret = client.createChangelist(newChange);
                if (ret.getId() <= 0) {
                    throw new P4Exception(P4Bundle.message("error.changelist.add.invalid-id", newChange.getId()));
                }

                // server cannot leave this method
                ret.setServer(null);

                return ret;
            }
        });
    }


    @Nullable
    public List<IFileSpec> getFileSpecsInChangelist(final int id)
            throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<List<IFileSpec>>() {
            @Override
            public List<IFileSpec> run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                count.invoke("getChangelist");
                IChangelist cl = server.getChangelist(id);
                if (cl == null) {
                    return null;
                }
                count.invoke("changelist.getFiles");
                return cl.getFiles(false);
            }
        });
    }


    /**
     * Get the state of the file specs.  This should only be invoked when the {@code files} references
     * only files, not glob patterns.  The {@code files} must be fully escaped IFileSpec objects.
     *
     * @param files
     * @return
     * @throws VcsException
     * @throws CancellationException
     */
    @NotNull
    public List<IExtendedFileSpec> getFileStatus(@NotNull final List<IFileSpec> files)
            throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<List<IExtendedFileSpec>>() {
            @Override
            public List<IExtendedFileSpec> run(@NotNull final IOptionsServer server,
                    @NotNull final IClient client, @NotNull final ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException,
                    P4Exception {
                count.invoke("getFileStatus");
                GetExtendedFilesOptions opts = new GetExtendedFilesOptions(
                    "-m", Integer.toString(files.size()));
                final List<IExtendedFileSpec> specs = server.getExtendedFiles(files, opts);

                // Make sure the specs are unescaped on return
                for (IExtendedFileSpec spec: specs) {
                    spec.setDepotPath(FileSpecUtil.unescapeP4PathNullable(spec.getDepotPathString()));
                    spec.setClientPath(FileSpecUtil.unescapeP4PathNullable(spec.getClientPathString()));
                    spec.setOriginalPath(FileSpecUtil.unescapeP4PathNullable(spec.getOriginalPathString()));
                    spec.setLocalPath(FileSpecUtil.unescapeP4PathNullable(spec.getLocalPathString()));
                    spec.setServer(null);
                }
                return specs;
            }
        });
    }


    @NotNull
    public List<P4StatusMessage> reopenFiles(@NotNull final List<IFileSpec> files,
            final int newChangelistId, @Nullable final String newFileType) throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<List<P4StatusMessage>>() {
            @Override
            public List<P4StatusMessage> run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                count.invoke("reopenFiles");
                return getErrors(client.reopenFiles(files, newChangelistId, newFileType));
            }
        });
    }


    @Nullable
    public String deletePendingChangelist(final int changelistId)
            throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<String>() {
            @Override
            public String run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                count.invoke("deletePendingChangelist");
                return server.deletePendingChangelist(changelistId);
            }
        });
    }

    @NotNull
    public List<P4StatusMessage> deleteFiles(@NotNull final List<IFileSpec> deleted,
            final int changelistId, final boolean deleteLocalFiles) throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<List<P4StatusMessage>>() {
            @Override
            public List<P4StatusMessage> run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                count.invoke("deleteFiles");
                return getErrors(client.deleteFiles(deleted, changelistId, deleteLocalFiles));
            }
        });
    }


    @NotNull
    public byte[] loadFile(@NotNull final IFileSpec spec)
            throws VcsException, CancellationException, IOException {
        return exec.runWithClient(project, new ClientExec.WithClient<byte[]>() {
            @Override
            public byte[] run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                ByteArrayOutputStream baos = new ByteArrayOutputStream();
                GetFileContentsOptions fileContentsOptions = new GetFileContentsOptions(false, true);
                // setting "don't annotate files" to true means we ignore the revision
                fileContentsOptions.setDontAnnotateFiles(false);
                count.invoke("getFileContents");
                InputStream inp = server.getFileContents(Collections.singletonList(spec),
                        fileContentsOptions);

                try {
                    byte[] buff = new byte[4096];
                    int len;
                    while ((len = inp.read(buff, 0, 4096)) > 0) {
                        baos.write(buff, 0, len);
                    }
                } finally {
                    // Note: be absolutely sure to close the InputStream that is returned.
                    inp.close();
                }
                return baos.toByteArray();
            }
        });
    }


    @NotNull
    public Map<IFileSpec, List<IFileRevisionData>> getRevisionHistory(
            @NotNull final List<IFileSpec> depotFiles, final int maxRevisions)
            throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<Map<IFileSpec, List<IFileRevisionData>>>() {
            @Override
            public Map<IFileSpec, List<IFileRevisionData>> run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                count.invoke("getRevisionHistory");

                return server.getRevisionHistory(depotFiles, maxRevisions, false, true, true, false);
            }
        });
    }


    @NotNull
    public List<P4StatusMessage> moveFile(@NotNull final IFileSpec source,
            @NotNull final IFileSpec target, final int changelistId, final boolean leaveLocalFiles)
            throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<List<P4StatusMessage>>() {
            @Override
            public List<P4StatusMessage> run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                count.invoke("moveFile");
                return getErrors(server.moveFile(changelistId, false, leaveLocalFiles, null,
                        source, target));
            }
        });
    }

    @NotNull
    public List<IFileSpec> synchronizeFiles(@NotNull final List<IFileSpec> files,
            final boolean forceSync)
            throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<List<IFileSpec>>() {
            @Override
            public List<IFileSpec> run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                count.invoke("sync");
                final List<IFileSpec> ret = client.sync(files, new SyncOptions(forceSync, false, false, false, true));
                if (ret == null) {
                    return Collections.emptyList();
                }
                return ret;
            }
        });
    }

    @NotNull
    public List<IFileAnnotation> getAnnotationsFor(@NotNull final List<IFileSpec> specs)
            throws VcsException, CancellationException {
        return exec.runWithClient(project,
                new ClientExec.WithClient<List<IFileAnnotation>>() {
            @Override
            public List<IFileAnnotation> run(@NotNull IOptionsServer server, @NotNull IClient client,
                    @NotNull ClientExec.ServerCount count) throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                count.invoke("getFileAnnotations");
                return server.getFileAnnotations(specs,
                        new GetFileAnnotationsOptions(
                                false, // allResults
                                false, // useChangeNumbers
                                false, // followBranches
                                false, // ignoreWhitespaceChanges
                                false, // ignoreWhitespace
                                true, // ignoreLineEndings
                                false // followAllIntegrations
                        ));
            }
        });
    }

    public void getServerInfo() throws VcsException, CancellationException {
        exec.runWithServer(project, new ClientExec.WithServer<Void>() {
            @Override
            public Void run(@NotNull IOptionsServer server, @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                count.invoke("getServerInfo");
                server.getServerInfo();
                return null;
            }
        });
    }

    @NotNull
    public List<P4StatusMessage> submit(final int changelistId,
            @NotNull final List<String> jobIds,
            @Nullable final String jobStatus) throws VcsException, CancellationException {
        return exec.runWithClient(project, new ClientExec.WithClient<List<P4StatusMessage>>() {
            @Override
            public List<P4StatusMessage> run(@NotNull final IOptionsServer server, @NotNull final IClient client,
                    @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                count.invoke("getChangelist");
                final IChangelist changelist = server.getChangelist(changelistId);
                SubmitOptions options = new SubmitOptions();
                options.setJobIds(jobIds);
                if (jobStatus != null) {
                    options.setJobStatus(jobStatus);
                }
                count.invoke("submit");
                return getErrors(changelist.submit(options));
            }
        });
    }

    /*
    @NotNull
    public Collection<P4FileInfo> revertUnchangedFiles(final Project project,
            @NotNull final List<IFileSpec> fileSpecs, final int changeListId,
            @NotNull final List<P4StatusMessage> errors, @NotNull FileInfoCache fileInfoCache)
            throws VcsException, CancellationException {
        final List<IFileSpec> reverted = runWithClient(project, new WithClient<List<IFileSpec>>() {
            @Override
            public List<IFileSpec> run(@NotNull final IOptionsServer server, @NotNull final IClient client, @NotNull final ServerCount count) throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                RevertFilesOptions options = new RevertFilesOptions(false, changeListId, true, false);
                count.invoke("revertFiles");
                final List<IFileSpec> results = client.revertFiles(fileSpecs, options);
                List<IFileSpec> reverted = new ArrayList<IFileSpec>(results.size());
                for (IFileSpec spec : results) {
                    if (P4StatusMessage.isErrorStatus(spec)) {
                        final P4StatusMessage msg = new P4StatusMessage(spec);
                        if (!msg.isFileNotFoundError()) {
                            errors.add(msg);
                        }
                    } else {
                        LOG.info("Revert for spec " + spec + ": action " + spec.getAction());
                        reverted.add(spec);
                    }
                }
                LOG.info("reverted specs: " + reverted);
                LOG.info("reverted errors: " + errors);
                return reverted;
            }
        });
        return runWithClient(project, new P4FileInfo.FstatLoadSpecs(reverted, fileInfoCache));
    }
    */


    /**
     * Returns the list of job status used by the server.  If there was a
     * problem reading the list, then the default list is returned instead.
     *
     * @return list of job status used by the server
     * @throws CancellationException
     */
    public List<String> getJobStatusValues() throws CancellationException {
        try {
            return exec.runWithServer(project, new ClientExec.WithServer<List<String>>() {
                @Override
                public List<String> run(@NotNull final IOptionsServer server, @NotNull ClientExec.ServerCount count)
                        throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                    count.invoke("getJobSpec");
                    final IJobSpec spec = server.getJobSpec();
                    final Map<String, List<String>> values = spec.getValues();
                    if (values != null && values.containsKey("Status")) {
                        return values.get("Status");
                    }
                    LOG.info("No Status values listed in job spec");
                    return DEFAULT_JOB_STATUS;
                }
            });
        } catch (VcsException e) {
            LOG.info("Could not access the job spec", e);
            return DEFAULT_JOB_STATUS;
        }
    }


    /**
     *
     * @param changelistId Perforce changelist id
     * @return null if there is no such changelist.
     * @throws VcsException
     * @throws CancellationException
     */
    @Nullable
    public Collection<String> getJobIdsForChangelist(final int changelistId) throws VcsException, CancellationException {
        if (changelistId <= IChangelist.DEFAULT) {
            // These changelists can never have a job associated with them.
            // Additionally, actually inquiring about the jobs will result
            // in returning *every job in Perforce*, which could potentially
            // be HUGE.
            return Collections.emptyList();
        }
        return exec.runWithServer(project, new ClientExec.WithServer<List<String>>() {
            @Override
            public List<String> run(@NotNull final IOptionsServer server, @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException {
                count.invoke("getChangelist");
                final IChangelist changelist = server.getChangelist(changelistId);
                if (changelist == null) {
                    return null;
                }

                count.invoke("getJobIds");
                final List<String> jobIds = changelist.getJobIds();
                LOG.debug("Changelist " + changelistId + " has " + jobIds.size() + " jobs");
                return jobIds;
            }
        });
    }


    @Nullable
    public P4Job getJobForId(@NotNull final String jobId) throws VcsException, CancellationException {
        return exec.runWithServer(project, new ClientExec.WithServer<P4Job>() {
            @Override
            public P4Job run(@NotNull final IOptionsServer server, @NotNull ClientExec.ServerCount count)
                    throws P4JavaException, IOException, InterruptedException, TimeoutException, URISyntaxException, P4Exception {
                P4Job job = null;
                LOG.debug("Loading information for job " + jobId);
                IJob iJob;
                count.invoke("getJob");
                try {
                    iJob = server.getJob(jobId);
                    job = iJob == null ? null : new P4Job(iJob);
                } catch (RequestException re) {
                    // Bug #33
                    LOG.warn(re);
                    if (re.getMessage().contains("Syntax error in")) {
                        job = new P4Job(jobId, P4Bundle.message("error.job.parse", jobId, re.getMessage()));
                    }
                }
                return job;
            }
        });
    }
}