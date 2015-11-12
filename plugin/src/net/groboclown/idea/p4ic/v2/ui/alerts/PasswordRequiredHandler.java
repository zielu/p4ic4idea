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

package net.groboclown.idea.p4ic.v2.ui.alerts;

import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.Project;
import net.groboclown.idea.p4ic.config.ServerConfig;
import net.groboclown.idea.p4ic.server.connection.ClientPasswordConnectionHandler;
import net.groboclown.idea.p4ic.v2.server.connection.CriticalErrorHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Date;

public class PasswordRequiredHandler implements CriticalErrorHandler {
    private static final Logger LOG = Logger.getInstance(PasswordRequiredHandler.class);

    private final Project project;
    private final ServerConfig config;

    public PasswordRequiredHandler(@Nullable final Project project,
            @NotNull ServerConfig config) {
        this.project = project;
        this.config = config;
    }

    @Override
    public void handleError(@NotNull final Date when) {
        LOG.warn("Login problem for " + config);

        if (project != null && project.isDisposed()) {
            return;
        }

        if (! ClientPasswordConnectionHandler.askPassword(project, config)) {
            if (project != null) {
                AbstractErrorHandler.tryConfigChangeFor(project);
            }
        }
    }
}
