/*
 * Copyright (c) 2012, 2017 Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */
package com.oracle.mxtool.jacoco;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;

import org.jacoco.core.analysis.Analyzer;
import org.jacoco.core.analysis.CoverageBuilder;
import org.jacoco.core.analysis.IBundleCoverage;
import org.jacoco.core.data.ExecutionDataReader;
import org.jacoco.core.data.ExecutionDataStore;
import org.jacoco.core.data.SessionInfoStore;
import org.jacoco.report.FileMultiReportOutput;
import org.jacoco.report.IReportGroupVisitor;
import org.jacoco.report.IReportVisitor;
import org.jacoco.report.InputStreamSourceFileLocator;
import org.jacoco.report.html.HTMLFormatter;

import joptsimple.ArgumentAcceptingOptionSpec;
import joptsimple.NonOptionArgumentSpec;
import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;

public class JacocoReport {
    private ExecutionDataStore executionDataStore;
    private SessionInfoStore sessionInfoStore;

    public JacocoReport() {
        executionDataStore = new ExecutionDataStore();
        sessionInfoStore = new SessionInfoStore();
    }

    /**
     * Project specification.
     */
    public static class ProjectSpec {
        private final File srcDir;
        private final File binDir;

        /**
         * @param spec a specification string in the form "project-dir:binary-dir".
         */
        public ProjectSpec(String spec) {
            String[] s = spec.split(":", 2);
            if (s.length != 2) {
                throw new RuntimeException(String.format("Unsupported project specification: %s", spec));
            }
            this.srcDir = new File(s[0]);
            this.binDir = new File(s[1]);
        }
    }

    public static void main(String... args) throws IOException {
        OptionParser parser = new OptionParser();
        ArgumentAcceptingOptionSpec<File> inputsSpec = parser.accepts("in", "Input converage file produced by JaCoCo").withRequiredArg().ofType(File.class).required();
        NonOptionArgumentSpec<ProjectSpec> projectsSpec = parser.nonOptions("The project directories to analyse").ofType(ProjectSpec.class);
        ArgumentAcceptingOptionSpec<File> outSpec = parser.accepts("out").withRequiredArg().ofType(File.class).defaultsTo(new File("coverage"));
        OptionSet options;
        try {
            options = parser.parse(args);
        } catch (OptionException e) {
            System.err.println(e.getMessage());
            parser.printHelpOn(System.err);
            return;
        }

        if (!options.has(projectsSpec) || options.valuesOf(projectsSpec).isEmpty()) {
            System.err.println("Project directories are required");
            parser.printHelpOn(System.err);
            return;
        }

        new JacocoReport().makeReport(options.valueOf(outSpec), options.valuesOf(projectsSpec), options.valuesOf(inputsSpec));
    }

    public void makeReport(File reportDirectory, List<ProjectSpec> projects, List<File> execDatas) throws IOException {
        for (File execData : execDatas) {
            System.out.print("Loading '" + execData.getName() + "'... ");
            loadExecutionData(execData);
            System.out.println("OK");
        }
        List<BundleAndProject> bundles = new ArrayList<>(projects.size());
        for (ProjectSpec project : projects) {
            System.out.print("Analyzing project '" + project.srcDir + "'... ");
            bundles.add(new BundleAndProject(analyseProject(project.binDir, project.srcDir.getName()), project.srcDir));
            System.out.println("OK");
        }
        System.out.print("Creating HTML report... ");
        createHtmlReport(reportDirectory, bundles);
        System.out.println("OK");
    }

    private static class BundleAndProject {
        final IBundleCoverage bundle;
        final File project;

        public BundleAndProject(IBundleCoverage bundle, File project) {
            this.bundle = bundle;
            this.project = project;
        }

        @Override
        public String toString() {
            return bundle.toString();
        }
    }

    public void loadExecutionData(File f) throws IOException {
        final FileInputStream fis = new FileInputStream(f);
        final ExecutionDataReader executionDataReader = new ExecutionDataReader(fis);

        executionDataReader.setExecutionDataVisitor(executionDataStore);
        executionDataReader.setSessionInfoVisitor(sessionInfoStore);

        while (executionDataReader.read()) {
        }

        fis.close();
    }

    private static class MultiDirectorySourceFileLocator extends InputStreamSourceFileLocator {
        private File[] direcotries;

        protected MultiDirectorySourceFileLocator(String encoding, int tabWidth, File... direcotries) {
            super(encoding, tabWidth);
            this.direcotries = direcotries;
        }

        @Override
        protected InputStream getSourceStream(String path) throws IOException {
            for (File directory : direcotries) {
                final File file = new File(directory, path);
                if (file.exists()) {
                    return new FileInputStream(file);
                }
            }
            return null;
        }
    }

    public void createHtmlReport(File reportDirectory, List<BundleAndProject> bundleAndProjects) throws IOException {
        final HTMLFormatter htmlFormatter = new HTMLFormatter();
        final IReportVisitor visitor = htmlFormatter.createVisitor(new FileMultiReportOutput(reportDirectory));

        visitor.visitInfo(sessionInfoStore.getInfos(), executionDataStore.getContents());
        IReportGroupVisitor group = visitor.visitGroup("Graal");
        for (BundleAndProject bundleAndProject : bundleAndProjects) {
            group.visitBundle(bundleAndProject.bundle, new MultiDirectorySourceFileLocator("utf-8", 4, new File(bundleAndProject.project, "src"), new File(bundleAndProject.project, "src_gen")));
        }

        visitor.visitEnd();
    }

    public IBundleCoverage analyseProject(File project, String name) throws IOException {
        final CoverageBuilder coverageBuilder = new CoverageBuilder();
        final Analyzer analyzer = new Analyzer(executionDataStore, coverageBuilder);

        analyzer.analyzeAll(project);

        return coverageBuilder.getBundle(name);
    }
}