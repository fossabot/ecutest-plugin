/*
 * Copyright (c) 2015-2017 TraceTronic GmbH
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without modification,
 * are permitted provided that the following conditions are met:
 *
 *   1. Redistributions of source code must retain the above copyright notice, this
 *      list of conditions and the following disclaimer.
 *
 *   2. Redistributions in binary form must reproduce the above copyright notice, this
 *      list of conditions and the following disclaimer in the documentation and/or
 *      other materials provided with the distribution.
 *
 *   3. Neither the name of TraceTronic GmbH nor the names of its
 *      contributors may be used to endorse or promote products derived from
 *      this software without specific prior written permission.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT HOLDER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON
 * ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */
package de.tracetronic.jenkins.plugins.ecutest.env;

import hudson.model.InvisibleAction;
import de.tracetronic.jenkins.plugins.ecutest.tool.client.ETClient;

/**
 * Helper invisible action which is used for exchanging information between {@link ETClient}s and other object like
 * {@link ToolEnvContributor}.
 *
 * @author Christian Pönisch <christian.poenisch@tracetronic.de>
 */
public class ToolEnvInvisibleAction extends InvisibleAction {

    private final int toolId;
    private final String toolName;
    private final String toolVersion;
    private final String toolInstallation;
    private final String toolWorkspace;
    private final String toolSettings;
    private final int timeout;
    private final boolean debug;
    private final String lastTbc;
    private final String lastTcf;

    /**
     * Instantiates a new {@link ToolEnvInvisibleAction}.
     *
     * @param toolId
     *            identifies this invisible action and is used as the suffix for the tool related build environment
     *            variables
     * @param toolClient
     *            the tool client holding the relevant information
     */
    public ToolEnvInvisibleAction(final int toolId, final ETClient toolClient) {
        super();
        this.toolId = toolId;
        toolName = toolClient.getToolName();
        toolVersion = toolClient.getVersion();
        toolInstallation = toolClient.getInstallPath();
        toolWorkspace = toolClient.getWorkspaceDir();
        toolSettings = toolClient.getSettingsDir();
        timeout = toolClient.getTimeout();
        debug = toolClient.isDebug();
        lastTbc = toolClient.getLastTbc();
        lastTcf = toolClient.getLastTcf();
    }

    /**
     * @return the tool id
     */
    public int getToolId() {
        return toolId;
    }

    /**
     * @return the tool name
     */
    public String getToolName() {
        return toolName;
    }

    /**
     * @return the tool version
     */
    public String getToolVersion() {
        return toolVersion;
    }

    /**
     * @return the tool installation
     */
    public String getToolInstallation() {
        return toolInstallation;
    }

    /**
     * @return the tool workspace
     */
    public String getToolWorkspace() {
        return toolWorkspace;
    }

    /**
     * @return the tool settings
     */
    public String getToolSettings() {
        return toolSettings;
    }

    /**
     * @return the timeout
     */
    public int getTimeout() {
        return timeout;
    }

    /**
     * @return the debug mode
     */
    public boolean isDebug() {
        return debug;
    }

    /**
     * @return the last loaded TBC file path
     */
    public String getLastTbc() {
        return lastTbc;
    }

    /**
     * @return the last loaded TCF file path
     */
    public String getLastTcf() {
        return lastTcf;
    }
}
