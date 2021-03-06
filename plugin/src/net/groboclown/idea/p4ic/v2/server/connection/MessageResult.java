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
import com.intellij.openapi.vcs.FilePath;
import com.intellij.openapi.vcs.VcsException;
import com.perforce.p4java.core.file.IFileOperationResult;
import com.perforce.p4java.core.file.IFileSpec;
import com.perforce.p4java.exception.MessageSeverityCode;
import net.groboclown.idea.p4ic.server.P4StatusMessage;
import org.jetbrains.annotations.NotNull;

import java.util.*;

public class MessageResult<T> {
    private static final Logger LOG = Logger.getInstance(MessageResult.class);


    private final T result;
    private final List<P4StatusMessage> messages;
    private final boolean error;

    public static <T extends IFileOperationResult> MessageResult<List<T>> create(@NotNull  List<T> specs) {
        List<T> results = new ArrayList<T>(specs.size());
        List<P4StatusMessage> messages = new ArrayList<P4StatusMessage>();
        for (T spec : specs) {
            if (P4StatusMessage.isValid(spec)) {
                results.add(spec);
            } else {
                final P4StatusMessage msg = new P4StatusMessage(spec);
                messages.add(msg);
            }
        }
        return new MessageResult<List<T>>(results, messages);
    }


    public static <T extends IFileOperationResult> MessageResult<List<T>> create(@NotNull  List<T> specs,
            boolean markFileNotFoundAsValid) {
        List<T> results = new ArrayList<T>(specs.size());
        List<P4StatusMessage> messages = new ArrayList<P4StatusMessage>();
        for (T spec : specs) {
            if (P4StatusMessage.isValid(spec)) {
                results.add(spec);
            } else if (markFileNotFoundAsValid && P4StatusMessage.isFileNotFoundError(spec)) {
                results.add(spec);
            } else {
                final P4StatusMessage msg = new P4StatusMessage(spec);
                messages.add(msg);
            }
        }
        return new MessageResult<List<T>>(results, messages);
    }


    @NotNull
    public static MessageResult<Collection<FilePath>> createForFilePath(
            @NotNull List<FilePath> originalFiles,
            @NotNull List<IFileSpec> specs,
            boolean markFileNotFoundAsValid) {
        List<FilePath> results = new ArrayList<FilePath>(specs.size());
        List<P4StatusMessage> messages = new ArrayList<P4StatusMessage>();
        Iterator<FilePath> iter = originalFiles.iterator();
        for (IFileSpec spec : specs) {
            if (P4StatusMessage.isValid(spec) ||
                    (markFileNotFoundAsValid && P4StatusMessage.isFileNotFoundError(spec))) {
                if (!iter.hasNext()) {
                    // FIXME this can happen when reverting both sides of the
                    // move operation at the same time.
                    LOG.warn("No direct mapping between files " + originalFiles +
                        " to messages " + specs);
                } else {
                    results.add(iter.next());
                }
            } else {
                if (spec.getSeverityCode() != MessageSeverityCode.E_FATAL) {
                    // advance but don't use the file reference
                    iter.next();
                }
                final P4StatusMessage msg = new P4StatusMessage(spec);
                messages.add(msg);
            }
        }
        return new MessageResult<Collection<FilePath>>(results, messages);
    }


    public MessageResult(final T result, final List<P4StatusMessage> messages) {
        this.result = result;
        this.messages = Collections.unmodifiableList(messages);
        boolean err = false;
        for (P4StatusMessage message : messages) {
            if (message == null || message.isError()) {
                err = true;
            }
        }
        error = err;
    }

    public T getResult() {
        return result;
    }

    @NotNull
    public List<P4StatusMessage> getMessages() {
        return messages;
    }

    public boolean isError() {
        return error;
    }

    @NotNull
    public Collection<? extends VcsException> messagesAsExceptions() {
        return P4StatusMessage.messagesAsErrors(getMessages());
    }

    @NotNull
    @Override
    public String toString() {
        if (isError()) {
            return getMessages().toString();
        }
        return getResult().toString();
    }

}
