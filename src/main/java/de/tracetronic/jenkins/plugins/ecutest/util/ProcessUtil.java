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
package de.tracetronic.jenkins.plugins.ecutest.util;

import hudson.Launcher;
import hudson.model.Computer;

import java.io.FileInputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;

import org.apache.commons.lang.StringUtils;
import org.jvnet.winp.WinProcess;
import org.jvnet.winp.WinpException;

import de.tracetronic.jenkins.plugins.ecutest.ETPluginException;

/**
 * Utility class providing process and system operations.
 *
 * @author Christian Pönisch <christian.poenisch@tracetronic.de>
 */
public final class ProcessUtil {

    /**
     * Defines ECU-TEST related process names.
     */
    private static final List<String> ET_PROCS = Arrays.asList("ECU-TEST.exe", "ECU-TEST_COM.exe", "ECU-TE~1.EXE");

    /**
     * Defines Tool-Server related process names.
     */
    private static final List<String> TS_PROCS = Arrays.asList("Tool-Server.exe");

    /**
     * Instantiates a new {@link ProcessUtil}.
     */
    private ProcessUtil() {
        throw new UnsupportedOperationException("Utility class");
    }

    /**
     * Check the ECU-TEST processes and kills them if appropriate.
     *
     * @param kill
     *            specifies whether to task-kill the running processes
     * @return the list of found or killed processes
     */
    public static List<String> checkETProcesses(final boolean kill) {
        return checkProcesses(ET_PROCS, kill);
    }

    /**
     * Check the Tool-Server processes and kills them if appropriate.
     *
     * @param kill
     *            specifies whether to task-kill the running processes
     * @return the list of found or killed processes
     */
    public static List<String> checkTSProcess(final boolean kill) {
        return checkProcesses(TS_PROCS, kill);
    }

    /**
     * Checks a list of processes and kills them if appropriate.
     *
     * @param processes
     *            the list of processes to check
     * @param kill
     *            specifies whether to task-kill the running processes
     * @return the list of found or killed processes
     * @throws IOException
     *             signals that an I/O exception has occurred
     */
    private static List<String> checkProcesses(final List<String> processes, final boolean kill) {
        final List<String> found = new ArrayList<String>();
        WinProcess.enableDebugPrivilege();
        final Iterator<WinProcess> openProcesses = WinProcess.all().iterator();
        while (openProcesses.hasNext()) {
            try {
                final WinProcess openProcess = openProcesses.next();
                final String cmdLine = openProcess.getCommandLine();
                for (final String process : processes) {
                    if (StringUtils.containsIgnoreCase(cmdLine, process)) {
                        found.add(process);
                        if (kill) {
                            killProcess(openProcess);
                        }
                    }
                }
            } catch (final WinpException e) {
                // Skip system pseudo-processes with insufficient security privileges
                continue;
            }
        }
        return found;
    }

    /**
     * Kills this process and all the descendant processes that this process launched.
     *
     * @param process
     *            the process to kill
     */
    private static void killProcess(final WinProcess process) {
        process.killRecursively();
    }

    /**
     * Checks the operating system of a launcher.
     * <p>
     * Most of the builders and publishers implemented by this plugin require to run on Windows.
     *
     * @param launcher
     *            the launcher
     * @throws ETPluginException
     *             if Unix-based launcher
     */
    public static void checkOS(final Launcher launcher) throws ETPluginException {
        if (launcher.isUnix()) {
            throw new ETPluginException("Trying to build Windows related configuration on an Unix-based system! "
                    + "Restrict the project to be built on a particular Windows slave or master.");
        }
    }

    /**
     * From https://stackoverflow.com/a/35418180
     * 
     * Reads the .exe file to find headers that tell us if the file is 32 or 64 bit.
     * 
     * Note: Assumes byte pattern 0x50, 0x45, 0x00, 0x00 just before the byte that tells us the architecture.
     * 
     * @param filePath
     *            fully qualified .exe file path.
     * @return {@code true} if the file is a 64-bit executable, {@code false} otherwise.
     * @throws IOException
     *             if there is a problem reading the file or the file does not end in .exe.
     */
    @SuppressWarnings("checkstyle:booleanexpressioncomplexity")
    public static boolean is64BitExecutable(final String filePath) throws IOException {
        if (!filePath.endsWith(".exe")) {
            throw new IOException(String.format("%s is not a Windows .exe file.", filePath));
        }
        // Should be enough bytes to make it to the necessary header
        final byte[] fileData = new byte[1024];
        try (FileInputStream input = new FileInputStream(filePath)) {
            final int bytesRead = input.read(fileData);
            for (int i = 0; i < bytesRead; i++) {
                if (fileData[i] == 0x50
                        && i + 5 < bytesRead
                        && fileData[i + 1] == 0x45
                        && fileData[i + 2] == 0
                        && fileData[i + 3] == 0) {
                    return fileData[i + 4] == 0x64;
                }
            }
        }
        return false;
    }

    /**
     * Checks whether the underlying JVM supports 64-bit architecture.
     *
     * @param computer
     *            the computer
     * @return {@code true} 64-bit architecture is supported, {@code false} otherwise.
     * @throws IOException
     *             signals that an I/O exception has occurred
     * @throws InterruptedException
     *             if the build gets interrupted
     */
    public static boolean is64BitJVM(final Computer computer) throws IOException, InterruptedException {
        if (computer == null) {
            throw new IOException("Could not access node properties!");
        }
        return "amd64".equals(computer.getSystemProperties().get("os.arch"));
    }
}
