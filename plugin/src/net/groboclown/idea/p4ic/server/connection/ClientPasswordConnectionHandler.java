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
package net.groboclown.idea.p4ic.server.connection;

import com.perforce.p4java.PropertyDefs;
import com.perforce.p4java.exception.AccessException;
import com.perforce.p4java.exception.P4JavaException;
import com.perforce.p4java.option.server.LoginOptions;
import com.perforce.p4java.server.IOptionsServer;
import net.groboclown.idea.p4ic.config.ServerConfig;
import net.groboclown.idea.p4ic.server.ConnectionHandler;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Properties;

/**
 * Does not look at the P4CONFIG.  Uses a password for authentication.
 */
public class ClientPasswordConnectionHandler extends ConnectionHandler {
    public static ClientPasswordConnectionHandler INSTANCE = new ClientPasswordConnectionHandler();

    private ClientPasswordConnectionHandler() {
        // stateless utility class
    }

    @Override
    public Properties getConnectionProperties(@NotNull ServerConfig config, @Nullable String clientName) {
        Properties ret = initializeConnectionProperties(config);
        ret.setProperty(PropertyDefs.USER_NAME_KEY, config.getUsername());
        if (clientName != null) {
            ret.setProperty(PropertyDefs.CLIENT_NAME_KEY, clientName);
        }

        // This property key doesn't actually seem to do anything.
        // A real login is still required.
        //char[] password = PasswordStore.getOptionalPasswordFor(config);
        //if (password != null && password.length > 0) {
        //    ret.setProperty(PropertyDefs.PASSWORD_KEY, new String(password));
        //    Arrays.fill(password, (char) 0);
        //}

        return ret;
    }

    @Override
    public boolean isConfigValid(@NotNull ServerConfig config) {
        // This config only uses the fields that are required in the
        // server config.  No additional checks are needed.
        return true;
    }

    @Override
    public void defaultAuthentication(@NotNull IOptionsServer server, @NotNull ServerConfig config, char[] password)
            throws P4JavaException {
        if (password != null && password.length > 0) {
            // If the password is blank, then there's no need for the
            // user to log in; in fact, that wil raise an error by Perforce
            try {
                server.login(new String(password), new LoginOptions(false, true));
            } catch (AccessException ex) {
                if (ex.getMessage().contains("'login' not necessary")) {
                    // ignore login and keep going
                    // TODO tell the caller that the password should be forgotten
                } else {
                    throw ex;
                }
            }
        }
    }

    @Override
    public boolean forcedAuthentication(@NotNull IOptionsServer server, @NotNull ServerConfig config, char[] password) throws P4JavaException {
        if (password != null && password.length > 0) {
            // If the password is blank, then there's no need for the
            // user to log in; in fact, that wil raise an error by Perforce
            server.login(new String(password), new LoginOptions(false, true));
            return true;
        } else {
            return false;
        }
    }
}
