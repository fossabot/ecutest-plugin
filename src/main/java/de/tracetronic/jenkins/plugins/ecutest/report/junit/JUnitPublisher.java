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
package de.tracetronic.jenkins.plugins.ecutest.report.junit;

import hudson.EnvVars;
import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.matrix.MatrixAggregatable;
import hudson.matrix.MatrixAggregator;
import hudson.matrix.MatrixBuild;
import hudson.model.BuildListener;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.Run;
import hudson.tasks.junit.TestResult;
import hudson.tasks.junit.TestResultAction;
import hudson.tasks.test.TestResultAggregator;
import hudson.util.FormValidation;

import java.io.IOException;
import java.math.BigDecimal;
import java.util.List;

import javax.annotation.CheckForNull;
import javax.annotation.Nonnull;

import org.apache.commons.lang.NotImplementedException;
import org.apache.commons.lang.StringUtils;
import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.DataBoundSetter;
import org.kohsuke.stapler.QueryParameter;

import de.tracetronic.jenkins.plugins.ecutest.ETPluginException;
import de.tracetronic.jenkins.plugins.ecutest.log.TTConsoleLogger;
import de.tracetronic.jenkins.plugins.ecutest.report.AbstractReportDescriptor;
import de.tracetronic.jenkins.plugins.ecutest.report.AbstractReportPublisher;
import de.tracetronic.jenkins.plugins.ecutest.tool.installation.AbstractToolInstallation;
import de.tracetronic.jenkins.plugins.ecutest.tool.installation.ETInstallation;
import de.tracetronic.jenkins.plugins.ecutest.util.ProcessUtil;
import de.tracetronic.jenkins.plugins.ecutest.util.validation.JUnitValidator;

/**
 * Publisher providing the generation of JUnit reports and adds a {@link TestResultAction} by invoking the
 * {@link JUnitTestResultParser}.
 *
 * @author Christian Pönisch <christian.poenisch@tracetronic.de>
 */
public class JUnitPublisher extends AbstractReportPublisher implements MatrixAggregatable {

    /**
     * File name of the UNIT report file.
     */
    protected static final String JUNIT_REPORT_FILE = "junit-report.xml";

    /**
     * Defines the path name containing the UNIT reports inside of the test report directory.
     */
    protected static final String UNIT_TEMPLATE_NAME = "UNIT";

    @Nonnull
    private final String toolName;
    private double unstableThreshold;
    private double failedThreshold;

    /**
     * Instantiates a new {@link JUnitPublisher}.
     *
     * @param toolName
     *            the tool name identifying the {@link ETInstallation} to be used
     */
    @DataBoundConstructor
    public JUnitPublisher(@Nonnull final String toolName) {
        super();
        this.toolName = StringUtils.trimToEmpty(toolName);
    }

    /**
     * @return the {@link ETInstallation} name
     */
    @Nonnull
    public String getToolName() {
        return toolName;
    }

    /**
     * @return the unstable threshold
     */
    public double getUnstableThreshold() {
        return unstableThreshold;
    }

    /**
     * @return the failed threshold
     */
    public double getFailedThreshold() {
        return failedThreshold;
    }

    /**
     * @param unstableThreshold
     *            the unstable threshold
     */
    @DataBoundSetter
    public void setUnstableThreshold(final double unstableThreshold) {
        this.unstableThreshold = convertToPercentage(unstableThreshold);
    }

    /**
     * @param failedThreshold
     *            the failed threshold
     */
    @DataBoundSetter
    public void setFailedThreshold(final double failedThreshold) {
        this.failedThreshold = convertToPercentage(failedThreshold);
    }

    /**
     * Gets the tool installation by descriptor and tool name.
     *
     * @param envVars
     *            the environment variables
     * @return the tool installation
     */
    @CheckForNull
    public AbstractToolInstallation getToolInstallation(final EnvVars envVars) {
        final String expToolName = envVars.expand(toolName);
        for (final AbstractToolInstallation installation : getDescriptor().getToolDescriptor().getInstallations()) {
            if (StringUtils.equals(expToolName, installation.getName())) {
                return installation;
            }
        }
        return null;
    }

    @Override
    public MatrixAggregator createAggregator(final MatrixBuild build, final Launcher launcher,
            final BuildListener listener) {
        return new TestResultAggregator(build, launcher, listener);
    }

    @Override
    public void performReport(final Run<?, ?> run, final FilePath workspace, final Launcher launcher,
            final TaskListener listener) throws InterruptedException, IOException, ETPluginException {
        final TTConsoleLogger logger = new TTConsoleLogger(listener);
        logger.logInfo("Publishing UNIT reports...");
        ProcessUtil.checkOS(launcher);

        final Result buildResult = run.getResult();
        if (buildResult != null && !canContinue(buildResult)) {
            logger.logInfo(String.format("Skipping publisher since build result is %s", buildResult));
            return;
        }
        final List<FilePath> reportFiles = getReportFiles(run, workspace, launcher);
        if (reportFiles.isEmpty() && !isAllowMissing()) {
            throw new ETPluginException("Empty test results are not allowed, setting build status to FAILURE!");
        }

        // Generate JUnit reports
        final ETInstallation installation = configureToolInstallation(toolName, workspace.toComputer(), listener,
                run.getEnvironment(listener));
        final JUnitReportGenerator generator = new JUnitReportGenerator();
        if (!generator.generate(installation, reportFiles, run, workspace, launcher, listener)) {
            run.setResult(Result.FAILURE);
            return;
        }

        // Parse generated JUnit reports
        final String includes = String.format("**/%s/%s", UNIT_TEMPLATE_NAME, JUNIT_REPORT_FILE);
        final List<FilePath> xmlFiles = getReportFiles(includes, "", run, workspace, launcher);
        final JUnitTestResultParser parser = new JUnitTestResultParser();
        final TestResult testResult = parser.parseResult(xmlFiles, listener);

        // Add or append to action for publishing JUnit results
        TestResultAction action = run.getAction(TestResultAction.class);
        if (action == null) {
            try {
                action = new TestResultAction(run, testResult, listener);
                run.addAction(action);
            } catch (final NullPointerException npe) {
                throw new ETPluginException(String.format("Parsing UNIT test results failed: %s", npe.getMessage()));
            }
        } else {
            action.setResult(testResult, listener);
        }
        testResult.freeze(action);

        // Change build result if thresholds exceeded
        if (setBuildResult(run, listener, testResult)) {
            logger.logInfo("UNIT reports published successfully.");
        }
    }

    /**
     * Sets the build result according to the test result.
     *
     * @param run
     *            the run
     * @param listener
     *            the listener
     * @param testResult
     *            the test result
     * @return {@code true} if test results exist and could be published
     */
    private boolean setBuildResult(final Run<?, ?> run, final TaskListener listener,
            final TestResult testResult) {
        final TTConsoleLogger logger = new TTConsoleLogger(listener);
        if (testResult.getTotalCount() == 0) {
            logger.logInfo("-> No UNIT test results found.");
            if (!isAllowMissing()) {
                logger.logError("Empty test results are not allowed, setting build status to FAILURE!");
                run.setResult(Result.FAILURE);
                return false;
            }
        } else {
            logger.logInfo(String.format(
                    "-> Found %d test result(s) in total: #Passed: %d, #Failed: %d, #Skipped: %d",
                    testResult.getTotalCount(), testResult.getPassCount(), testResult.getFailCount(),
                    testResult.getSkipCount()));
        }

        final double failedPercentage = getFailedPercentage(testResult.getFailCount(),
                testResult.getTotalCount());
        if (failedPercentage > failedThreshold) {
            logger.logInfo(String.format(
                    "-> %.1f%% of failed test results exceed failed threshold of %.1f%%, "
                            + "setting build status to FAILURE!", failedPercentage, failedThreshold));
            run.setResult(Result.FAILURE);
        } else if (failedPercentage > unstableThreshold) {
            logger.logInfo(String.format(
                    "-> %.1f%% of failed test results exceed unstable threshold of %.1f%%, "
                            + "setting build status to UNSTABLE!", failedPercentage, unstableThreshold));
            run.setResult(Result.UNSTABLE);
        }
        return true;
    }

    /**
     * Gets the failed percentage.
     *
     * @param failedCount
     *            the failed count
     * @param totalCount
     *            the total count
     * @return the unstable percentage
     */
    public static double getFailedPercentage(final int failedCount, final int totalCount) {
        if (totalCount == 0) {
            return 0;
        }

        final double percentage = (double) failedCount / (double) totalCount * 100;
        return roundToDecimals(percentage, 1);
    }

    /**
     * Round to decimals.
     *
     * @param value
     *            the value to round
     * @param decimals
     *            the number of decimals
     * @return the rounded value
     */
    private static double roundToDecimals(final double value, final int decimals) {
        BigDecimal bd = new BigDecimal(Double.toString(value));
        bd = bd.setScale(decimals, BigDecimal.ROUND_HALF_UP);
        return bd.doubleValue();
    }

    /**
     * Converts to percentage value.
     *
     * @param value
     *            the value to convert
     * @return the percentage value
     */
    private static double convertToPercentage(final double value) {
        if (value < 0.0) {
            return 0.0;
        } else if (value > 100.0) {
            return 100.0;
        } else {
            return value;
        }
    }

    @Override
    protected String getUrlName() {
        throw new NotImplementedException();
    }

    /**
     * DescriptorImpl for {@link JUnitPublisher}.
     */
    @Symbol("publishUNIT")
    @Extension(ordinal = 10003, optional = true)
    public static final class DescriptorImpl extends AbstractReportDescriptor {

        private final JUnitValidator unitValidator;

        /**
         * Instantiates a new {@link DescriptorImpl}.
         */
        public DescriptorImpl() {
            super();
            unitValidator = new JUnitValidator();
        }

        @Override
        public String getDisplayName() {
            return Messages.JUnitPublisher_DisplayName();
        }

        /**
         * Validates the unstable threshold.
         *
         * @param value
         *            the threshold
         * @return the form validation
         */
        public FormValidation doCheckUnstableThreshold(@QueryParameter final String value) {
            return unitValidator.validateUnstableThreshold(value);
        }

        /**
         * Validates the failed threshold.
         *
         * @param value
         *            the threshold
         * @return the form validation
         */
        public FormValidation doCheckFailedThreshold(@QueryParameter final String value) {
            return unitValidator.validateFailedThreshold(value);
        }
    }
}
