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
package de.tracetronic.jenkins.plugins.ecutest.report.trf;

import hudson.Extension;
import hudson.FilePath;
import hudson.Launcher;
import hudson.Util;
import hudson.model.Result;
import hudson.model.TaskListener;
import hudson.model.Run;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import org.jenkinsci.Symbol;
import org.kohsuke.stapler.DataBoundConstructor;

import de.tracetronic.jenkins.plugins.ecutest.log.TTConsoleLogger;
import de.tracetronic.jenkins.plugins.ecutest.report.AbstractReportDescriptor;
import de.tracetronic.jenkins.plugins.ecutest.report.AbstractReportPublisher;

/**
 * Publisher providing links to saved {@link TRFReport}s.
 *
 * @author Christian Pönisch <christian.poenisch@tracetronic.de>
 */
public class TRFPublisher extends AbstractReportPublisher {

    /**
     * File name extension of TRF files.
     */
    public static final String TRF_EXTENSION = ".trf";

    /**
     * Ant-style include pattern for listing up top level TRF files.
     */
    public static final String TRF_INCLUDE = "*" + TRF_EXTENSION;

    /**
     * Ant-style include pattern for listing up TRF files recursively.
     */
    public static final String TRF_INCLUDES = "**/*" + TRF_EXTENSION;

    /**
     * Ant-style pattern for excluding job analysis files.
     */
    public static final String TRF_EXCLUDE = "Job_*" + TRF_EXTENSION;

    /**
     * Ant-style pattern for excluding job analysis files recursively.
     */
    public static final String TRF_EXCLUDES = "*/**/Job_*" + TRF_EXTENSION;

    /**
     * The URL name to {@link TRFReport}s holding by {@link AbstractTRFAction}.
     */
    protected static final String URL_NAME = "trf-reports";

    /**
     * Instantiates a new {@link TRFPublisher}.
     */
    @DataBoundConstructor
    public TRFPublisher() {
        super();
    }

    @SuppressWarnings({ "checkstyle:cyclomaticcomplexity", "checkstyle:npathcomplexity" })
    @Override
    public void performReport(final Run<?, ?> run, final FilePath workspace, final Launcher launcher,
            final TaskListener listener) throws InterruptedException, IOException {
        final TTConsoleLogger logger = new TTConsoleLogger(listener);
        logger.logInfo("Publishing TRF reports...");

        final Result buildResult = run.getResult();
        if (buildResult != null && !canContinue(buildResult)) {
            logger.logInfo(String.format("Skipping publisher since build result is %s", buildResult));
            return;
        }

        if (isArchiving()) {
            int index = 0;
            final List<TRFReport> trfReports = new ArrayList<TRFReport>();
            final FilePath archiveTarget = getArchiveTarget(run);

            // Removing old artifacts at project level
            final List<FilePath> reportDirs = getReportDirs(run, workspace, launcher);
            if (!reportDirs.isEmpty() && !isKeepAll()) {
                archiveTarget.deleteRecursive();
                removePreviousReports(run, TRFBuildAction.class);
            }
            for (final FilePath reportDir : reportDirs) {
                final FilePath archiveTargetDir = archiveTarget.child(reportDir.getName());
                final FilePath reportFile = getFirstReportFile(reportDir);
                if (reportFile != null && reportFile.exists()) {
                    try {
                        logger.logInfo(String.format("- Archiving TRF report: %s", reportFile));
                        final int copiedFiles = reportDir.copyRecursiveTo(TRF_INCLUDES, TRF_EXCLUDES,
                                archiveTargetDir);
                        if (copiedFiles == 0) {
                            continue;
                        } else if (copiedFiles > 1) {
                            logger.logInfo(String.format("-> Archived %d sub-report(s).", copiedFiles - 1));
                        }
                    } catch (final IOException e) {
                        Util.displayIOException(e, listener);
                        logger.logError("Failed publishing TRF reports.");
                        run.setResult(Result.FAILURE);
                        return;
                    }
                    index = traverseReports(trfReports, archiveTargetDir, index);
                } else {
                    if (isAllowMissing()) {
                        continue;
                    } else {
                        logger.logError(String.format("Specified TRF file '%s' does not exist.", reportFile));
                        run.setResult(Result.FAILURE);
                        return;
                    }
                }
            }

            if (trfReports.isEmpty() && !isAllowMissing()) {
                logger.logError("Empty test results are not allowed, setting build status to FAILURE!");
                run.setResult(Result.FAILURE);
                return;
            }

            addBuildAction(run, trfReports);
            logger.logInfo("TRF reports published successfully.");
        } else {
            logger.logInfo("Archiving TRF reports is disabled.");
        }
    }

    /**
     * Creates the main report and adds the sub-reports by traversing them recursively.
     *
     * @param trfReports
     *            the TRF reports
     * @param archiveTargetDir
     *            the archive target directory
     * @param id
     *            the report id
     * @return the current report id
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    private int traverseReports(final List<TRFReport> trfReports, final FilePath archiveTargetDir, int id)
            throws IOException, InterruptedException {
        final FilePath trfFile = getFirstReportFile(archiveTargetDir);
        if (trfFile != null && trfFile.exists()) {
            final String relFilePath = archiveTargetDir.getParent().toURI().relativize(trfFile.toURI()).getPath();
            final TRFReport trfReport = new TRFReport(String.format("%d", ++id),
                    trfFile.getParent().getName(), relFilePath, trfFile.length());
            trfReports.add(trfReport);

            // Search for sub-reports
            id = traverseSubReports(trfReport, archiveTargetDir.getParent(), archiveTargetDir, id);
        }
        return id;
    }

    /**
     * Traverses the sub-report directories recursively and searches for TRF reports.
     * Includes the report files generated during separate sub-project execution.
     *
     * @param trfReport
     *            the TRF report
     * @param testReportDir
     *            the main test report directory
     * @param subTestReportDir
     *            the sub test report directory
     * @param id
     *            the report id
     * @return the current report id
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    private int traverseSubReports(final TRFReport trfReport, final FilePath testReportDir,
            final FilePath subTestReportDir, int id) throws IOException, InterruptedException {
        for (final FilePath subDir : subTestReportDir.listDirectories()) {
            final FilePath reportFile = getFirstReportFile(subDir);
            if (reportFile != null && reportFile.exists()) {
                final String relFilePath = testReportDir.toURI().relativize(reportFile.toURI()).getPath();
                final TRFReport subReport = new TRFReport(String.format("%d", ++id), reportFile.getParent()
                        .getName().replaceFirst("^Report\\s", ""), relFilePath, reportFile.length());
                trfReport.addSubReport(subReport);
                id = traverseSubReports(subReport, testReportDir, subDir, id);
            }
        }
        return id;
    }

    /**
     * Adds the {@link TRFBuildAction} to the build holding the found {@link TRFReport}s.
     *
     * @param run
     *            the run
     * @param trfReports
     *            the list of {@link TRFReport}s to add
     * @throws IOException
     *             signals that an I/O exception has occurred
     */
    private void addBuildAction(final Run<?, ?> run, final List<TRFReport> trfReports) throws IOException {
        TRFBuildAction action = run.getAction(TRFBuildAction.class);
        if (action == null) {
            action = new TRFBuildAction(!isKeepAll());
            run.addAction(action);
        }
        action.addAll(trfReports);
    }

    @Override
    protected String getUrlName() {
        return URL_NAME;
    }

    /**
     * DescriptorImpl for {@link TRFPublisher}.
     */
    @Symbol("publishTRF")
    @Extension(ordinal = 10004)
    public static final class DescriptorImpl extends AbstractReportDescriptor {

        @Override
        public String getDisplayName() {
            return Messages.TRFPublisher_DisplayName();
        }
    }
}
