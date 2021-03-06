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

import java.io.File;
import java.io.IOException;
import java.util.List;

import jenkins.security.MasterToSlaveCallable;

import org.apache.commons.io.FilenameUtils;

import de.tracetronic.jenkins.plugins.ecutest.log.TTConsoleLogger;
import de.tracetronic.jenkins.plugins.ecutest.test.client.AbstractTestClient.CheckInfoHolder.Seriousness;
import de.tracetronic.jenkins.plugins.ecutest.test.config.ExecutionConfig;
import de.tracetronic.jenkins.plugins.ecutest.test.config.ProjectConfig;
import de.tracetronic.jenkins.plugins.ecutest.test.config.TestConfig;
import de.tracetronic.jenkins.plugins.ecutest.util.DllUtil;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComClient;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComException;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.ETComProperty;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.Project;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.TestEnvironment;
import de.tracetronic.jenkins.plugins.ecutest.wrapper.com.TestExecutionInfo;

/**
 * Client to execute ECU-TEST projects via COM interface.
 *
 * @author Christian Pönisch <christian.poenisch@tracetronic.de>
 */
public class ProjectClient extends AbstractTestClient {

    private final ProjectConfig projectConfig;

    /**
     * Instantiates a new {@link ProjectClient}.
     *
     * @param testFile
     *            the project file
     * @param testConfig
     *            the test configuration
     * @param projectConfig
     *            the project configuration
     * @param executionConfig
     *            the execution configuration
     */
    public ProjectClient(final String testFile, final TestConfig testConfig,
            final ProjectConfig projectConfig, final ExecutionConfig executionConfig) {
        super(testFile, testConfig, executionConfig);
        this.projectConfig = projectConfig;
    }

    /**
     * @return the project configuration
     */
    public ProjectConfig getProjectConfig() {
        return projectConfig;
    }

    @Override
    public boolean runTestCase(final FilePath workspace, final Launcher launcher, final TaskListener listener)
            throws IOException, InterruptedException {
        final TTConsoleLogger logger = new TTConsoleLogger(listener);

        // Load JACOB library
        if (!DllUtil.loadLibrary(workspace.toComputer())) {
            logger.logError("Could not load JACOB library!");
            return false;
        }

        // Load test configuration
        if (!getTestConfig().isKeepConfig() && !launcher.getChannel().call(
                new LoadConfigCallable(getTestConfig(), listener))) {
            return false;
        }

        // Open and check project
        if (!launcher.getChannel().call(
                new OpenProjectCallable(getTestFile(), getProjectConfig(), getExecutionConfig().isCheckTestFile(),
                        listener))) {
            return false;
        }

        // Set default project information
        setTestDescription("");
        setTestName(FilenameUtils.getBaseName(new File(getTestFile()).getName()));

        try {
            // Run project
            final TestInfoHolder testInfo = launcher.getChannel().call(
                    new RunProjectCallable(getTestFile(), getProjectConfig(), getExecutionConfig(), listener));

            // Set project information
            if (testInfo != null) {
                setTestResult(testInfo.getTestResult());
                setTestReportDir(testInfo.getTestReportDir());
                setAborted(testInfo.isAborted());
            } else {
                return false;
            }
        } catch (final InterruptedException e) {
            logger.logError("Test execution has been interrupted!");
            return false;
        }

        // Close project
        if (!launcher.getChannel().call(new CloseProjectCallable(getTestFile(), listener))) {
            return false;
        }

        return true;
    }

    /**
     * {@link Callable} providing remote access to open a project via COM.
     */
    private static final class OpenProjectCallable extends MasterToSlaveCallable<Boolean, IOException> {

        private static final long serialVersionUID = 1L;

        private final String projectFile;
        private final ProjectConfig projectConfig;
        private final boolean checkTestFile;
        private final TaskListener listener;

        /**
         * Instantiates a new {@link OpenProjectCallable}.
         *
         * @param projectFile
         *            the project file
         * @param projectConfig
         *            the project configuration
         * @param checkTestFile
         *            specifies whether to check the project file
         * @param listener
         *            the listener
         */
        OpenProjectCallable(final String projectFile, final ProjectConfig projectConfig,
                final boolean checkTestFile, final TaskListener listener) {
            this.projectFile = projectFile;
            this.projectConfig = projectConfig;
            this.checkTestFile = checkTestFile;
            this.listener = listener;
        }

        @Override
        public Boolean call() throws IOException {
            final boolean execInCurrentPkgDir = projectConfig.isExecInCurrentPkgDir();
            final String filterExpression = projectConfig.getFilterExpression();
            boolean isOpened = true;
            final TTConsoleLogger logger = new TTConsoleLogger(listener);
            logger.logInfo("- Opening project...");
            final String progId = ETComProperty.getInstance().getProgId();
            try (ETComClient comClient = new ETComClient(progId);
                    Project project = (Project) comClient.openProject(projectFile, execInCurrentPkgDir,
                            filterExpression)) {
                logger.logInfo("-> Project opened successfully.");
                if (checkTestFile) {
                    logger.logInfo("- Checking project...");
                    final List<CheckInfoHolder> checks = project.check();
                    for (final CheckInfoHolder check : checks) {
                        final String logMessage = String.format("%s (line %s): %s", check.getFilePath(),
                                check.getLineNumber(), check.getErrorMessage());
                        final Seriousness seriousness = check.getSeriousness();
                        switch (seriousness) {
                            case NOTE:
                                logger.logInfo(logMessage);
                                break;
                            case WARNING:
                                logger.logWarn(logMessage);
                                break;
                            case ERROR:
                                logger.logError(logMessage);
                                isOpened = false;
                                break;
                            default:
                                break;
                        }
                    }
                    if (checks.isEmpty()) {
                        logger.logInfo("-> Project validated successfully!");
                    }
                }
            } catch (final ETComException e) {
                isOpened = false;
                logger.logError("-> Opening project failed!");
                logger.logComException(e.getMessage());
            }
            return isOpened;
        }
    }

    /**
     * {@link Callable} providing remote access to run a project via COM.
     */
    private static final class RunProjectCallable extends MasterToSlaveCallable<TestInfoHolder, InterruptedException> {

        private static final long serialVersionUID = 1L;

        private final String projectFile;
        private final ProjectConfig projectConfig;
        private final ExecutionConfig executionConfig;
        private final TaskListener listener;

        /**
         * Instantiates a new {@link RunProjectCallable}.
         *
         * @param projectFile
         *            the project file
         * @param projectConfig
         *            the project configuration
         * @param executionConfig
         *            the execution configuration
         * @param listener
         *            the listener
         */
        RunProjectCallable(final String projectFile, final ProjectConfig projectConfig,
                final ExecutionConfig executionConfig, final TaskListener listener) {
            this.projectFile = projectFile;
            this.projectConfig = projectConfig;
            this.executionConfig = executionConfig;
            this.listener = listener;
        }

        @Override
        public TestInfoHolder call() throws InterruptedException {
            final int jobExecutionMode = projectConfig.getJobExecMode().getValue();
            final int timeout = executionConfig.getParsedTimeout();
            TestInfoHolder testInfo = null;
            final TTConsoleLogger logger = new TTConsoleLogger(listener);
            logger.logInfo("- Running project...");
            final String progId = ETComProperty.getInstance().getProgId();
            try (ETComClient comClient = new ETComClient(progId);
                    TestEnvironment testEnv = (TestEnvironment) comClient.getTestEnvironment();
                    TestExecutionInfo execInfo = (TestExecutionInfo) testEnv.executeProject(projectFile, true,
                            jobExecutionMode)) {
                boolean isAborted = false;
                int tickCounter = 0;
                final long endTimeMillis = System.currentTimeMillis() + Long.valueOf(timeout) * 1000L;
                while ("RUNNING".equals(execInfo.getState())) {
                    if (tickCounter % 60 == 0) {
                        logger.logInfo("-- tick...");
                    }
                    if (timeout > 0 && System.currentTimeMillis() > endTimeMillis) {
                        logger.logWarn(String.format("-> Test execution timeout of %d seconds reached! "
                                + "Aborting project now...", timeout));
                        isAborted = true;
                        execInfo.abort();
                        break;
                    }
                    Thread.sleep(1000L);
                    tickCounter++;
                }
                testInfo = getTestInfo(execInfo, isAborted, logger);
                postExecution(timeout, comClient, logger);
            } catch (final ETComException e) {
                logger.logComException(e.getMessage());
            } catch (final InterruptedException e) {
                testInfo = abortTestExecution(timeout, progId, logger);
            }
            return testInfo;
        }

        /**
         * Aborts the test execution.
         *
         * @param timeout
         *            the timeout
         * @param progId
         *            the programmatic id
         * @param logger
         *            the logger
         * @return the test information
         */
        private TestInfoHolder abortTestExecution(final int timeout, final String progId,
                final TTConsoleLogger logger) {
            TestInfoHolder testInfo = null;
            try (ETComClient comClient = new ETComClient(progId);
                    TestEnvironment testEnv = (TestEnvironment) comClient.getTestEnvironment();
                    TestExecutionInfo execInfo = (TestExecutionInfo) testEnv.getTestExecutionInfo()) {
                logger.logWarn(String.format("-> Build interrupted! Aborting test exection..."));
                execInfo.abort();
                testInfo = getTestInfo(execInfo, true, logger);
                postExecution(timeout, comClient, logger);
            } catch (final ETComException exc) {
                logger.logError("Caught ComException: " + exc.getMessage());
            }
            return testInfo;
        }

        /**
         * Gets the information of the executed test.
         *
         * @param execInfo
         *            the execution info
         * @param isAborted
         *            specifies whether the test execution is aborted
         * @param logger
         *            the logger
         * @return the test information
         * @throws ETComException
         *             in case of a COM exception
         */
        private TestInfoHolder getTestInfo(final TestExecutionInfo execInfo, final boolean isAborted,
                final TTConsoleLogger logger) throws ETComException {
            final String testResult = execInfo.getResult();
            logger.logInfo(String.format("-> Project execution completed with result: %s", testResult));
            final String testReportDir = new File(execInfo.getReportDb()).getParentFile().getAbsolutePath();
            logger.logInfo(String.format("-> Test report directory: %s", testReportDir));
            return new TestInfoHolder(testResult, testReportDir, isAborted);
        }

        /**
         * Timeout handling for post execution.
         *
         * @param timeout
         *            the timeout
         * @param comClient
         *            the COM client
         * @param logger
         *            the logger
         * @throws ETComException
         *             in case of a COM exception
         */
        private void postExecution(final int timeout, final ETComClient comClient, final TTConsoleLogger logger)
                throws ETComException {
            if (!comClient.waitForIdle(timeout)) {
                logger.logWarn(String.format("-> Post-execution timeout of %d seconds reached!", timeout));
            }
        }
    }

    /**
     * {@link Callable} providing remote access to close a project via COM.
     */
    private static final class CloseProjectCallable extends MasterToSlaveCallable<Boolean, IOException> {

        private static final long serialVersionUID = 1L;

        private final String projectFile;
        private final TaskListener listener;

        /**
         * Instantiates a new {@link CloseProjectCallable}.
         *
         * @param projectFile
         *            the project file
         * @param listener
         *            the listener
         */
        CloseProjectCallable(final String projectFile, final TaskListener listener) {
            this.projectFile = projectFile;
            this.listener = listener;
        }

        @Override
        public Boolean call() throws IOException {
            boolean isClosed = false;
            final TTConsoleLogger logger = new TTConsoleLogger(listener);
            logger.logInfo("- Closing project...");
            final String progId = ETComProperty.getInstance().getProgId();
            try (ETComClient comClient = new ETComClient(progId)) {
                if (comClient.closeProject(projectFile)) {
                    isClosed = true;
                    logger.logInfo("-> Project closed successfully.");
                } else {
                    logger.logError("-> Closing project failed!");
                }
            } catch (final ETComException e) {
                logger.logComException(e.getMessage());
            }
            return isClosed;
        }
    }
}
