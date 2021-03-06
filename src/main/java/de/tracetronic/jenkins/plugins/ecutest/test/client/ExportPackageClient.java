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
package de.tracetronic.jenkins.plugins.ecutest.test.client;

import hudson.FilePath;
import hudson.Launcher;
import hudson.model.TaskListener;
import hudson.remoting.Callable;

import java.io.IOException;

import jenkins.security.MasterToSlaveCallable;

import com.cloudbees.plugins.credentials.common.StandardUsernamePasswordCredentials;

import de.tracetronic.jenkins.plugins.ecutest.ETPlugin.ToolVersion;
import de.tracetronic.jenkins.plugins.ecutest.log.TTConsoleLogger;
import de.tracetronic.jenkins.plugins.ecutest.test.config.ExportPackageAttributeConfig;
import de.tracetronic.jenkins.plugins.ecutest.test.config.ExportPackageConfig;
import de.tracetronic.jenkins.plugins.ecutest.test.config.TMSConfig;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComClient;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComException;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComProperty;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.TestManagement;

/**
 * Client to export ECU-TEST packages via COM interface.
 *
 * @author Christian Pönisch <christian.poenisch@tracetronic.de>
 */
public class ExportPackageClient extends AbstractTMSClient {

    /**
     * Defines the minimum required ECU-TEST version for this client to work properly.
     */
    private static final ToolVersion ET_MIN_VERSION = new ToolVersion(6, 6, 0, 0);

    private final TMSConfig exportConfig;

    /**
     * Instantiates a new {@link ExportPackageClient}.
     *
     * @param exportConfig
     *            the export configuration
     */
    public ExportPackageClient(final TMSConfig exportConfig) {
        this.exportConfig = exportConfig;
    }

    /**
     * @return the export package configuration
     */
    public TMSConfig getExportConfig() {
        return exportConfig;
    }

    /**
     * Exports a package according to given export configuration.
     *
     * @param workspace
     *            the workspace
     * @param launcher
     *            the launcher
     * @param listener
     *            the listener
     * @return {@code true} if successful, {@code false} otherwise
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    public boolean exportPackage(final FilePath workspace, final Launcher launcher, final TaskListener listener)
            throws IOException, InterruptedException {
        boolean isExported = false;
        if (isCompatible(ET_MIN_VERSION, workspace, launcher, listener)) {
            try {
                final StandardUsernamePasswordCredentials credentials = ((ExportPackageConfig) exportConfig)
                        .getCredentials();
                if (login(credentials, launcher, listener)) {
                    isExported = exportPackageToTMS(launcher, listener);
                }
            } finally {
                logout(launcher, listener);
            }
        }
        return isExported;
    }

    /**
     * Exports package attributes according to given export configuration.
     *
     * @param workspace
     *            the workspace
     * @param launcher
     *            the launcher
     * @param listener
     *            the listener
     * @return {@code true} if successful, {@code false} otherwise
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    public boolean exportPackageAttributes(final FilePath workspace, final Launcher launcher,
            final TaskListener listener) throws IOException, InterruptedException {
        boolean isExported = false;
        if (isCompatible(ET_MIN_VERSION, workspace, launcher, listener)) {
            try {
                final StandardUsernamePasswordCredentials credentials = ((ExportPackageAttributeConfig) exportConfig)
                        .getCredentials();
                if (login(credentials, launcher, listener)) {
                    isExported = exportPackageAttributesToTMS(launcher, listener);
                }
            } finally {
                logout(launcher, listener);
            }
        }
        return isExported;
    }

    /**
     * Exports a package to test management service.
     *
     * @param launcher
     *            the launcher
     * @param listener
     *            the listener
     * @return {@code true}, if export succeeded, {@code false} otherwise
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    private boolean exportPackageToTMS(final Launcher launcher, final TaskListener listener)
            throws IOException, InterruptedException {
        return launcher.getChannel().call(
                new ExportPackageCallable((ExportPackageConfig) exportConfig, listener));
    }

    /**
     * Exports package attributes to test management service.
     *
     * @param launcher
     *            the launcher
     * @param listener
     *            the listener
     * @return {@code true}, if export succeeded, {@code false} otherwise
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    private boolean exportPackageAttributesToTMS(final Launcher launcher, final TaskListener listener)
            throws IOException, InterruptedException {
        return launcher.getChannel().call(
                new ExportPackageAttributeCallable((ExportPackageAttributeConfig) exportConfig, listener));
    }

    /**
     * {@link Callable} providing remote access to export a package to test management system via COM.
     */
    private static final class ExportPackageCallable extends MasterToSlaveCallable<Boolean, IOException> {

        private static final long serialVersionUID = 1L;

        private final ExportPackageConfig exportConfig;
        private final TaskListener listener;

        /**
         * Instantiates a new {@link ExportPackageCallable}.
         *
         * @param exportConfig
         *            the export configuration
         * @param listener
         *            the listener
         */
        ExportPackageCallable(final ExportPackageConfig exportConfig, final TaskListener listener) {
            this.exportConfig = exportConfig;
            this.listener = listener;
        }

        @Override
        public Boolean call() throws IOException {
            boolean isExported = false;
            final TTConsoleLogger logger = new TTConsoleLogger(listener);
            logger.logInfo(String.format("- Exporting package %s to test management system...",
                    exportConfig.getFilePath()));
            final String progId = ETComProperty.getInstance().getProgId();
            try (ETComClient comClient = new ETComClient(progId)) {
                final TestManagement tm = (TestManagement) comClient.getTestManagement();
                if (isExported = tm.exportPackage(exportConfig.getFilePath(), exportConfig.getExportPath(),
                        exportConfig.isCreateNewPath(), exportConfig.getParsedTimeout())) {
                    logger.logInfo(String.format("-> Package exported successfully to target directory %s.",
                            exportConfig.getExportPath()));
                }
            } catch (final ETComException e) {
                logger.logError("-> Exporting package failed: " + e.getMessage());
            }
            return isExported;
        }
    }

    /**
     * {@link Callable} providing remote access to export package attributes to test management system via COM.
     */
    private static final class ExportPackageAttributeCallable extends MasterToSlaveCallable<Boolean, IOException> {

        private static final long serialVersionUID = 1L;

        private final ExportPackageAttributeConfig exportConfig;
        private final TaskListener listener;

        /**
         * Instantiates a new {@link ExportPackageAttributeCallable}.
         *
         * @param exportConfig
         *            the export configuration
         * @param listener
         *            the listener
         */
        ExportPackageAttributeCallable(final ExportPackageAttributeConfig exportConfig, final TaskListener listener) {
            this.exportConfig = exportConfig;
            this.listener = listener;
        }

        @Override
        public Boolean call() throws IOException {
            boolean isExported = false;
            final TTConsoleLogger logger = new TTConsoleLogger(listener);
            logger.logInfo(String.format("- Exporting attributes of package %s to test management system...",
                    exportConfig.getFilePath()));
            final String progId = ETComProperty.getInstance().getProgId();
            try (ETComClient comClient = new ETComClient(progId)) {
                final TestManagement tm = (TestManagement) comClient.getTestManagement();
                if (isExported = tm.exportPackageAttributes(exportConfig.getFilePath(),
                        exportConfig.getParsedTimeout())) {
                    logger.logInfo("-> Package attributes exported successfully.");
                }
            } catch (final ETComException e) {
                logger.logError("-> Exporting package attributes failed: " + e.getMessage());
            }
            return isExported;
        }
    }
}
