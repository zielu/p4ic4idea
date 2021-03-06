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
package net.groboclown.idea.p4ic.ui.connection;

import com.intellij.openapi.fileChooser.FileChooserDescriptor;
import com.intellij.openapi.ui.TextFieldWithBrowseButton;
import com.intellij.openapi.util.io.FileUtil;
import com.intellij.uiDesigner.core.GridConstraints;
import com.intellij.uiDesigner.core.GridLayoutManager;
import com.perforce.p4java.server.IServerAddress.Protocol;
import net.groboclown.idea.p4ic.P4Bundle;
import net.groboclown.idea.p4ic.config.ManualP4Config;
import net.groboclown.idea.p4ic.config.P4Config;
import net.groboclown.idea.p4ic.config.P4Config.ConnectionMethod;
import net.groboclown.idea.p4ic.config.P4ConfigUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import javax.swing.event.DocumentEvent;
import javax.swing.event.DocumentListener;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.ResourceBundle;

public class AuthTicketConnectionPanel implements ConnectionPanel {
    private JTextField myPort;
    private JTextField myUsername;
    private TextFieldWithBrowseButton myAuthTicket;
    private JPanel myRootPanel;
    private JLabel myTrustFingerprintLabel;
    private JTextField myTrustFingerprint;

    public AuthTicketConnectionPanel() {
        myAuthTicket.addActionListener(new ActionListener() {
            @Override
            public void actionPerformed(ActionEvent e) {
                //validateAuthTicket();
            }
        });
        myAuthTicket.addBrowseFolderListener(
                P4Bundle.message("configuration.connection-choice.picker.authticket"),
                P4Bundle.message("configuration.authticket.chooser"),
                null, new FileChooserDescriptor(true, false, false, false, false, false));
        myTrustFingerprintLabel.setEnabled(false);
        myTrustFingerprint.setEnabled(false);
        myTrustFingerprint.setEditable(false);
        myPort.getDocument().addDocumentListener(new DocumentListener() {
            @Override
            public void insertUpdate(final DocumentEvent e) {
                changedUpdate(e);
            }

            @Override
            public void removeUpdate(final DocumentEvent e) {
                changedUpdate(e);
            }

            @Override
            public void changedUpdate(final DocumentEvent e) {
                final boolean isSecure = isSecureProtocolPort();
                myTrustFingerprintLabel.setEnabled(isSecure);
                myTrustFingerprint.setEnabled(isSecure);
                myTrustFingerprint.setEditable(isSecure);
            }
        });
    }

    @Override
    public boolean isModified(@NotNull P4Config config) {
        if (P4ConfigUtil.isPortModified(config, myPort.getText())) {
            return true;
        }
        if (config.getUsername() == null) {
            if (getUsername() != null) {
                return true;
            }
        } else {
            if (!config.getUsername().equals(getUsername())) {
                return true;
            }
        }
        if (config.getAuthTicketPath() == null) {
            if (getAuthTicket() != null) {
                return true;
            }
        } else {
            String ticket = getAuthTicket();
            if (ticket == null) {
                return true;
            }
            if (!FileUtil.filesEqual(new File(ticket), new File(config.getAuthTicketPath()))) {
                return true;
            }
        }
        if (config.getServerFingerprint() == null) {
            if (getServerFingerprint() != null) {
                return true;
            }
        } else {
            String fingerprint = getServerFingerprint();
            if (fingerprint == null) {
                return true;
            }
            if (!fingerprint.equalsIgnoreCase(config.getServerFingerprint())) {
                return true;
            }
        }

        return false;
    }

    @Override
    public String getName() {
        return P4Bundle.message("configuration.connection-choice.picker.auth-ticket");
    }

    @Override
    public String getDescription() {
        return P4Bundle.message("connection.auth-ticket.description");
    }

    @Override
    public ConnectionMethod getConnectionMethod() {
        return ConnectionMethod.AUTH_TICKET;
    }

    @Override
    public void loadSettingsIntoGUI(@NotNull P4Config config) {
        myUsername.setText(config.getUsername());
        myPort.setText(P4ConfigUtil.toFullPort(config.getProtocol(), config.getPort()));
        setAuthTicket(config.getAuthTicketPath());
        myTrustFingerprint.setText(config.getServerFingerprint());
    }

    @Override
    public void saveSettingsToConfig(@NotNull ManualP4Config config) {
        config.setUsername(getUsername());
        config.setProtocol(P4ConfigUtil.getProtocolFromPort(myPort.getText()));
        config.setPort(P4ConfigUtil.getSimplePortFromPort(myPort.getText()));
        config.setAuthTicketPath(getAuthTicket());
        config.setServerFingerprint(getServerFingerprint());
    }


    private void setAuthTicket(@Nullable String ticket) {
        if (ticket == null || ticket.length() <= 0) {
            ticket = P4ConfigUtil.getDefaultTicketFile().getAbsolutePath();
        }
        myAuthTicket.setText(ticket);
        //validateAuthTicket();
    }


    @Nullable
    private String getAuthTicket() {
        String ticket = myAuthTicket.getText();
        if (ticket == null || ticket.length() <= 0) {
            return null;
        }
        return ticket;
    }


    @Nullable
    private String getUsername() {
        String ret = myUsername.getText();
        if (ret == null || ret.length() <= 0) {
            return null;
        }
        return ret.trim();
    }


    @Nullable
    private String getServerFingerprint() {
        if (myTrustFingerprint.isEnabled()) {
            String text = myTrustFingerprint.getText();
            if (text != null && text.length() > 0) {
                return text.trim();
            }
        }
        return null;
    }


    private boolean isSecureProtocolPort() {
        final Protocol protocol =
                P4ConfigUtil.getProtocolFromPort(myPort.getText());
        return (protocol != null && protocol.isSecure());
    }


    {
// GUI initializer generated by IntelliJ IDEA GUI Designer
// >>> IMPORTANT!! <<<
// DO NOT EDIT OR ADD ANY CODE HERE!
        $$$setupUI$$$();
    }

    /**
     * Method generated by IntelliJ IDEA GUI Designer
     * >>> IMPORTANT!! <<<
     * DO NOT edit this method OR call it in your code!
     *
     * @noinspection ALL
     */
    private void $$$setupUI$$$() {
        myRootPanel = new JPanel();
        myRootPanel.setLayout(new BorderLayout(0, 0));
        final JPanel panel1 = new JPanel();
        panel1.setLayout(new GridLayoutManager(4, 2, new Insets(0, 0, 0, 0), -1, -1));
        myRootPanel.add(panel1, BorderLayout.CENTER);
        panel1.setBorder(BorderFactory.createTitledBorder(BorderFactory.createLineBorder(Color.black),
                ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle")
                        .getString("configuration.connection.auth-ticket")));
        final JLabel label1 = new JLabel();
        this.$$$loadLabelText$$$(label1,
                ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.port"));
        panel1.add(label1, new GridConstraints(0, 0, 1, 1, GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JLabel label2 = new JLabel();
        this.$$$loadLabelText$$$(label2,
                ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.username"));
        panel1.add(label2, new GridConstraints(1, 0, 1, 1, GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        final JLabel label3 = new JLabel();
        this.$$$loadLabelText$$$(label3,
                ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.auth-ticket"));
        panel1.add(label3, new GridConstraints(2, 0, 1, 1, GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE,
                GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        myAuthTicket = new TextFieldWithBrowseButton();
        myAuthTicket.setFocusable(true);
        myAuthTicket.setToolTipText(ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle")
                .getString("configuration.p4config.chooser"));
        panel1.add(myAuthTicket,
                new GridConstraints(2, 1, 1, 1, GridConstraints.ANCHOR_CENTER, GridConstraints.FILL_HORIZONTAL,
                        GridConstraints.SIZEPOLICY_CAN_SHRINK | GridConstraints.SIZEPOLICY_CAN_GROW,
                        GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0, false));
        myTrustFingerprintLabel = new JLabel();
        myTrustFingerprintLabel.setEnabled(false);
        this.$$$loadLabelText$$$(myTrustFingerprintLabel, ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle")
                .getString("configuration.connection.trust-fingerprint"));
        panel1.add(myTrustFingerprintLabel,
                new GridConstraints(3, 0, 1, 1, GridConstraints.ANCHOR_EAST, GridConstraints.FILL_NONE,
                        GridConstraints.SIZEPOLICY_FIXED, GridConstraints.SIZEPOLICY_FIXED, null, null, null, 0,
                        false));
        myUsername = new JTextField();
        myUsername.setToolTipText(ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle")
                .getString("configuration.username.tooltip"));
        panel1.add(myUsername,
                new GridConstraints(1, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                        GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null,
                        new Dimension(150, -1), null, 0, false));
        myPort = new JTextField();
        myPort.setToolTipText(
                ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle").getString("configuration.port.tooltip"));
        panel1.add(myPort, new GridConstraints(0, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null, new Dimension(150, -1),
                null, 0, false));
        myTrustFingerprint = new JTextField();
        myTrustFingerprint.setEditable(false);
        myTrustFingerprint.setEnabled(false);
        myTrustFingerprint.setText("");
        myTrustFingerprint.setToolTipText(ResourceBundle.getBundle("net/groboclown/idea/p4ic/P4Bundle")
                .getString("configuration.fingerprint.tooltip"));
        panel1.add(myTrustFingerprint,
                new GridConstraints(3, 1, 1, 1, GridConstraints.ANCHOR_WEST, GridConstraints.FILL_HORIZONTAL,
                        GridConstraints.SIZEPOLICY_WANT_GROW, GridConstraints.SIZEPOLICY_FIXED, null,
                        new Dimension(150, -1), null, 0, false));
        label1.setLabelFor(myPort);
        label2.setLabelFor(myUsername);
        label3.setLabelFor(myAuthTicket);
        myTrustFingerprintLabel.setLabelFor(myTrustFingerprint);
    }

    /**
     * @noinspection ALL
     */
    private void $$$loadLabelText$$$(JLabel component, String text) {
        StringBuffer result = new StringBuffer();
        boolean haveMnemonic = false;
        char mnemonic = '\0';
        int mnemonicIndex = -1;
        for (int i = 0; i < text.length(); i++) {
            if (text.charAt(i) == '&') {
                i++;
                if (i == text.length()) {
                    break;
                }
                if (!haveMnemonic && text.charAt(i) != '&') {
                    haveMnemonic = true;
                    mnemonic = text.charAt(i);
                    mnemonicIndex = result.length();
                }
            }
            result.append(text.charAt(i));
        }
        component.setText(result.toString());
        if (haveMnemonic) {
            component.setDisplayedMnemonic(mnemonic);
            component.setDisplayedMnemonicIndex(mnemonicIndex);
        }
    }

    /**
     * @noinspection ALL
     */
    public JComponent $$$getRootComponent$$$() {
        return myRootPanel;
    }
}
